package com.idavy.drtops.integration.media;

/** The external media service contract is unavailable; callers must degrade, never block alarms. */
public class MediaServiceUnavailableException extends RuntimeException {
    public MediaServiceUnavailableException(String message) {
        super(message);
    }

    public MediaServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
