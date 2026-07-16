package com.idavy.drtops.domain.location;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class PostgresIdempotencyKeyLock implements IdempotencyKeyLock {

    private final JdbcTemplate jdbcTemplate;

    public PostgresIdempotencyKeyLock(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void acquire(UUID idempotencyKey) {
        long lockKey = idempotencyKey.getMostSignificantBits() ^ idempotencyKey.getLeastSignificantBits();
        jdbcTemplate.query("select pg_advisory_xact_lock(?)", resultSet -> { }, lockKey);
    }
}
