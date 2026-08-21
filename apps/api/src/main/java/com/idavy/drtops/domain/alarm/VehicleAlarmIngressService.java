package com.idavy.drtops.domain.alarm;

import com.idavy.drtops.domain.location.JtGatewayIngressReceipt;
import com.idavy.drtops.domain.location.JtGatewayIngressReceiptClaimer;
import com.idavy.drtops.domain.location.JtGatewayIngressReceiptRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Service
public class VehicleAlarmIngressService {
    private static final Instant POSTGRES_TIMESTAMPTZ_MIN = Instant.ofEpochSecond(-210_866_803_200L);
    private static final Instant POSTGRES_TIMESTAMPTZ_MAX = Instant.ofEpochSecond(9_224_318_016_000L)
            .minusNanos(1_000);
    private final AlarmStore store;
    private final JtGatewayIngressReceiptRepository receipts;
    private final JtGatewayIngressReceiptClaimer receiptClaimer;
    private final TransactionTemplate itemTransaction;
    public VehicleAlarmIngressService(AlarmStore store) {
        this.store = Objects.requireNonNull(store);
        this.receipts = null;
        this.receiptClaimer = null;
        this.itemTransaction = null;
    }
    @Autowired
    public VehicleAlarmIngressService(
            AlarmStore store,
            JtGatewayIngressReceiptRepository receipts,
            JtGatewayIngressReceiptClaimer receiptClaimer,
            PlatformTransactionManager transactionManager) {
        this.store = Objects.requireNonNull(store);
        this.receipts = Objects.requireNonNull(receipts);
        this.receiptClaimer = Objects.requireNonNull(receiptClaimer);
        this.itemTransaction = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        this.itemTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    @Transactional
    public void ingest(List<AlarmFact> batch) {
        if (batch == null || batch.isEmpty() || batch.size() > 50) {
            throw new IllegalArgumentException("invalid alarm batch");
        }
        batch.forEach(VehicleAlarmIngressService::validate);
        batch.stream()
                .map(AlarmFact::terminalId)
                .distinct()
                .sorted(Comparator.comparing(UUID::toString))
                .forEach(store::lockTerminal);
        batch.forEach(fact -> {
            if (!store.matchesBindingAt(
                    fact.terminalId(), fact.vehicleId(), fact.gatewayReceivedAt())) {
                throw new IllegalArgumentException("terminal vehicle binding mismatch");
            }
        });
        batch.forEach(this::ingestValidated);
    }
    public Result ingest(UUID idempotencyKey, AlarmFact fact) {
        if (idempotencyKey == null || itemTransaction == null) {
            throw new IllegalArgumentException("alarm ingress item must be correlatable");
        }
        return itemTransaction.execute(status -> ingestClaimed(idempotencyKey, fact));
    }
    private Result ingestClaimed(UUID idempotencyKey, AlarmFact fact) {
        if (receiptClaimer.claim(idempotencyKey) == 0) {
            JtGatewayIngressReceipt receipt = receipts.findById(idempotencyKey).orElseThrow();
            return new Result(idempotencyKey,
                    "ACCEPTED".equals(receipt.getFinalStatus()) ? "REPLAYED" : "REJECTED",
                    receipt.getReasonCodes());
        }
        Result result = applyOne(idempotencyKey, fact);
        JtGatewayIngressReceipt receipt = receipts.findById(idempotencyKey).orElseThrow();
        receipt.complete("REJECTED".equals(result.status()) ? "REJECTED" : "ACCEPTED",
                result.reasonCodes(), OffsetDateTime.now(ZoneOffset.UTC));
        return result;
    }
    private Result applyOne(UUID idempotencyKey, AlarmFact fact) {
        try {
            validate(fact);
        } catch (IllegalArgumentException invalid) {
            return Result.rejected(idempotencyKey, "INVALID_PAYLOAD");
        }
        try {
            store.lockTerminal(fact.terminalId());
        } catch (EmptyResultDataAccessException unknownTerminal) {
            return Result.rejected(idempotencyKey, "TERMINAL_BINDING_MISMATCH");
        }
        if (!store.matchesBindingAt(fact.terminalId(), fact.vehicleId(), fact.gatewayReceivedAt())) {
            return Result.rejected(idempotencyKey, "TERMINAL_BINDING_MISMATCH");
        }
        if ("END".equals(fact.state())) {
            var open = store.findOpenStart(fact);
            if (open.isPresent()) {
                if (fact.occurredAt().isBefore(open.get().getOccurredAt())) {
                    return Result.rejected(idempotencyKey, "ALARM_STATE_INVALID");
                }
                store.end(open.get(), fact.occurredAt());
                store.appendOutbox(open.get(), "ALARM_ENDED");
                return Result.accepted(idempotencyKey);
            }
            return store.findStart(fact).isPresent()
                    ? Result.replayed(idempotencyKey)
                    : Result.rejected(idempotencyKey, "ALARM_STATE_INVALID");
        }
        String key = keyFor(fact);
        if (store.findByDeduplicationKey(key).isPresent() || store.findOpenStart(fact).isPresent()) {
            return Result.replayed(idempotencyKey);
        }
        AlarmStore.LocationReference location = store.findLocation(
                fact.positionIdempotencyKey(), fact.terminalId(), fact.vehicleId()).orElse(null);
        if (location == null) {
            return Result.rejected(idempotencyKey,
                    store.hasLocationDependency(fact.positionIdempotencyKey())
                            ? "POSITION_DEPENDENCY_MISMATCH"
                            : "POSITION_INGRESS_NOT_SETTLED");
        }
        VehicleAlarm alarm = store.save(VehicleAlarm.start(fact, key, location));
        store.appendOutbox(alarm, "ALARM_CREATED");
        return Result.accepted(idempotencyKey);
    }
    private void ingestValidated(AlarmFact fact) {
        if ("END".equals(fact.state())) {
            store.findOpenStart(fact).ifPresent(start -> { store.end(start, fact.occurredAt()); store.appendOutbox(start, "ALARM_ENDED"); });
            return;
        }
        String key = keyFor(fact);
        if (store.findByDeduplicationKey(key).isPresent()) return;
        if (store.findOpenStart(fact).isPresent()) return;
        AlarmStore.LocationReference location = store.findLocation(
                        fact.positionIdempotencyKey(), fact.terminalId(), fact.vehicleId())
                .orElseThrow(() -> new IllegalStateException("position ingress is not settled"));
        VehicleAlarm alarm = store.save(VehicleAlarm.start(fact, key, location));
        store.appendOutbox(alarm, "ALARM_CREATED");
    }
    private static void validate(AlarmFact fact) {
        if (fact == null
                || fact.terminalId() == null
                || fact.vehicleId() == null
                || !validText(fact.standard(), 40)
                || !("ADAS".equals(fact.module()) || "DMS".equals(fact.module()))
                || fact.terminalAlarmId() < 0 || fact.terminalAlarmId() > 0xffff_ffffL
                || fact.typeCode() < 0 || fact.typeCode() > 255
                || !validText(fact.alarmType(), 80)
                || !("START".equals(fact.state()) || "END".equals(fact.state()))
                || fact.level() < 0 || fact.level() > 255
                || !validText(fact.terminalAlarmIdentifier(), 64)
                || !validPostgresTimestamp(fact.occurredAt())
                || !validPostgresTimestamp(fact.gatewayReceivedAt())
                || !fitsNumeric(fact.longitude(), 10, 7)
                || fact.longitude().compareTo(BigDecimal.valueOf(-180)) < 0
                || fact.longitude().compareTo(BigDecimal.valueOf(180)) > 0
                || !fitsNumeric(fact.latitude(), 10, 7)
                || fact.latitude().compareTo(BigDecimal.valueOf(-90)) < 0
                || fact.latitude().compareTo(BigDecimal.valueOf(90)) > 0
                || (fact.speedKph() != null
                    && (!fitsNumeric(fact.speedKph(), 6, 2) || fact.speedKph().signum() < 0))
                || fact.payloadDigest() == null
                || fact.positionIdempotencyKey() == null
                || !"UNASSESSED".equals(fact.locationQualityStatus())
                || !fact.payloadDigest().matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid alarm fact");
        }
    }
    private static boolean validText(String value, int maximumLength) {
        return value != null && !value.isBlank() && value.length() <= maximumLength;
    }
    private static boolean validPostgresTimestamp(Instant value) {
        return value != null && !value.isBefore(POSTGRES_TIMESTAMPTZ_MIN) && !value.isAfter(POSTGRES_TIMESTAMPTZ_MAX);
    }
    private static boolean fitsNumeric(BigDecimal value, int precision, int scale) {
        if (value == null) return false;
        BigDecimal normalized = value.stripTrailingZeros();
        long fractionalDigits = Math.max((long) normalized.scale(), 0L);
        long integerDigits = Math.max((long) normalized.precision() - normalized.scale(), 0L);
        return fractionalDigits <= scale && integerDigits <= precision - scale;
    }
    private static String keyFor(AlarmFact fact) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                (fact.terminalId()+"|"+fact.standard()+"|"+fact.module()+"|"+fact.terminalAlarmId()+"|"
                        +fact.terminalAlarmIdentifier()+"|"
                        +fact.typeCode()+"|"+fact.occurredAt()+"|"+fact.payloadDigest())
                        .getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    public record AlarmFact(UUID terminalId, UUID vehicleId, String standard, String module, int typeCode,
            String alarmType, long terminalAlarmId,
            String state, int level, String terminalAlarmIdentifier, Instant occurredAt, Instant gatewayReceivedAt,
            BigDecimal longitude, BigDecimal latitude, BigDecimal speedKph, UUID positionIdempotencyKey,
            String locationQualityStatus, String payloadDigest) {
        public AlarmFact endAt(Instant endedAt) { return new AlarmFact(terminalId, vehicleId, standard, module, typeCode,
                alarmType, terminalAlarmId, "END", level, terminalAlarmIdentifier, endedAt, gatewayReceivedAt,
                longitude, latitude, speedKph,
                positionIdempotencyKey, locationQualityStatus, payloadDigest); }
        public AlarmFact atPosition(UUID positionKey) { return new AlarmFact(terminalId, vehicleId, standard, module, typeCode,
                alarmType, terminalAlarmId, state, level, terminalAlarmIdentifier, occurredAt, gatewayReceivedAt,
                longitude, latitude, speedKph,
                positionKey, locationQualityStatus, payloadDigest); }
    }
    public record Result(UUID idempotencyKey, String status, List<String> reasonCodes) {
        static Result accepted(UUID key) { return new Result(key, "ACCEPTED", List.of()); }
        static Result replayed(UUID key) { return new Result(key, "REPLAYED", List.of()); }
        static Result rejected(UUID key, String reason) { return new Result(key, "REJECTED", List.of(reason)); }
    }
}
