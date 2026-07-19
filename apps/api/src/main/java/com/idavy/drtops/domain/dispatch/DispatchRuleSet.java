package com.idavy.drtops.domain.dispatch;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "dispatch_rule_sets")
public class DispatchRuleSet {

    @Id
    private UUID id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false)
    private int maxWaitMinutes;

    @Column(nullable = false)
    private int maxDetourMinutes;

    @Column(nullable = false)
    private int bookingWindowMinutes;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal autoDispatchScoreThreshold;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal manualReviewScoreThreshold;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal waitWeight;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal detourWeight;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal stabilityWeight;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal utilizationWeight;

    @Column(nullable = false, length = 40)
    private String insertionPolicy;

    @Column(nullable = false)
    private boolean enabled;

    @Column(nullable = false)
    private OffsetDateTime createdAt;

    protected DispatchRuleSet() {
    }

    private DispatchRuleSet(UUID id, String name) {
        this.id = id;
        this.name = name;
        this.createdAt = OffsetDateTime.now();
        this.enabled = true;
    }

    public static DispatchRuleSet defaultRules(UUID id) {
        DispatchRuleSet ruleSet = new DispatchRuleSet(id, "Default dynamic insertion rules");
        ruleSet.updateRules(
                10,
                8,
                60,
                new BigDecimal("82.00"),
                new BigDecimal("62.00"),
                new BigDecimal("0.35"),
                new BigDecimal("0.20"),
                new BigDecimal("0.30"),
                new BigDecimal("0.15"),
                "DYNAMIC_INSERTION");
        return ruleSet;
    }

    public static DispatchRuleSet create(
            UUID id,
            String name,
            int maxWaitMinutes,
            int maxDetourMinutes,
            int bookingWindowMinutes,
            BigDecimal autoDispatchScoreThreshold,
            BigDecimal manualReviewScoreThreshold,
            BigDecimal waitWeight,
            BigDecimal detourWeight,
            BigDecimal stabilityWeight,
            BigDecimal utilizationWeight,
            String insertionPolicy) {
        DispatchRuleSet ruleSet = new DispatchRuleSet(id, name);
        ruleSet.updateRules(
                maxWaitMinutes,
                maxDetourMinutes,
                bookingWindowMinutes,
                autoDispatchScoreThreshold,
                manualReviewScoreThreshold,
                waitWeight,
                detourWeight,
                stabilityWeight,
                utilizationWeight,
                insertionPolicy);
        return ruleSet;
    }

    public void updateRules(
            int maxWaitMinutes,
            int maxDetourMinutes,
            int bookingWindowMinutes,
            BigDecimal autoDispatchScoreThreshold,
            BigDecimal manualReviewScoreThreshold,
            BigDecimal waitWeight,
            BigDecimal detourWeight,
            BigDecimal stabilityWeight,
            BigDecimal utilizationWeight,
            String insertionPolicy) {
        this.maxWaitMinutes = maxWaitMinutes;
        this.maxDetourMinutes = maxDetourMinutes;
        this.bookingWindowMinutes = bookingWindowMinutes;
        this.autoDispatchScoreThreshold = autoDispatchScoreThreshold;
        this.manualReviewScoreThreshold = manualReviewScoreThreshold;
        this.waitWeight = waitWeight;
        this.detourWeight = detourWeight;
        this.stabilityWeight = stabilityWeight;
        this.utilizationWeight = utilizationWeight;
        this.insertionPolicy = insertionPolicy;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public int getMaxWaitMinutes() {
        return maxWaitMinutes;
    }

    public int getMaxDetourMinutes() {
        return maxDetourMinutes;
    }

    public int getBookingWindowMinutes() {
        return bookingWindowMinutes;
    }

    public BigDecimal getAutoDispatchScoreThreshold() {
        return autoDispatchScoreThreshold;
    }

    public BigDecimal getManualReviewScoreThreshold() {
        return manualReviewScoreThreshold;
    }

    public BigDecimal getWaitWeight() {
        return waitWeight;
    }

    public BigDecimal getDetourWeight() {
        return detourWeight;
    }

    public BigDecimal getStabilityWeight() {
        return stabilityWeight;
    }

    public BigDecimal getUtilizationWeight() {
        return utilizationWeight;
    }

    public String getInsertionPolicy() {
        return insertionPolicy;
    }

    public boolean isEnabled() {
        return enabled;
    }
}
