package com.idavy.drtops.domain.alarm;

/** Stable error-coded attachment request rejection (mapped to HTTP 422). */
public class AlarmAttachmentRequestException extends RuntimeException {
    private final String errorCode;

    public AlarmAttachmentRequestException(String errorCode) {
        super(errorCode);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
