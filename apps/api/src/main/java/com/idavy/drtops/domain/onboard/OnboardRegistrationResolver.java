package com.idavy.drtops.domain.onboard;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idavy.drtops.domain.audit.AuditLog;
import com.idavy.drtops.domain.audit.AuditLogRepository;
import com.idavy.drtops.domain.fleet.Vehicle;
import com.idavy.drtops.domain.fleet.VehicleRepository;
import com.idavy.drtops.domain.onboard.OnboardDeviceCapability.Capability;
import com.idavy.drtops.domain.onboard.OnboardDeviceCapability.CapabilityStatus;
import com.idavy.drtops.domain.onboard.OnboardDeviceProtocolProfile.BusinessProfile;
import com.idavy.drtops.domain.onboard.OnboardDeviceProtocolProfile.MediaProfile;
import com.idavy.drtops.domain.onboard.OnboardDeviceProtocolProfile.SafetyProfile;
import com.idavy.drtops.domain.onboard.OnboardDeviceProtocolProfile.TransportProfile;
import com.idavy.drtops.domain.onboard.OnboardDeviceRoleAssignment.Role;
import com.idavy.drtops.domain.terminal.JtTerminal;
import com.idavy.drtops.domain.terminal.JtTerminalRepository;
import com.idavy.drtops.domain.terminal.TerminalConflictException;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OnboardRegistrationResolver {

    public static final String VEHICLE_IDENTIFIER_MISMATCH = "VEHICLE_IDENTIFIER_MISMATCH";
    public static final int SESSION_CONTRACT_VERSION = 2;

    private final JtTerminalRepository terminalRepository;
    private final OnboardDeviceMembershipRepository membershipRepository;
    private final OnboardSystemRepository systemRepository;
    private final OnboardSystemRuntimeStateRepository runtimeRepository;
    private final OnboardDeviceRoleAssignmentRepository roleRepository;
    private final OnboardDeviceProtocolProfileRepository profileRepository;
    private final OnboardDeviceCapabilityRepository capabilityRepository;
    private final VehicleRepository vehicleRepository;
    private final AuditLogRepository auditLogRepository;
    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public OnboardRegistrationResolver(
            JtTerminalRepository terminalRepository,
            OnboardDeviceMembershipRepository membershipRepository,
            OnboardSystemRepository systemRepository,
            OnboardSystemRuntimeStateRepository runtimeRepository,
            OnboardDeviceRoleAssignmentRepository roleRepository,
            OnboardDeviceProtocolProfileRepository profileRepository,
            OnboardDeviceCapabilityRepository capabilityRepository,
            VehicleRepository vehicleRepository,
            AuditLogRepository auditLogRepository,
            EntityManager entityManager,
            ObjectMapper objectMapper,
            ObjectProvider<Clock> clocks) {
        this.terminalRepository = terminalRepository;
        this.membershipRepository = membershipRepository;
        this.systemRepository = systemRepository;
        this.runtimeRepository = runtimeRepository;
        this.roleRepository = roleRepository;
        this.profileRepository = profileRepository;
        this.capabilityRepository = capabilityRepository;
        this.vehicleRepository = vehicleRepository;
        this.auditLogRepository = auditLogRepository;
        this.entityManager = entityManager;
        this.objectMapper = objectMapper;
        this.clock = clocks.getIfAvailable(Clock::systemUTC);
    }

    @Transactional
    public RegistrationDecision verify(RegistrationRequest request) {
        Objects.requireNonNull(request, "request");
        if (JtTerminalRepository.canonicalProtocolVersion(request.protocolVersion()) == null) {
            return RegistrationDecision.rejected("PROTOCOL_VERSION_MISMATCH");
        }
        List<JtTerminal> phoneMatches = terminalRepository.findAllBySemanticPhone(
                request.terminalPhone(), request.protocolVersion());
        if (phoneMatches.isEmpty()) {
            return RegistrationDecision.rejected("TERMINAL_PHONE_NOT_FOUND");
        }
        if (phoneMatches.size() != 1) {
            return RegistrationDecision.rejected("TERMINAL_PHONE_AMBIGUOUS");
        }
        List<JtTerminal> codeMatches = terminalRepository.findAllByTerminalCode(
                request.terminalCode());
        if (codeMatches.isEmpty()) {
            return RegistrationDecision.rejected("TERMINAL_CODE_NOT_FOUND");
        }
        if (codeMatches.size() != 1) {
            return RegistrationDecision.rejected("TERMINAL_CODE_AMBIGUOUS");
        }
        JtTerminal phoneTerminal = phoneMatches.getFirst();
        JtTerminal codeTerminal = codeMatches.getFirst();
        if (!phoneTerminal.getId().equals(codeTerminal.getId())) {
            return RegistrationDecision.rejected("TERMINAL_IDENTITY_MISMATCH");
        }

        List<OnboardDeviceMembership> initialMemberships = activeMemberships(codeTerminal.getId());
        if (initialMemberships.size() != 1) {
            return RegistrationDecision.rejected("ACTIVE_MEMBERSHIP_MISSING");
        }
        OnboardDeviceMembership initialMembership = initialMemberships.getFirst();
        LockedRegistrationState locked = loadLockedSessionAuthority(
                codeTerminal.getId(), initialMembership.getOnboardSystemId(), true);
        if (locked == null) {
            return RegistrationDecision.rejected("ACTIVE_MEMBERSHIP_MISSING");
        }
        JtTerminal terminal = locked.terminal();
        OnboardDeviceMembership membership = locked.membership();
        OnboardSystem system = locked.system();
        OnboardSystemRuntimeState runtime = locked.runtime();

        if (!registrationIdentityStillMatches(terminal, request)) {
            return RegistrationDecision.rejected("TERMINAL_IDENTITY_CHANGED");
        }
        String terminalGate = terminalGate(terminal, request);
        if (terminalGate != null) {
            return RegistrationDecision.rejected(terminalGate);
        }
        if (system.getStatus() != OnboardSystem.Status.ACTIVE) {
            return RegistrationDecision.rejected("ONBOARD_SYSTEM_UNAVAILABLE");
        }
        Vehicle vehicle = vehicleRepository.findById(system.getVehicleId()).orElse(null);
        if (vehicle == null) {
            return RegistrationDecision.rejected("VEHICLE_UNAVAILABLE");
        }
        if (!membership.getOnboardSystemId().equals(system.getId())) {
            return RegistrationDecision.rejected("ACTIVE_MEMBERSHIP_MISSING");
        }

        if (!secureEquals(vehicle.getPlateNumber(), request.vehicleIdentifier())) {
            Vehicle conflictingVehicle = request.vehicleIdentifier() == null
                    || request.vehicleIdentifier().isBlank()
                    ? null : vehicleRepository.findByPlateNumber(request.vehicleIdentifier()).orElse(null);
            if (conflictingVehicle != null
                    && !conflictingVehicle.getId().equals(vehicle.getId())) {
                audit(system, "VEHICLE_IDENTIFIER_CONFLICT", "conflictCount", 1);
                return RegistrationDecision.rejected("VEHICLE_IDENTIFIER_CONFLICT");
            }
            if (request.vehicleIdentifier() == null || request.vehicleIdentifier().isBlank()) {
                return RegistrationDecision.rejected("VEHICLE_IDENTIFIER_INVALID");
            }
            addMismatchWarning(runtime);
            audit(system, VEHICLE_IDENTIFIER_MISMATCH,
                    "warningCount", runtime.getWarningCodes().size());
            return RegistrationDecision.approved(
                    context(locked),
                    List.of(VEHICLE_IDENTIFIER_MISMATCH));
        }

        removeMismatchWarning(runtime);
        return RegistrationDecision.approved(
                context(locked), List.of());
    }

    @Transactional
    public JtTerminal completeRegistration(
            UUID terminalId,
            int tokenVersion,
            String tokenSha256,
            String gatewayInstance) {
        requireGatewayInstance(gatewayInstance);
        JtTerminal located = terminalRepository.findById(terminalId)
                .orElseThrow(() -> new TerminalConflictException(
                        "terminal is not eligible for registration completion"));
        List<OnboardDeviceMembership> memberships = activeMemberships(located.getId());
        if (memberships.size() != 1) {
            throw new TerminalConflictException(
                    "terminal is not eligible for registration completion");
        }
        LockedRegistrationState locked = loadLockedSessionAuthority(
                located.getId(), memberships.getFirst().getOnboardSystemId(), false);
        if (locked == null
                || locked.system().getStatus() != OnboardSystem.Status.ACTIVE
                || vehicleRepository.findById(locked.system().getVehicleId()).isEmpty()
                || !registrationPending(locked.terminal())
                || locked.terminal().getLastRegisteredAt() != null
                || locked.terminal().getAuthTokenVersion() != tokenVersion) {
            throw new TerminalConflictException(
                    "terminal is not eligible for registration completion");
        }
        try {
            locked.terminal().completeRegistration(tokenVersion, tokenSha256);
        } catch (IllegalStateException invalidState) {
            throw new TerminalConflictException(
                    "terminal is not eligible for registration completion");
        }
        return terminalRepository.saveAndFlush(locked.terminal());
    }

    @Transactional
    public AuthenticationDecision authenticateByTerminalId(
            UUID terminalId,
            int tokenVersion,
            String tokenSha256,
            String gatewayInstance) {
        requireGatewayInstance(gatewayInstance);
        return authenticateLocked(
                terminalId, tokenVersion, null, null, tokenSha256);
    }

    @Transactional
    public AuthenticationDecision authenticateByIdentity(
            String protocolVersion,
            String terminalPhone,
            String tokenSha256,
            String gatewayInstance) {
        requireGatewayInstance(gatewayInstance);
        String canonicalProtocol = JtTerminalRepository.canonicalProtocolVersion(protocolVersion);
        if (canonicalProtocol == null) {
            return AuthenticationDecision.rejected("AUTHENTICATION_REJECTED");
        }
        List<JtTerminal> terminals = terminalRepository.findAllBySemanticPhone(
                terminalPhone, canonicalProtocol);
        if (terminals.size() != 1) {
            return AuthenticationDecision.rejected("AUTHENTICATION_REJECTED");
        }
        return authenticateLocked(
                terminals.getFirst().getId(), null, canonicalProtocol,
                terminalPhone, tokenSha256);
    }

    private AuthenticationDecision authenticateLocked(
            UUID terminalId,
            Integer expectedTokenVersion,
            String expectedProtocolVersion,
            String expectedTerminalPhone,
            String tokenSha256) {
        if (terminalId == null || tokenSha256 == null
                || !tokenSha256.matches("[0-9a-f]{64}")) {
            return AuthenticationDecision.rejected("AUTHENTICATION_REJECTED");
        }
        List<OnboardDeviceMembership> memberships = activeMemberships(terminalId);
        if (memberships.size() != 1) {
            return AuthenticationDecision.rejected("AUTHENTICATION_REJECTED");
        }
        LockedRegistrationState locked = loadLockedSessionAuthority(
                terminalId, memberships.getFirst().getOnboardSystemId(), false);
        if (locked == null
                || locked.system().getStatus() != OnboardSystem.Status.ACTIVE
                || vehicleRepository.findById(locked.system().getVehicleId()).isEmpty()) {
            return AuthenticationDecision.rejected("AUTHENTICATION_REJECTED");
        }
        JtTerminal terminal = locked.terminal();
        if (expectedTerminalPhone != null
                        && !semanticPhoneStillMatches(
                                terminal.getId(), expectedTerminalPhone,
                                expectedProtocolVersion)
                || terminal.getStatus() != JtTerminal.Status.ACTIVE
                || expectedTokenVersion != null
                        && terminal.getAuthTokenVersion() != expectedTokenVersion
                || expectedProtocolVersion != null
                        && !secureEquals(
                                JtTerminalRepository.canonicalProtocolVersion(
                                        terminal.getProtocolVersion()),
                                expectedProtocolVersion)
                || !secureEquals(terminal.getAuthTokenHash(), tokenSha256)) {
            return AuthenticationDecision.rejected("AUTHENTICATION_REJECTED");
        }
        TerminalSessionContext context = context(locked);
        terminal.recordSuccessfulAuthentication(OffsetDateTime.now(clock));
        terminalRepository.saveAndFlush(terminal);
        return AuthenticationDecision.approved(context);
    }

    private boolean registrationIdentityStillMatches(
            JtTerminal terminal, RegistrationRequest request) {
        return semanticPhoneStillMatches(
                        terminal.getId(), request.terminalPhone(), request.protocolVersion())
                && uniqueTerminalStillMatches(
                        terminalRepository.findAllByTerminalCode(request.terminalCode()),
                        terminal.getId());
    }

    private boolean semanticPhoneStillMatches(
            UUID terminalId, String terminalPhone, String protocolVersion) {
        return uniqueTerminalStillMatches(
                terminalRepository.findAllBySemanticPhone(terminalPhone, protocolVersion),
                terminalId);
    }

    private static boolean uniqueTerminalStillMatches(
            List<JtTerminal> terminals, UUID terminalId) {
        return terminals.size() == 1 && terminals.getFirst().getId().equals(terminalId);
    }

    private LockedRegistrationState loadLockedSessionAuthority(
            UUID terminalId, UUID onboardSystemId, boolean includeRuntime) {
        if (!lockOne("onboard_systems", "id", onboardSystemId)) {
            return null;
        }
        if (!lockOne("jt_terminals", "id", terminalId)) {
            return null;
        }
        List<?> lockedMemberships = entityManager.createNativeQuery("""
                        select id from onboard_device_memberships
                        where terminal_id = :terminalId
                          and status = 'ACTIVE' and valid_to is null
                        order by id
                        for update
                        """)
                .setParameter("terminalId", terminalId)
                .getResultList();
        if (lockedMemberships.size() != 1) {
            return null;
        }
        entityManager.createNativeQuery("""
                        select id from onboard_device_role_assignments
                        where onboard_system_id = :onboardSystemId
                          and terminal_id = :terminalId
                          and status = 'ACTIVE' and valid_to is null
                        order by role, id
                        for update
                        """)
                .setParameter("onboardSystemId", onboardSystemId)
                .setParameter("terminalId", terminalId)
                .getResultList();
        List<?> lockedProfiles = entityManager.createNativeQuery("""
                        select id from onboard_device_protocol_profiles
                        where terminal_id = :terminalId
                          and status = 'ACTIVE' and valid_to is null
                        order by id
                        for update
                        """)
                .setParameter("terminalId", terminalId)
                .getResultList();
        if (lockedProfiles.size() != 1) {
            return null;
        }
        entityManager.createNativeQuery("""
                        select id from onboard_device_capabilities
                        where terminal_id = :terminalId and status = 'VERIFIED'
                        order by capability, id
                        for update
                        """)
                .setParameter("terminalId", terminalId)
                .getResultList();
        if (includeRuntime
                && !lockOne("onboard_system_runtime_state", "onboard_system_id", onboardSystemId)) {
            return null;
        }
        JtTerminal terminal = terminalRepository.findById(terminalId).orElse(null);
        OnboardSystem system = systemRepository.findById(onboardSystemId).orElse(null);
        OnboardSystemRuntimeState runtime = includeRuntime
                ? runtimeRepository.findById(onboardSystemId).orElse(null) : null;
        if (terminal == null || system == null || includeRuntime && runtime == null) {
            return null;
        }
        entityManager.refresh(terminal);
        entityManager.refresh(system);
        if (runtime != null) {
            entityManager.refresh(runtime);
        }
        List<OnboardDeviceMembership> memberships = activeMemberships(terminalId);
        if (memberships.size() != 1) {
            return null;
        }
        OnboardDeviceMembership membership = memberships.getFirst();
        entityManager.refresh(membership);
        if (!membership.getOnboardSystemId().equals(onboardSystemId)) {
            return null;
        }
        List<OnboardDeviceRoleAssignment> assignments = roleRepository
                .findActiveByTerminalIdOrderByValidFromAsc(terminalId).stream()
                .filter(assignment -> onboardSystemId.equals(assignment.getOnboardSystemId()))
                .toList();
        EnumSet<Role> roles = EnumSet.noneOf(Role.class);
        assignments.forEach(assignment -> roles.add(assignment.getRole()));
        OnboardDeviceProtocolProfile profile = profileRepository
                .findActiveByTerminalId(terminalId).orElse(null);
        if (profile == null) {
            return null;
        }
        Set<Capability> verifiedCapabilities = capabilityRepository
                .findCurrentByTerminalIdOrderByCreatedAtAsc(terminalId).stream()
                .filter(fact -> fact.getStatus() == CapabilityStatus.VERIFIED)
                .map(OnboardDeviceCapability::getCapability)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new LockedRegistrationState(
                terminal, membership, system, runtime,
                Set.copyOf(roles), profile, verifiedCapabilities);
    }

    private boolean lockOne(String table, String idColumn, UUID id) {
        return entityManager.createNativeQuery(
                        "select " + idColumn + " from " + table
                                + " where " + idColumn + " = :id for update")
                .setParameter("id", id)
                .getResultList().size() == 1;
    }

    private List<OnboardDeviceMembership> activeMemberships(UUID terminalId) {
        return entityManager.createQuery("""
                        select membership
                        from OnboardDeviceMembership membership
                        where membership.terminalId = :terminalId
                          and membership.status = :status and membership.validTo is null
                        order by membership.id
                        """, OnboardDeviceMembership.class)
                .setParameter("terminalId", terminalId)
                .setParameter("status", OnboardDeviceMembership.Status.ACTIVE)
                .getResultList();
    }

    private String terminalGate(JtTerminal terminal, RegistrationRequest request) {
        if (!registrationPending(terminal)) {
            return "TERMINAL_STATE_INVALID";
        }
        if (terminal.getLastRegisteredAt() != null) {
            return "TERMINAL_ALREADY_REGISTERED";
        }
        if (!secureEquals(terminal.getManufacturerId(), request.manufacturerId())) {
            return "MANUFACTURER_MISMATCH";
        }
        if (!secureEquals(terminal.getModel(), request.model())) {
            return "MODEL_MISMATCH";
        }
        String presented = JtTerminalRepository.canonicalProtocolVersion(request.protocolVersion());
        String stored = JtTerminalRepository.canonicalProtocolVersion(terminal.getProtocolVersion());
        return secureEquals(stored, presented) ? null : "PROTOCOL_VERSION_MISMATCH";
    }

    private static boolean registrationPending(JtTerminal terminal) {
        return terminal.getStatus() == JtTerminal.Status.PENDING
                || terminal.getStatus() == JtTerminal.Status.SUSPENDED
                        && terminal.getLastRegisteredAt() == null;
    }

    private static void requireGatewayInstance(String gatewayInstance) {
        if (gatewayInstance == null || gatewayInstance.isBlank()) {
            throw new IllegalArgumentException("gatewayInstance must not be blank");
        }
    }

    private TerminalSessionContext context(LockedRegistrationState authority) {
        JtTerminal terminal = authority.terminal();
        OnboardSystem system = authority.system();
        OnboardDeviceProtocolProfile profile = authority.profile();
        List<String> modules = enabledActiveSafetyModules(
                authority.roles(), authority.verifiedCapabilities());
        SessionProtocolProfile sessionProfile = new SessionProtocolProfile(
                profile.getTransportProfile(),
                profile.getBusinessProfile(),
                profile.getSafetyProfile(),
                profile.getMediaProfile(),
                modules,
                profile.getActivePositionIntervalSeconds(),
                profile.getIdlePositionIntervalSeconds());
        return new TerminalSessionContext(
                SESSION_CONTRACT_VERSION,
                terminal.getId(),
                authority.membership().getOnboardSystemId(),
                system.getVehicleId(),
                system.getVersion(),
                authority.roles(),
                terminal.getSourceCoordinateSystem(),
                sessionProfile,
                compatibilitySafetyStandard(profile.getSafetyProfile()),
                modules,
                terminal.getAuthTokenVersion());
    }

    private static List<String> enabledActiveSafetyModules(
            Set<Role> roles, Set<Capability> verifiedCapabilities) {
        if (!roles.contains(Role.ACTIVE_SAFETY)) {
            return List.of();
        }
        List<String> modules = new ArrayList<>(2);
        if (verifiedCapabilities.contains(Capability.ADAS)) {
            modules.add("ADAS");
        }
        if (verifiedCapabilities.contains(Capability.DMS)) {
            modules.add("DMS");
        }
        return List.copyOf(modules);
    }

    private static String compatibilitySafetyStandard(SafetyProfile profile) {
        return switch (profile) {
            case JSATL12_2017 -> "T/JSATL12-2017";
            case GBT28787_2023 -> "GB/T 28787-2023";
            case NONE -> null;
        };
    }

    private void addMismatchWarning(OnboardSystemRuntimeState runtime) {
        LinkedHashSet<String> warnings = new LinkedHashSet<>(runtime.getWarningCodes());
        if (warnings.add(VEHICLE_IDENTIFIER_MISMATCH)) {
            runtime.replaceWarningCodes(warnings, OffsetDateTime.now(clock));
            runtimeRepository.saveAndFlush(runtime);
        }
    }

    private void removeMismatchWarning(OnboardSystemRuntimeState runtime) {
        List<String> warnings = runtime.getWarningCodes().stream()
                .filter(code -> !VEHICLE_IDENTIFIER_MISMATCH.equals(code))
                .toList();
        if (!warnings.equals(runtime.getWarningCodes())) {
            runtime.replaceWarningCodes(warnings, OffsetDateTime.now(clock));
            runtimeRepository.saveAndFlush(runtime);
        }
    }

    private void audit(OnboardSystem system, String action, String countName, int count) {
        auditLogRepository.save(AuditLog.record(
                "ONBOARD_SYSTEM", system.getId(), action,
                "SYSTEM", "JT_GATEWAY_REGISTRATION", action,
                json(Map.of(countName, count))));
    }

    private String json(Map<String, Integer> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("failed to encode safe registration audit", impossible);
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

    public record RegistrationRequest(
            String terminalPhone,
            String terminalCode,
            String manufacturerId,
            String model,
            String vehicleIdentifier,
            String protocolVersion) {
    }

    public record SessionProtocolProfile(
            TransportProfile transportProfile,
            BusinessProfile businessProfile,
            SafetyProfile safetyProfile,
            MediaProfile mediaProfile,
            List<String> enabledActiveSafetyModules,
            int activePositionIntervalSeconds,
            int idlePositionIntervalSeconds) {
        public SessionProtocolProfile {
            Objects.requireNonNull(transportProfile, "transportProfile");
            Objects.requireNonNull(businessProfile, "businessProfile");
            Objects.requireNonNull(safetyProfile, "safetyProfile");
            Objects.requireNonNull(mediaProfile, "mediaProfile");
            enabledActiveSafetyModules = List.copyOf(enabledActiveSafetyModules);
            if (activePositionIntervalSeconds <= 0
                    || idlePositionIntervalSeconds <= 0) {
                throw new IllegalArgumentException("position intervals must be positive");
            }
        }
    }

    public record TerminalSessionContext(
            int contractVersion,
            UUID terminalId,
            UUID onboardSystemId,
            UUID vehicleId,
            long onboardConfigurationVersion,
            Set<Role> roles,
            String sourceCoordinateSystem,
            SessionProtocolProfile protocolProfile,
            String activeSafetyStandard,
            List<String> activeSafetyModules,
            int tokenVersion) {
        public TerminalSessionContext {
            if (contractVersion != SESSION_CONTRACT_VERSION) {
                throw new IllegalArgumentException("unsupported session contract version");
            }
            Objects.requireNonNull(terminalId, "terminalId");
            Objects.requireNonNull(onboardSystemId, "onboardSystemId");
            Objects.requireNonNull(vehicleId, "vehicleId");
            Objects.requireNonNull(protocolProfile, "protocolProfile");
            roles = roles == null || roles.isEmpty()
                    ? Set.of()
                    : Collections.unmodifiableSet(EnumSet.copyOf(roles));
            activeSafetyModules = List.copyOf(activeSafetyModules);
        }
    }

    public record RegistrationDecision(
            boolean approved,
            TerminalSessionContext context,
            List<String> warnings,
            String reasonCode) {
        public RegistrationDecision {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
            if (approved && (context == null || reasonCode != null)) {
                throw new IllegalArgumentException("approved registration decision is invalid");
            }
            if (!approved && (context != null || reasonCode == null
                    || !reasonCode.matches("[A-Z][A-Z0-9_]{2,79}"))) {
                throw new IllegalArgumentException("rejected registration decision is invalid");
            }
        }

        static RegistrationDecision approved(
                TerminalSessionContext context, List<String> warnings) {
            return new RegistrationDecision(true, context, warnings, null);
        }

        static RegistrationDecision rejected(String reasonCode) {
            return new RegistrationDecision(false, null, List.of(), reasonCode);
        }
    }

    public record AuthenticationDecision(
            boolean approved,
            TerminalSessionContext context,
            String reasonCode) {
        public AuthenticationDecision {
            if (approved && (context == null || reasonCode != null)) {
                throw new IllegalArgumentException("approved authentication decision is invalid");
            }
            if (!approved && (context != null || reasonCode == null
                    || !reasonCode.matches("[A-Z][A-Z0-9_]{2,79}"))) {
                throw new IllegalArgumentException("rejected authentication decision is invalid");
            }
        }

        static AuthenticationDecision approved(TerminalSessionContext context) {
            return new AuthenticationDecision(true, context, null);
        }

        static AuthenticationDecision rejected(String reasonCode) {
            return new AuthenticationDecision(false, null, reasonCode);
        }
    }

    private record LockedRegistrationState(
            JtTerminal terminal,
            OnboardDeviceMembership membership,
            OnboardSystem system,
            OnboardSystemRuntimeState runtime,
            Set<Role> roles,
            OnboardDeviceProtocolProfile profile,
            Set<Capability> verifiedCapabilities) {
    }
}
