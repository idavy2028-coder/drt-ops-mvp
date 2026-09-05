package com.idavy.drtops.domain.alarm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idavy.drtops.integration.media.AlarmAttachmentMediaPort;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:media_callback_security;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
@Import(MediaCallbackSecurityTest.MediaPortConfiguration.class)
class MediaCallbackSecurityTest {
    private static final String CALLBACK_PATH = "/internal/media-callbacks/alarm-attachments";
    private static final String SECRET = "test-media-callback-secret";
    private static final String FILE_NAME = "00_64_6401_01_SYNTHETIC0001.jpg";
    private static final String DIGEST = "b".repeat(64);
    private static final AtomicBoolean MEDIA_UNAVAILABLE = new AtomicBoolean(false);

    @Autowired MockMvc mockMvc;
    @Autowired VehicleAlarmRepository alarms;
    @Autowired VehicleAlarmAttachmentRepository attachments;
    @Autowired VehicleAlarmAttachmentTransferRepository transfers;

    @BeforeEach
    void reset() {
        MEDIA_UNAVAILABLE.set(false);
        transfers.deleteAll();
        attachments.deleteAll();
        alarms.deleteAll();
    }

    @Test
    void confirmsASignedFreshCallbackWithoutRequiringAnOperatorSession() throws Exception {
        VehicleAlarmAttachment attachment = seedUploading("ref-signed");
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String nonce = "nonce-signed-1";
        byte[] body = callbackBody("ref-signed", FILE_NAME, 12345L, DIGEST, "SUCCESS")
                .getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(post(CALLBACK_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("X-Media-Signature", sign(SECRET, timestamp, nonce, body))
                        .header("X-Media-Timestamp", timestamp)
                        .header("X-Media-Nonce", nonce))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("CONFIRMED"));
        assertThat(attachments.findById(attachment.getId()).orElseThrow().getStatus())
                .isEqualTo(VehicleAlarmAttachment.Status.AVAILABLE);
    }

