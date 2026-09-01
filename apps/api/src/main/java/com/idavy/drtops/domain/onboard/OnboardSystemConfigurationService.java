package com.idavy.drtops.domain.onboard;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idavy.drtops.domain.audit.AuditLog;
import com.idavy.drtops.domain.audit.AuditLogRepository;
import com.idavy.drtops.domain.onboard.OnboardDeviceCapability.Capability;
import com.idavy.drtops.domain.onboard.OnboardDeviceCapability.CapabilityStatus;
import com.idavy.drtops.domain.onboard.OnboardDeviceMembership.NetworkMode;
import com.idavy.drtops.domain.onboard.OnboardDeviceProtocolProfile.BusinessProfile;
import com.idavy.drtops.domain.onboard.OnboardDeviceProtocolProfile.MediaProfile;
import com.idavy.drtops.domain.onboard.OnboardDeviceProtocolProfile.SafetyProfile;
import com.idavy.drtops.domain.onboard.OnboardDeviceProtocolProfile.TransportProfile;
import com.idavy.drtops.domain.onboard.OnboardDeviceRoleAssignment.Role;
import com.idavy.drtops.domain.onboard.OnboardReadinessService.OnboardReadiness;
import com.idavy.drtops.domain.onboard.OnboardSystem.OperatingMode;
import com.idavy.drtops.domain.terminal.JtTerminal;
import com.idavy.drtops.domain.terminal.JtTerminalRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.HexFormat;
import java.util.regex.Pattern;
import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OnboardSystemConfigurationService {

    private static final int MAX_COMMAND_REASON_CODE_POINTS = 300;
    private static final List<String> CHANGED_FIELD_ORDER = List.of(
            "operatingMode", "devices", "protocolProfiles", "roles", "wanUplink");

    private final OnboardSystemRepository systemRepository;
    private final OnboardDeviceMembershipRepository membershipRepository;
    private final OnboardDeviceCapabilityRepository capabilityRepository;
    private final OnboardDeviceProtocolProfileRepository profileRepository;
    private final OnboardDeviceRoleAssignmentRepository roleRepository;
    private final OnboardSystemRuntimeStateRepository runtimeStateRepository;
    private final OnboardReadinessService readinessService;
    private final JtTerminalRepository terminalRepository;
    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final EntityManager entityManager;

    public OnboardSystemConfigurationService(
            OnboardSystemRepository systemRepository,
            OnboardDeviceMembershipRepository membershipRepository,
            OnboardDeviceCapabilityRepository capabilityRepository,
            OnboardDeviceProtocolProfileRepository profileRepository,
            OnboardDeviceRoleAssignmentRepository roleRepository,
            OnboardSystemRuntimeStateRepository runtimeStateRepository,
            OnboardReadinessService readinessService,
            JtTerminalRepository terminalRepository,
            AuditLogRepository auditLogRepository,
            ObjectMapper objectMapper,
            EntityManager entityManager,
            ObjectProvider<Clock> clocks) {
        this.systemRepository = systemRepository;
        this.membershipRepository = membershipRepository;
        this.capabilityRepository = capabilityRepository;
        this.profileRepository = profileRepository;
        this.roleRepository = roleRepository;
        this.runtimeStateRepository = runtimeStateRepository;
        this.readinessService = readinessService;
        this.terminalRepository = terminalRepository;
        this.auditLogRepository = auditLogRepository;
        this.objectMapper = objectMapper;
        this.entityManager = entityManager;
        this.clock = clocks.getIfAvailable(Clock::systemUTC);
    }

    @Transactional
    public ConfigurationPreview preview(UUID vehicleId, ConfigurationCommand command) {
        try {
            return previewLocked(vehicleId, command);
        } catch (ObjectOptimisticLockingFailureException | OptimisticLockException conflict) {
            throw conflict("STALE_CONFIGURATION_VERSION");
        }
    }

    private ConfigurationPreview previewLocked(
            UUID vehicleId, ConfigurationCommand command) {
        OnboardSystem system = lockedActiveSystem(vehicleId);
        Map<String, JtTerminal> lockedTerminals = lockConfigurationState(
                system.getId(), command.devices());
        EvaluatedConfiguration evaluated = evaluate(system, command, lockedTerminals);
        return new ConfigurationPreview(
                system.getId(), system.getVehicleId(), system.getVersion(),
                evaluated.changedFields(), List.of());
    }

    @Transactional(readOnly = true)
    public List<OnboardSystemView> listSystems() {
        return listSystems(0, 20).items();
    }

    @Transactional(readOnly = true)
    public OnboardSystemPage listSystems(int page, int size) {
        if (page < 0) {
            throw new IllegalArgumentException("page must not be negative");
        }
        if (size < 1 || size > 100) {
            throw new IllegalArgumentException("size must be between 1 and 100");
        }
        long firstResult = (long) page * size;
        if (firstResult > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("page is too large");
        }
        List<Object[]> systemRows = entityManager.createQuery("""
                        select system, runtime
                        from OnboardSystem system
                        left join OnboardSystemRuntimeState runtime
                          on runtime.onboardSystemId = system.id
                        where system.status = :status
                        order by system.vehicleId, system.id
                        """, Object[].class)
                .setParameter("status", OnboardSystem.Status.ACTIVE)
                .setFirstResult((int) firstResult)
                .setMaxResults(size)
                .getResultList();
        List<OnboardSystem> systems = systemRows.stream()
                .map(row -> (OnboardSystem) row[0])
                .toList();
        Map<UUID, OnboardSystemRuntimeState> runtimeBySystem = systemRows.stream()
                .filter(row -> row[1] != null)
                .collect(java.util.stream.Collectors.toMap(
                        row -> ((OnboardSystem) row[0]).getId(),
                        row -> (OnboardSystemRuntimeState) row[1]));
        long totalElements = entityManager.createQuery("""
                        select count(system)
                        from OnboardSystem system
                        where system.status = :status
                        """, Long.class)
                .setParameter("status", OnboardSystem.Status.ACTIVE)
                .getSingleResult();
        long totalPages = (totalElements + size - 1) / size;
        return new OnboardSystemPage(
                assembleViews(systems, runtimeBySystem), page, size, totalElements, totalPages);
    }

    @Transactional(readOnly = true)
    public OnboardSystemView getSystem(UUID vehicleId) {
        OnboardSystem system = activeSystem(vehicleId);
        Map<UUID, OnboardSystemRuntimeState> runtime = runtimeStateRepository
                .findById(system.getId())
                .map(state -> Map.of(system.getId(), state))
                .orElseGet(Map::of);
        return assembleViews(List.of(system), runtime).getFirst();
    }

    @Transactional
    public OnboardSystemDetailSnapshot getSystemDetail(UUID vehicleId) {
        OnboardSystem system = lockedActiveSystem(vehicleId);
        OnboardSystemRuntimeState runtime = runtimeStateRepository
                .findLockedByOnboardSystemId(system.getId())
                .orElseThrow(() -> conflict("ONBOARD_RUNTIME_STATE_NOT_FOUND"));
        entityManager.refresh(runtime);
        OnboardSystemView view = assembleViews(
                List.of(system), Map.of(system.getId(), runtime)).getFirst();
        OnboardReadiness readiness = readinessService.evaluate(vehicleId);
        return new OnboardSystemDetailSnapshot(view, readiness);
    }

    @Transactional
    public ConfigurationPreview apply(
            UUID vehicleId, ConfigurationCommand command, UUID actorId) {
        try {
            return applyLocked(vehicleId, command, actorId);
        } catch (ObjectOptimisticLockingFailureException | OptimisticLockException conflict) {
            throw conflict("STALE_CONFIGURATION_VERSION");
        } catch (DataIntegrityViolationException conflict) {
            throw translateKnownOnboardConstraint(conflict);
        }
    }

    private ConfigurationPreview applyLocked(
            UUID vehicleId, ConfigurationCommand command, UUID actorId) {
        Objects.requireNonNull(actorId, "actorId");
        OnboardSystem system = lockedActiveSystem(vehicleId);
        Map<String, JtTerminal> lockedTerminals = lockConfigurationState(
                system.getId(), command.devices());
        EvaluatedConfiguration evaluated = evaluate(system, command, lockedTerminals);
        if (evaluated.changedFields().isEmpty()) {
            throw conflict("NO_CONFIGURATION_CHANGES");
        }

        long oldVersion = system.getVersion();
        OffsetDateTime changedAt = OffsetDateTime.now(clock);
        reconcileMemberships(system, evaluated, actorId, command.reason(), changedAt);
        reconcileProfiles(evaluated, actorId, command.reason(), changedAt);
        reconcileRoles(system, evaluated, actorId, command.reason(), changedAt);
        if (system.getOperatingMode() != command.operatingMode()) {
            system.changeOperatingMode(command.operatingMode(), actorId, changedAt);
        } else {
            system.touchConfiguration(actorId, changedAt);
        }
        system = systemRepository.saveAndFlush(system);

        auditLogRepository.save(AuditLog.record(
                "ONBOARD_SYSTEM", system.getId(),
                "ONBOARD_SYSTEM_CONFIGURATION_CHANGED", "USER", actorId.toString(),
                command.reason(),
                safeConfigurationMetadata(
                        evaluated, oldVersion, system.getVersion())));
        return new ConfigurationPreview(
                system.getId(), system.getVehicleId(), system.getVersion(),
                evaluated.changedFields(), List.of());
    }

    @Transactional
    public OnboardSystem bindLegacyTerminal(
            UUID vehicleId,
            JtTerminal terminal,
            String reason,
            UUID actorId) {
        try {
            return bindLegacyTerminalLocked(vehicleId, terminal, reason, actorId);
        } catch (ObjectOptimisticLockingFailureException | OptimisticLockException conflict) {
            throw conflict("STALE_CONFIGURATION_VERSION");
        } catch (DataIntegrityViolationException conflict) {
            throw translateKnownOnboardConstraint(conflict);
        }
    }

    @Transactional(readOnly = true)
    public boolean hasCurrentActiveMembership(UUID terminalId) {
        Objects.requireNonNull(terminalId, "terminalId");
        return membershipRepository.findActiveByTerminalId(terminalId)
                .flatMap(membership -> systemRepository.findById(membership.getOnboardSystemId()))
                .filter(system -> system.getStatus() == OnboardSystem.Status.ACTIVE)
                .isPresent();
    }

    @Transactional(readOnly = true)
    public java.util.Optional<UUID> findCurrentVehicleId(UUID terminalId) {
        Objects.requireNonNull(terminalId, "terminalId");
        return membershipRepository.findActiveByTerminalId(terminalId)
                .flatMap(membership -> systemRepository.findById(membership.getOnboardSystemId()))
                .filter(system -> system.getStatus() == OnboardSystem.Status.ACTIVE)
                .map(OnboardSystem::getVehicleId);
    }

    @Transactional(readOnly = true)
    public UUID requireCurrentVehicleId(UUID terminalId) {
        return findCurrentVehicleId(terminalId)
                .orElseThrow(() -> conflict("ACTIVE_MEMBERSHIP_MISSING"));
    }

    @Transactional
    public OnboardLifecycleResult retireTerminal(
            UUID terminalId, long expectedTerminalVersion, String reason, UUID actorId) {
        Objects.requireNonNull(terminalId, "terminalId");
        Objects.requireNonNull(actorId, "actorId");
        OnboardText.requireAuditText(reason, "reason");
        OnboardDeviceMembership discovered = membershipRepository
                .findActiveByTerminalId(terminalId)
                .orElseThrow(() -> conflict("ACTIVE_MEMBERSHIP_MISSING"));
        OnboardSystem discoveredSystem = systemRepository
                .findById(discovered.getOnboardSystemId())
                .orElseThrow(() -> conflict("ONBOARD_SYSTEM_NOT_ACTIVE"));
        OnboardSystem system = lockedActiveSystem(discoveredSystem.getVehicleId());
        lockTerminalState(List.of(terminalId));
        requireTerminalVersion(terminalId, expectedTerminalVersion);
        OnboardDeviceMembership membership = membershipRepository
                .findActiveByTerminalId(terminalId)
                .filter(candidate -> candidate.getOnboardSystemId().equals(system.getId()))
                .orElseThrow(() -> conflict("ACTIVE_MEMBERSHIP_MISSING"));
        List<OnboardDeviceMembership> currentMembers = membershipRepository
                .findActiveByOnboardSystemIdOrderByValidFromAsc(system.getId());
        List<OnboardDeviceRoleAssignment> currentRoles = roleRepository
                .findActiveByTerminalIdOrderByValidFromAsc(terminalId).stream()
                .filter(role -> role.getOnboardSystemId().equals(system.getId()))
                .toList();
        OnboardSystemRuntimeState runtime = runtimeStateRepository
                .findLockedByOnboardSystemId(system.getId())
                .orElseThrow(() -> conflict("ONBOARD_RUNTIME_STATE_MISSING"));

        OffsetDateTime changedAt = OffsetDateTime.now(clock);
        currentRoles.forEach(role -> {
            role.revoke(reason, actorId, changedAt);
            roleRepository.save(role);
        });
        roleRepository.flush();
        membership.remove(reason, actorId, changedAt);
        membershipRepository.saveAndFlush(membership);
        if (terminalId.equals(runtime.getActiveLocationTerminalId())) {
            runtime.clearLocationSource(changedAt);
            runtimeStateRepository.saveAndFlush(runtime);
        }
        if (currentMembers.size() == 1) {
            system.suspend(actorId, changedAt);
        } else {
            system.touchConfiguration(actorId, changedAt);
        }
        systemRepository.saveAndFlush(system);
        return new OnboardLifecycleResult(system.getId(), system.getVehicleId());
    }

    @Transactional
    public OnboardReplacementResult replaceTerminal(
            UUID oldTerminalId,
            long expectedOldTerminalVersion,
            UUID replacementTerminalId,
            long expectedReplacementTerminalVersion,
            String reason,
            UUID actorId) {
        Objects.requireNonNull(oldTerminalId, "oldTerminalId");
        Objects.requireNonNull(replacementTerminalId, "replacementTerminalId");
        Objects.requireNonNull(actorId, "actorId");
        OnboardText.requireAuditText(reason, "reason");
        if (oldTerminalId.equals(replacementTerminalId)) {
            throw conflict("ONBOARD_REPLACEMENT_TERMINAL_INVALID");
        }

        OnboardDeviceMembership discovered = membershipRepository
                .findActiveByTerminalId(oldTerminalId)
                .orElseThrow(() -> conflict("ACTIVE_MEMBERSHIP_MISSING"));
        OnboardSystem discoveredSystem = systemRepository
                .findById(discovered.getOnboardSystemId())
                .orElseThrow(() -> conflict("ONBOARD_SYSTEM_NOT_ACTIVE"));
        OnboardSystem system = lockedActiveSystem(discoveredSystem.getVehicleId());
        lockTerminalState(List.of(oldTerminalId, replacementTerminalId));
        requireTerminalVersion(oldTerminalId, expectedOldTerminalVersion);
        requireTerminalVersion(replacementTerminalId, expectedReplacementTerminalVersion);
        OnboardDeviceMembership oldMembership = membershipRepository
                .findActiveByTerminalId(oldTerminalId)
                .filter(candidate -> candidate.getOnboardSystemId().equals(system.getId()))
                .orElseThrow(() -> conflict("ACTIVE_MEMBERSHIP_MISSING"));
        OnboardDeviceMembership replacementMembership = membershipRepository
                .findActiveByTerminalId(replacementTerminalId)
                .filter(candidate -> candidate.getOnboardSystemId().equals(system.getId()))
                .orElseThrow(() -> conflict("ONBOARD_REPLACEMENT_MEMBERSHIP_MISSING"));
        if (profileRepository.findActiveByTerminalId(replacementTerminalId).isEmpty()) {
            throw conflict("ONBOARD_REPLACEMENT_PROFILE_MISSING");
        }
        List<OnboardDeviceRoleAssignment> rolesToTransfer = roleRepository
                .findActiveByTerminalIdOrderByValidFromAsc(oldTerminalId).stream()
                .filter(role -> role.getOnboardSystemId().equals(system.getId()))
                .toList();
        Set<Capability> verifiedCapabilities = capabilityRepository
                .findCurrentByTerminalIdOrderByCreatedAtAsc(replacementTerminalId).stream()
                .filter(fact -> fact.getStatus() == CapabilityStatus.VERIFIED)
                .map(OnboardDeviceCapability::getCapability)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        for (OnboardDeviceRoleAssignment role : rolesToTransfer) {
            if (role.getRole() == Role.WAN_UPLINK) {
                if (replacementMembership.getNetworkMode() != NetworkMode.DIRECT_CELLULAR) {
                    throw conflict("ONBOARD_REPLACEMENT_NETWORK_MODE_INVALID");
                }
            } else if (!supports(role.getRole(), verifiedCapabilities)) {
                throw conflict("ONBOARD_REPLACEMENT_CAPABILITY_MISSING");
            }
        }

        OnboardSystemRuntimeState runtime = runtimeStateRepository
                .findLockedByOnboardSystemId(system.getId())
                .orElseThrow(() -> conflict("ONBOARD_RUNTIME_STATE_MISSING"));
        OffsetDateTime changedAt = OffsetDateTime.now(clock);
        rolesToTransfer.forEach(role -> {
            role.revoke(reason, actorId, changedAt);
            roleRepository.save(role);
        });
        roleRepository.flush();
        rolesToTransfer.forEach(role -> roleRepository.save(
                OnboardDeviceRoleAssignment.assign(
                        system.getId(), replacementTerminalId, role.getRole(),
                        reason, actorId, changedAt)));
        roleRepository.flush();
        oldMembership.remove(reason, actorId, changedAt);
        membershipRepository.saveAndFlush(oldMembership);
        if (oldTerminalId.equals(runtime.getActiveLocationTerminalId())) {
            boolean transfersLocation = rolesToTransfer.stream().anyMatch(role ->
                    role.getRole() == Role.LOCATION_PRIMARY
                            || role.getRole() == Role.LOCATION_BACKUP);
            if (transfersLocation) {
                runtime.selectLocationSource(replacementTerminalId, changedAt);
            } else {
                runtime.clearLocationSource(changedAt);
            }
            runtimeStateRepository.saveAndFlush(runtime);
        }
        system.touchConfiguration(actorId, changedAt);
        systemRepository.saveAndFlush(system);
        return new OnboardReplacementResult(
                system.getId(), system.getVehicleId(),
                rolesToTransfer.stream().map(OnboardDeviceRoleAssignment::getRole).collect(
                        java.util.stream.Collectors.toUnmodifiableSet()));
    }

    private OnboardSystem bindLegacyTerminalLocked(
            UUID vehicleId,
            JtTerminal terminal,
            String reason,
            UUID actorId) {
        Objects.requireNonNull(vehicleId, "vehicleId");
        Objects.requireNonNull(terminal, "terminal");
        Objects.requireNonNull(actorId, "actorId");
        OnboardText.requireAuditText(reason, "reason");
        OnboardSystem system = lockedActiveSystemOrNull(vehicleId);
        lockTerminalState(List.of(terminal.getId()));
        JtTerminal lockedTerminal = terminalRepository.findById(terminal.getId())
                .orElseThrow(() -> conflict("TERMINAL_NOT_FOUND"));
        entityManager.refresh(lockedTerminal);
        if (lockedTerminal.getStatus() == JtTerminal.Status.RETIRED) {
            throw conflict("TERMINAL_RETIRED");
        }
        membershipRepository.findActiveByTerminalId(lockedTerminal.getId()).ifPresent(existing -> {
            OnboardSystem assignedSystem = systemRepository.findById(existing.getOnboardSystemId())
                    .orElseThrow(() -> conflict("TERMINAL_ALREADY_ASSIGNED"));
            if (!assignedSystem.getVehicleId().equals(vehicleId)) {
                throw conflict("TERMINAL_ALREADY_ASSIGNED");
            }
        });

        OffsetDateTime changedAt = OffsetDateTime.now(clock);
        boolean created = system == null;
        if (created) {
            system = systemRepository.saveAndFlush(OnboardSystem.create(
                    vehicleId, OperatingMode.SAFETY_MONITOR_ONLY, actorId, changedAt));
            runtimeStateRepository.saveAndFlush(
                    OnboardSystemRuntimeState.initialize(system.getId(), changedAt));
        } else if (runtimeStateRepository.findById(system.getId()).isEmpty()) {
            runtimeStateRepository.saveAndFlush(
                    OnboardSystemRuntimeState.initialize(system.getId(), changedAt));
        }
        if (membershipRepository.findActiveByTerminalId(lockedTerminal.getId()).isEmpty()) {
            membershipRepository.saveAndFlush(OnboardDeviceMembership.join(
                    system.getId(), lockedTerminal.getId(), NetworkMode.DIRECT_CELLULAR,
                    reason, actorId, changedAt));
            if (!created) {
                system.touchConfiguration(actorId, changedAt);
                system = systemRepository.saveAndFlush(system);
            }
        }
        return system;
    }

    @Transactional
    public CapabilityVerificationView verifyCapability(
            String terminalCode,
            CapabilityVerificationCommand command,
            UUID actorId) {
        try {
            return verifyCapabilityLocked(terminalCode, command, actorId);
        } catch (ObjectOptimisticLockingFailureException | OptimisticLockException conflict) {
            throw conflict("STALE_CAPABILITY_VERSION");
        } catch (DataIntegrityViolationException conflict) {
            throw translateKnownOnboardConstraint(conflict);
        }
    }

    private CapabilityVerificationView verifyCapabilityLocked(
            String terminalCode,
            CapabilityVerificationCommand command,
            UUID actorId) {
        if (terminalCode == null || terminalCode.isBlank()) {
            throw new IllegalArgumentException("TERMINAL_CODE_REQUIRED");
        }
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(actorId, "actorId");
        if (command.capability() == null) {
            throw new IllegalArgumentException("CAPABILITY_REQUIRED");
        }
        OnboardText.requireAuditText(command.reason(), "reason");
        OnboardText.requireAuditText(command.evidenceRef(), "evidenceRef");
        JtTerminal terminal = lockTerminalByCode(terminalCode);
        if (terminal.getStatus() == JtTerminal.Status.RETIRED) {
            throw conflict("TERMINAL_RETIRED");
        }

        OffsetDateTime verifiedAt = OffsetDateTime.now(clock);
        OnboardDeviceCapability fact = capabilityRepository
                .findCurrentByTerminalIdAndCapability(terminal.getId(), command.capability())
                .orElse(null);
        long oldVersion = -1;
        if (fact == null) {
            if (command.expectedVersion() != null) {
                throw conflict("STALE_CAPABILITY_VERSION");
            }
            fact = OnboardDeviceCapability.declare(
                    terminal.getId(), command.capability(), command.reason(), verifiedAt);
        } else {
            oldVersion = fact.getVersion();
            if (fact.getStatus() == CapabilityStatus.VERIFIED) {
                throw conflict("CAPABILITY_ALREADY_VERIFIED");
            }
            if (command.expectedVersion() == null
                    || fact.getVersion() != command.expectedVersion()) {
                throw conflict("STALE_CAPABILITY_VERSION");
            }
        }
        fact.verify(command.evidenceRef(), actorId, command.reason(), verifiedAt);
        fact = capabilityRepository.saveAndFlush(fact);

        auditLogRepository.save(AuditLog.record(
                "JT_TERMINAL", terminal.getId(), "DEVICE_CAPABILITY_VERIFIED",
                "USER", actorId.toString(), command.reason(),
                safeCapabilityMetadata(fact, oldVersion)));
        return new CapabilityVerificationView(
                safeDeviceAlias(terminal.getId()), fact.getCapability(),
                fact.getStatus(), fact.getVersion());
    }

    private Map<String, JtTerminal> lockConfigurationState(
            UUID onboardSystemId, List<DeviceConfiguration> devices) {
        Set<UUID> terminalIds = new HashSet<>();
        Map<String, UUID> currentAliasTerminalIds = new LinkedHashMap<>();
        Map<String, UUID> desiredTerminalIds = new LinkedHashMap<>();
        Map<String, String> desiredAliases = new LinkedHashMap<>();
        if (onboardSystemId != null) {
            membershipRepository.findActiveByOnboardSystemIdOrderByValidFromAsc(onboardSystemId)
                    .forEach(membership -> {
                        UUID terminalId = membership.getTerminalId();
                        terminalIds.add(terminalId);
                        String alias = safeDeviceAlias(terminalId);
                        if (currentAliasTerminalIds.putIfAbsent(alias, terminalId) != null) {
                            throw conflict("DUPLICATE_DEVICE_ALIAS");
                        }
                    });
        }
        for (DeviceConfiguration device : devices) {
            String selector = selectorKey(device);
            UUID terminalId;
            if (hasText(device.deviceAlias())) {
                terminalId = currentAliasTerminalIds.get(device.deviceAlias());
                if (terminalId == null) {
                    throw conflict("DEVICE_ALIAS_CHANGED");
                }
                desiredAliases.put(selector, device.deviceAlias());
            } else {
                terminalId = terminalRepository.findByTerminalCode(device.terminalCode())
                        .map(JtTerminal::getId)
                        .orElseThrow(() -> conflict("TERMINAL_NOT_FOUND"));
            }
            if (desiredTerminalIds.containsValue(terminalId)) {
                throw conflict(hasText(device.deviceAlias())
                        ? "DUPLICATE_DEVICE_ALIAS" : "DUPLICATE_TERMINAL_CODE");
            }
            desiredTerminalIds.put(selector, terminalId);
            terminalIds.add(terminalId);
        }
        lockTerminalState(terminalIds);

        Map<String, UUID> lockedAliases = new LinkedHashMap<>();
        if (onboardSystemId != null) {
            membershipRepository.findActiveByOnboardSystemIdOrderByValidFromAsc(onboardSystemId)
                    .forEach(membership -> {
                        String alias = safeDeviceAlias(membership.getTerminalId());
                        if (lockedAliases.putIfAbsent(alias, membership.getTerminalId()) != null) {
                            throw conflict("DUPLICATE_DEVICE_ALIAS");
                        }
                    });
        }
        Map<String, JtTerminal> lockedTerminals = new LinkedHashMap<>();
        desiredTerminalIds.forEach((selector, terminalId) -> {
            JtTerminal terminal = terminalRepository.findById(terminalId)
                    .orElseThrow(() -> conflict("TERMINAL_NOT_FOUND"));
            String alias = desiredAliases.get(selector);
            if (alias != null && !terminalId.equals(lockedAliases.get(alias))) {
                throw conflict("DEVICE_ALIAS_CHANGED");
            }
            if (alias == null && !selector.equals("terminalCode:" + terminal.getTerminalCode())) {
                throw conflict("TERMINAL_CODE_CHANGED");
            }
            lockedTerminals.put(selector, terminal);
        });
        requireUniqueDesiredAliases(desiredTerminalIds.values());
        return Map.copyOf(lockedTerminals);
    }

    private static void requireUniqueDesiredAliases(
            java.util.Collection<UUID> desiredTerminalIds) {
        Set<String> aliases = new HashSet<>();
        for (UUID terminalId : desiredTerminalIds) {
            if (!aliases.add(safeDeviceAlias(terminalId))) {
                throw conflict("DUPLICATE_DEVICE_ALIAS");
            }
        }
    }

    private static String selectorKey(DeviceConfiguration device) {
        if (device == null) {
            throw new IllegalArgumentException("DEVICE_SELECTOR_REQUIRED");
        }
        boolean hasTerminalCode = hasText(device.terminalCode());
        boolean hasDeviceAlias = hasText(device.deviceAlias());
        if (hasTerminalCode == hasDeviceAlias) {
            throw new IllegalArgumentException(hasTerminalCode
                    ? "DEVICE_SELECTOR_MIXED" : "DEVICE_SELECTOR_REQUIRED");
        }
        return hasDeviceAlias
                ? "deviceAlias:" + device.deviceAlias()
                : "terminalCode:" + device.terminalCode();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private JtTerminal lockTerminalByCode(String terminalCode) {
        JtTerminal located = terminalRepository.findByTerminalCode(terminalCode)
                .orElseThrow(() -> conflict("TERMINAL_NOT_FOUND"));
        lockTerminalState(List.of(located.getId()));
        JtTerminal locked = terminalRepository.findById(located.getId())
                .orElseThrow(() -> conflict("TERMINAL_NOT_FOUND"));
        if (!terminalCode.equals(locked.getTerminalCode())) {
            throw conflict("TERMINAL_CODE_CHANGED");
        }
        return locked;
    }

    private void lockTerminalState(java.util.Collection<UUID> requestedTerminalIds) {
        List<UUID> terminalIds = requestedTerminalIds.stream()
                .distinct()
                .sorted(Comparator.comparing(UUID::toString))
                .toList();
        for (UUID terminalId : terminalIds) {
            entityManager.createNativeQuery("""
                            select id from jt_terminals
                            where id = :terminalId
                            for update
                            """)
                    .setParameter("terminalId", terminalId)
                    .getSingleResult();
        }
        for (UUID terminalId : terminalIds) {
            entityManager.createNativeQuery("""
                            select id from onboard_device_memberships
                            where terminal_id = :terminalId
                              and status = 'ACTIVE' and valid_to is null
                            order by id
                            for update
                            """)
                    .setParameter("terminalId", terminalId)
                    .getResultList();
        }
        for (UUID terminalId : terminalIds) {
            entityManager.createNativeQuery("""
                            select id from onboard_device_capabilities
                            where terminal_id = :terminalId
                              and status in ('DECLARED', 'VERIFIED')
                            order by capability, id
                            for update
                            """)
                    .setParameter("terminalId", terminalId)
                    .getResultList();
        }
        for (UUID terminalId : terminalIds) {
            JtTerminal terminal = terminalRepository.findById(terminalId)
                    .orElseThrow(() -> conflict("TERMINAL_NOT_FOUND"));
            entityManager.refresh(terminal);
        }
    }

    private void requireTerminalVersion(UUID terminalId, long expectedVersion) {
        JtTerminal terminal = terminalRepository.findById(terminalId)
                .orElseThrow(() -> conflict("TERMINAL_NOT_FOUND"));
        if (terminal.getVersion() != expectedVersion) {
            throw conflict("terminal version conflict");
        }
    }

    private RuntimeException translateKnownOnboardConstraint(
            DataIntegrityViolationException exception) {
        String constraint = knownConstraintName(exception);
        if (constraint == null) {
            return exception;
        }
        return switch (constraint) {
            case "uq_onboard_systems_active_vehicle" ->
                    conflict("ACTIVE_ONBOARD_SYSTEM_CONFLICT");
            case "uq_onboard_device_memberships_active_terminal" ->
                    conflict("TERMINAL_ALREADY_ASSIGNED");
            case "uq_onboard_device_capabilities_active_terminal_capability" ->
                    conflict("CAPABILITY_VERIFICATION_CONFLICT");
            case "uq_onboard_device_protocol_profiles_active_terminal" ->
                    conflict("PROTOCOL_PROFILE_CONFLICT");
            case "uq_onboard_device_role_assignments_active_system_role" ->
                    conflict("EXCLUSIVE_ROLE_CONFLICT");
            default -> exception;
        };
    }

    private static String knownConstraintName(Throwable exception) {
        Set<String> known = Set.of(
                "uq_onboard_systems_active_vehicle",
                "uq_onboard_device_memberships_active_terminal",
                "uq_onboard_device_capabilities_active_terminal_capability",
                "uq_onboard_device_protocol_profiles_active_terminal",
                "uq_onboard_device_role_assignments_active_system_role");
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                String normalized = normalizeConstraintName(violation.getConstraintName());
                if (known.contains(normalized)) {
                    return normalized;
                }
            }
            if (cause instanceof SQLException sql
                    && "23505".equals(sql.getSQLState())
                    && sql.getMessage() != null) {
                for (String candidate : known) {
                    if (containsCompleteConstraintName(sql.getMessage(), candidate)) {
                        return candidate;
                    }
                }
            }
        }
        return null;
    }

    private static boolean containsCompleteConstraintName(
            String message, String constraintName) {
        Pattern completeName = Pattern.compile(
                "(?i)(?:^|[\".\\s])" + Pattern.quote(constraintName)
                        + "(?:\"|\\s|$)");
        return completeName.matcher(message).find();
    }

    private static String normalizeConstraintName(String constraintName) {
        if (constraintName == null) {
            return null;
        }
        String normalized = constraintName.replace("\"", "").toLowerCase(Locale.ROOT);
        return normalized.substring(normalized.lastIndexOf('.') + 1);
    }

    private List<OnboardSystemView> assembleViews(
            List<OnboardSystem> systems,
            Map<UUID, OnboardSystemRuntimeState> runtimeBySystem) {
        if (systems.isEmpty()) {
            return List.of();
        }
        List<UUID> systemIds = systems.stream().map(OnboardSystem::getId).toList();
        List<Object[]> membershipRows = entityManager.createQuery("""
                        select membership, terminal
                        from OnboardDeviceMembership membership
                        join JtTerminal terminal on terminal.id = membership.terminalId
                        where membership.onboardSystemId in :systemIds
                          and membership.status = :status and membership.validTo is null
                        order by membership.validFrom, membership.id
                        """, Object[].class)
                .setParameter("systemIds", systemIds)
                .setParameter("status", OnboardDeviceMembership.Status.ACTIVE)
                .getResultList();
        List<OnboardDeviceMembership> memberships = membershipRows.stream()
                .map(row -> (OnboardDeviceMembership) row[0])
                .toList();
        Map<UUID, JtTerminal> terminalById = membershipRows.stream()
                .collect(java.util.stream.Collectors.toMap(
                        row -> ((JtTerminal) row[1]).getId(),
                        row -> (JtTerminal) row[1],
                        (first, duplicate) -> first));
        List<OnboardDeviceRoleAssignment> roles = entityManager.createQuery("""
                        select assignment
                        from OnboardDeviceRoleAssignment assignment
                        where assignment.onboardSystemId in :systemIds
                          and assignment.status = :status and assignment.validTo is null
                        order by assignment.validFrom, assignment.id
                        """, OnboardDeviceRoleAssignment.class)
                .setParameter("systemIds", systemIds)
                .setParameter("status", OnboardDeviceRoleAssignment.Status.ACTIVE)
                .getResultList();
        List<UUID> terminalIds = memberships.stream()
                .map(OnboardDeviceMembership::getTerminalId)
                .distinct()
                .toList();
        List<OnboardDeviceCapability> capabilities = terminalIds.isEmpty()
                ? List.of()
                : entityManager.createQuery("""
                                select capability
                                from OnboardDeviceCapability capability
                                where capability.terminalId in :terminalIds
                                  and capability.status = :status
                                order by capability.createdAt, capability.id
                                """, OnboardDeviceCapability.class)
                        .setParameter("terminalIds", terminalIds)
                        .setParameter("status", CapabilityStatus.VERIFIED)
                        .getResultList();
        List<OnboardDeviceProtocolProfile> profiles = terminalIds.isEmpty()
                ? List.of()
                : entityManager.createQuery("""
                                select profile
                                from OnboardDeviceProtocolProfile profile
                                where profile.terminalId in :terminalIds
                                  and profile.status = :status and profile.validTo is null
                                order by profile.validFrom, profile.id
                                """, OnboardDeviceProtocolProfile.class)
                        .setParameter("terminalIds", terminalIds)
                        .setParameter("status", OnboardDeviceProtocolProfile.Status.ACTIVE)
                        .getResultList();

        Map<UUID, List<OnboardDeviceMembership>> membershipsBySystem = memberships.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        OnboardDeviceMembership::getOnboardSystemId));
        Map<UUID, List<OnboardDeviceRoleAssignment>> rolesByTerminal = roles.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        OnboardDeviceRoleAssignment::getTerminalId));
        Map<UUID, List<OnboardDeviceCapability>> capabilitiesByTerminal = capabilities.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        OnboardDeviceCapability::getTerminalId));
        Map<UUID, OnboardDeviceProtocolProfile> profileByTerminal = profiles.stream()
                .collect(java.util.stream.Collectors.toMap(
                        OnboardDeviceProtocolProfile::getTerminalId,
                        java.util.function.Function.identity()));

        return systems.stream().map(system -> {
            List<DeviceView> devices = membershipsBySystem
                    .getOrDefault(system.getId(), List.of()).stream()
                    .map(membership -> toDeviceView(
                            membership, rolesByTerminal, capabilitiesByTerminal,
                            profileByTerminal, terminalById))
                    .sorted(Comparator.comparing(DeviceView::deviceAlias))
                    .toList();
            Set<UUID> memberIds = membershipsBySystem
                    .getOrDefault(system.getId(), List.of()).stream()
                    .map(OnboardDeviceMembership::getTerminalId)
                    .collect(java.util.stream.Collectors.toSet());
            UUID activeLocationTerminalId = java.util.Optional.ofNullable(
                            runtimeBySystem.get(system.getId()))
                    .map(OnboardSystemRuntimeState::getActiveLocationTerminalId)
                    .orElse(null);
            String activeLocationDeviceAlias = memberIds.contains(activeLocationTerminalId)
                    ? safeDeviceAlias(activeLocationTerminalId) : null;
            UUID wanTerminalId = roles.stream()
                    .filter(role -> role.getOnboardSystemId().equals(system.getId()))
                    .filter(role -> role.getRole() == Role.WAN_UPLINK)
                    .map(OnboardDeviceRoleAssignment::getTerminalId)
                    .filter(memberIds::contains)
                    .findFirst().orElse(null);
            String wanDeviceAlias = wanTerminalId == null
                    ? null : safeDeviceAlias(wanTerminalId);
            return new OnboardSystemView(
                    system.getId(), system.getVehicleId(), system.getStatus(),
                    system.getOperatingMode(), system.getVersion(),
                    activeLocationDeviceAlias, wanDeviceAlias, devices);
        }).toList();
    }

    private DeviceView toDeviceView(
            OnboardDeviceMembership membership,
            Map<UUID, List<OnboardDeviceRoleAssignment>> rolesByTerminal,
            Map<UUID, List<OnboardDeviceCapability>> capabilitiesByTerminal,
            Map<UUID, OnboardDeviceProtocolProfile> profileByTerminal,
            Map<UUID, JtTerminal> terminalById) {
        UUID terminalId = membership.getTerminalId();
        JtTerminal terminal = Objects.requireNonNull(
                terminalById.get(terminalId), "active membership terminal");
        List<String> roles = rolesByTerminal.getOrDefault(terminalId, List.of()).stream()
                .map(OnboardDeviceRoleAssignment::getRole)
                .sorted(Comparator.comparingInt(Enum::ordinal))
                .map(Enum::name)
                .toList();
        List<String> capabilities = capabilitiesByTerminal
                .getOrDefault(terminalId, List.of()).stream()
                .map(OnboardDeviceCapability::getCapability)
                .sorted(Comparator.comparingInt(Enum::ordinal))
                .map(Enum::name)
                .toList();
        ProtocolProfiles profiles = java.util.Optional.ofNullable(profileByTerminal.get(terminalId))
                .map(OnboardSystemConfigurationService::toProfiles)
                .orElse(null);
        return new DeviceView(
                safeDeviceAlias(terminalId), membership.getNetworkMode(),
                roles, profiles, capabilities, terminal.getStatus(),
                terminal.getLastAuthenticatedAt() != null,
                terminal.getLastRegisteredAt(), terminal.getLastAuthenticatedAt(),
                terminal.getLastSeenAt());
    }

    private static ProtocolProfiles toProfiles(OnboardDeviceProtocolProfile profile) {
        return new ProtocolProfiles(
                profile.getTransportProfile().name(), profile.getBusinessProfile().name(),
                profile.getSafetyProfile().name(), profile.getMediaProfile().name(),
                profile.getActivePositionIntervalSeconds(),
                profile.getIdlePositionIntervalSeconds());
    }

    private String safeCapabilityMetadata(OnboardDeviceCapability fact, long oldVersion) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("capability", fact.getCapability().name());
        metadata.put("status", fact.getStatus().name());
        metadata.put("oldVersion", oldVersion);
        metadata.put("newVersion", fact.getVersion());
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to encode safe capability audit", exception);
        }
    }

    static String safeDeviceAlias(UUID terminalId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    terminalId.toString().getBytes(StandardCharsets.US_ASCII));
            return "device-" + HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private OnboardSystem activeSystem(UUID vehicleId) {
        Objects.requireNonNull(vehicleId, "vehicleId");
        return systemRepository.findActiveByVehicleId(vehicleId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "onboard system not found"));
    }

    private OnboardSystem lockedActiveSystem(UUID vehicleId) {
        OnboardSystem discovered = activeSystem(vehicleId);
        return lockAndRefreshActiveSystem(vehicleId, discovered);
    }

    private OnboardSystem lockedActiveSystemOrNull(UUID vehicleId) {
        Objects.requireNonNull(vehicleId, "vehicleId");
        return systemRepository.findActiveByVehicleId(vehicleId)
                .map(system -> lockAndRefreshActiveSystem(vehicleId, system))
                .orElse(null);
    }

    private OnboardSystem lockAndRefreshActiveSystem(
            UUID vehicleId, OnboardSystem discovered) {
        List<?> lockedRows = entityManager.createNativeQuery("""
                        select id from onboard_systems
                        where id = :onboardSystemId
                        for update
                        """)
                .setParameter("onboardSystemId", discovered.getId())
                .getResultList();
        if (lockedRows.size() != 1) {
            throw conflict("ONBOARD_SYSTEM_NOT_ACTIVE");
        }
        entityManager.refresh(discovered);
        if (discovered.getStatus() != OnboardSystem.Status.ACTIVE
                || !discovered.getVehicleId().equals(vehicleId)) {
            throw conflict("ONBOARD_SYSTEM_NOT_ACTIVE");
        }
        return discovered;
    }

    private EvaluatedConfiguration evaluate(
            OnboardSystem system,
            ConfigurationCommand command,
            Map<String, JtTerminal> lockedTerminals) {
        Objects.requireNonNull(command, "command");
        OnboardText.requireAuditText(command.reason(), "reason");
        if (system.getVersion() != command.expectedVersion()) {
            throw conflict("STALE_CONFIGURATION_VERSION");
        }
        if (command.operatingMode() == null) {
            throw new IllegalArgumentException("OPERATING_MODE_REQUIRED");
        }
        if (command.devices().isEmpty()) {
            throw conflict("DEVICE_REQUIRED");
        }

        Map<String, DesiredDevice> desiredByCode = resolveDesiredDevices(
                command.devices(), lockedTerminals);
        validateMembershipOwnership(system.getId(), desiredByCode.values());
        validateExclusiveRoles(desiredByCode.values());
        validatePrimaryAndBackup(desiredByCode.values());
        validateOperatingMode(command.operatingMode(), desiredByCode.values());
        validateRoleEvidence(desiredByCode.values());

        CurrentConfiguration current = loadCurrent(system.getId());
        List<String> changedFields = diff(
                system.getOperatingMode(), command.operatingMode(), current, desiredByCode);
        return new EvaluatedConfiguration(desiredByCode, current, changedFields);
    }

    private void validateMembershipOwnership(
            UUID onboardSystemId, java.util.Collection<DesiredDevice> devices) {
        for (DesiredDevice device : devices) {
            membershipRepository.findActiveByTerminalId(device.terminal().getId())
                    .filter(membership -> !membership.getOnboardSystemId().equals(onboardSystemId))
                    .ifPresent(membership -> {
                        throw conflict("TERMINAL_ALREADY_ASSIGNED");
                    });
        }
    }

    private Map<String, DesiredDevice> resolveDesiredDevices(
            List<DeviceConfiguration> devices,
            Map<String, JtTerminal> lockedTerminals) {
        Map<String, DesiredDevice> resolved = new LinkedHashMap<>();
        for (DeviceConfiguration device : devices) {
            String selector = selectorKey(device);
            if (resolved.containsKey(selector)) {
                throw conflict(hasText(device.deviceAlias())
                        ? "DUPLICATE_DEVICE_ALIAS" : "DUPLICATE_TERMINAL_CODE");
            }
            JtTerminal terminal = lockedTerminals.get(selector);
            if (terminal == null) {
                throw conflict(hasText(device.deviceAlias())
                        ? "DEVICE_ALIAS_CHANGED" : "TERMINAL_CODE_CHANGED");
            }
            if (terminal.getStatus() == JtTerminal.Status.RETIRED) {
                throw conflict("TERMINAL_RETIRED");
            }
            if (device.networkMode() == null) {
                throw new IllegalArgumentException("NETWORK_MODE_REQUIRED");
            }
            ResolvedProfiles profiles = resolveProfiles(device.protocolProfiles());
            Set<Capability> verifiedCapabilities = capabilityRepository
                    .findCurrentByTerminalIdOrderByCreatedAtAsc(terminal.getId()).stream()
                    .filter(fact -> fact.getStatus() == CapabilityStatus.VERIFIED)
                    .map(OnboardDeviceCapability::getCapability)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            Set<Role> roles = device.roles() == null
                    ? Set.of() : Set.copyOf(device.roles());
            resolved.put(selector, new DesiredDevice(
                    terminal, device.networkMode(), roles, profiles, verifiedCapabilities));
        }
        return Map.copyOf(resolved);
    }

    private static ResolvedProfiles resolveProfiles(ProtocolProfiles profiles) {
        if (profiles == null) {
            throw new IllegalArgumentException("PROTOCOL_PROFILES_REQUIRED");
        }
        if (profiles.activePositionIntervalSeconds() <= 0
                || profiles.idlePositionIntervalSeconds() <= 0) {
            throw new IllegalArgumentException("POSITION_INTERVAL_INVALID");
        }
        try {
            return new ResolvedProfiles(
                    TransportProfile.valueOf(profiles.transportProfile()),
                    BusinessProfile.valueOf(profiles.businessProfile()),
                    SafetyProfile.valueOf(profiles.safetyProfile()),
                    MediaProfile.valueOf(profiles.mediaProfile()),
                    profiles.activePositionIntervalSeconds(),
                    profiles.idlePositionIntervalSeconds());
        } catch (RuntimeException invalidProfile) {
            throw new IllegalArgumentException("PROTOCOL_PROFILE_INVALID");
        }
    }

    private static void validateExclusiveRoles(
            java.util.Collection<DesiredDevice> devices) {
        EnumMap<Role, Integer> counts = new EnumMap<>(Role.class);
        devices.forEach(device -> device.roles().forEach(
                role -> counts.merge(role, 1, Integer::sum)));
        for (Role role : Role.values()) {
            if (counts.getOrDefault(role, 0) > 1) {
                throw conflict("EXCLUSIVE_ROLE_CONFLICT:" + role.name());
            }
        }
    }

    private static void validatePrimaryAndBackup(
            java.util.Collection<DesiredDevice> devices) {
        for (DesiredDevice device : devices) {
            if (device.roles().contains(Role.LOCATION_PRIMARY)
                    && device.roles().contains(Role.LOCATION_BACKUP)) {
                throw conflict("PRIMARY_BACKUP_SAME_TERMINAL");
            }
        }
    }

    private static void validateOperatingMode(
            OperatingMode operatingMode,
            java.util.Collection<DesiredDevice> devices) {
        if (operatingMode != OperatingMode.DISPATCH_SERVICE) {
            return;
        }
        long dispatch = devices.stream()
                .filter(device -> device.roles().contains(Role.DISPATCH)).count();
        long primary = devices.stream()
                .filter(device -> device.roles().contains(Role.LOCATION_PRIMARY)).count();
        if (dispatch != 1) {
            throw conflict("DISPATCH_ROLE_REQUIRED");
        }
        if (primary != 1) {
            throw conflict("LOCATION_PRIMARY_REQUIRED");
        }
    }

    private static void validateRoleEvidence(
            java.util.Collection<DesiredDevice> devices) {
        for (DesiredDevice device : devices) {
            List<Role> orderedRoles = device.roles().stream()
                    .sorted(Comparator.comparingInt(Enum::ordinal))
                    .toList();
            for (Role role : orderedRoles) {
                if (role == Role.WAN_UPLINK) {
                    if (device.networkMode() != NetworkMode.DIRECT_CELLULAR) {
                        throw conflict("WAN_UPLINK_REQUIRES_DIRECT_CELLULAR");
                    }
                } else if (!supports(role, device.verifiedCapabilities())) {
                    throw conflict("ROLE_CAPABILITY_MISSING:" + role.name());
                }
            }
        }
    }

    private static boolean supports(Role role, Set<Capability> capabilities) {
        return switch (role) {
            case DISPATCH -> capabilities.contains(Capability.GBT28787_DISPATCH)
                    || capabilities.contains(Capability.VENDOR_DISPATCH);
            case LOCATION_PRIMARY, LOCATION_BACKUP ->
                    capabilities.contains(Capability.JT808_LOCATION);
            case ACTIVE_SAFETY -> capabilities.contains(Capability.ADAS)
                    || capabilities.contains(Capability.DMS);
            case VIDEO -> capabilities.contains(Capability.VIDEO);
            case WAN_UPLINK -> true;
        };
    }

    private CurrentConfiguration loadCurrent(UUID onboardSystemId) {
        Map<UUID, OnboardDeviceMembership> memberships = new HashMap<>();
        membershipRepository.findActiveByOnboardSystemIdOrderByValidFromAsc(onboardSystemId)
                .forEach(membership -> memberships.put(membership.getTerminalId(), membership));
        Map<UUID, OnboardDeviceProtocolProfile> profiles = new HashMap<>();
        memberships.keySet().forEach(terminalId -> profileRepository
                .findActiveByTerminalId(terminalId)
                .ifPresent(profile -> profiles.put(terminalId, profile)));
        Map<UUID, Set<Role>> roles = new HashMap<>();
        roleRepository.findActiveByOnboardSystemIdOrderByValidFromAsc(onboardSystemId)
                .forEach(assignment -> roles.computeIfAbsent(
                        assignment.getTerminalId(), ignored -> EnumSet.noneOf(Role.class))
                        .add(assignment.getRole()));
        return new CurrentConfiguration(memberships, profiles, roles);
    }

    private void reconcileMemberships(
            OnboardSystem system,
            EvaluatedConfiguration evaluated,
            UUID actorId,
            String reason,
            OffsetDateTime changedAt) {
        Map<UUID, DesiredDevice> desiredById = desiredById(evaluated.desiredByCode());
        evaluated.current().memberships().values().stream()
                .sorted(Comparator.comparing(membership -> membership.getTerminalId().toString()))
                .forEach(current -> {
                    DesiredDevice desired = desiredById.get(current.getTerminalId());
                    if (desired == null || current.getNetworkMode() != desired.networkMode()) {
                        current.remove(reason, actorId, changedAt);
                        membershipRepository.save(current);
                    }
                });
        membershipRepository.flush();
        evaluated.desiredByCode().values().stream()
                .sorted(Comparator.comparing(device -> device.terminal().getTerminalCode()))
                .forEach(desired -> {
                    OnboardDeviceMembership current = evaluated.current().memberships()
                            .get(desired.terminal().getId());
                    if (current == null || current.getNetworkMode() != desired.networkMode()) {
                        membershipRepository.save(OnboardDeviceMembership.join(
                                system.getId(), desired.terminal().getId(), desired.networkMode(),
                                reason, actorId, changedAt));
                    }
                });
    }

    private void reconcileProfiles(
            EvaluatedConfiguration evaluated,
            UUID actorId,
            String reason,
            OffsetDateTime changedAt) {
        Map<UUID, DesiredDevice> desiredById = desiredById(evaluated.desiredByCode());
        evaluated.current().profiles().values().stream()
                .sorted(Comparator.comparing(profile -> profile.getTerminalId().toString()))
                .forEach(current -> {
                    DesiredDevice desired = desiredById.get(current.getTerminalId());
                    if (desired == null || !profileMatches(current, desired.profiles())) {
                        current.supersede(reason, actorId, changedAt);
                        profileRepository.save(current);
                    }
                });
        profileRepository.flush();
        evaluated.desiredByCode().values().stream()
                .sorted(Comparator.comparing(device -> device.terminal().getTerminalCode()))
                .forEach(desired -> {
                    OnboardDeviceProtocolProfile current = evaluated.current().profiles()
                            .get(desired.terminal().getId());
                    if (current == null || !profileMatches(current, desired.profiles())) {
                        ResolvedProfiles profiles = desired.profiles();
                        profileRepository.save(OnboardDeviceProtocolProfile.activate(
                                desired.terminal().getId(),
                                profiles.transportProfile(), profiles.businessProfile(),
                                profiles.safetyProfile(), profiles.mediaProfile(),
                                profiles.activePositionIntervalSeconds(),
                                profiles.idlePositionIntervalSeconds(),
                                reason, actorId, changedAt));
                    }
                });
    }

    private void reconcileRoles(
            OnboardSystem system,
            EvaluatedConfiguration evaluated,
            UUID actorId,
            String reason,
            OffsetDateTime changedAt) {
        Map<UUID, DesiredDevice> desiredById = desiredById(evaluated.desiredByCode());
        Map<RoleKey, OnboardDeviceRoleAssignment> current = new HashMap<>();
        roleRepository.findActiveByOnboardSystemIdOrderByValidFromAsc(system.getId())
                .forEach(assignment -> current.put(
                        new RoleKey(assignment.getTerminalId(), assignment.getRole()), assignment));
        Set<RoleKey> desiredKeys = new HashSet<>();
        desiredById.forEach((terminalId, device) -> device.roles().forEach(
                role -> desiredKeys.add(new RoleKey(terminalId, role))));

        current.entrySet().stream()
                .filter(entry -> !desiredKeys.contains(entry.getKey()))
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    entry.getValue().revoke(reason, actorId, changedAt);
                    roleRepository.save(entry.getValue());
                });
        roleRepository.flush();
        desiredKeys.stream()
                .filter(key -> !current.containsKey(key))
                .sorted()
                .forEach(key -> roleRepository.save(OnboardDeviceRoleAssignment.assign(
                        system.getId(), key.terminalId(), key.role(), reason, actorId, changedAt)));
    }

    private String safeConfigurationMetadata(
            EvaluatedConfiguration evaluated, long oldVersion, long newVersion) {
        List<String> roleNames = evaluated.desiredByCode().values().stream()
                .flatMap(device -> device.roles().stream())
                .distinct()
                .sorted(Comparator.comparingInt(role -> role.ordinal()))
                .map(Enum::name)
                .toList();
        List<String> capabilityNames = evaluated.desiredByCode().values().stream()
                .flatMap(device -> device.verifiedCapabilities().stream())
                .distinct()
                .sorted(Comparator.comparingInt(capability -> capability.ordinal()))
                .map(Enum::name)
                .toList();
        int roleCount = evaluated.desiredByCode().values().stream()
                .mapToInt(device -> device.roles().size()).sum();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("changedFields", evaluated.changedFields());
        metadata.put("oldVersion", oldVersion);
        metadata.put("newVersion", newVersion);
        metadata.put("deviceCount", evaluated.desiredByCode().size());
        metadata.put("roleCount", roleCount);
        metadata.put("roleNames", roleNames);
        metadata.put("capabilityNames", capabilityNames);
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to encode safe onboard audit", exception);
        }
    }

    private static Map<UUID, DesiredDevice> desiredById(
            Map<String, DesiredDevice> desiredByCode) {
        Map<UUID, DesiredDevice> desiredById = new HashMap<>();
        desiredByCode.values().forEach(device ->
                desiredById.put(device.terminal().getId(), device));
        return desiredById;
    }

    private static List<String> diff(
            OperatingMode currentMode,
            OperatingMode desiredMode,
            CurrentConfiguration current,
            Map<String, DesiredDevice> desiredByCode) {
        Set<String> changes = new HashSet<>();
        if (currentMode != desiredMode) {
            changes.add("operatingMode");
        }

        Map<UUID, DesiredDevice> desiredById = new HashMap<>();
        desiredByCode.values().forEach(device ->
                desiredById.put(device.terminal().getId(), device));
        if (!current.memberships().keySet().equals(desiredById.keySet())
                || desiredById.entrySet().stream().anyMatch(entry -> {
                    OnboardDeviceMembership membership = current.memberships().get(entry.getKey());
                    return membership == null
                            || membership.getNetworkMode() != entry.getValue().networkMode();
                })) {
            changes.add("devices");
        }
        if (!current.profiles().keySet().equals(desiredById.keySet())
                || desiredById.entrySet().stream().anyMatch(entry ->
                        !profileMatches(current.profiles().get(entry.getKey()),
                                entry.getValue().profiles()))) {
            changes.add("protocolProfiles");
        }
        boolean nonWanRolesChanged = desiredById.entrySet().stream().anyMatch(entry ->
                !withoutWan(current.roles().getOrDefault(entry.getKey(), Set.of()))
                        .equals(withoutWan(entry.getValue().roles())))
                || current.roles().keySet().stream()
                        .filter(terminalId -> !desiredById.containsKey(terminalId))
                        .anyMatch(terminalId -> !withoutWan(current.roles().get(terminalId)).isEmpty());
        if (nonWanRolesChanged) {
            changes.add("roles");
        }
        UUID currentWan = terminalForRole(current.roles(), Role.WAN_UPLINK);
        UUID desiredWan = desiredById.entrySet().stream()
                .filter(entry -> entry.getValue().roles().contains(Role.WAN_UPLINK))
                .map(Map.Entry::getKey)
                .findFirst().orElse(null);
        if (!Objects.equals(currentWan, desiredWan)) {
            changes.add("wanUplink");
        }
        return CHANGED_FIELD_ORDER.stream().filter(changes::contains).toList();
    }

    private static boolean profileMatches(
            OnboardDeviceProtocolProfile current, ResolvedProfiles desired) {
        return current != null
                && current.getTransportProfile() == desired.transportProfile()
                && current.getBusinessProfile() == desired.businessProfile()
                && current.getSafetyProfile() == desired.safetyProfile()
                && current.getMediaProfile() == desired.mediaProfile()
                && current.getActivePositionIntervalSeconds()
                        == desired.activePositionIntervalSeconds()
                && current.getIdlePositionIntervalSeconds()
                        == desired.idlePositionIntervalSeconds();
    }

    private static Set<Role> withoutWan(Set<Role> roles) {
        EnumSet<Role> result = roles.isEmpty()
                ? EnumSet.noneOf(Role.class) : EnumSet.copyOf(roles);
        result.remove(Role.WAN_UPLINK);
        return Set.copyOf(result);
    }

    private static UUID terminalForRole(Map<UUID, Set<Role>> roles, Role role) {
        return roles.entrySet().stream()
                .filter(entry -> entry.getValue().contains(role))
                .map(Map.Entry::getKey)
                .findFirst().orElse(null);
    }

    private static OnboardConfigurationConflictException conflict(String code) {
        return new OnboardConfigurationConflictException(code);
    }

    private static String requireCommandReason(String reason) {
        String value = OnboardText.requireAuditText(reason, "reason");
        if (value.codePointCount(0, value.length()) > MAX_COMMAND_REASON_CODE_POINTS) {
            throw new IllegalArgumentException(
                    "reason must not exceed " + MAX_COMMAND_REASON_CODE_POINTS + " characters");
        }
        return value;
    }

    public record ConfigurationCommand(
            Long expectedVersion,
            OperatingMode operatingMode,
            List<DeviceConfiguration> devices,
            String reason) {
        public ConfigurationCommand {
            if (expectedVersion == null) {
                throw new IllegalArgumentException("expectedVersion must not be null");
            }
            devices = devices == null ? List.of() : List.copyOf(devices);
            reason = requireCommandReason(reason);
        }
    }

    public record DeviceConfiguration(
            String terminalCode,
            String deviceAlias,
            NetworkMode networkMode,
            Set<Role> roles,
            ProtocolProfiles protocolProfiles) {
        public DeviceConfiguration(
                String terminalCode,
                NetworkMode networkMode,
                Set<Role> roles,
                ProtocolProfiles protocolProfiles) {
            this(terminalCode, null, networkMode, roles, protocolProfiles);
        }

        public DeviceConfiguration {
            roles = roles == null ? Set.of() : Set.copyOf(roles);
        }
    }

    public record ProtocolProfiles(
            String transportProfile,
            String businessProfile,
            String safetyProfile,
            String mediaProfile,
            int activePositionIntervalSeconds,
            int idlePositionIntervalSeconds) {
    }

    public record ConfigurationPreview(
            UUID onboardSystemId,
            UUID vehicleId,
            long currentVersion,
            List<String> changedFields,
            List<String> warnings) {
        public ConfigurationPreview {
            changedFields = changedFields == null ? List.of() : List.copyOf(changedFields);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    public record CapabilityVerificationCommand(
            Capability capability,
            Long expectedVersion,
            String reason,
            String evidenceRef) {
        public CapabilityVerificationCommand {
            reason = requireCommandReason(reason);
            evidenceRef = OnboardText.requireAuditText(evidenceRef, "evidenceRef");
        }
    }

    public record CapabilityVerificationView(
            String deviceAlias,
            Capability capability,
            CapabilityStatus status,
            long version) {
    }

    public record OnboardLifecycleResult(UUID onboardSystemId, UUID vehicleId) {
    }

    public record OnboardReplacementResult(
            UUID onboardSystemId, UUID vehicleId, Set<Role> transferredRoles) {
        public OnboardReplacementResult {
            transferredRoles = transferredRoles == null || transferredRoles.isEmpty()
                    ? Set.of() : Set.copyOf(transferredRoles);
        }
    }

    public record OnboardSystemView(
            UUID onboardSystemId,
            UUID vehicleId,
            OnboardSystem.Status status,
            OperatingMode operatingMode,
            long version,
            String activeLocationDeviceAlias,
            String wanDeviceAlias,
            List<DeviceView> devices) {
        public OnboardSystemView {
            devices = devices == null ? List.of() : List.copyOf(devices);
        }
    }

    public record OnboardSystemPage(
            List<OnboardSystemView> items,
            int page,
            int size,
            long totalElements,
            long totalPages) {
        public OnboardSystemPage {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    public record OnboardSystemDetailSnapshot(
            OnboardSystemView system,
            OnboardReadiness readiness) {
        public OnboardSystemDetailSnapshot {
            Objects.requireNonNull(system, "system");
            Objects.requireNonNull(readiness, "readiness");
        }
    }

    public record DeviceView(
            String deviceAlias,
            NetworkMode networkMode,
            List<String> roles,
            ProtocolProfiles protocolProfiles,
            List<String> verifiedCapabilities,
            JtTerminal.Status terminalStatus,
            boolean authenticationPresent,
            OffsetDateTime lastRegisteredAt,
            OffsetDateTime lastAuthenticatedAt,
            OffsetDateTime lastSeenAt) {
        public DeviceView {
            roles = roles == null ? List.of() : List.copyOf(roles);
            verifiedCapabilities = verifiedCapabilities == null
                    ? List.of() : List.copyOf(verifiedCapabilities);
        }
    }

    private record DesiredDevice(
            JtTerminal terminal,
            NetworkMode networkMode,
            Set<Role> roles,
            ResolvedProfiles profiles,
            Set<Capability> verifiedCapabilities) {
    }

    private record ResolvedProfiles(
            TransportProfile transportProfile,
            BusinessProfile businessProfile,
            SafetyProfile safetyProfile,
            MediaProfile mediaProfile,
            int activePositionIntervalSeconds,
            int idlePositionIntervalSeconds) {
    }

    private record CurrentConfiguration(
            Map<UUID, OnboardDeviceMembership> memberships,
            Map<UUID, OnboardDeviceProtocolProfile> profiles,
            Map<UUID, Set<Role>> roles) {
    }

    private record EvaluatedConfiguration(
            Map<String, DesiredDevice> desiredByCode,
            CurrentConfiguration current,
            List<String> changedFields) {
    }

    private record RoleKey(UUID terminalId, Role role) implements Comparable<RoleKey> {
        @Override
        public int compareTo(RoleKey other) {
            int terminalOrder = terminalId.toString().compareTo(other.terminalId.toString());
            return terminalOrder != 0 ? terminalOrder
                    : Integer.compare(role.ordinal(), other.role.ordinal());
        }
    }
}
