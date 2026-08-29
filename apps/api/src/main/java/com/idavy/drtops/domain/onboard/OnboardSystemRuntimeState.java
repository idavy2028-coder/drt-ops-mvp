package com.idavy.drtops.domain.onboard;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "onboard_system_runtime_state")
public class OnboardSystemRuntimeState {

    public static final int MAX_WARNING_CODES = 32;
    public static final int MAX_WARNING_CODE_LENGTH = 80;

    @Id
    private UUID onboardSystemId;

    private UUID activeLocationTerminalId;

    @Column(nullable = false)
    private int primaryRecoveryStreak;

    @Column(nullable = false)
    private boolean primaryEligible;

    private OffsetDateTime lastPrimaryValidAt;
    private OffsetDateTime lastLocationSwitchAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<String> warningCodes;

    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    @Version
    @Column(name = "runtime_version")
    private long runtimeVersion;

    protected OnboardSystemRuntimeState() {
    }

    private OnboardSystemRuntimeState(UUID onboardSystemId, OffsetDateTime createdAt) {
        this.onboardSystemId = Objects.requireNonNull(onboardSystemId, "onboardSystemId");
        this.primaryRecoveryStreak = 0;
        this.primaryEligible = true;
        this.warningCodes = List.of();
        this.updatedAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public static OnboardSystemRuntimeState initialize(
            UUID onboardSystemId, OffsetDateTime createdAt) {
        return new OnboardSystemRuntimeState(onboardSystemId, createdAt);
    }

    public void selectLocationSource(UUID terminalId, OffsetDateTime switchedAt) {
        UUID nextTerminalId = Objects.requireNonNull(terminalId, "terminalId");
        OffsetDateTime nextSwitchedAt = requireMonotonicTime(switchedAt, "switchedAt");
        this.activeLocationTerminalId = nextTerminalId;
        this.primaryRecoveryStreak = 0;
        this.lastLocationSwitchAt = nextSwitchedAt;
        this.updatedAt = nextSwitchedAt;
    }

    public void clearLocationSource(OffsetDateTime changedAt) {
        OffsetDateTime nextChangedAt = requireMonotonicTime(changedAt, "changedAt");
        this.activeLocationTerminalId = null;
        this.primaryRecoveryStreak = 0;
        this.lastLocationSwitchAt = nextChangedAt;
        this.updatedAt = nextChangedAt;
    }

    public int recordPrimaryRecovery() {
        return ++primaryRecoveryStreak;
    }

    public void resetPrimaryRecovery() {
        primaryRecoveryStreak = 0;
    }

    public void recordPrimaryValidAt(OffsetDateTime validAt) {
        OffsetDateTime candidate = Objects.requireNonNull(validAt, "validAt");
        if (lastPrimaryValidAt != null && candidate.isBefore(lastPrimaryValidAt)) {
            throw new IllegalArgumentException("validAt must not be before lastPrimaryValidAt");
        }
        this.lastPrimaryValidAt = candidate;
        if (candidate.isAfter(updatedAt)) {
            updatedAt = candidate;
        }
    }

    public void setPrimaryEligible(boolean eligible, OffsetDateTime changedAt) {
        OffsetDateTime nextChangedAt = requireMonotonicTime(changedAt, "changedAt");
        this.primaryEligible = eligible;
        this.updatedAt = nextChangedAt;
    }

    public void replaceWarningCodes(Collection<String> warningCodes, OffsetDateTime changedAt) {
        Objects.requireNonNull(warningCodes, "warningCodes");
        if (warningCodes.size() > MAX_WARNING_CODES) {
            throw new IllegalArgumentException("warningCodes must contain at most " + MAX_WARNING_CODES + " items");
        }
        List<String> nextWarningCodes = warningCodes.stream()
                .map(code -> requireText(code, "warningCode", MAX_WARNING_CODE_LENGTH))
                .distinct()
                .toList();
        OffsetDateTime nextChangedAt = requireMonotonicTime(changedAt, "changedAt");
        this.warningCodes = nextWarningCodes;
        this.updatedAt = nextChangedAt;
    }

    public UUID getOnboardSystemId() { return onboardSystemId; }
    public UUID getActiveLocationTerminalId() { return activeLocationTerminalId; }
    public int getPrimaryRecoveryStreak() { return primaryRecoveryStreak; }
    public boolean isPrimaryEligible() { return primaryEligible; }
    public OffsetDateTime getLastPrimaryValidAt() { return lastPrimaryValidAt; }
    public OffsetDateTime getLastLocationSwitchAt() { return lastLocationSwitchAt; }
    public List<String> getWarningCodes() { return List.copyOf(warningCodes); }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public long getRuntimeVersion() { return runtimeVersion; }

    private OffsetDateTime requireMonotonicTime(OffsetDateTime value, String field) {
        OffsetDateTime candidate = Objects.requireNonNull(value, field);
        if (candidate.isBefore(updatedAt)) {
            throw new IllegalArgumentException(field + " must not be before updatedAt");
        }
        return candidate;
    }

    private static String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (value.codePointCount(0, value.length()) > maxLength) {
            throw new IllegalArgumentException(field + " must not exceed " + maxLength + " characters");
        }
        return value;
    }
}
