package com.idavy.drtops.domain.location;

import java.sql.SQLException;
import java.util.UUID;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JtGatewayIngressReceiptClaimer {

    private static final String POSTGRES_CLAIM = """
            insert into jt_gateway_ingress_receipts (
              idempotency_key, final_status, reason_codes, created_at
            ) values (?, 'PROCESSING', '[]', current_timestamp)
            on conflict (idempotency_key) do nothing
            """;
    private static final String H2_CLAIM = """
            insert into jt_gateway_ingress_receipts (
              idempotency_key, final_status, reason_codes, created_at
            ) select ?, 'PROCESSING', '[]' format json, current_timestamp
            where not exists (
              select 1 from jt_gateway_ingress_receipts where idempotency_key = ?
            )
            """;

    private final JdbcTemplate jdbcTemplate;

    public JtGatewayIngressReceiptClaimer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public int claim(UUID idempotencyKey) {
        if (isPostgreSql()) {
            return jdbcTemplate.update(POSTGRES_CLAIM, idempotencyKey);
        }
        return jdbcTemplate.update(H2_CLAIM, idempotencyKey, idempotencyKey);
    }

    private boolean isPostgreSql() {
        return Boolean.TRUE.equals(jdbcTemplate.execute((ConnectionCallback<Boolean>) connection -> {
            try {
                return "PostgreSQL".equals(connection.getMetaData().getDatabaseProductName());
            } catch (SQLException exception) {
                throw new IllegalStateException("cannot detect ingress receipt database", exception);
            }
        }));
    }
}
