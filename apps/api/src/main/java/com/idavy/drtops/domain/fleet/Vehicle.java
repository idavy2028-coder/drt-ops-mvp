package com.idavy.drtops.domain.fleet;

import com.idavy.drtops.domain.location.LocationSource;
import com.idavy.drtops.domain.location.GeographyPoint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.locationtech.jts.geom.Point;

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

    @JdbcTypeCode(SqlTypes.GEOGRAPHY)
    @Column(columnDefinition = "geometry")
    private Point currentLocation;

    @Column(length = 300)
    private String currentLocationAddress;

    @Enumerated(EnumType.STRING)
    @Column(length = 40)
    private LocationSource currentLocationSource;

    @Column(length = 20)
    private String currentLocationCoordinateSystem;

    private OffsetDateTime currentLocationReportedAt;

    private OffsetDateTime currentLocationRecordedAt;

    private UUID currentLocationEventId;

    private UUID currentLocationTaskId;

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
        this.currentLocation = GeographyPoint.fromWkt(currentLocation);
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
        return GeographyPoint.toWkt(currentLocation);
    }

    public String getFleetName() {
        return fleetName;
    }

    public boolean isDispatchable() {
        return dispatchable;
    }

    public boolean applyLocationSnapshot(
            String location,
            String locationAddress,
            LocationSource source,
            String coordinateSystem,
            OffsetDateTime reportedAt,
            OffsetDateTime recordedAt,
            UUID eventId,
            UUID taskId) {
        if (currentLocationReportedAt != null && reportedAt.isBefore(currentLocationReportedAt)) {
            return false;
        }
        this.currentLocation = GeographyPoint.fromWkt(location);
        this.currentLocationAddress = locationAddress;
        this.currentLocationSource = source;
        this.currentLocationCoordinateSystem = coordinateSystem;
        this.currentLocationReportedAt = reportedAt;
        this.currentLocationRecordedAt = recordedAt;
        this.currentLocationEventId = eventId;
        this.currentLocationTaskId = taskId;
        return true;
    }

    public String getCurrentLocationAddress() {
        return currentLocationAddress;
    }

    public LocationSource getCurrentLocationSource() {
        return currentLocationSource;
    }

    public String getCurrentLocationCoordinateSystem() {
        return currentLocationCoordinateSystem;
    }

    public OffsetDateTime getCurrentLocationReportedAt() {
        return currentLocationReportedAt;
    }

    public OffsetDateTime getCurrentLocationRecordedAt() {
        return currentLocationRecordedAt;
    }

    public UUID getCurrentLocationEventId() {
        return currentLocationEventId;
    }

    public UUID getCurrentLocationTaskId() {
        return currentLocationTaskId;
    }
}
