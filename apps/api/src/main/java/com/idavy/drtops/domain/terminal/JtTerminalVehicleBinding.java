package com.idavy.drtops.domain.terminal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "jt_terminal_vehicle_bindings")
public class JtTerminalVehicleBinding {

    public enum Status { ACTIVE, UNBOUND }

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "terminal_id", nullable = false)
    private JtTerminal terminal;

    @Column(nullable = false)
    private UUID vehicleId;

    @Column(nullable = false)
    private OffsetDateTime validFrom;
    private OffsetDateTime validTo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(nullable = false, length = 500)
    private String bindingReason;

    @Column(length = 500)
    private String unbindingReason;
    private UUID boundBy;
    private UUID unboundBy;

    @Column(nullable = false)
    private OffsetDateTime createdAt;

    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    protected JtTerminalVehicleBinding() {
    }

    private JtTerminalVehicleBinding(JtTerminal terminal, UUID vehicleId, String reason, UUID actorId) {
        this.id = UUID.randomUUID();
        this.terminal = terminal;
        this.vehicleId = vehicleId;
        this.validFrom = OffsetDateTime.now();
        this.status = Status.ACTIVE;
        this.bindingReason = requireReason(reason);
        this.boundBy = actorId;
        this.createdAt = validFrom;
        this.updatedAt = validFrom;
    }

    public static JtTerminalVehicleBinding bind(
            JtTerminal terminal, UUID vehicleId, String reason, UUID actorId) {
        return new JtTerminalVehicleBinding(terminal, vehicleId, reason, actorId);
    }

    public void unbind(String reason, UUID actorId) {
        if (status != Status.ACTIVE) {
            throw new IllegalStateException("binding is already inactive");
        }
        status = Status.UNBOUND;
        validTo = OffsetDateTime.now();
        unbindingReason = requireReason(reason);
        unboundBy = actorId;
        updatedAt = validTo;
    }

    public UUID getId() { return id; }
    public JtTerminal getTerminal() { return terminal; }
    public UUID getVehicleId() { return vehicleId; }
    public OffsetDateTime getValidFrom() { return validFrom; }
    public OffsetDateTime getValidTo() { return validTo; }
    public Status getStatus() { return status; }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        return reason;
    }
}
