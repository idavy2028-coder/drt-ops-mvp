package com.idavy.drtops.domain.onboard;

import com.idavy.drtops.domain.audit.AuditLogRepository;
import com.idavy.drtops.domain.fleet.Vehicle;
import com.idavy.drtops.domain.fleet.VehicleRepository;
import com.idavy.drtops.domain.location.CanonicalPositionIngress;
import com.idavy.drtops.domain.location.CoordinateTransformer;
import com.idavy.drtops.domain.location.LocationQualityDecision;
import com.idavy.drtops.domain.location.LocationQualityStatus;
import com.idavy.drtops.domain.location.VehicleLocationEvent;
import com.idavy.drtops.domain.location.VehicleLocationEventRepository;
import com.idavy.drtops.domain.onboard.OnboardDeviceMembership.NetworkMode;
import com.idavy.drtops.domain.onboard.OnboardDeviceRoleAssignment.Role;
import com.idavy.drtops.domain.onboard.OnboardSystemConfigurationService.ConfigurationCommand;
import com.idavy.drtops.domain.onboard.OnboardSystemConfigurationService.DeviceConfiguration;
import com.idavy.drtops.domain.onboard.OnboardSystemConfigurationService.ProtocolProfiles;
import com.idavy.drtops.domain.terminal.JtTerminal;
import com.idavy.drtops.domain.terminal.JtTerminalRepository;
import com.idavy.drtops.domain.terminal.JtTerminalVehicleBindingRepository;
import java.time.OffsetDateTime;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.test.context.TestComponent;

@TestComponent
public class OnboardTestFixtures {

    static final UUID ACTOR_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private final OnboardSystemRepository systemRepository;
    private final OnboardSystemRuntimeStateRepository runtimeStateRepository;
    private final OnboardDeviceMembershipRepository membershipRepository;
    private final OnboardDeviceCapabilityRepository capabilityRepository;
    private final OnboardDeviceProtocolProfileRepository profileRepository;
    private final OnboardDeviceRoleAssignmentRepository roleRepository;
    private final JtTerminalRepository terminalRepository;
    private final JtTerminalVehicleBindingRepository bindingRepository;
    private final VehicleRepository vehicleRepository;
    private final VehicleLocationEventRepository locationEventRepository;
    private final AuditLogRepository auditLogRepository;
    private final OnboardSystemConfigurationService configurationService;

    public OnboardTestFixtures(
            OnboardSystemRepository systemRepository,
            OnboardSystemRuntimeStateRepository runtimeStateRepository,
            OnboardDeviceMembershipRepository membershipRepository,
            OnboardDeviceCapabilityRepository capabilityRepository,
            OnboardDeviceProtocolProfileRepository profileRepository,
            OnboardDeviceRoleAssignmentRepository roleRepository,
            JtTerminalRepository terminalRepository,
            JtTerminalVehicleBindingRepository bindingRepository,
            VehicleRepository vehicleRepository,
            VehicleLocationEventRepository locationEventRepository,
            AuditLogRepository auditLogRepository,
            OnboardSystemConfigurationService configurationService) {
        this.systemRepository = systemRepository;
        this.runtimeStateRepository = runtimeStateRepository;
        this.membershipRepository = membershipRepository;
        this.capabilityRepository = capabilityRepository;
        this.profileRepository = profileRepository;
        this.roleRepository = roleRepository;
        this.terminalRepository = terminalRepository;
        this.bindingRepository = bindingRepository;
        this.vehicleRepository = vehicleRepository;
        this.locationEventRepository = locationEventRepository;
        this.auditLogRepository = auditLogRepository;
        this.configurationService = configurationService;
    }

    public void clear() {
        roleRepository.deleteAll();
        profileRepository.deleteAll();
        capabilityRepository.deleteAll();
        membershipRepository.deleteAll();
        runtimeStateRepository.deleteAll();
        locationEventRepository.deleteAll();
        systemRepository.deleteAll();
        auditLogRepository.deleteAll();
        bindingRepository.deleteAll();
        terminalRepository.deleteAll();
        vehicleRepository.deleteAll();
    }

