package com.idavy.drtops.jtgateway.ingress;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Sanitized attachment signaling metadata; identities, raw frames and upload targets are absent. */
public record CanonicalAttachmentMetadata(
        UUID terminalId,
        UUID vehicleId,
        int messageId,
        Instant gatewayReceivedAt,
        String alarmIdentifierDigest,
        String alarmNumberDigest,
        Integer infoType,
        Integer responseSerialNo,
        Integer uploadResult,
        List<AttachmentFile> files,
        String payloadDigest) {
    public CanonicalAttachmentMetadata {
        Objects.requireNonNull(terminalId, "terminalId");
        Objects.requireNonNull(vehicleId, "vehicleId");
        Objects.requireNonNull(gatewayReceivedAt, "gatewayReceivedAt");
        files = List.copyOf(Objects.requireNonNull(files, "files"));
        Objects.requireNonNull(payloadDigest, "payloadDigest");
    }

    public record AttachmentFile(String fileName, long fileSize) {
        public AttachmentFile {
            if (fileName == null || fileName.isBlank() || fileSize < 0) {
                throw new IllegalArgumentException("attachment file metadata is invalid");
            }
        }
    }
}
