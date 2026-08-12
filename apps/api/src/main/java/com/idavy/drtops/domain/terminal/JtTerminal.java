package com.idavy.drtops.domain.terminal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "jt_terminals")
public class JtTerminal {

    public enum Status { PENDING, ACTIVE, SUSPENDED, RETIRED }

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 30)
    private String terminalPhone;

    @Column(nullable = false, unique = true, length = 80)
    private String terminalCode;

    @Column(nullable = false, length = 80)
    private String manufacturerId;

    @Column(nullable = false, length = 120)
    private String model;

    @Column(nullable = false, length = 40)
    private String protocolVersion;

    @Column(nullable = false, length = 20)
    private String sourceCoordinateSystem;

    @Column(length = 40)
    private String activeSafetyStandard;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String activeSafetyModules;

    @Column(nullable = false)
    private boolean jt1078Enabled;

    @Column(length = 80)
    private String attachmentUploadProfile;

    @Column(length = 120)
    private String mediaServerProfileId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(nullable = false, length = 64, columnDefinition = "char(64)")
    private String authTokenHash;

    @Column(nullable = false)
    private int authTokenVersion;

    private OffsetDateTime lastRegisteredAt;
    private OffsetDateTime lastAuthenticatedAt;
    private OffsetDateTime lastSeenAt;
    private UUID createdBy;

    @Column(nullable = false)
    private OffsetDateTime createdAt;

    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    @Version
    private long version;

    protected JtTerminal() {
    }

    private JtTerminal(
            UUID id,
            String terminalPhone,
            String terminalCode,
            String manufacturerId,
            String model,
            String protocolVersion,
            String sourceCoordinateSystem,
            UUID createdBy) {
        this.id = Objects.requireNonNull(id, "id");
        this.terminalPhone = requireText(terminalPhone, "terminalPhone");
        this.terminalCode = requireText(terminalCode, "terminalCode");
        this.manufacturerId = requireText(manufacturerId, "manufacturerId");
        this.model = requireText(model, "model");
        this.protocolVersion = requireText(protocolVersion, "protocolVersion");
        this.sourceCoordinateSystem = requireCoordinateSystem(sourceCoordinateSystem);
        this.activeSafetyModules = "[]";
        this.status = Status.PENDING;
        this.authTokenHash = unregisteredHash();
        this.authTokenVersion = 1;
        this.createdBy = Objects.requireNonNull(createdBy, "createdBy");
        this.createdAt = OffsetDateTime.now();
        this.updatedAt = createdAt;
    }

    public static JtTerminal preset(
            UUID id,
            String terminalPhone,
            String terminalCode,
            String manufacturerId,
            String model,
            String protocolVersion,
            String sourceCoordinateSystem,
            UUID createdBy) {
        return new JtTerminal(id, terminalPhone, terminalCode, manufacturerId, model,
                protocolVersion, sourceCoordinateSystem, createdBy);
    }

    public void completeRegistration(int tokenVersion, String tokenSha256) {
        requireMutable();
        if (tokenVersion <= 0) {
            throw new IllegalArgumentException("tokenVersion must be positive");
        }
        this.authTokenHash = requireSha256(tokenSha256);
        this.authTokenVersion = tokenVersion;
        this.lastRegisteredAt = OffsetDateTime.now();
        touch();
    }

    public void beginAuthenticationRotation() {
        requireMutable();
        if (status != Status.ACTIVE && status != Status.SUSPENDED) {
            throw new IllegalStateException("terminal cannot rotate authentication from " + status);
        }
        authTokenVersion++;
        authTokenHash = unregisteredHash();
        lastRegisteredAt = null;
        status = Status.SUSPENDED;
        touch();
    }

    public void activate(boolean hasActiveBinding) {
        requireMutable();
        if (status != Status.PENDING && status != Status.SUSPENDED) {
            throw new IllegalStateException("terminal cannot be activated from " + status);
        }
        if (lastRegisteredAt == null || !hasActiveBinding) {
            throw new IllegalStateException("terminal requires completed registration and active binding");
        }
        status = Status.ACTIVE;
        touch();
    }

    public void suspend() {
        requireMutable();
        if (status != Status.ACTIVE) {
            throw new IllegalStateException("terminal cannot be suspended from " + status);
        }
        status = Status.SUSPENDED;
        touch();
    }

    public void retire() {
        requireMutable();
        status = Status.RETIRED;
        touch();
    }

    public void retireAndInvalidateAuthentication() {
        requireMutable();
        authTokenVersion++;
        authTokenHash = unregisteredHash();
        status = Status.RETIRED;
        touch();
    }

    public void prepareForReplacementRegistration() {
        requireMutable();
        if (status != Status.PENDING) {
            throw new IllegalStateException("replacement terminal must be pending");
        }
        authTokenVersion++;
        authTokenHash = unregisteredHash();
        lastRegisteredAt = null;
        touch();
    }

    public void touch() {
        updatedAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public String getTerminalPhone() { return terminalPhone; }
    public String getTerminalCode() { return terminalCode; }
    public String getManufacturerId() { return manufacturerId; }
    public String getModel() { return model; }
    public String getProtocolVersion() { return protocolVersion; }
    public String getSourceCoordinateSystem() { return sourceCoordinateSystem; }
    public Status getStatus() { return status; }
    public String getAuthTokenHash() { return authTokenHash; }
    public int getAuthTokenVersion() { return authTokenVersion; }
    public OffsetDateTime getLastRegisteredAt() { return lastRegisteredAt; }
    public long getVersion() { return version; }

    private void requireMutable() {
        if (status == Status.RETIRED) {
            throw new IllegalStateException("retired terminal is immutable");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static String requireCoordinateSystem(String value) {
        if (!"GCJ02".equals(value) && !"WGS84".equals(value)) {
            throw new IllegalArgumentException("sourceCoordinateSystem is invalid");
        }
        return value;
    }

    static String requireSha256(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("token digest is invalid");
        }
        return value;
    }

    private static String unregisteredHash() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        try {
            return HexFormat.of().formatHex(bytes);
        } finally {
            java.util.Arrays.fill(bytes, (byte) 0);
        }
    }
}
