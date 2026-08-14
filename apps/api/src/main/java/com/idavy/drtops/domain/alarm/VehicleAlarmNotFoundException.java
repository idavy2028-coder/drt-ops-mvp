package com.idavy.drtops.domain.alarm;

public class VehicleAlarmNotFoundException extends IllegalStateException {
    public VehicleAlarmNotFoundException(String message) {
        super(message);
    }
}