    @Test
    void rejectsMissingTamperedAndWrongKeySignatures() throws Exception {
        seedUploading("ref-tampered");
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        byte[] body = callbackBody("ref-tampered", FILE_NAME, 12345L, DIGEST, "SUCCESS")
                .getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(post(CALLBACK_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("X-Media-Timestamp", timestamp)
                        .header("X-Media-Nonce", "nonce-missing-sig"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("CALLBACK_SIGNATURE_INVALID"));

        byte[] tampered = callbackBody("ref-tampered", FILE_NAME, 99999L, DIGEST, "SUCCESS")
                .getBytes(StandardCharsets.UTF_8);
        mockMvc.perform(post(CALLBACK_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tampered)
                        .header("X-Media-Signature", sign(SECRET, timestamp, "nonce-tampered", body))
                        .header("X-Media-Timestamp", timestamp)
                        .header("X-Media-Nonce", "nonce-tampered"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post(CALLBACK_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("X-Media-Signature", sign("attacker-key", timestamp, "nonce-wrong-key", body))
                        .header("X-Media-Timestamp", timestamp)
                        .header("X-Media-Nonce", "nonce-wrong-key"))
                .andExpect(status().isUnauthorized());
        assertThat(attachments.findAll()).allSatisfy(attachment ->
                assertThat(attachment.getStatus()).isEqualTo(VehicleAlarmAttachment.Status.UPLOADING));
    }

    @Test
    void rejectsStaleTimestampsAndReplayedNonces() throws Exception {
        seedUploading("ref-replay");
        byte[] body = callbackBody("ref-replay", FILE_NAME, 12345L, DIGEST, "SUCCESS")
                .getBytes(StandardCharsets.UTF_8);
        String stale = Long.toString(Instant.now().getEpochSecond() - 301);
        mockMvc.perform(post(CALLBACK_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("X-Media-Signature", sign(SECRET, stale, "nonce-stale", body))
                        .header("X-Media-Timestamp", stale)
                        .header("X-Media-Nonce", "nonce-stale"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("CALLBACK_STALE"));

        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String nonce = "nonce-replayed";
        String signature = sign(SECRET, timestamp, nonce, body);
        mockMvc.perform(post(CALLBACK_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("X-Media-Signature", signature)
                        .header("X-Media-Timestamp", timestamp)
                        .header("X-Media-Nonce", nonce))
                .andExpect(status().isOk());
        mockMvc.perform(post(CALLBACK_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("X-Media-Signature", signature)
                        .header("X-Media-Timestamp", timestamp)
                        .header("X-Media-Nonce", nonce))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("CALLBACK_REPLAYED"));
    }

    @Test
    void failsClosedWhileTheMediaServiceContractIsUnavailable() throws Exception {
        MEDIA_UNAVAILABLE.set(true);
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        byte[] body = callbackBody("ref-any", FILE_NAME, 12345L, DIGEST, "SUCCESS")
                .getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(post(CALLBACK_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("X-Media-Signature", sign(SECRET, timestamp, "nonce-unavailable", body))
                        .header("X-Media-Timestamp", timestamp)
                        .header("X-Media-Nonce", "nonce-unavailable"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("MEDIA_SERVICE_UNAVAILABLE"));
    }

    @Test
    void mapsUnknownReferencesAndUnexpectedStates() throws Exception {
        VehicleAlarm alarm = saveAlarm();
        VehicleAlarmAttachment waiting = attachments.saveAndFlush(VehicleAlarmAttachment.register(
                alarm.getId(), "IMAGE", "64", "jpg", FILE_NAME, 12345L, DIGEST,
                VehicleAlarmAttachment.Status.WAITING_MEDIA_SERVICE, Instant.parse("2026-08-15T02:00:00Z")));
        transfers.saveAndFlush(uploadingTransfer(waiting, "ref-waiting"));
        String timestamp = Long.toString(Instant.now().getEpochSecond());

        byte[] unknown = callbackBody("ref-not-seeded", FILE_NAME, 12345L, DIGEST, "SUCCESS")
                .getBytes(StandardCharsets.UTF_8);
        mockMvc.perform(post(CALLBACK_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(unknown)
                        .header("X-Media-Signature", sign(SECRET, timestamp, "nonce-unknown-ref", unknown))
                        .header("X-Media-Timestamp", timestamp)
                        .header("X-Media-Nonce", "nonce-unknown-ref"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.outcome").value("UNKNOWN_REFERENCE"));

        byte[] wrongState = callbackBody("ref-waiting", FILE_NAME, 12345L, DIGEST, "SUCCESS")
                .getBytes(StandardCharsets.UTF_8);
        mockMvc.perform(post(CALLBACK_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(wrongState)
                        .header("X-Media-Signature", sign(SECRET, timestamp, "nonce-wrong-state", wrongState))
                        .header("X-Media-Timestamp", timestamp)
                        .header("X-Media-Nonce", "nonce-wrong-state"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.outcome").value("UNEXPECTED_STATE"));
    }

    @Test
    void rejectsMalformedPayloadsAndOversizedNonces() throws Exception {
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        byte[] malformed = "{not-json".getBytes(StandardCharsets.UTF_8);
        mockMvc.perform(post(CALLBACK_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(malformed)
                        .header("X-Media-Signature", sign(SECRET, timestamp, "nonce-malformed", malformed))
                        .header("X-Media-Timestamp", timestamp)
                        .header("X-Media-Nonce", "nonce-malformed"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("CALLBACK_PAYLOAD_INVALID"));

        byte[] body = callbackBody("ref-any", FILE_NAME, 12345L, DIGEST, "SUCCESS")
                .getBytes(StandardCharsets.UTF_8);
        String oversizedNonce = "n".repeat(200);
        mockMvc.perform(post(CALLBACK_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("X-Media-Signature", sign(SECRET, timestamp, oversizedNonce, body))
                        .header("X-Media-Timestamp", timestamp)
                        .header("X-Media-Nonce", oversizedNonce))
                .andExpect(status().isUnauthorized());
    }

    private VehicleAlarmAttachment seedUploading(String reference) {
        VehicleAlarm alarm = saveAlarm();
        VehicleAlarmAttachment attachment = attachments.saveAndFlush(VehicleAlarmAttachment.register(
                alarm.getId(), "IMAGE", "64", "jpg", FILE_NAME, 12345L, DIGEST,
                VehicleAlarmAttachment.Status.UPLOADING, Instant.parse("2026-08-15T02:00:00Z")));
        transfers.saveAndFlush(uploadingTransfer(attachment, reference));
        return attachment;
    }

    private VehicleAlarmAttachmentTransfer uploadingTransfer(
            VehicleAlarmAttachment attachment, String reference) {
        VehicleAlarmAttachmentTransfer transfer = VehicleAlarmAttachmentTransfer.requested(
                attachment.getId(), "0x9208", 1, reference, Instant.parse("2026-08-15T02:00:10Z"));
        transfer.markUploading(7);
        return transfer;
    }

    private VehicleAlarm saveAlarm() {
        VehicleAlarmIngressService.AlarmFact fact = new VehicleAlarmIngressService.AlarmFact(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "T/JSATL12-2017", "ADAS", 1,
                "FORWARD_COLLISION", 4097L, "START", 1, "00000001",
                Instant.parse("2026-01-15T02:00:00Z"), Instant.parse("2026-01-15T02:00:01Z"),
                new BigDecimal("118.0000000"), new BigDecimal("32.0000000"), new BigDecimal("60.00"),
                UUID.randomUUID(), "UNASSESSED", "a".repeat(64));
        return alarms.saveAndFlush(VehicleAlarm.start(fact, UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", ""),
                new AlarmStore.LocationReference(
                        UUID.randomUUID(), fact.onboardSystemId(), fact.occurredAt(), "GOOD", "[]")));
    }

    private static String callbackBody(
            String reference, String fileName, long sizeBytes, String sha256, String result) {
        return """
                {"externalMediaReference":"%s","fileName":"%s","fileType":"IMAGE","sizeBytes":%d,"sha256":"%s","result":"%s"}
                """.formatted(reference, fileName, sizeBytes, sha256, result);
    }

    private static String sign(String secret, String timestamp, String nonce, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) '\n');
            mac.update(nonce.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) '\n');
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    @TestConfiguration
    static class MediaPortConfiguration {
        @Bean
        @Primary
        AlarmAttachmentMediaPort alarmAttachmentMediaPort() {
            return new AlarmAttachmentMediaPort() {
                @Override
                public UploadTarget requestUploadTarget(UploadTargetRequest request) {
                    throw new MediaServiceUnavailableExceptionForTest();
                }

                @Override
                public ViewUrl issueShortLivedViewUrl(String externalMediaReference, Duration timeToLive) {
                    throw new MediaServiceUnavailableExceptionForTest();
                }

                @Override
                public CallbackVerification verifyCallbackSignature(
                        String signature, String timestamp, String nonce, byte[] rawBody) {
                    if (MEDIA_UNAVAILABLE.get()) {
                        return CallbackVerification.UNAVAILABLE;
                    }
                    String expected = sign(SECRET, timestamp, nonce, rawBody);
                    return java.security.MessageDigest.isEqual(
                            expected.getBytes(StandardCharsets.UTF_8),
                            signature.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.UTF_8))
                            ? CallbackVerification.VERIFIED : CallbackVerification.REJECTED;
                }
            };
        }
    }

    private static final class MediaServiceUnavailableExceptionForTest
            extends com.idavy.drtops.integration.media.MediaServiceUnavailableException {
        private MediaServiceUnavailableExceptionForTest() {
            super("media service unavailable in test");
        }
    }
}
