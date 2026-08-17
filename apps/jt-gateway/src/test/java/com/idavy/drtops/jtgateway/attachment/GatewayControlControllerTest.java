package com.idavy.drtops.jtgateway.attachment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idavy.drtops.jt.protocol.codec.Jt808Frame;
import com.idavy.drtops.jtgateway.session.TerminalSession;
import com.idavy.drtops.jtgateway.session.TerminalSessionRegistry;
import io.netty.channel.embedded.EmbeddedChannel;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Fixture identifiers are DERIVED_SYNTHETIC placeholders; no real terminal capture is involved. */
class GatewayControlControllerTest {
    private static final UUID TERMINAL_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID VEHICLE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String TERMINAL_NUMBER = "000000000000";
    private static final String CREDENTIAL = "test-control-credential";
    private static final String CREDENTIAL_VERSION = "v1";
    private static final String PATH = "/internal/control/terminals/{terminalId}/attachment-upload";

    private final TerminalSessionRegistry registry = new TerminalSessionRegistry();
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
            new GatewayControlController(new AttachmentCommandService(registry), CREDENTIAL, CREDENTIAL_VERSION))
            .build();

    @Test
    void rejectsMissingWrongAndUnconfiguredCredentials() throws Exception {
        mockMvc.perform(post(PATH, TERMINAL_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("CONTROL_CREDENTIAL_INVALID"));
        mockMvc.perform(post(PATH, TERMINAL_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody())
                        .header("Authorization", "Bearer wrong-credential")
                        .header("X-Control-Credential-Version", CREDENTIAL_VERSION))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(PATH, TERMINAL_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody())
                        .header("Authorization", "Bearer " + CREDENTIAL)
                        .header("X-Control-Credential-Version", "v0"))
                .andExpect(status().isUnauthorized());

        MockMvc unconfigured = MockMvcBuilders.standaloneSetup(
                new GatewayControlController(new AttachmentCommandService(registry), "", ""))
                .build();
        unconfigured.perform(post(PATH, TERMINAL_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody())
                        .header("Authorization", "Bearer " + CREDENTIAL)
                        .header("X-Control-Credential-Version", CREDENTIAL_VERSION))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsMalformedCommandsWithoutEchoingTheAlarmIdentifier() throws Exception {
        String shortIdentifier = validBody().replace("11".repeat(16), "11");
        mockMvc.perform(authorized().content(shortIdentifier))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("MALFORMED_COMMAND"));

        String unknownVersion = validBody().replace("JT808_2013", "JT808_2099");
        mockMvc.perform(authorized().content(unknownVersion))
                .andExpect(status().isBadRequest());

        String negativePort = validBody().replace("\"tcpPort\":7611", "\"tcpPort\":-1");
        mockMvc.perform(authorized().content(negativePort))
                .andExpect(status().isBadRequest());

        String response = mockMvc.perform(authorized().content(shortIdentifier))
                .andReturn().getResponse().getContentAsString();
        assertFalse(response.contains("11".repeat(16)));
    }

    @Test
    void mapsOfflineCapabilityAndIdentityFailures() throws Exception {
        mockMvc.perform(authorized().content(validBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("TERMINAL_OFFLINE"));

        EmbeddedChannel incapableChannel = new EmbeddedChannel();
        TerminalSession incapable = new TerminalSession(incapableChannel, Instant.now());
        incapable.registrationAccepted(TERMINAL_ID, VEHICLE_ID, "WGS84", 1, TERMINAL_NUMBER);
        incapable.authenticated(Instant.now());
        registry.claim(incapable);
        mockMvc.perform(authorized().content(validBody()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("CAPABILITY_MISSING"));
        registry.remove(incapable);
        incapableChannel.finishAndReleaseAll();

        EmbeddedChannel channel = new EmbeddedChannel();
        claimCapableSession(channel);
        String mismatched = validBody().replace(TERMINAL_NUMBER, "999999999999");
        mockMvc.perform(authorized().content(mismatched))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("IDENTITY_MISMATCH"));
        channel.finishAndReleaseAll();
    }

    @Test
    void sendsTheUploadCommandToAnAuthenticatedCapableTerminal() throws Exception {
        EmbeddedChannel channel = new EmbeddedChannel();
        claimCapableSession(channel);

        mockMvc.perform(authorized().content(validBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("SENT"));

        Object outbound = channel.readOutbound();
        assertNotNull(outbound);
        Jt808Frame frame = (Jt808Frame) outbound;
        assertEquals(0x9208, frame.header().messageId());
        frame.body().release();
        channel.finishAndReleaseAll();
    }

    private void claimCapableSession(EmbeddedChannel channel) {
        TerminalSession session = new TerminalSession(channel, Instant.now());
        session.registrationAccepted(TERMINAL_ID, VEHICLE_ID, "WGS84", 1, TERMINAL_NUMBER,
                "T/JSATL12-2017", List.of("ADAS", "DMS"));
        session.authenticated(Instant.now());
        registry.claim(session);
    }

    private MockHttpServletRequestBuilder authorized() {
        return post(PATH, TERMINAL_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + CREDENTIAL)
                .header("X-Control-Credential-Version", CREDENTIAL_VERSION);
    }

    private static String validBody() {
        return """
                {"terminalIdentity":"%s","protocolVersion":"JT808_2013","serverAddress":"127.0.0.1",
                 "tcpPort":7611,"udpPort":7612,"alarmIdentifierHex":"%s","alarmNumberHex":"%s"}
                """.formatted(TERMINAL_NUMBER, "11".repeat(16), "22".repeat(32));
    }
}
