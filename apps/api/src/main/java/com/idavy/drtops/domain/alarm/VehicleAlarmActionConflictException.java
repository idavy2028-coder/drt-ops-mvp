package com.idavy.drtops.domain.alarm;

public class VehicleAlarmActionConflictException extends IllegalStateException {
    public VehicleAlarmActionConflictException(String message) {
        super(message);
    }
}
