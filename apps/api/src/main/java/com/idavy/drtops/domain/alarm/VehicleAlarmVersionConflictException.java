package com.idavy.drtops.domain.alarm;

public class VehicleAlarmVersionConflictException extends VehicleAlarmActionConflictException {
    public VehicleAlarmVersionConflictException(String message) {
        super(message);
    }
}
