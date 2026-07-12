package com.idavy.drtops.domain.area;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "service_areas")
public class ServiceArea {

    @Id
    private UUID id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 1000)
    private String boundary;

    @Column(nullable = false)
    private LocalTime serviceStart;

    @Column(nullable = false)
    private LocalTime serviceEnd;

    @Column(nullable = false)
    private UUID ruleSetId;

    @Column(nullable = false)
    private boolean enabled;

    @Column(nullable = false)
    private OffsetDateTime createdAt;

    protected ServiceArea() {
    }

    private ServiceArea(UUID id, String name, String boundary, LocalTime serviceStart, LocalTime serviceEnd, UUID ruleSetId) {
        this.id = id;
        this.name = name;
        this.boundary = boundary;
        this.serviceStart = serviceStart;
        this.serviceEnd = serviceEnd;
        this.ruleSetId = ruleSetId;
        this.enabled = true;
        this.createdAt = OffsetDateTime.now();
    }

    public static ServiceArea create(
            UUID id,
            String name,
            String boundaryWkt,
            String serviceStart,
            String serviceEnd,
            UUID ruleSetId) {
        return new ServiceArea(
                id,
                name,
                boundaryWkt,
                LocalTime.parse(serviceStart),
                LocalTime.parse(serviceEnd),
                ruleSetId);
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getBoundary() {
        return boundary;
    }

    public LocalTime getServiceStart() {
        return serviceStart;
    }

    public LocalTime getServiceEnd() {
        return serviceEnd;
    }

    public UUID getRuleSetId() {
        return ruleSetId;
    }

    public boolean isEnabled() {
        return enabled;
    }
}
