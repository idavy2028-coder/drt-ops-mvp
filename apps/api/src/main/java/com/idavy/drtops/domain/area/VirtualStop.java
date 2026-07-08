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
}
