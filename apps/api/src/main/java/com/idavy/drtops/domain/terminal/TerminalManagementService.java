package com.idavy.drtops.domain.terminal;

import com.idavy.drtops.domain.audit.AuditLog;
import com.idavy.drtops.domain.audit.AuditLogRepository;
import com.idavy.drtops.domain.fleet.VehicleRepository;
import com.idavy.drtops.integration.jtgateway.JtGatewayControlClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class TerminalManagementService {

    private static final java.util.Set<String> KNOWN_ACTIVE_SAFETY_STANDARDS =
            java.util.Set.of("T/JSATL12-2017", "T/GD-ACTIVE-SAFETY");

    private final JtTerminalRepository terminalRepository;
    private final JtTerminalVehicleBindingRepository bindingRepository;
    private final VehicleRepository vehicleRepository;
    private final AuditLogRepository auditLogRepository;
    private final JtGatewayAuditEventRepository gatewayAuditRepository;
    private final JtGatewayControlClient controlClient;
    private final TransactionTemplate committedStateTransaction;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public TerminalManagementService(
            JtTerminalRepository terminalRepository,
            JtTerminalVehicleBindingRepository bindingRepository,
            VehicleRepository vehicleRepository,
            AuditLogRepository auditLogRepository,
            JtGatewayAuditEventRepository gatewayAuditRepository,
            JtGatewayControlClient controlClient,
            PlatformTransactionManager transactionManager,
            ObjectMapper objectMapper,
            ObjectProvider<Clock> clocks) {
        this.terminalRepository = terminalRepository;
        this.bindingRepository = bindingRepository;
        this.vehicleRepository = vehicleRepository;
        this.auditLogRepository = auditLogRepository;
        this.gatewayAuditRepository = gatewayAuditRepository;
        this.controlClient = controlClient;
        this.committedStateTransaction = new TransactionTemplate(transactionManager);
        this.committedStateTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.objectMapper = objectMapper;
        this.clock = clocks.getIfAvailable(Clock::systemUTC);
    }

    @Transactional(readOnly = true)
    public List<JtTerminal> list() {
        return terminalRepository.findAll().stream()
                .sorted(java.util.Comparator.comparing(JtTerminal::getTerminalCode))
                .toList();
    }

    @Transactional(readOnly = true)
    public JtTerminal get(String terminalCode) {
        return terminalRepository.findByTerminalCode(terminalCode)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "terminal not found"));
    }

    @Transactional(readOnly = true)
    public TerminalDetail getDetail(String terminalCode) {
        JtTerminal terminal = get(terminalCode);
        List<BindingSummary> bindings = bindingRepository.findByTerminalIdOrderByValidFromDesc(terminal.getId()).stream()
                .map(binding -> new BindingSummary(
                        vehicleRepository.findById(binding.getVehicleId()).map(vehicle -> vehicle.getPlateNumber()).orElse("车辆已不可用"),
                        binding.getStatus().name(), binding.getValidFrom(), binding.getValidTo()))
                .toList();
        List<GatewayAuditSummary> audits = gatewayAuditRepository.findByTerminalIdOrderByOccurredAtDesc(terminal.getId()).stream()
                .map(event -> new GatewayAuditSummary(event.getEventType().name(), event.getResult().name(),
                        event.getReasonCode(), event.getProtocolVersion(), event.getMessageId(), event.getOccurredAt()))
                .toList();
        OffsetDateTime lastSeenAt = terminal.getLastSeenAt();
        OnlineStatus onlineStatus = lastSeenAt == null ? OnlineStatus.NEVER_SEEN
                : lastSeenAt.isBefore(OffsetDateTime.now(clock).minusSeconds(180)) ? OnlineStatus.OFFLINE : OnlineStatus.ONLINE;
        BindingSummary currentBinding = bindingRepository.findByTerminalIdAndStatus(terminal.getId(),
                        JtTerminalVehicleBinding.Status.ACTIVE)
                .map(binding -> new BindingSummary(vehicleRepository.findById(binding.getVehicleId())
                        .map(vehicle -> vehicle.getPlateNumber()).orElse("车辆已不可用"), binding.getStatus().name(),
                        binding.getValidFrom(), binding.getValidTo()))
                .orElse(null);
        return new TerminalDetail(terminal, onlineStatus, lastSeenAt,
                onlineStatus == OnlineStatus.OFFLINE ? lastSeenAt.plusSeconds(180) : null, currentBinding, bindings, audits);
    }

    @Transactional
    public JtTerminal preset(PresetCommand command) {
        requireReason(command.reason());
        JtTerminal terminal = terminalRepository.saveAndFlush(JtTerminal.preset(
                UUID.randomUUID(), command.terminalPhone(), command.terminalCode(),
                command.manufacturerId(), command.model(), command.protocolVersion(),
                command.sourceCoordinateSystem(), command.actorId()));
        audit(terminal, "JT_TERMINAL_PRESET", command.actorId(), command.reason());
        return terminal;
    }

    @Transactional
    public JtTerminal configureCapabilities(
            String terminalCode,
            long expectedVersion,
            String activeSafetyStandard,
            List<String> activeSafetyModules,
            boolean jt1078Enabled,
            String reason,
            UUID actorId) {
        JtTerminal terminal = requireVersion(terminalCode, expectedVersion);
        requireReason(reason);
        CapabilityProfile profile = validateCapabilityProfile(activeSafetyStandard, activeSafetyModules, jt1078Enabled);
        String serializedModules;
        try {
            serializedModules = objectMapper.writeValueAsString(profile.activeSafetyModules());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to encode terminal capability profile", exception);
        }
        asConflict(() -> terminal.configureCapabilities(
                profile.activeSafetyStandard(), serializedModules, profile.jt1078Enabled()));
        terminalRepository.saveAndFlush(terminal);
        audit(terminal, "JT_TERMINAL_CAPABILITY_PROFILE_CONFIGURED", actorId, reason,
                capabilityMetadata(profile));
        return terminal;
    }

    @Transactional
    public JtTerminal completeRegistration(
            UUID terminalId, int tokenVersion, String tokenSha256, String gatewayInstance) {
        if (gatewayInstance == null || gatewayInstance.isBlank()) {
            throw new IllegalArgumentException("gatewayInstance must not be blank");
        }
        JtTerminal terminal = terminalRepository.findById(terminalId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "terminal not found"));
        boolean hasActiveBinding = bindingRepository
                .findByTerminalIdAndStatus(terminalId, JtTerminalVehicleBinding.Status.ACTIVE)
                .isPresent();
        if (!registrationPending(terminal)
                || terminal.getLastRegisteredAt() != null
                || !hasActiveBinding
                || terminal.getAuthTokenVersion() != tokenVersion) {
            throw new TerminalConflictException("terminal is not eligible for registration completion");
        }
        asConflict(() -> terminal.completeRegistration(tokenVersion, tokenSha256));
        return terminalRepository.saveAndFlush(terminal);
    }

    @Transactional(readOnly = true)
    public RegistrationDecision verifyRegistration(
            String terminalPhone,
            String terminalCode,
            String manufacturerId,
            String model,
            String vehicleIdentifier,
            String protocolVersion) {
        JtTerminal terminal = terminalRepository.findByTerminalCode(terminalCode).orElse(null);
        if (terminal == null || !registrationPending(terminal)
                || terminal.getLastRegisteredAt() != null
                || !secureEquals(terminal.getTerminalPhone(), terminalPhone)
                || !secureEquals(terminal.getManufacturerId(), manufacturerId)
                || !secureEquals(terminal.getModel(), model)
                || !secureEquals(terminal.getProtocolVersion(), protocolVersion)) {
            return RegistrationDecision.rejected();
        }
        JtTerminalVehicleBinding binding = bindingRepository
                .findByTerminalIdAndStatus(terminal.getId(), JtTerminalVehicleBinding.Status.ACTIVE)
                .orElse(null);
        if (binding == null) {
            return RegistrationDecision.rejected();
        }
        boolean vehicleMatches = vehicleRepository.findById(binding.getVehicleId())
                .map(vehicle -> secureEquals(vehicle.getPlateNumber(), vehicleIdentifier))
                .orElse(false);
        return vehicleMatches
                ? new RegistrationDecision(true, terminal.getId(), binding.getVehicleId(),
                        terminal.getSourceCoordinateSystem(), terminal.getActiveSafetyStandard(),
                        parseActiveSafetyModules(terminal.getActiveSafetyModules()), terminal.getAuthTokenVersion(), null)
                : RegistrationDecision.rejected();
    }

    @Transactional
    public AuthenticationDecision verifyAuthentication(
            UUID terminalId, int tokenVersion, String tokenSha256, String gatewayInstance) {
        if (gatewayInstance == null || gatewayInstance.isBlank()) {
            throw new IllegalArgumentException("gatewayInstance must not be blank");
        }
        JtTerminal terminal = terminalRepository.findById(terminalId).orElse(null);
        if (terminal == null || terminal.getStatus() != JtTerminal.Status.ACTIVE
                || terminal.getAuthTokenVersion() != tokenVersion
                || tokenSha256 == null || !tokenSha256.matches("[0-9a-f]{64}")) {
            return AuthenticationDecision.rejected();
        }
        if (bindingRepository
                .findByTerminalIdAndStatus(terminalId, JtTerminalVehicleBinding.Status.ACTIVE)
                .isEmpty()) {
            return AuthenticationDecision.rejected();
        }
        byte[] expected = terminal.getAuthTokenHash().getBytes(StandardCharsets.US_ASCII);
        byte[] presented = tokenSha256.getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expected, presented)) {
            return AuthenticationDecision.rejected();
        }
        terminal.recordSuccessfulAuthentication(OffsetDateTime.now(clock));
        terminalRepository.saveAndFlush(terminal);
        return new AuthenticationDecision(true, null);
    }

    public GatewayAuditResult recordGatewayAudit(JtGatewayAuditEvent event) {
        try {
            return committedStateTransaction.execute(status -> {
                if (gatewayAuditRepository.existsByIdempotencyKey(event.getIdempotencyKey())) {
                    return new GatewayAuditResult(event.getIdempotencyKey(), "REPLAYED");
                }
                gatewayAuditRepository.saveAndFlush(event);
                return new GatewayAuditResult(event.getIdempotencyKey(), "ACCEPTED");
            });
        } catch (org.springframework.dao.DataIntegrityViolationException conflict) {
            Boolean replayed = committedStateTransaction.execute(status ->
                    gatewayAuditRepository.existsByIdempotencyKey(event.getIdempotencyKey()));
            if (Boolean.TRUE.equals(replayed)) {
                return new GatewayAuditResult(event.getIdempotencyKey(), "REPLAYED");
            }
            throw conflict;
        }
    }

    @Transactional
    public JtTerminal bind(
            String terminalCode, UUID vehicleId, long expectedVersion, String reason, UUID actorId) {
        JtTerminal terminal = requireVersion(terminalCode, expectedVersion);
        requireReason(reason);
        if (!vehicleRepository.existsById(vehicleId)) {
            throw new ResponseStatusException(NOT_FOUND, "vehicle not found");
        }
        if (bindingRepository.findByTerminalIdAndStatus(terminal.getId(), JtTerminalVehicleBinding.Status.ACTIVE).isPresent()
                || bindingRepository.findByVehicleIdAndStatus(vehicleId, JtTerminalVehicleBinding.Status.ACTIVE).isPresent()) {
            throw new TerminalConflictException("terminal or vehicle already has an active binding");
        }
        bindingRepository.save(JtTerminalVehicleBinding.bind(terminal, vehicleId, reason, actorId));
        terminal.touch();
        terminalRepository.saveAndFlush(terminal);
        audit(terminal, "JT_TERMINAL_BOUND", actorId, reason);
        return terminal;
    }

    @Transactional
    public JtTerminal activate(String terminalCode, long expectedVersion, String reason, UUID actorId) {
        JtTerminal terminal = requireVersion(terminalCode, expectedVersion);
        requireReason(reason);
        boolean bound = bindingRepository
                .findByTerminalIdAndStatus(terminal.getId(), JtTerminalVehicleBinding.Status.ACTIVE)
                .isPresent();
        asConflict(() -> terminal.activate(bound));
        terminalRepository.saveAndFlush(terminal);
        audit(terminal, "JT_TERMINAL_ACTIVATED", actorId, reason);
        return terminal;
    }

    public ActionResult suspend(String terminalCode, long expectedVersion, String reason, UUID actorId) {
        PendingDisconnect pending = committedStateTransaction.execute(status -> {
            JtTerminal terminal = requireVersion(terminalCode, expectedVersion);
            requireReason(reason);
            asConflict(terminal::suspend);
            terminalRepository.saveAndFlush(terminal);
            audit(terminal, "JT_TERMINAL_SUSPENDED", actorId, reason);
            audit(terminal, "JT_TERMINAL_DISCONNECT_REQUESTED", actorId, reason);
            return new PendingDisconnect(terminal, "TERMINAL_SUSPENDED");
        });
        return requestDisconnect(pending);
    }

    public ActionResult retire(String terminalCode, long expectedVersion, String reason, UUID actorId) {
        PendingDisconnect pending = committedStateTransaction.execute(status -> {
            JtTerminal terminal = requireVersion(terminalCode, expectedVersion);
            requireReason(reason);
            asConflict(terminal::retire);
            bindingRepository.findByTerminalIdAndStatus(terminal.getId(), JtTerminalVehicleBinding.Status.ACTIVE)
                    .ifPresent(binding -> binding.unbind(reason, actorId));
            terminalRepository.saveAndFlush(terminal);
            audit(terminal, "JT_TERMINAL_RETIRED", actorId, reason);
            audit(terminal, "JT_TERMINAL_DISCONNECT_REQUESTED", actorId, reason);
            return new PendingDisconnect(terminal, "TERMINAL_RETIRED");
        });
        return requestDisconnect(pending);
    }

    public ReplacementResult replace(
            String terminalCode,
            String replacementTerminalCode,
            long expectedVersion,
            long replacementExpectedVersion,
            String reason,
            UUID actorId) {
        ReplacementPending pending = committedStateTransaction.execute(status -> {
            JtTerminal oldTerminal = requireVersion(terminalCode, expectedVersion);
            JtTerminal replacement = requireVersion(replacementTerminalCode, replacementExpectedVersion);
            requireReason(reason);
            if (oldTerminal.getId().equals(replacement.getId())
                    || replacement.getStatus() != JtTerminal.Status.PENDING
                    || replacement.getLastRegisteredAt() != null
                    || bindingRepository.findByTerminalIdAndStatus(
                            replacement.getId(), JtTerminalVehicleBinding.Status.ACTIVE).isPresent()) {
                throw new TerminalConflictException("replacement terminal is not ready");
            }
            JtTerminalVehicleBinding oldBinding = bindingRepository
                    .findByTerminalIdAndStatus(oldTerminal.getId(), JtTerminalVehicleBinding.Status.ACTIVE)
                    .orElseThrow(() -> new TerminalConflictException("terminal has no active binding"));
            UUID vehicleId = oldBinding.getVehicleId();
            String vehiclePlate = vehicleRepository.findById(vehicleId)
                    .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "vehicle not found"))
                    .getPlateNumber();
            oldBinding.unbind(reason, actorId);
            asConflict(oldTerminal::retireAndInvalidateAuthentication);
            asConflict(replacement::prepareForReplacementRegistration);
            bindingRepository.save(JtTerminalVehicleBinding.bind(replacement, vehicleId, reason, actorId));
            terminalRepository.saveAndFlush(oldTerminal);
            terminalRepository.saveAndFlush(replacement);
            String metadata = replacementMetadata(oldTerminal, replacement, vehiclePlate);
            audit(oldTerminal, "JT_TERMINAL_REPLACED", actorId, reason, metadata);
            audit(oldTerminal, "JT_TERMINAL_DISCONNECT_REQUESTED", actorId, reason);
            gatewayAuditRepository.save(JtGatewayAuditEvent.record(
                    oldTerminal.getId(), vehicleId,
                    JtGatewayAuditEvent.EventType.TERMINAL_REPLACED,
                    JtGatewayAuditEvent.Result.APPLIED,
                    "TERMINAL_REPLACED", oldTerminal.getProtocolVersion(), null,
                    null, null, OffsetDateTime.now(), "API_MANAGEMENT"));
            gatewayAuditRepository.flush();
            return new ReplacementPending(replacement, oldTerminal.getId());
        });
        boolean confirmed = controlClient.disconnect(pending.oldTerminalId(), "TERMINAL_REPLACED");
        return new ReplacementResult(pending.replacement(), disconnectStatus(confirmed));
    }

    public ActionResult rotateAuthentication(
            String terminalCode, long expectedVersion, String reason, UUID actorId) {
        PendingDisconnect pending = committedStateTransaction.execute(status -> {
            JtTerminal terminal = requireVersion(terminalCode, expectedVersion);
            requireReason(reason);
            boolean bound = bindingRepository
                    .findByTerminalIdAndStatus(terminal.getId(), JtTerminalVehicleBinding.Status.ACTIVE)
                    .isPresent();
            if (!bound || terminal.getLastRegisteredAt() == null) {
                throw new TerminalConflictException("terminal is not eligible for authentication rotation");
            }
            asConflict(terminal::beginAuthenticationRotation);
            terminalRepository.saveAndFlush(terminal);
            audit(terminal, "JT_TERMINAL_AUTH_ROTATED", actorId, reason);
            audit(terminal, "JT_TERMINAL_DISCONNECT_REQUESTED", actorId, reason);
            return new PendingDisconnect(terminal, "AUTHENTICATION_ROTATED");
        });
        return requestDisconnect(pending);
    }

    public ActionResult disconnect(
            String terminalCode, long expectedVersion, String reason, UUID actorId) {
        PendingDisconnect pending = committedStateTransaction.execute(status -> {
            JtTerminal terminal = requireVersion(terminalCode, expectedVersion);
            requireReason(reason);
            terminal.touch();
            terminalRepository.saveAndFlush(terminal);
            audit(terminal, "JT_TERMINAL_DISCONNECT_REQUESTED", actorId, reason);
            return new PendingDisconnect(terminal, "OPERATOR_REQUESTED");
        });
        return requestDisconnect(pending);
    }

    private ActionResult requestDisconnect(PendingDisconnect pending) {
        boolean confirmed = controlClient.disconnect(pending.terminal().getId(), pending.reasonCode());
        return new ActionResult(pending.terminal(), disconnectStatus(confirmed));
    }

    private static String disconnectStatus(boolean confirmed) {
        return confirmed ? "DISCONNECT_CONFIRMED" : "DISCONNECT_PENDING_CONFIRMATION";
    }

    private JtTerminal requireVersion(String terminalCode, long expectedVersion) {
        JtTerminal terminal = terminalRepository.findByTerminalCode(terminalCode)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "terminal not found"));
        if (terminal.getVersion() != expectedVersion) {
            throw new TerminalConflictException("terminal version conflict");
        }
        return terminal;
    }

    private void audit(JtTerminal terminal, String action, UUID actorId, String reason) {
        audit(terminal, action, actorId, reason, "{}");
    }

    private void audit(JtTerminal terminal, String action, UUID actorId, String reason, String metadata) {
        auditLogRepository.save(AuditLog.record(
                "JT_TERMINAL", terminal.getId(), action, "USER", actorId.toString(), reason, metadata));
    }

    private String replacementMetadata(JtTerminal oldTerminal, JtTerminal replacement, String vehiclePlate) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "oldTerminalCode", oldTerminal.getTerminalCode(),
                    "replacementTerminalCode", replacement.getTerminalCode(),
                    "vehiclePlate", vehiclePlate,
                    "oldTokenVersion", oldTerminal.getAuthTokenVersion(),
                    "replacementTokenVersion", replacement.getAuthTokenVersion()));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to encode replacement audit metadata", exception);
        }
    }

    private static CapabilityProfile validateCapabilityProfile(
            String standard, List<String> modules, boolean jt1078Enabled) {
        List<String> requestedModules = modules == null ? List.of() : List.copyOf(modules);
        if (standard == null || standard.isBlank()) {
            if (!requestedModules.isEmpty()) {
                throw new IllegalArgumentException("active safety modules require a standard");
            }
            return new CapabilityProfile(null, List.of(), jt1078Enabled);
        }
        if (!KNOWN_ACTIVE_SAFETY_STANDARDS.contains(standard)) {
            throw new IllegalArgumentException("unsupported active safety standard");
        }
        if (requestedModules.isEmpty()
                || requestedModules.stream().anyMatch(module -> !("ADAS".equals(module) || "DMS".equals(module)))
                || requestedModules.stream().distinct().count() != requestedModules.size()) {
            throw new IllegalArgumentException("invalid active safety modules");
        }
        return new CapabilityProfile(standard, requestedModules, jt1078Enabled);
    }

    private String capabilityMetadata(CapabilityProfile profile) {
        try {
            java.util.Map<String, Object> metadata = new java.util.LinkedHashMap<>();
            metadata.put("activeSafetyStandard", profile.activeSafetyStandard());
            metadata.put("activeSafetyModules", profile.activeSafetyModules());
            metadata.put("jt1078Enabled", profile.jt1078Enabled());
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to encode terminal capability audit", exception);
        }
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
    }

    private static boolean secureEquals(String expected, String presented) {
        if (expected == null || presented == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean registrationPending(JtTerminal terminal) {
        return terminal.getStatus() == JtTerminal.Status.PENDING
                || terminal.getStatus() == JtTerminal.Status.SUSPENDED
                        && terminal.getLastRegisteredAt() == null;
    }

    private static void asConflict(Runnable transition) {
        try {
            transition.run();
        } catch (IllegalStateException exception) {
            throw new TerminalConflictException(exception.getMessage());
        }
    }

    public record PresetCommand(
            String terminalPhone,
            String terminalCode,
            String manufacturerId,
            String model,
            String protocolVersion,
            String sourceCoordinateSystem,
            UUID actorId,
            String reason) {
    }

    private record CapabilityProfile(
            String activeSafetyStandard, List<String> activeSafetyModules, boolean jt1078Enabled) {
    }

    public record ActionResult(JtTerminal terminal, String disconnectStatus) {
    }

    public enum OnlineStatus { ONLINE, OFFLINE, NEVER_SEEN }

    public record BindingSummary(String plateNumber, String status, OffsetDateTime validFrom, OffsetDateTime validTo) {
    }

    public record GatewayAuditSummary(
            String eventType, String result, String reasonCode, String protocolVersion, Integer messageId,
            OffsetDateTime occurredAt) {
    }

    public record TerminalDetail(
            JtTerminal terminal,
            OnlineStatus onlineStatus,
            OffsetDateTime lastValidMessageAt,
            OffsetDateTime offlineAt,
            BindingSummary currentBinding,
            List<BindingSummary> bindingHistory,
            List<GatewayAuditSummary> securityAudits) {
    }

    public record ReplacementResult(JtTerminal terminal, String disconnectStatus) {
    }

    private record PendingDisconnect(JtTerminal terminal, String reasonCode) {
    }

    private record ReplacementPending(JtTerminal replacement, UUID oldTerminalId) {
    }

    private List<String> parseActiveSafetyModules(String serialized) {
        if (serialized == null || serialized.isBlank()) {
            return List.of();
        }
        try {
            return List.copyOf(objectMapper.readValue(serialized,
                    new com.fasterxml.jackson.core.type.TypeReference<List<String>>() { }));
        } catch (JsonProcessingException malformedProfile) {
            return List.of();
        }
    }

    public record RegistrationDecision(
            boolean approved,
            UUID terminalId,
            UUID vehicleId,
            String sourceCoordinateSystem,
            String activeSafetyStandard,
            List<String> activeSafetyModules,
            int tokenVersion,
            String reasonCode) {
        public RegistrationDecision {
            activeSafetyModules = activeSafetyModules == null ? List.of() : List.copyOf(activeSafetyModules);
        }

        static RegistrationDecision rejected() {
            return new RegistrationDecision(false, null, null, null, null, List.of(), 0, "REGISTRATION_REJECTED");
        }
    }

    public record AuthenticationDecision(boolean approved, String reasonCode) {
        static AuthenticationDecision rejected() {
            return new AuthenticationDecision(false, "AUTHENTICATION_REJECTED");
        }
    }
    public record GatewayAuditResult(UUID idempotencyKey, String status) { }
}
