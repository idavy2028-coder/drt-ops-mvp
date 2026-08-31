package com.idavy.drtops.domain.onboard;

import com.idavy.drtops.domain.fleet.Vehicle;
import com.idavy.drtops.domain.fleet.VehicleRepository;
import com.idavy.drtops.domain.location.LocationQualityStatus;
import com.idavy.drtops.domain.location.LocationSource;
import com.idavy.drtops.domain.onboard.OnboardDeviceCapability.Capability;
import com.idavy.drtops.domain.onboard.OnboardDeviceProtocolProfile.BusinessProfile;
import com.idavy.drtops.domain.onboard.OnboardDeviceProtocolProfile.MediaProfile;
import com.idavy.drtops.domain.onboard.OnboardDeviceProtocolProfile.SafetyProfile;
import com.idavy.drtops.domain.onboard.OnboardDeviceRoleAssignment.Role;
import com.idavy.drtops.domain.terminal.JtTerminal;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OnboardReadinessService {

    private static final Duration MINIMUM_STALE_AFTER = Duration.ofSeconds(30);

    private final OnboardSystemRepository systemRepository;
    private final OnboardSystemRuntimeStateRepository runtimeRepository;
    private final VehicleRepository vehicleRepository;
    private final EntityManager entityManager;
    private final Clock clock;

    public OnboardReadinessService(
            OnboardSystemRepository systemRepository,
            OnboardSystemRuntimeStateRepository runtimeRepository,
            VehicleRepository vehicleRepository,
            EntityManager entityManager,
            ObjectProvider<Clock> clocks) {
        this.systemRepository = systemRepository;
        this.runtimeRepository = runtimeRepository;
        this.vehicleRepository = vehicleRepository;
        this.entityManager = entityManager;
        this.clock = clocks.getIfAvailable(Clock::systemUTC);
    }

    @Transactional
    public OnboardReadiness evaluate(UUID vehicleId) {
        return evaluateWithinTransaction(vehicleId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OnboardReadiness evaluateIsolated(UUID vehicleId) {
        return evaluateWithinTransaction(vehicleId);
    }

    private OnboardReadiness evaluateWithinTransaction(UUID vehicleId) {
        if (vehicleId == null) {
            return offlineNotInstalled();
        }
        OnboardSystem discovered = systemRepository.findActiveByVehicleId(vehicleId).orElse(null);
        if (discovered == null) {
            return offlineNotInstalled();
        }
        OnboardSystem system = systemRepository.findLockedById(discovered.getId()).orElse(null);
        refresh(system);
        if (!activeSystemForVehicle(system, vehicleId)) {
            return offlineNotInstalled();
        }
        Vehicle vehicle = vehicleRepository.findById(vehicleId).orElse(null);
        if (vehicle == null) {
            return offlineNotInstalled();
        }
        entityManager.refresh(vehicle);

        List<MembershipFact> memberships = loadMemberships(system.getId());
        List<RoleFact> roles = loadRoles(system.getId());
        OnboardSystemRuntimeState runtime = runtimeRepository
                .findLockedByOnboardSystemId(system.getId()).orElse(null);
        refresh(runtime);
        Snapshot snapshot = loadSnapshot(system, memberships, roles, runtime, vehicle);

        ReadinessState connectivity = connectivity(snapshot);
        ReadinessState dispatch = system.getOperatingMode() == OnboardSystem.OperatingMode.SAFETY_MONITOR_ONLY
                ? ReadinessState.NOT_INSTALLED
                : dispatch(snapshot);
        ReadinessState location = location(snapshot);
        ReadinessState activeSafety = activeSafety(snapshot);
        ReadinessState video = video(snapshot);
        boolean dispatchEligible = vehicle.isDispatchable()
                && system.getOperatingMode() == OnboardSystem.OperatingMode.DISPATCH_SERVICE
                && dispatch == ReadinessState.READY
                && usableLocation(location);
        String overallStatus = overallStatus(
                system.getOperatingMode(), connectivity, dispatch, location, activeSafety, video);
        return new OnboardReadiness(
                connectivity,
                dispatch,
                location,
                activeSafety,
                video,
                dispatchEligible,
                overallStatus);
    }

    private Snapshot loadSnapshot(
            OnboardSystem system,
            List<MembershipFact> memberships,
            List<RoleFact> roles,
            OnboardSystemRuntimeState runtime,
            Vehicle vehicle) {
        Set<UUID> referencedTerminalIds = java.util.stream.Stream.concat(
                        memberships.stream().map(MembershipFact::terminalId),
                        roles.stream().map(RoleFact::terminalId))
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        Map<UUID, TerminalFact> terminals = loadTerminals(referencedTerminalIds);
        Map<UUID, Set<Capability>> verifiedCapabilities = loadCapabilities(referencedTerminalIds);
        Map<UUID, List<ProfileFact>> profiles = loadProfiles(referencedTerminalIds);
        return new Snapshot(
                system,
                memberships,
                roles,
                runtime,
                vehicle,
                Map.copyOf(terminals),
                Map.copyOf(verifiedCapabilities),
                Map.copyOf(profiles));
    }

    private List<MembershipFact> loadMemberships(UUID systemId) {
        return entityManager.createQuery("""
                        select membership.onboardSystemId, membership.terminalId,
                               membership.status, membership.validTo
                        from OnboardDeviceMembership membership
                        where membership.onboardSystemId = :systemId
                          and membership.status = :status and membership.validTo is null
                        order by membership.validFrom, membership.id
                        """, Object[].class)
                .setParameter("systemId", systemId)
                .setParameter("status", OnboardDeviceMembership.Status.ACTIVE)
                .getResultList().stream()
                .map(row -> new MembershipFact(
                        (UUID) row[0],
                        (UUID) row[1],
                        (OnboardDeviceMembership.Status) row[2],
                        (OffsetDateTime) row[3]))
                .toList();
    }

    private List<RoleFact> loadRoles(UUID systemId) {
        return entityManager.createQuery("""
                        select assignment.onboardSystemId, assignment.terminalId,
                               assignment.role, assignment.status, assignment.validTo
                        from OnboardDeviceRoleAssignment assignment
                        where assignment.onboardSystemId = :systemId
                          and assignment.status = :status and assignment.validTo is null
                        order by assignment.validFrom, assignment.id
                        """, Object[].class)
                .setParameter("systemId", systemId)
                .setParameter("status", OnboardDeviceRoleAssignment.Status.ACTIVE)
                .getResultList().stream()
                .map(row -> new RoleFact(
                        (UUID) row[0],
                        (UUID) row[1],
                        (Role) row[2],
                        (OnboardDeviceRoleAssignment.Status) row[3],
                        (OffsetDateTime) row[4]))
                .toList();
    }

    private Map<UUID, TerminalFact> loadTerminals(Set<UUID> terminalIds) {
        if (terminalIds.isEmpty()) {
            return Map.of();
        }
        return entityManager.createQuery("""
                        select terminal.id, terminal.status, terminal.lastAuthenticatedAt
                        from JtTerminal terminal
                        where terminal.id in :terminalIds
                        order by terminal.id
                        """, Object[].class)
                .setParameter("terminalIds", terminalIds)
                .getResultList().stream()
                .collect(Collectors.toMap(
                        row -> (UUID) row[0],
                        row -> new TerminalFact(
                                (JtTerminal.Status) row[1],
                                (OffsetDateTime) row[2]),
                        (first, ignored) -> first,
                        LinkedHashMap::new));
    }

    private Map<UUID, Set<Capability>> loadCapabilities(Set<UUID> terminalIds) {
        if (terminalIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Set<Capability>> capabilities = new LinkedHashMap<>();
        entityManager.createQuery("""
                        select capability.terminalId, capability.capability
                        from OnboardDeviceCapability capability
                        where capability.terminalId in :terminalIds
                          and capability.status = :status
                        order by capability.terminalId, capability.createdAt, capability.id
                        """, Object[].class)
                .setParameter("terminalIds", terminalIds)
                .setParameter("status", OnboardDeviceCapability.CapabilityStatus.VERIFIED)
                .getResultList()
                .forEach(row -> capabilities
                        .computeIfAbsent((UUID) row[0], ignored -> new java.util.LinkedHashSet<>())
                        .add((Capability) row[1]));
        Map<UUID, Set<Capability>> immutable = new LinkedHashMap<>();
        capabilities.forEach((terminalId, values) -> immutable.put(terminalId, Set.copyOf(values)));
        return Map.copyOf(immutable);
    }

    private Map<UUID, List<ProfileFact>> loadProfiles(Set<UUID> terminalIds) {
        if (terminalIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<ProfileFact>> profiles = new LinkedHashMap<>();
        entityManager.createQuery("""
                        select profile.terminalId, profile.businessProfile,
                               profile.safetyProfile, profile.mediaProfile,
                               profile.activePositionIntervalSeconds,
                               profile.idlePositionIntervalSeconds
                        from OnboardDeviceProtocolProfile profile
                        where profile.terminalId in :terminalIds
                          and profile.status = :status and profile.validTo is null
                        order by profile.terminalId, profile.validFrom, profile.id
                        """, Object[].class)
                .setParameter("terminalIds", terminalIds)
                .setParameter("status", OnboardDeviceProtocolProfile.Status.ACTIVE)
                .getResultList()
                .forEach(row -> profiles
                        .computeIfAbsent((UUID) row[0], ignored -> new ArrayList<>())
                        .add(new ProfileFact(
                                (BusinessProfile) row[1],
                                (SafetyProfile) row[2],
                                (MediaProfile) row[3],
                                (Integer) row[4],
                                (Integer) row[5])));
        Map<UUID, List<ProfileFact>> immutable = new LinkedHashMap<>();
        profiles.forEach((terminalId, values) -> immutable.put(terminalId, List.copyOf(values)));
        return Map.copyOf(immutable);
    }

    private ReadinessState connectivity(Snapshot snapshot) {
        if (snapshot.memberships().isEmpty()) {
            return ReadinessState.NOT_INSTALLED;
        }
        Set<UUID> memberIds = snapshot.memberIds();
        if (memberIds.size() != snapshot.memberships().size()) {
            return ReadinessState.UNAVAILABLE;
        }
        long operational = memberIds.stream().filter(snapshot::terminalAuthenticated).count();
        if (operational == 0) {
            return ReadinessState.UNAVAILABLE;
        }
        return operational == memberIds.size()
                ? ReadinessState.READY
                : ReadinessState.DEGRADED;
    }

    private ReadinessState dispatch(Snapshot snapshot) {
        List<RoleFact> assignments = snapshot.roles(Role.DISPATCH);
        if (assignments.isEmpty()) {
            return ReadinessState.NOT_INSTALLED;
        }
        if (assignments.size() != 1) {
            return ReadinessState.UNAVAILABLE;
        }
        RoleFact assignment = assignments.getFirst();
        if (!snapshot.operationalRole(assignment)) {
            return ReadinessState.UNAVAILABLE;
        }
        UUID terminalId = assignment.terminalId();
        ProfileFact profile = snapshot.profile(terminalId);
        if (profile == null) {
            return ReadinessState.UNAVAILABLE;
        }
        if (profile.businessProfile() == BusinessProfile.VENDOR_DISPATCH
                && snapshot.hasCapability(terminalId, Capability.VENDOR_DISPATCH)) {
            return ReadinessState.READY;
        }
        // Full GB/T 28787 bus-business registration is intentionally absent in this phase.
        return ReadinessState.UNAVAILABLE;
    }

    private ReadinessState location(Snapshot snapshot) {
        List<RoleFact> primary = snapshot.roles(Role.LOCATION_PRIMARY);
        if (primary.isEmpty()) {
            return ReadinessState.NOT_INSTALLED;
        }
        List<RoleFact> backup = snapshot.roles(Role.LOCATION_BACKUP);
        if (primary.size() != 1 || backup.size() > 1) {
            return ReadinessState.UNAVAILABLE;
        }
        UUID primaryId = primary.getFirst().terminalId();
        UUID backupId = backup.isEmpty() ? null : backup.getFirst().terminalId();
        if (primaryId.equals(backupId) || snapshot.runtime() == null) {
            return ReadinessState.UNAVAILABLE;
        }
        UUID activeId = snapshot.runtime().getActiveLocationTerminalId();
        if (activeId == null || !activeId.equals(snapshot.vehicle().getCurrentLocationTerminalId())) {
            return ReadinessState.UNAVAILABLE;
        }
        RoleFact activeAssignment;
        if (activeId.equals(primaryId)) {
            activeAssignment = primary.getFirst();
            if (!snapshot.runtime().isPrimaryEligible()) {
                return ReadinessState.UNAVAILABLE;
            }
        } else if (backupId != null && activeId.equals(backupId)) {
            activeAssignment = backup.getFirst();
        } else {
            return ReadinessState.UNAVAILABLE;
        }
        if (!snapshot.operationalRole(activeAssignment)
                || !snapshot.hasCapability(activeId, Capability.JT808_LOCATION)) {
            return ReadinessState.UNAVAILABLE;
        }
        ProfileFact profile = snapshot.profile(activeId);
        if (profile == null
                || snapshot.vehicle().getCurrentLocationSource() != LocationSource.GPS_DEVICE
                || !eligibleQuality(snapshot.vehicle().getCurrentLocationQualityStatus())
                || snapshot.vehicle().isCurrentLocationStale()
                || !fresh(snapshot.vehicle(), profile)) {
            return ReadinessState.UNAVAILABLE;
        }
        return activeId.equals(primaryId)
                ? ReadinessState.READY
                : ReadinessState.DEGRADED;
    }

    private ReadinessState activeSafety(Snapshot snapshot) {
        return capabilityDimension(
                snapshot,
                Role.ACTIVE_SAFETY,
                terminalId -> snapshot.hasCapability(terminalId, Capability.ADAS)
                        || snapshot.hasCapability(terminalId, Capability.DMS),
                profile -> profile.safetyProfile() != SafetyProfile.NONE);
    }

    private ReadinessState video(Snapshot snapshot) {
        return capabilityDimension(
                snapshot,
                Role.VIDEO,
                terminalId -> snapshot.hasCapability(terminalId, Capability.VIDEO),
                profile -> profile.mediaProfile() != MediaProfile.NONE);
    }

    private ReadinessState capabilityDimension(
            Snapshot snapshot,
            Role role,
            Predicate<UUID> capabilityReady,
            Predicate<ProfileFact> profileReady) {
        List<RoleFact> assignments = snapshot.roles(role);
        if (assignments.isEmpty()) {
            return ReadinessState.NOT_INSTALLED;
        }
        if (assignments.size() != 1) {
            return ReadinessState.UNAVAILABLE;
        }
        RoleFact assignment = assignments.getFirst();
        ProfileFact profile = snapshot.profile(assignment.terminalId());
        return snapshot.operationalRole(assignment)
                && capabilityReady.test(assignment.terminalId())
                && profile != null
                && profileReady.test(profile)
                ? ReadinessState.READY
                : ReadinessState.UNAVAILABLE;
    }

    private boolean fresh(Vehicle vehicle, ProfileFact profile) {
        if (vehicle.getCurrentLocationGatewayReceivedAt() == null) {
            return false;
        }
        int expectedSeconds = "IDLE".equals(vehicle.getCurrentStatus())
                ? profile.idlePositionIntervalSeconds()
                : profile.activePositionIntervalSeconds();
        if (expectedSeconds <= 0) {
            return false;
        }
        try {
            Duration doubled = Duration.ofSeconds(expectedSeconds).multipliedBy(2);
            Duration staleAfter = doubled.compareTo(MINIMUM_STALE_AFTER) < 0
                    ? MINIMUM_STALE_AFTER
                    : doubled;
            Instant staleAt = vehicle.getCurrentLocationGatewayReceivedAt().toInstant().plus(staleAfter);
            return clock.instant().isBefore(staleAt);
        } catch (ArithmeticException | DateTimeException invalidInterval) {
            return false;
        }
    }

    private static boolean eligibleQuality(LocationQualityStatus quality) {
        return quality == LocationQualityStatus.GOOD || quality == LocationQualityStatus.WARNING;
    }

    private static boolean activeSystemForVehicle(OnboardSystem system, UUID vehicleId) {
        return system != null
                && system.getStatus() == OnboardSystem.Status.ACTIVE
                && vehicleId.equals(system.getVehicleId());
    }

    private void refresh(Object entity) {
        if (entity != null) {
            entityManager.refresh(entity);
        }
    }

    private static boolean usableLocation(ReadinessState location) {
        return location == ReadinessState.READY || location == ReadinessState.DEGRADED;
    }

    private static String overallStatus(
            OnboardSystem.OperatingMode mode,
            ReadinessState connectivity,
            ReadinessState dispatch,
            ReadinessState location,
            ReadinessState activeSafety,
            ReadinessState video) {
        if (connectivity == ReadinessState.NOT_INSTALLED
                || connectivity == ReadinessState.UNAVAILABLE) {
            return "OFFLINE";
        }
        boolean requiredUsable = mode == OnboardSystem.OperatingMode.DISPATCH_SERVICE
                ? dispatch == ReadinessState.READY && usableLocation(location)
                : usableLocation(location);
        boolean unavailableInstalledDimension = java.util.stream.Stream.of(
                        dispatch, location, activeSafety, video)
                .anyMatch(state -> state == ReadinessState.UNAVAILABLE);
        boolean degradedDimension = connectivity == ReadinessState.DEGRADED
                || java.util.stream.Stream.of(dispatch, location, activeSafety, video)
                        .anyMatch(state -> state == ReadinessState.DEGRADED);
        return requiredUsable && !unavailableInstalledDimension && !degradedDimension
                ? "OPERATIONAL"
                : "DEGRADED";
    }

    private static OnboardReadiness offlineNotInstalled() {
        return new OnboardReadiness(
                ReadinessState.NOT_INSTALLED,
                ReadinessState.NOT_INSTALLED,
                ReadinessState.NOT_INSTALLED,
                ReadinessState.NOT_INSTALLED,
                ReadinessState.NOT_INSTALLED,
                false,
                "OFFLINE");
    }

    public enum ReadinessState {
        READY,
        DEGRADED,
        UNAVAILABLE,
        NOT_INSTALLED
    }

    public record OnboardReadiness(
            ReadinessState connectivity,
            ReadinessState dispatch,
            ReadinessState location,
            ReadinessState activeSafety,
            ReadinessState video,
            boolean dispatchEligible,
            String overallStatus) {

        public OnboardReadiness {
            Objects.requireNonNull(connectivity, "connectivity");
            Objects.requireNonNull(dispatch, "dispatch");
            Objects.requireNonNull(location, "location");
            Objects.requireNonNull(activeSafety, "activeSafety");
            Objects.requireNonNull(video, "video");
            if (!Set.of("OPERATIONAL", "DEGRADED", "OFFLINE").contains(overallStatus)) {
                throw new IllegalArgumentException("overallStatus is invalid");
            }
        }
    }

    private record Snapshot(
            OnboardSystem system,
            List<MembershipFact> memberships,
            List<RoleFact> roleAssignments,
            OnboardSystemRuntimeState runtime,
            Vehicle vehicle,
            Map<UUID, TerminalFact> terminals,
            Map<UUID, Set<Capability>> verifiedCapabilities,
            Map<UUID, List<ProfileFact>> profiles) {

        Snapshot {
            memberships = List.copyOf(memberships);
            roleAssignments = List.copyOf(roleAssignments);
        }

        Set<UUID> memberIds() {
            return memberships.stream()
                    .map(MembershipFact::terminalId)
                    .collect(Collectors.toUnmodifiableSet());
        }

        List<RoleFact> roles(Role role) {
            return roleAssignments.stream()
                    .filter(assignment -> assignment.role() == role)
                    .toList();
        }

        boolean terminalAuthenticated(UUID terminalId) {
            TerminalFact terminal = terminals.get(terminalId);
            return terminal != null
                    && terminal.status() == JtTerminal.Status.ACTIVE
                    && terminal.lastAuthenticatedAt() != null;
        }

        boolean operationalRole(RoleFact assignment) {
            return assignment.status() == OnboardDeviceRoleAssignment.Status.ACTIVE
                    && assignment.validTo() == null
                    && system.getId().equals(assignment.onboardSystemId())
                    && memberIds().contains(assignment.terminalId())
                    && terminalAuthenticated(assignment.terminalId());
        }

        boolean hasCapability(UUID terminalId, Capability capability) {
            return verifiedCapabilities.getOrDefault(terminalId, Set.of()).contains(capability);
        }

        ProfileFact profile(UUID terminalId) {
            List<ProfileFact> current = profiles.getOrDefault(terminalId, List.of());
            return current.size() == 1 ? current.getFirst() : null;
        }
    }

    private record MembershipFact(
            UUID onboardSystemId,
            UUID terminalId,
            OnboardDeviceMembership.Status status,
            OffsetDateTime validTo) {
    }

    private record RoleFact(
            UUID onboardSystemId,
            UUID terminalId,
            Role role,
            OnboardDeviceRoleAssignment.Status status,
            OffsetDateTime validTo) {
    }

    private record TerminalFact(
            JtTerminal.Status status,
            OffsetDateTime lastAuthenticatedAt) {
    }

    private record ProfileFact(
            BusinessProfile businessProfile,
            SafetyProfile safetyProfile,
            MediaProfile mediaProfile,
            int activePositionIntervalSeconds,
            int idlePositionIntervalSeconds) {
    }
}
