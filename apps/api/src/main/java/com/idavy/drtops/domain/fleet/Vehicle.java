package com.idavy.drtops.domain.fleet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "vehicles")
public class Vehicle {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 30)
    private String plateNumber;

    @Column(nullable = false, length = 60)
    private String vehicleType;

    @Column(nullable = false)
    private int capacity;

    @Column(nullable = false, length = 40)
    private String currentStatus;

    @Column(length = 120)
    private String currentLocation;

    @Column(nullable = false, length = 100)
    private String fleetName;

    @Column(nullable = false)
    private boolean dispatchable;

    @Column(nullable = false)
    private OffsetDateTime createdAt;

    protected Vehicle() {
    }

    private Vehicle(
            UUID id,
            String plateNumber,
            String vehicleType,
            int capacity,
            String currentStatus,
            String currentLocation,
            String fleetName,
            boolean dispatchable) {
        this.id = id;
        this.plateNumber = plateNumber;
        this.vehicleType = vehicleType;
        this.capacity = capacity;
        this.currentStatus = currentStatus;
        this.currentLocation = currentLocation;
        this.fleetName = fleetName;
        this.dispatchable = dispatchable;
        this.createdAt = OffsetDateTime.now();
    }

    public static Vehicle create(
            UUID id,
            String plateNumber,
            String vehicleType,
            int capacity,
            String currentStatus,
            String currentLocationWkt,
            String fleetName,
            boolean dispatchable) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        return new Vehicle(id, plateNumber, vehicleType, capacity, currentStatus, currentLocationWkt, fleetName, dispatchable);
    }

    public UUID getId() {
        return id;
    }

    public String getPlateNumber() {
        return plateNumber;
    }

    public String getVehicleType() {
        return vehicleType;
    }

    public int getCapacity() {
        return capacity;
    }

    public String getCurrentStatus() {
        return currentStatus;
    }

    public String getCurrentLocation() {
        return currentLocation;
    }

    public String getFleetName() {
        return fleetName;
    }

    public boolean isDispatchable() {
        return dispatchable;
    }
}