    public OnboardSystem activeSystem(OnboardSystem.OperatingMode operatingMode) {
        UUID vehicleId = UUID.randomUUID();
        String plate = "SYN-" + vehicleId.toString().substring(0, 8);
        createVehicle(vehicleId, plate, false);
        OnboardSystem system = systemRepository.saveAndFlush(OnboardSystem.create(
                vehicleId, operatingMode, ACTOR_ID, OffsetDateTime.now()));
        runtimeStateRepository.saveAndFlush(
                OnboardSystemRuntimeState.initialize(system.getId(), OffsetDateTime.now()));
        return system;
    }

    public void verifyDispatchAndLocation(String terminalCode) {
        verify(terminalCode, OnboardDeviceCapability.Capability.GBT28787_DISPATCH);
        verify(terminalCode, OnboardDeviceCapability.Capability.JT808_LOCATION);
    }

    public void verifySafetyVideoAndLocation(String terminalCode) {
        verify(terminalCode, OnboardDeviceCapability.Capability.JT808_LOCATION);
        verify(terminalCode, OnboardDeviceCapability.Capability.ADAS);
        verify(terminalCode, OnboardDeviceCapability.Capability.VIDEO);
    }

    public void configureDualDeviceSystem(
            String dispatchTerminalCode,
            String recorderTerminalCode,
            String vehicleIdentifier) {
        Vehicle vehicle = vehicleRepository.findByPlateNumber(vehicleIdentifier)
                .orElseGet(() -> createVehicle(UUID.randomUUID(), vehicleIdentifier, true));
        OnboardSystem system = activeSystem(vehicle, OnboardSystem.OperatingMode.DISPATCH_SERVICE);
        verifyDispatchAndLocation(dispatchTerminalCode);
        verifySafetyVideoAndLocation(recorderTerminalCode);
        configurationService.apply(vehicle.getId(), new ConfigurationCommand(
                system.getVersion(), OnboardSystem.OperatingMode.DISPATCH_SERVICE,
                List.of(
                        device(dispatchTerminalCode, NetworkMode.DIRECT_CELLULAR,
                                Set.of(Role.DISPATCH, Role.LOCATION_PRIMARY, Role.WAN_UPLINK),
                                profiles("JT808_2019", "GBT28787_2023", "NONE", "NONE")),
                        device(recorderTerminalCode, NetworkMode.SHARED_LAN_CLIENT,
                                Set.of(Role.LOCATION_BACKUP, Role.ACTIVE_SAFETY, Role.VIDEO),
                                profiles("JT808_2019", "NONE", "JSATL12_2017", "JT1078_2016"))),
                "configure synthetic dual-device system"), ACTOR_ID);
    }

    public void configureRecorderSystem(
            String recorderTerminalCode, String vehicleIdentifier) {
        Vehicle vehicle = vehicleRepository.findByPlateNumber(vehicleIdentifier)
                .orElseGet(() -> createVehicle(UUID.randomUUID(), vehicleIdentifier, false));
        OnboardSystem system = activeSystem(vehicle, OnboardSystem.OperatingMode.SAFETY_MONITOR_ONLY);
        verifySafetyVideoAndLocation(recorderTerminalCode);
        configurationService.apply(vehicle.getId(), new ConfigurationCommand(
                system.getVersion(), OnboardSystem.OperatingMode.SAFETY_MONITOR_ONLY,
                List.of(device(recorderTerminalCode, NetworkMode.DIRECT_CELLULAR,
                        Set.of(Role.LOCATION_PRIMARY, Role.ACTIVE_SAFETY, Role.VIDEO, Role.WAN_UPLINK),
                        profiles("JT808_2019", "NONE", "JSATL12_2017", "JT1078_2016"))),
                "configure synthetic recorder system"), ACTOR_ID);
    }

