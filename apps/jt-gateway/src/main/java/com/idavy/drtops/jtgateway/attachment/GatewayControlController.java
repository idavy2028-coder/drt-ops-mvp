package com.idavy.drtops.jtgateway.attachment;

import com.idavy.drtops.jt.protocol.codec.ProtocolVersion;
import com.idavy.drtops.jt.protocol.jt1078.AlarmAttachmentMessageCodec;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal control plane for attachment upload commands. Authenticated by a provisioned bearer
 * credential plus its version; fails closed (401) while no credential is configured. Command
 * payloads carry one-time upload targets and the terminal alarm identifier: they are validated,
 * encoded and sent, but never echoed in responses, persisted or logged.
 */
@RestController
public class GatewayControlController {
    static final String ERROR_CREDENTIAL_INVALID = "CONTROL_CREDENTIAL_INVALID";
    static final String ERROR_MALFORMED_COMMAND = "MALFORMED_COMMAND";
    private static final int ALARM_IDENTIFIER_HEX_LENGTH = 32;
    private static final int ALARM_NUMBER_HEX_LENGTH = 64;
    private static final int RESERVED_LENGTH = 16;
    private static final Charset ADDRESS_CHARSET = Charset.forName("GBK");

    private final AttachmentCommandService commands;
    private final String credential;
    private final String credentialVersion;

    public GatewayControlController(
            AttachmentCommandService commands,
            @Value("${drt.gateway.control.credential:}") String credential,
            @Value("${drt.gateway.control.credential-version:}") String credentialVersion) {
        this.commands = java.util.Objects.requireNonNull(commands, "commands");
        this.credential = credential == null ? "" : credential;
        this.credentialVersion = credentialVersion == null ? "" : credentialVersion;
    }

    @PostMapping("/internal/control/terminals/{terminalId}/attachment-upload")
    public ResponseEntity<Map<String, String>> requestAttachmentUpload(
            @PathVariable UUID terminalId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Control-Credential-Version", required = false) String version,
            @RequestBody AttachmentUploadRequest request) {
        if (!isAuthorized(authorization, version)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("errorCode", ERROR_CREDENTIAL_INVALID));
        }
        AttachmentCommandService.Command command = toCommand(terminalId, request);
        AttachmentCommandService.Result result = commands.sendUploadCommand(command);
        return switch (result) {
            case SENT -> ResponseEntity.ok(Map.of("result", "SENT"));
            case TERMINAL_OFFLINE -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("errorCode", "TERMINAL_OFFLINE"));
            case IDENTITY_MISMATCH -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("errorCode", "IDENTITY_MISMATCH"));
            case CAPABILITY_MISSING -> ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(Map.of("errorCode", "CAPABILITY_MISSING"));
        };
    }

    /** Malformed commands are rejected without echoing any command material back to the caller. */
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> malformedCommand() {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("errorCode", ERROR_MALFORMED_COMMAND));
    }

    private boolean isAuthorized(String authorization, String version) {
        if (credential.isBlank() || credentialVersion.isBlank()) {
            return false;
        }
        if (authorization == null || version == null) {
            return false;
        }
        String expectedAuthorization = "Bearer " + credential;
        return MessageDigest.isEqual(
                        expectedAuthorization.getBytes(StandardCharsets.UTF_8),
                        authorization.getBytes(StandardCharsets.UTF_8))
                && MessageDigest.isEqual(
                        credentialVersion.getBytes(StandardCharsets.UTF_8),
                        version.getBytes(StandardCharsets.UTF_8));
    }

    private AttachmentCommandService.Command toCommand(UUID terminalId, AttachmentUploadRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("command body is required");
        }
        if (isBlank(request.terminalIdentity())) {
            throw new IllegalArgumentException("terminalIdentity is required");
        }
        ProtocolVersion protocolVersion = parseProtocolVersion(request.protocolVersion());
        int protocolVersionByte = protocolVersion.versionedHeader() ? 1 : 0;
        if (isBlank(request.serverAddress())
                || request.serverAddress().getBytes(ADDRESS_CHARSET).length > 0xff) {
            throw new IllegalArgumentException("serverAddress is required");
        }
        int tcpPort = requirePort(request.tcpPort(), "tcpPort");
        int udpPort = requirePort(request.udpPort(), "udpPort");
        byte[] alarmIdentifier = parseFixedHex(request.alarmIdentifierHex(), ALARM_IDENTIFIER_HEX_LENGTH);
        byte[] alarmNumber = parseFixedHex(request.alarmNumberHex(), ALARM_NUMBER_HEX_LENGTH);
        AlarmAttachmentMessageCodec.AttachmentUploadCommand upload =
                new AlarmAttachmentMessageCodec.AttachmentUploadCommand(
                        request.serverAddress(), tcpPort, udpPort,
                        alarmIdentifier, alarmNumber, new byte[RESERVED_LENGTH]);
        return new AttachmentCommandService.Command(
                terminalId, request.terminalIdentity(), protocolVersion, protocolVersionByte, upload);
    }

    private static ProtocolVersion parseProtocolVersion(String value) {
        if (isBlank(value)) {
            throw new IllegalArgumentException("protocolVersion is required");
        }
        try {
            return ProtocolVersion.valueOf(value.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("protocolVersion is not supported");
        }
    }

    private static int requirePort(Integer port, String field) {
        if (port == null || port < 0 || port > 0xffff) {
            throw new IllegalArgumentException(field + " must fit an unsigned short");
        }
        return port;
    }

    private static byte[] parseFixedHex(String hex, int expectedLength) {
        if (hex == null || hex.length() != expectedLength) {
            throw new IllegalArgumentException("hex field has an invalid length");
        }
        try {
            return HexFormat.of().parseHex(hex);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("hex field is not valid hexadecimal");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record AttachmentUploadRequest(
            String terminalIdentity,
            String protocolVersion,
            String serverAddress,
            Integer tcpPort,
            Integer udpPort,
            String alarmIdentifierHex,
            String alarmNumberHex) { }
}
