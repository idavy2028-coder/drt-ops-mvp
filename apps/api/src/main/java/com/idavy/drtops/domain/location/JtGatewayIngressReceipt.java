package com.idavy.drtops.domain.location;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "jt_gateway_ingress_receipts")
public class JtGatewayIngressReceipt {

    @Id
    private UUID idempotencyKey;

    @Column(nullable = false, length = 20)
    private String finalStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<String> reasonCodes;

    @Column(nullable = false)
    private OffsetDateTime createdAt;

    private OffsetDateTime completedAt;

    protected JtGatewayIngressReceipt() {
    }

    public void complete(String status, List<String> reasons, OffsetDateTime completedAt) {
        if (!"ACCEPTED".equals(status) && !"REJECTED".equals(status)) {
            throw new IllegalArgumentException("receipt status must be final");
        }
        this.finalStatus = status;
        this.reasonCodes = List.copyOf(reasons);
        this.completedAt = completedAt;
    }

    public UUID getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getFinalStatus() {
        return finalStatus;
    }

    public List<String> getReasonCodes() {
        return List.copyOf(reasonCodes);
    }
}
