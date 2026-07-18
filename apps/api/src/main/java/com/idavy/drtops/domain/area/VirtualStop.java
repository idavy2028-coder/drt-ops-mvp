package com.idavy.drtops.domain.area;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "virtual_stops")
public class VirtualStop {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID serviceAreaId;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 120)
    private String location;

    @Column(length = 300)
    private String address;

    @Column(length = 120)
    private String areaName;

    @Column(length = 20)
    private String coordinateSystem;

    @Column(length = 40)
    private String source;

    private OffsetDateTime verifiedAt;

    private UUID verifiedBy;

    private OffsetDateTime updatedAt;

    @Column(nullable = false)
    private int serviceRadiusMeters;

    @Column(nullable = false)
    private boolean boardingEnabled;

    @Column(nullable = false)
    private boolean alightingEnabled;

    @Column(nullable = false, length = 300)
    private String safetyNote;

    @Column(nullable = false)
    private boolean enabled;

    @Column(nullable = false)
    private OffsetDateTime createdAt;

    protected VirtualStop() {
    }

    private VirtualStop(
            UUID id,
            UUID serviceAreaId,
            String name,
            String location,
            int serviceRadiusMeters,
            boolean boardingEnabled,
            boolean alightingEnabled,
            String safetyNote) {
        this.id = id;
        this.serviceAreaId = serviceAreaId;
        this.name = name;
        this.location = location;
        this.serviceRadiusMeters = serviceRadiusMeters;
        this.boardingEnabled = boardingEnabled;
        this.alightingEnabled = alightingEnabled;
        this.safetyNote = safetyNote;
        this.enabled = true;
        this.createdAt = OffsetDateTime.now();
        this.updatedAt = this.createdAt;
    }

    public static VirtualStop create(
            UUID id,
            UUID serviceAreaId,
            String name,
            String locationWkt,
            int serviceRadiusMeters,
            boolean boardingEnabled,
            boolean alightingEnabled,
            String safetyNote) {
        if (serviceRadiusMeters <= 0) {
            throw new IllegalArgumentException("serviceRadiusMeters must be positive");
        }
        return new VirtualStop(
                id,
                serviceAreaId,
                name,
                locationWkt,
                serviceRadiusMeters,
                boardingEnabled,
                alightingEnabled,
                safetyNote);
    }

    public static VirtualStop createForPilot(
            UUID serviceAreaId,
            String serviceAreaName,
            String name,
            String address,
            String locationWkt,
            int serviceRadiusMeters,
            boolean boardingEnabled,
            boolean alightingEnabled,
            String safetyNote,
            boolean enabled,
            String source,
            UUID verifiedBy) {
        VirtualStop stop = create(UUID.randomUUID(), serviceAreaId, name, locationWkt, serviceRadiusMeters,
                boardingEnabled, alightingEnabled, safetyNote);
        stop.address = address;
        stop.areaName = serviceAreaName;
        stop.coordinateSystem = "GCJ-02";
        stop.source = source;
        stop.enabled = enabled;
        stop.verifiedAt = OffsetDateTime.now();
        stop.verifiedBy = verifiedBy;
        return stop;
    }

    void updateForPilot(
            String name,
            String address,
            String locationWkt,
            int serviceRadiusMeters,
            boolean boardingEnabled,
            boolean alightingEnabled,
            String safetyNote,
            boolean enabled,
            UUID verifiedBy) {
        if (serviceRadiusMeters <= 0) {
            throw new IllegalArgumentException("服务半径必须为正数");
        }
        this.name = name;
        this.address = address;
        this.location = locationWkt;
        this.serviceRadiusMeters = serviceRadiusMeters;
        this.boardingEnabled = boardingEnabled;
        this.alightingEnabled = alightingEnabled;
        this.safetyNote = safetyNote;
        this.enabled = enabled;
        this.coordinateSystem = "GCJ-02";
        this.source = "MANUAL";
        this.verifiedAt = OffsetDateTime.now();
        this.verifiedBy = verifiedBy;
        this.updatedAt = this.verifiedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getServiceAreaId() {
        return serviceAreaId;
    }

    public String getName() {
        return name;
    }

    public String getLocation() {
        return location;
    }

    public int getServiceRadiusMeters() {
        return serviceRadiusMeters;
    }

    public boolean isBoardingEnabled() {
        return boardingEnabled;
    }

    public boolean isAlightingEnabled() {
        return alightingEnabled;
    }

    public String getSafetyNote() {
        return safetyNote;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getAddress() { return address; }

    public String getAreaName() { return areaName; }

    public String getCoordinateSystem() { return coordinateSystem; }

    public String getSource() { return source; }

    public OffsetDateTime getVerifiedAt() { return verifiedAt; }

    public UUID getVerifiedBy() { return verifiedBy; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
