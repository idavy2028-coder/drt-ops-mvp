package com.idavy.drtops.integration.media;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Admission contract for a future external media service (Task 14 gate: no provider environment,
 * no callback key material, no real one-time upload targets exist yet). One-time upload targets and
 * short-lived view URLs exist only in call memory: they are never persisted, logged or published.
 */
public interface AlarmAttachmentMediaPort {
    /** Asks the media service for a one-time upload target; the target is single-use and expires. */
    UploadTarget requestUploadTarget(UploadTargetRequest request);

    /** Issues a short-lived view URL for an already available attachment. */
    ViewUrl issueShortLivedViewUrl(String externalMediaReference, Duration timeToLive);

    /**
     * Verifies the media service callback signature over the raw body. The contract requires
     * HMAC-SHA256 (hex) over timestamp + '\n' + nonce + '\n' + rawBody with the provisioned key.
     */
    CallbackVerification verifyCallbackSignature(String signature, String timestamp, String nonce, byte[] rawBody);

    record UploadTargetRequest(UUID attachmentId, String fileName, long sizeBytes) { }

    record UploadTarget(String targetReference, String serverAddress, int tcpPort, int udpPort, Instant expiresAt) { }

    record ViewUrl(String url, Instant expiresAt) { }

    enum CallbackVerification {
        VERIFIED,
        REJECTED,
        UNAVAILABLE
    }
}
