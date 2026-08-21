package com.idavy.drtops.integration.media;

import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Fail-closed adapter used while no external media service is contracted (Task 14 gate). Alarms keep
 * flowing; attachments stay in WAITING_MEDIA_SERVICE and callbacks are answered UNAVAILABLE.
 */
@Component
public class UnavailableAlarmAttachmentMediaAdapter implements AlarmAttachmentMediaPort {
    @Override
    public UploadTarget requestUploadTarget(UploadTargetRequest request) {
        throw new MediaServiceUnavailableException("external media service is not contracted");
    }

    @Override
    public ViewUrl issueShortLivedViewUrl(String externalMediaReference, Duration timeToLive) {
        throw new MediaServiceUnavailableException("external media service is not contracted");
    }

    @Override
    public CallbackVerification verifyCallbackSignature(
            String signature, String timestamp, String nonce, byte[] rawBody) {
        return CallbackVerification.UNAVAILABLE;
    }
}
