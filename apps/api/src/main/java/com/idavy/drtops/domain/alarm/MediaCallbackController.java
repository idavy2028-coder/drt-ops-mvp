package com.idavy.drtops.domain.alarm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idavy.drtops.integration.media.AlarmAttachmentMediaPort;
import com.idavy.drtops.integration.media.MediaServiceUnavailableException;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Receives external media service callbacks for alarm attachments. The endpoint is reachable
 * without an operator session, so every callback must pass HMAC signature verification, a fresh
 * timestamp and nonce replay protection before any business state is touched. Fail-closed: while
 * the media service contract is unavailable every callback is answered 503. Callback payloads are
 * metadata only; binary content never passes this boundary.
 */
@RestController
@RequestMapping("/internal/media-callbacks")
public class MediaCallbackController {
    private static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(5);
    private static final Duration NONCE_RETENTION = Duration.ofMinutes(15);
    private static final int MAX_NONCE_LENGTH = 128;
    private static final int MAX_REMEMBERED_NONCES = 100_000;

    private final AlarmAttachmentMediaPort mediaPort;
    private final AlarmAttachmentService attachmentService;
    private final ObjectMapper objectMapper;
    /** nonce -> callback epoch second; bounded in-memory replay guard for this single API instance. */
    private final ConcurrentMap<String, Long> seenNonces = new ConcurrentHashMap<>();

    MediaCallbackController(
            AlarmAttachmentMediaPort mediaPort,
            AlarmAttachmentService attachmentService,
            ObjectMapper objectMapper) {
        this.mediaPort = Objects.requireNonNull(mediaPort, "mediaPort");
        this.attachmentService = Objects.requireNonNull(attachmentService, "attachmentService");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @PostMapping(path = "/alarm-attachments", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, String>> alarmAttachmentCallback(
            @RequestHeader(value = "X-Media-Signature", required = false) String signature,
            @RequestHeader(value = "X-Media-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Media-Nonce", required = false) String nonce,
            @RequestBody byte[] rawBody) {
        if (isBlank(signature) || isBlank(timestamp) || isBlank(nonce)
                || nonce.length() > MAX_NONCE_LENGTH) {
            return error(HttpStatus.UNAUTHORIZED, "CALLBACK_SIGNATURE_INVALID");
        }
        Long callbackEpoch = parseEpochSeconds(timestamp);
        if (callbackEpoch == null) {
            return error(HttpStatus.UNAUTHORIZED, "CALLBACK_STALE");
        }
        long now = Instant.now().getEpochSecond();
        long skew = now - callbackEpoch;
        if (skew > MAX_CLOCK_SKEW.toSeconds() || skew < -MAX_CLOCK_SKEW.toSeconds()) {
            return error(HttpStatus.UNAUTHORIZED, "CALLBACK_STALE");
        }
        evictExpiredNonces(now);
        if (seenNonces.containsKey(nonce)) {
            return error(HttpStatus.UNAUTHORIZED, "CALLBACK_REPLAYED");
        }
        AlarmAttachmentMediaPort.CallbackVerification verification;
        try {
            verification = mediaPort.verifyCallbackSignature(signature, timestamp, nonce, rawBody);
        } catch (MediaServiceUnavailableException unavailable) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "MEDIA_SERVICE_UNAVAILABLE");
        }
        if (verification == null || verification == AlarmAttachmentMediaPort.CallbackVerification.UNAVAILABLE) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "MEDIA_SERVICE_UNAVAILABLE");
        }
        if (verification == AlarmAttachmentMediaPort.CallbackVerification.REJECTED) {
            return error(HttpStatus.UNAUTHORIZED, "CALLBACK_SIGNATURE_INVALID");
        }
        if (seenNonces.putIfAbsent(nonce, callbackEpoch) != null) {
            return error(HttpStatus.UNAUTHORIZED, "CALLBACK_REPLAYED");
        }
        AlarmAttachmentService.AttachmentMediaCallback callback;
        try {
            callback = parseCallback(rawBody);
        } catch (IllegalArgumentException invalid) {
            return error(HttpStatus.BAD_REQUEST, "CALLBACK_PAYLOAD_INVALID");
        }
        AlarmAttachmentService.MediaCallbackOutcome outcome = attachmentService.handleMediaCallback(callback);
        return switch (outcome) {
            case CONFIRMED -> ResponseEntity.ok(Map.of("outcome", "CONFIRMED"));
            case DUPLICATE -> ResponseEntity.ok(Map.of("outcome", "DUPLICATE"));
            case UNKNOWN_REFERENCE -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("outcome", "UNKNOWN_REFERENCE"));
            case UNEXPECTED_STATE -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("outcome", "UNEXPECTED_STATE"));
        };
    }

    private AlarmAttachmentService.AttachmentMediaCallback parseCallback(byte[] rawBody) {
        CallbackPayload payload;
        try {
            payload = objectMapper.readValue(rawBody, CallbackPayload.class);
        } catch (IOException exception) {
            throw new IllegalArgumentException("callback payload is not valid JSON");
        }
        AlarmAttachmentService.AttachmentMediaCallback.Result result;
        try {
            result = AlarmAttachmentService.AttachmentMediaCallback.Result.valueOf(
                    Objects.requireNonNull(payload.result(), "result"));
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new IllegalArgumentException("callback result is not supported");
        }
        return new AlarmAttachmentService.AttachmentMediaCallback(
                payload.externalMediaReference(), payload.fileName(), payload.fileType(),
                payload.sizeBytes(), payload.sha256(), result);
    }

    private void evictExpiredNonces(long nowEpochSeconds) {
        if (seenNonces.size() < MAX_REMEMBERED_NONCES) {
            return;
        }
        long cutoff = nowEpochSeconds - NONCE_RETENTION.toSeconds();
        seenNonces.values().removeIf(seenAt -> seenAt < cutoff);
    }

    private static Long parseEpochSeconds(String timestamp) {
        try {
            return Long.parseLong(timestamp.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String errorCode) {
        return ResponseEntity.status(status).body(Map.of("errorCode", errorCode));
    }

    /** Raw callback payload; field-level validation happens in the attachment state machine. */
    record CallbackPayload(
            String externalMediaReference,
            String fileName,
            String fileType,
            Long sizeBytes,
            String sha256,
            String result) { }
}
