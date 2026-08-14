package com.idavy.drtops.jtgateway.ingress;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class GatewayOutboxRepository {
    private static final String DEPENDENCY_DEAD_LETTER = "DEPENDENCY_DEAD_LETTER";
    private static final Duration DEAD_LETTER_RETENTION = Duration.ofDays(7);
    private static final Duration DELIVERY_LEASE = Duration.ofMinutes(1);
    private static final Duration LOCATION_BATCH_INTERVAL = Duration.ofSeconds(1);
    private static final RowMapper<OutboxEntry> ENTRY_MAPPER = GatewayOutboxRepository::mapEntry;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public GatewayOutboxRepository(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.jdbc = new JdbcTemplate(dataSource);
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    public boolean insert(GatewayIngressEnvelope envelope, Instant availableAt) {
        return insert(envelope, availableAt, null);
    }

    public boolean insert(
            GatewayIngressEnvelope envelope, Instant availableAt, UUID dependencyIdempotencyKey) {
        try {
            jdbc.update("""
                    INSERT INTO gateway_outbox (
                        idempotency_key, kind, schema_version, payload_json, status,
                        attempt_count, next_attempt_at, created_at, dependency_idempotency_key)
                    VALUES (?, ?, ?, ?, 'PENDING', 0, ?, ?, ?)
                    """,
                    envelope.idempotencyKey(),
                    envelope.kind().name(),
                    envelope.schemaVersion(),
                    envelope.payloadJson(),
                    atOffset(availableAt),
                    atOffset(envelope.gatewayReceivedAt()),
                    dependencyIdempotencyKey);
            return true;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
    }

    public int recoverInterruptedDeliveries(Instant availableAt) {
        return jdbc.update("""
                UPDATE gateway_outbox
                SET status = 'PENDING', next_attempt_at = ?, last_error_code = 'RECOVERED_AFTER_RESTART'
                WHERE status = 'DELIVERING'
                """, atOffset(availableAt));
    }

    public List<OutboxEntry> claimEligible(Instant now, Priority priority, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        return transactions.execute(status -> {
            recoverExpiredClaims(now);
            if (priority == Priority.LOCATION && !locationBatchWindowIsOpen(now)) {
                return List.of();
            }
            String kindPredicate = priority == Priority.HIGH
                    ? "kind IN ('ALARM', 'PROTOCOL_AUDIT', 'ATTACHMENT_CONTROL') "
                            + "AND (dependency_idempotency_key IS NULL OR dependency_idempotency_key IN "
                            + "(SELECT idempotency_key FROM gateway_outbox dependency WHERE dependency.status = 'DELIVERED'))"
                    : "kind = 'LOCATION'";
            List<OutboxEntry> selected = jdbc.query("""
                    SELECT idempotency_key, kind, schema_version, payload_json, status,
                           attempt_count, next_attempt_at, created_at, delivered_at, last_error_code,
                           dependency_idempotency_key
                    FROM gateway_outbox
                    WHERE status = 'PENDING' AND next_attempt_at <= ? AND %s
                    ORDER BY created_at, idempotency_key
                    LIMIT %d
                    FOR UPDATE
                    """.formatted(kindPredicate, limit), ENTRY_MAPPER, atOffset(now));
            List<OutboxEntry> claimed = new ArrayList<>(selected.size());
            for (OutboxEntry entry : selected) {
                int updated = jdbc.update("""
                        UPDATE gateway_outbox SET status = 'DELIVERING', next_attempt_at = ?
                        WHERE idempotency_key = ? AND status = 'PENDING'
                        """, atOffset(now.plus(DELIVERY_LEASE)), entry.idempotencyKey());
                requireSingleStateTransition(updated);
                claimed.add(entry.claimedUntil(now.plus(DELIVERY_LEASE)));
            }
            if (priority == Priority.LOCATION && !claimed.isEmpty()) {
                jdbc.update("""
                        UPDATE gateway_dispatch_state SET next_batch_at = ?
                        WHERE lane = 'LOCATION'
                        """, atOffset(now.plus(LOCATION_BATCH_INTERVAL)));
            }
            return List.copyOf(claimed);
        });
    }

    public List<OutboxEntry> claimHighPriorityDependencies(Instant now, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        return transactions.execute(status -> {
            recoverExpiredClaims(now);
            List<UUID> candidates = jdbc.queryForList("""
                    SELECT urgent.dependency_idempotency_key
                    FROM gateway_outbox urgent
                    JOIN gateway_outbox dependency
                      ON dependency.idempotency_key = urgent.dependency_idempotency_key
                    WHERE urgent.status = 'PENDING' AND urgent.next_attempt_at <= ?
                      AND urgent.kind IN ('ALARM', 'PROTOCOL_AUDIT', 'ATTACHMENT_CONTROL')
                      AND dependency.status = 'PENDING' AND dependency.next_attempt_at <= ?
                    GROUP BY urgent.dependency_idempotency_key
                    ORDER BY MIN(urgent.created_at), urgent.dependency_idempotency_key
                    LIMIT %d
                    """.formatted(limit), UUID.class, atOffset(now), atOffset(now));
            List<OutboxEntry> claimed = new ArrayList<>();
            for (UUID dependencyId : candidates) {
                Optional<OutboxEntry> dependency = jdbc.query("""
                        SELECT idempotency_key, kind, schema_version, payload_json, status,
                               attempt_count, next_attempt_at, created_at, delivered_at, last_error_code,
                               dependency_idempotency_key
                        FROM gateway_outbox
                        WHERE idempotency_key = ? AND status = 'PENDING' AND next_attempt_at <= ?
                        FOR UPDATE
                        """, ENTRY_MAPPER, dependencyId, atOffset(now)).stream().findFirst();
                if (dependency.isEmpty()) {
                    continue;
                }
                int updated = jdbc.update("""
                        UPDATE gateway_outbox SET status = 'DELIVERING', next_attempt_at = ?
                        WHERE idempotency_key = ? AND status = 'PENDING'
                        """, atOffset(now.plus(DELIVERY_LEASE)), dependencyId);
                requireSingleStateTransition(updated);
                claimed.add(dependency.get().claimedUntil(now.plus(DELIVERY_LEASE)));
            }
            return List.copyOf(claimed);
        });
    }

    private void recoverExpiredClaims(Instant now) {
        jdbc.update("""
                UPDATE gateway_outbox
                SET status = 'PENDING', next_attempt_at = ?,
                    last_error_code = 'DELIVERY_LEASE_EXPIRED'
                WHERE status = 'DELIVERING' AND next_attempt_at <= ?
                """, atOffset(now), atOffset(now));
    }

    private boolean locationBatchWindowIsOpen(Instant now) {
        OffsetDateTime nextBatchAt = jdbc.queryForObject("""
                SELECT next_batch_at FROM gateway_dispatch_state
                WHERE lane = 'LOCATION'
                FOR UPDATE
                """, OffsetDateTime.class);
        return nextBatchAt != null && !now.isBefore(nextBatchAt.toInstant());
    }

    public void markDelivered(List<OutboxEntry> entries, Instant deliveredAt) {
        transactions.executeWithoutResult(status -> {
            for (OutboxEntry entry : entries) {
                int updated = jdbc.update("""
                        UPDATE gateway_outbox
                        SET status = 'DELIVERED', payload_json = NULL,
                            delivered_at = ?, last_error_code = NULL
                        WHERE idempotency_key = ? AND status = 'DELIVERING'
                          AND next_attempt_at = ?
                        """,
                        atOffset(deliveredAt),
                        entry.idempotencyKey(),
                        atOffset(entry.nextAttemptAt()));
                requireSingleStateTransition(updated);
            }
        });
    }

    public void markFailed(List<FailureUpdate> updates) {
        transactions.executeWithoutResult(status -> {
            for (FailureUpdate update : updates) {
                int changed = jdbc.update("""
                        UPDATE gateway_outbox
                        SET status = ?, attempt_count = ?, next_attempt_at = ?, last_error_code = ?
                        WHERE idempotency_key = ? AND status = 'DELIVERING'
                          AND next_attempt_at = ?
                        """,
                        update.deadLetter() ? DeliveryStatus.DEAD_LETTER.name()
                                : DeliveryStatus.PENDING.name(),
                        update.attemptCount(),
                        atOffset(update.nextAttemptAt()),
                        update.errorCode(),
                        update.entry().idempotencyKey(),
                        atOffset(update.entry().nextAttemptAt()));
                requireSingleStateTransition(changed);
                if (update.deadLetter()) {
                    jdbc.update("""
                            UPDATE gateway_outbox
                            SET status = 'DEAD_LETTER', next_attempt_at = ?, last_error_code = ?
                            WHERE dependency_idempotency_key = ?
                              AND status IN ('PENDING', 'DELIVERING')
                            """,
                            atOffset(update.nextAttemptAt()),
                            DEPENDENCY_DEAD_LETTER,
                            update.entry().idempotencyKey());
                }
            }
        });
    }

    private static void requireSingleStateTransition(int updated) {
        if (updated != 1) {
            throw new OptimisticLockingFailureException(
                    "gateway outbox state changed during batch completion");
        }
    }

    public int purgeExpiredDeadLetters(Instant now) {
        return transactions.execute(status -> {
            int totalDeleted = 0;
            int deleted;
            do {
                deleted = jdbc.update("""
                        DELETE FROM gateway_outbox
                        WHERE idempotency_key IN (
                            SELECT candidate.idempotency_key
                            FROM gateway_outbox candidate
                            LEFT JOIN gateway_outbox dependent
                              ON dependent.dependency_idempotency_key = candidate.idempotency_key
                            WHERE candidate.status = 'DEAD_LETTER'
                              AND candidate.next_attempt_at < ?
                            GROUP BY candidate.idempotency_key
                            HAVING COUNT(dependent.idempotency_key) = 0
                        )
                        """, atOffset(now.minus(DEAD_LETTER_RETENTION)));
                totalDeleted += deleted;
            } while (deleted > 0);
            return totalDeleted;
        });
    }

    public Optional<OutboxEntry> find(UUID idempotencyKey) {
        return jdbc.query("""
                SELECT idempotency_key, kind, schema_version, payload_json, status,
                       attempt_count, next_attempt_at, created_at, delivered_at, last_error_code,
                       dependency_idempotency_key
                FROM gateway_outbox WHERE idempotency_key = ?
                """, ENTRY_MAPPER, idempotencyKey).stream().findFirst();
    }

    public int totalCount() {
        return count("SELECT COUNT(*) FROM gateway_outbox");
    }

    public int pendingCount() {
        return count("SELECT COUNT(*) FROM gateway_outbox WHERE status = 'PENDING'");
    }

    public int deliveredCount() {
        return count("SELECT COUNT(*) FROM gateway_outbox WHERE status = 'DELIVERED'");
    }

    private int count(String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }

    private static OutboxEntry mapEntry(ResultSet result, int rowNumber) throws SQLException {
        OffsetDateTime delivered = result.getObject("delivered_at", OffsetDateTime.class);
        return new OutboxEntry(
                result.getObject("idempotency_key", UUID.class),
                IngressKind.valueOf(result.getString("kind")),
                result.getInt("schema_version"),
                result.getString("payload_json"),
                DeliveryStatus.valueOf(result.getString("status")),
                result.getInt("attempt_count"),
                result.getObject("next_attempt_at", OffsetDateTime.class).toInstant(),
                result.getObject("created_at", OffsetDateTime.class).toInstant(),
                delivered == null ? null : delivered.toInstant(),
                result.getString("last_error_code"),
                result.getObject("dependency_idempotency_key", UUID.class));
    }

    private static OffsetDateTime atOffset(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    public enum DeliveryStatus {
        PENDING,
        DELIVERING,
        DELIVERED,
        DEAD_LETTER
    }

    public enum Priority {
        HIGH,
        LOCATION
    }

    public record FailureUpdate(
            OutboxEntry entry,
            int attemptCount,
            Instant nextAttemptAt,
            boolean deadLetter,
            String errorCode) {
        public FailureUpdate {
            Objects.requireNonNull(entry, "entry");
            Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
            if (attemptCount < 1) {
                throw new IllegalArgumentException("attemptCount must be positive");
            }
            if (errorCode == null || errorCode.isBlank()) {
                throw new IllegalArgumentException("errorCode must not be blank");
            }
        }
    }

    public record OutboxEntry(
            UUID idempotencyKey,
            IngressKind kind,
            int schemaVersion,
            String payloadJson,
            DeliveryStatus status,
            int attemptCount,
            Instant nextAttemptAt,
            Instant createdAt,
            Instant deliveredAt,
            String lastErrorCode,
            UUID dependencyIdempotencyKey) {
        private OutboxEntry claimedUntil(Instant leaseExpiresAt) {
            return new OutboxEntry(
                    idempotencyKey, kind, schemaVersion, payloadJson, DeliveryStatus.DELIVERING,
                    attemptCount, leaseExpiresAt, createdAt, deliveredAt, lastErrorCode, dependencyIdempotencyKey);
        }

        public GatewayIngressEnvelope toEnvelope() {
            if (payloadJson == null) {
                throw new IllegalStateException("delivered payload has already been removed");
            }
            return new GatewayIngressEnvelope(
                    schemaVersion, idempotencyKey, kind, createdAt, payloadJson);
        }
    }
}
