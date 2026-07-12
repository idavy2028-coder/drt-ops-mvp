package com.idavy.drtops.domain.fleet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "drivers")
public class Driver {

    @Id
    private UUID id;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(nullable = false, unique = true, length = 30)
    private String phone;

    @Column(nullable = false, length = 40)
    private String qualificationStatus;

    private OffsetDateTime shiftStart;

    private OffsetDateTime shiftEnd;

    @Column(nullable = false, length = 40)
    private String currentStatus;

    @Column(nullable = false, length = 100)
    private String fleetName;

    @Column(nullable = false)
    private OffsetDateTime createdAt;

    protected Driver() {
    }

    private Driver(
            UUID id,
            String name,
            String phone,
            String qualificationStatus,
            OffsetDateTime shiftStart,
            OffsetDateTime shiftEnd,
            String currentStatus,
            String fleetName) {
        this.id = id;
        this.name = name;
        this.phone = phone;
        this.qualificationStatus = qualificationStatus;
        this.shiftStart = shiftStart;
        this.shiftEnd = shiftEnd;
        this.currentStatus = currentStatus;
        this.fleetName = fleetName;
        this.createdAt = OffsetDateTime.now();
    }

    public static Driver create(
            UUID id,
            String name,
            String phone,
            String qualificationStatus,
            OffsetDateTime shiftStart,
            OffsetDateTime shiftEnd,
            String currentStatus,
            String fleetName) {
        return new Driver(id, name, phone, qualificationStatus, shiftStart, shiftEnd, currentStatus, fleetName);
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getPhone() {
        return phone;
    }

    public String getQualificationStatus() {
        return qualificationStatus;
    }

    public OffsetDateTime getShiftStart() {
        return shiftStart;
    }

    public OffsetDateTime getShiftEnd() {
        return shiftEnd;
    }

    public String getCurrentStatus() {
        return currentStatus;
    }

    public String getFleetName() {
        return fleetName;
    }
}
