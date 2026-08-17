package com.idavy.drtops.domain.alarm;

/** Stable error-coded conflict for attachment state-machine violations (mapped to HTTP 409). */
public class AlarmAttachmentConflictException extends RuntimeException {
    private final String errorCode;

    public AlarmAttachmentConflictException(String errorCode) {
        super(errorCode);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
