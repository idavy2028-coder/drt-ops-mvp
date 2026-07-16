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

    @Column(length = 1000)
    private String boundary;

    @Column(length = 1000)
    private String draftBoundary;

    @Column(nullable = false)
    private LocalTime serviceStart;

    @Column(nullable = false)
    private LocalTime serviceEnd;

    @Column(nullable = false)
    private UUID ruleSetId;

    @Column(nullable = false)
    private boolean enabled;

    @Column(nullable = false, length = 40)
    private String boundarySource;

    @Column(nullable = false)
    private int boundaryVersion;

    @Column(length = 40)
    private String draftBoundarySource;

    @Column(nullable = false)
    private int draftBoundaryVersion;

    @Column(length = 20, nullable = false)
    private String coordinateSystem;

    private OffsetDateTime publishedAt;

    @Column(nullable = false)
    private OffsetDateTime createdAt;

    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    protected ServiceArea() {
    }

    private ServiceArea(UUID id, String name, String boundary, LocalTime serviceStart, LocalTime serviceEnd, UUID ruleSetId) {
        this.id = id;
        this.name = name;
        this.boundary = null;
        this.draftBoundary = boundary;
        this.serviceStart = serviceStart;
        this.serviceEnd = serviceEnd;
        this.ruleSetId = ruleSetId;
        this.enabled = true;
        this.boundarySource = "LEGACY";
        this.boundaryVersion = 0;
        this.draftBoundarySource = "MANUAL";
        this.draftBoundaryVersion = 1;
        this.coordinateSystem = "GCJ02";
        this.createdAt = OffsetDateTime.now();
        this.updatedAt = createdAt;
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

    public String getDraftBoundary() {
        return draftBoundary;
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

    public String getBoundarySource() {
        return boundarySource;
    }

    public int getBoundaryVersion() {
        return boundaryVersion;
    }

    public String getDraftBoundarySource() {
        return draftBoundarySource;
    }

    public int getDraftBoundaryVersion() {
        return draftBoundaryVersion;
    }

    public String getCoordinateSystem() {
        return coordinateSystem;
    }

    public OffsetDateTime getPublishedAt() {
        return publishedAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    void replaceDraftBoundary(String boundaryWkt, String source) {
        draftBoundary = boundaryWkt;
        draftBoundarySource = source;
        draftBoundaryVersion++;
        coordinateSystem = "GCJ02";
        updatedAt = OffsetDateTime.now();
    }

    void publishDraft() {
        if (draftBoundary == null) {
            throw new IllegalStateException("服务区没有可发布的边界草稿");
        }
        boundary = draftBoundary;
        boundarySource = draftBoundarySource;
        boundaryVersion = draftBoundaryVersion;
        publishedAt = OffsetDateTime.now();
        enabled = true;
        coordinateSystem = "GCJ02";
        updatedAt = publishedAt;
    }
}
