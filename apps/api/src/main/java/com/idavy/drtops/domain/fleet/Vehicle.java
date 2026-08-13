package com.idavy.drtops.domain.fleet;

import com.idavy.drtops.domain.location.LocationSource;
import com.idavy.drtops.domain.location.LocationQualityStatus;
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
    private UUID currentLocationTerminalId;
    @Enumerated(EnumType.STRING) @Column(length = 20) private LocationQualityStatus currentLocationQualityStatus;
    @org.hibernate.annotations.JdbcTypeCode(SqlTypes.JSON) private String currentLocationQualityReasons;
    private OffsetDateTime currentLocationGatewayReceivedAt;
    private java.math.BigDecimal currentLocationSpeedKph;
    private Integer currentLocationDirectionDegrees;
    @Column(nullable = false) private boolean currentLocationStale;

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

    public void reserveForDispatch() {
        if (!dispatchable) {
            throw new IllegalStateException("Vehicle is not dispatchable");
        }
        requireStatus("IDLE");
        this.currentStatus = "DISPATCHED";
    }

    public void startService() {
        if ("IN_SERVICE".equals(currentStatus)) {
            return;
        }
        if (!"DISPATCHED".equals(currentStatus) && !"IDLE".equals(currentStatus)) {
            throw new IllegalStateException("Vehicle status " + currentStatus + " cannot start service");
        }
        this.currentStatus = "IN_SERVICE";
    }

    public void releaseToIdle() {
        if ("IDLE".equals(currentStatus)) {
            return;
        }
        if (!"DISPATCHED".equals(currentStatus) && !"IN_SERVICE".equals(currentStatus)) {
            throw new IllegalStateException("Vehicle status " + currentStatus + " cannot be released");
        }
        this.currentStatus = "IDLE";
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
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
    public UUID getCurrentLocationTerminalId() { return currentLocationTerminalId; }
    public LocationQualityStatus getCurrentLocationQualityStatus() { return currentLocationQualityStatus; }
    public String getCurrentLocationQualityReasons() { return currentLocationQualityReasons; }
    public OffsetDateTime getCurrentLocationGatewayReceivedAt() { return currentLocationGatewayReceivedAt; }
    public java.math.BigDecimal getCurrentLocationSpeedKph() { return currentLocationSpeedKph; }
    public Integer getCurrentLocationDirectionDegrees() { return currentLocationDirectionDegrees; }
    public boolean isCurrentLocationStale() { return currentLocationStale; }

    public void applyGpsLocationSnapshot(com.idavy.drtops.domain.location.VehicleLocationEvent event) {
        applyLocationSnapshot(event.getLocation(), null, LocationSource.GPS_DEVICE, "GCJ02", event.getDriverReportedAt(),
                event.getRecordedAt(), event.getId(), null);
        currentLocationTerminalId = event.getTerminalId(); currentLocationQualityStatus = event.getQualityStatus();
        currentLocationQualityReasons = event.getQualityReasons(); currentLocationGatewayReceivedAt = event.getGatewayReceivedAt();
        currentLocationSpeedKph = event.getSpeedKph(); currentLocationDirectionDegrees = event.getDirectionDegrees();
        currentLocationStale = false;
    }

    private void requireStatus(String expectedStatus) {
        if (!expectedStatus.equals(currentStatus)) {
            throw new IllegalStateException(
                    "Vehicle status " + currentStatus + " does not match " + expectedStatus);
        }
    }
}