    public UUID recorderOnlyVehicleId() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String code = "recorder-ready-" + suffix;
        String plate = "REC-" + suffix;
        configureRecorderSystem(code, plate);
        UUID vehicleId = vehicleRepository.findByPlateNumber(plate).orElseThrow().getId();
        makeAuthenticated(code);
        makeLocationCurrent(vehicleId, code);
        return vehicleId;
    }

    public UUID dispatchSystemWithoutAuthenticationVehicleId() {
        return dispatchReadinessSystem("dispatch-no-auth", false, false);
    }

    public UUID dispatchSystemWithoutLocationVehicleId() {
        return dispatchReadinessSystem("dispatch-no-location", true, false);
    }

    public UUID readyDispatchSystemVehicleId() {
        return dispatchReadinessSystem("dispatch-ready", true, true);
    }

    public void declareCapability(
            String terminalCode, OnboardDeviceCapability.Capability capability) {
        JtTerminal terminal = terminal(terminalCode);
        capabilityRepository.saveAndFlush(OnboardDeviceCapability.declare(
                terminal.getId(), capability, "synthetic declaration", OffsetDateTime.now()));
    }

    public Vehicle createVehicle(String plateNumber) {
        return vehicleRepository.findByPlateNumber(plateNumber)
                .orElseGet(() -> createVehicle(UUID.randomUUID(), plateNumber, false));
    }

    public Vehicle createVehicle(UUID vehicleId, String plateNumber, boolean dispatchable) {
        return vehicleRepository.saveAndFlush(Vehicle.create(
                vehicleId, plateNumber, "Synthetic", 8, "IDLE",
                "POINT(120.155 30.274)", "Synthetic fleet", dispatchable));
    }

    private void verify(String terminalCode, OnboardDeviceCapability.Capability capability) {
        JtTerminal terminal = terminal(terminalCode);
        if (capabilityRepository.findCurrentByTerminalIdAndCapability(
                terminal.getId(), capability).isPresent()) {
            return;
        }
        OnboardDeviceCapability fact = OnboardDeviceCapability.declare(
                terminal.getId(), capability, "synthetic declaration", OffsetDateTime.now());
        fact.verify("synthetic-evidence", ACTOR_ID, "synthetic verification", OffsetDateTime.now());
        capabilityRepository.saveAndFlush(fact);
    }

    public JtTerminal terminal(String terminalCode) {
        return terminalRepository.findByTerminalCode(terminalCode)
                .orElseGet(() -> terminalRepository.saveAndFlush(JtTerminal.preset(
                        UUID.randomUUID(), phoneFor(terminalCode), terminalCode,
                        "SYNTH", "SYNTHETIC", "JT808_2019", "WGS84", ACTOR_ID)));
    }

    private OnboardSystem activeSystem(
            Vehicle vehicle, OnboardSystem.OperatingMode operatingMode) {
        return systemRepository.findActiveByVehicleId(vehicle.getId())
                .orElseGet(() -> {
                    OnboardSystem system = systemRepository.saveAndFlush(OnboardSystem.create(
                            vehicle.getId(), operatingMode, ACTOR_ID, OffsetDateTime.now()));
                    runtimeStateRepository.saveAndFlush(OnboardSystemRuntimeState.initialize(
                            system.getId(), OffsetDateTime.now()));
                    return system;
                });
    }

    private UUID dispatchReadinessSystem(
            String prefix, boolean authenticated, boolean located) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String code = prefix + "-" + suffix;
        Vehicle vehicle = createVehicle(UUID.randomUUID(), "DSP-" + suffix, true);
        OnboardSystem system = activeSystem(vehicle, OnboardSystem.OperatingMode.DISPATCH_SERVICE);
        verify(code, OnboardDeviceCapability.Capability.VENDOR_DISPATCH);
        verify(code, OnboardDeviceCapability.Capability.JT808_LOCATION);
        configurationService.apply(vehicle.getId(), new ConfigurationCommand(
                system.getVersion(), OnboardSystem.OperatingMode.DISPATCH_SERVICE,
                List.of(device(code, NetworkMode.DIRECT_CELLULAR,
                        Set.of(Role.DISPATCH, Role.LOCATION_PRIMARY, Role.WAN_UPLINK),
                        profiles("JT808_2019", "VENDOR_DISPATCH", "NONE", "NONE"))),
                "configure synthetic dispatch readiness state"), ACTOR_ID);
        if (authenticated) {
            makeAuthenticated(code);
        }
        if (located) {
            makeLocationCurrent(vehicle.getId(), code);
        }
        return vehicle.getId();
    }

    private void makeAuthenticated(String terminalCode) {
        JtTerminal terminal = terminal(terminalCode);
        if (terminal.getLastRegisteredAt() == null) {
            terminal.completeRegistration(1, "a".repeat(64));
        }
        if (terminal.getStatus() != JtTerminal.Status.ACTIVE) {
            terminal.activate(true);
        }
        terminal.recordSuccessfulAuthentication(OffsetDateTime.now());
        terminalRepository.saveAndFlush(terminal);
    }

    private void makeLocationCurrent(UUID vehicleId, String terminalCode) {
        JtTerminal terminal = terminal(terminalCode);
        OnboardSystem system = systemRepository.findActiveByVehicleId(vehicleId).orElseThrow();
        Role sourceRole = roleRepository.findActiveByTerminalIdOrderByValidFromAsc(terminal.getId()).stream()
                .filter(assignment -> assignment.getOnboardSystemId().equals(system.getId()))
                .map(OnboardDeviceRoleAssignment::getRole)
                .filter(role -> role == Role.LOCATION_PRIMARY || role == Role.LOCATION_BACKUP)
                .findFirst()
                .orElseThrow();
        Instant now = Instant.now();
        CanonicalPositionIngress ingress = new CanonicalPositionIngress(
                terminal.getId(), system.getId(), vehicleId, sourceRole.name(), "JT808_2019", 1,
                new BigDecimal("120.155"), new BigDecimal("30.274"), "GCJ02",
                now, now, 0L, 0L, BigDecimal.ZERO, 0, 0, 8, "b".repeat(64));
        VehicleLocationEvent event = VehicleLocationEvent.recordGps(
                vehicleId, terminal.getId(), ingress,
                new CoordinateTransformer.StandardizedCoordinate(
                        new BigDecimal("120.155"), new BigDecimal("30.274"),
                        "GCJ02", "SYNTHETIC_IDENTITY"),
                new LocationQualityDecision(
                        LocationQualityStatus.GOOD, Set.of(), true, true),
                UUID.randomUUID(), "c".repeat(64), OffsetDateTime.now(), now, false);
        event = locationEventRepository.saveAndFlush(event);
        Vehicle vehicle = vehicleRepository.findById(vehicleId).orElseThrow();
        vehicle.applyGpsLocationSnapshot(event);
        vehicleRepository.saveAndFlush(vehicle);
        OnboardSystemRuntimeState runtime = runtimeStateRepository.findById(system.getId()).orElseThrow();
        runtime.selectLocationSource(terminal.getId(), OffsetDateTime.now());
        runtimeStateRepository.saveAndFlush(runtime);
    }

    private static DeviceConfiguration device(
            String terminalCode,
            NetworkMode networkMode,
            Set<Role> roles,
            ProtocolProfiles profiles) {
        return new DeviceConfiguration(terminalCode, networkMode, roles, profiles);
    }

    private static ProtocolProfiles profiles(
            String transport, String business, String safety, String media) {
        return new ProtocolProfiles(transport, business, safety, media, 30, 60);
    }

    private static String phoneFor(String terminalCode) {
        if ("dispatch-01".equals(terminalCode)) {
            return "PHONE-DISPATCH";
        }
        if ("recorder-01".equals(terminalCode)) {
            return "PHONE-RECORDER";
        }
        return "PHONE-" + terminalCode;
    }
}
