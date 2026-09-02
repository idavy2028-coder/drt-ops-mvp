package com.idavy.drtops.domain.onboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.idavy.drtops.domain.onboard.OnboardReadinessService.OnboardReadiness;
import com.idavy.drtops.domain.onboard.OnboardReadinessService.ReadinessState;
import com.idavy.drtops.domain.fleet.VehicleRepository;
import com.idavy.drtops.domain.location.CanonicalPositionIngress;
import com.idavy.drtops.domain.location.GatewayIngressEnvelope;
import com.idavy.drtops.domain.location.GpsLocationIngressService;
import com.idavy.drtops.domain.location.ServiceAreaLocationChecker;
import com.idavy.drtops.domain.onboard.OnboardDeviceRoleAssignment.Role;
import com.idavy.drtops.domain.terminal.JtTerminalRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * Cross-checks the installation-shape promises exercised by the dual-device simulator against
 * the API's real readiness projection. All terminal codes are synthetic test data.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:composite_onboard_e2e;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@Import({OnboardTestFixtures.class, CompositeOnboardEndToEndTest.AlwaysInsideArea.class})
class CompositeOnboardEndToEndTest {

    @Autowired OnboardReadinessService readinessService;
    @Autowired OnboardTestFixtures fixtures;
    @Autowired GpsLocationIngressService locationIngress;
    @Autowired ObjectMapper objectMapper;
    @Autowired VehicleRepository vehicleRepository;
    @Autowired OnboardSystemRepository systemRepository;
    @Autowired OnboardDeviceRoleAssignmentRepository roleRepository;
    @Autowired OnboardDeviceMembershipRepository membershipRepository;
    @Autowired OnboardSystemConfigurationService configurationService;
    @Autowired JtTerminalRepository terminalRepository;

    @BeforeEach
    void setUp() {
        fixtures.clear();
    }

    @Test
    void recorderOnlySystemRemainsNonDispatchableWhileSafetyAndVideoAreInstalled() {
        // Mutation caught: granting DISPATCH merely because a recorder is connected and located.
        OnboardReadiness readiness = readinessService.evaluate(fixtures.recorderOnlyVehicleId());

        assertThat(readiness.dispatch()).isEqualTo(ReadinessState.NOT_INSTALLED);
        assertThat(readiness.dispatchEligible()).isFalse();
        assertThat(readiness.activeSafety()).isEqualTo(ReadinessState.READY);
        assertThat(readiness.video()).isEqualTo(ReadinessState.READY);
    }

    @Test
    void dispatchOnlySystemReportsSafetyAndVideoAsNotInstalled() {
        // Mutation caught: conflating an absent safety/video role with an unavailable installed device.
        OnboardReadiness readiness = readinessService.evaluate(fixtures.readyDispatchSystemVehicleId());

        assertThat(readiness.dispatch()).isEqualTo(ReadinessState.READY);
        assertThat(readiness.dispatchEligible()).isTrue();
        assertThat(readiness.activeSafety()).isEqualTo(ReadinessState.NOT_INSTALLED);
        assertThat(readiness.video()).isEqualTo(ReadinessState.NOT_INSTALLED);
    }

    @Test
    void realIngressKeepsFreshPrimaryThenFailsOverAtExactThresholdAndFailsBackOnThirdReport() throws Exception {
        // Mutation caught: allowing a fresh backup to overwrite, using a strict threshold, or failing back before report three.
        fixtures.configureDualDeviceSystem("dispatch-01", "recorder-01", "VEHICLE-A");
        UUID vehicleId = vehicleRepository.findByPlateNumber("VEHICLE-A").orElseThrow().getId();
        OnboardSystem system = systemRepository.findActiveByVehicleId(vehicleId).orElseThrow();
        UUID primary = terminalRepository.findByTerminalCode("dispatch-01").orElseThrow().getId();
        UUID backup = terminalRepository.findByTerminalCode("recorder-01").orElseThrow().getId();
        Instant base = Instant.parse("2026-09-01T00:00:00Z");

        ingest(position(primary, system.getId(), vehicleId, Role.LOCATION_PRIMARY, base, 1));
        ingest(position(backup, system.getId(), vehicleId, Role.LOCATION_BACKUP, base.plusSeconds(119), 2));
        assertThat(vehicleRepository.findById(vehicleId).orElseThrow().getCurrentLocationTerminalId())
                .isEqualTo(primary);

        ingest(position(backup, system.getId(), vehicleId, Role.LOCATION_BACKUP, base.plusSeconds(120), 3));
        assertThat(vehicleRepository.findById(vehicleId).orElseThrow().getCurrentLocationTerminalId())
                .isEqualTo(backup);

        ingest(position(primary, system.getId(), vehicleId, Role.LOCATION_PRIMARY, base.plusSeconds(121), 4));
        assertThat(vehicleRepository.findById(vehicleId).orElseThrow().getCurrentLocationTerminalId())
                .isEqualTo(backup);
        ingest(position(primary, system.getId(), vehicleId, Role.LOCATION_PRIMARY, base.plusSeconds(122), 5));
        assertThat(vehicleRepository.findById(vehicleId).orElseThrow().getCurrentLocationTerminalId())
                .isEqualTo(backup);
        ingest(position(primary, system.getId(), vehicleId, Role.LOCATION_PRIMARY, base.plusSeconds(123), 6));
        assertThat(vehicleRepository.findById(vehicleId).orElseThrow().getCurrentLocationTerminalId())
                .isEqualTo(primary);
    }

    @Test
    void wanUplinkMoveChangesOnlyNetworkFactsAndKeepsPhysicalTerminalIdentity() {
        // Mutation caught: treating a WAN-uplink move as a terminal replacement or role-identity migration.
        fixtures.configureDualDeviceSystem("dispatch-01", "recorder-01", "VEHICLE-A");
        UUID vehicleId = vehicleRepository.findByPlateNumber("VEHICLE-A").orElseThrow().getId();
        OnboardSystem before = systemRepository.findActiveByVehicleId(vehicleId).orElseThrow();
        UUID dispatchId = terminalRepository.findByTerminalCode("dispatch-01").orElseThrow().getId();
        UUID recorderId = terminalRepository.findByTerminalCode("recorder-01").orElseThrow().getId();

        configurationService.apply(vehicleId, new OnboardSystemConfigurationService.ConfigurationCommand(
                before.getVersion(), OnboardSystem.OperatingMode.DISPATCH_SERVICE,
                List.of(
                        device("dispatch-01", OnboardDeviceMembership.NetworkMode.SHARED_LAN_CLIENT,
                                java.util.Set.of(Role.DISPATCH, Role.LOCATION_PRIMARY),
                                profiles("JT808_2019", "GBT28787_2023", "NONE", "NONE")),
                        device("recorder-01", OnboardDeviceMembership.NetworkMode.DIRECT_CELLULAR,
                                java.util.Set.of(Role.LOCATION_BACKUP, Role.ACTIVE_SAFETY, Role.VIDEO, Role.WAN_UPLINK),
                                profiles("JT808_2019", "NONE", "JSATL12_2017", "JT1078_2016"))),
                "move synthetic WAN uplink without replacing terminals"), OnboardTestFixtures.ACTOR_ID);

        assertThat(terminalRepository.findByTerminalCode("dispatch-01").orElseThrow().getId()).isEqualTo(dispatchId);
        assertThat(terminalRepository.findByTerminalCode("recorder-01").orElseThrow().getId()).isEqualTo(recorderId);
        assertThat(membershipRepository.findActiveByOnboardSystemIdOrderByValidFromAsc(before.getId()))
                .extracting(OnboardDeviceMembership::getTerminalId)
                .containsExactlyInAnyOrder(dispatchId, recorderId);
        assertThat(membershipRepository.findActiveByOnboardSystemIdOrderByValidFromAsc(before.getId()))
                .filteredOn(member -> member.getTerminalId().equals(dispatchId))
                .extracting(OnboardDeviceMembership::getNetworkMode)
                .containsExactly(OnboardDeviceMembership.NetworkMode.SHARED_LAN_CLIENT);
        assertThat(membershipRepository.findActiveByOnboardSystemIdOrderByValidFromAsc(before.getId()))
                .filteredOn(member -> member.getTerminalId().equals(recorderId))
                .extracting(OnboardDeviceMembership::getNetworkMode)
                .containsExactly(OnboardDeviceMembership.NetworkMode.DIRECT_CELLULAR);
        assertThat(roleRepository.findActiveByOnboardSystemIdOrderByValidFromAsc(before.getId()))
                .filteredOn(role -> role.getTerminalId().equals(dispatchId))
                .extracting(OnboardDeviceRoleAssignment::getRole)
                .containsExactlyInAnyOrder(Role.DISPATCH, Role.LOCATION_PRIMARY);
        assertThat(roleRepository.findActiveByOnboardSystemIdOrderByValidFromAsc(before.getId()))
                .filteredOn(role -> role.getTerminalId().equals(recorderId))
                .extracting(OnboardDeviceRoleAssignment::getRole)
                .containsExactlyInAnyOrder(Role.LOCATION_BACKUP, Role.ACTIVE_SAFETY, Role.VIDEO, Role.WAN_UPLINK);
    }

    private void ingest(CanonicalPositionIngress position) throws Exception {
        GatewayIngressEnvelope envelope = new GatewayIngressEnvelope(
                1, UUID.randomUUID(), "POSITION", position.gatewayReceivedAt(),
                objectMapper.writeValueAsString(position));
        assertThat(locationIngress.ingest(List.of(envelope))).singleElement()
                .extracting(GpsLocationIngressService.Result::status).isEqualTo("ACCEPTED");
    }

    private static CanonicalPositionIngress position(
            UUID terminalId, UUID systemId, UUID vehicleId, Role role, Instant at, int serial) {
        return new CanonicalPositionIngress(terminalId, systemId, vehicleId, role.name(), "JT808_2019", serial,
                new BigDecimal("120.155"), new BigDecimal("30.274"), "WGS84", at, at,
                0L, 2L, new BigDecimal("20"), 90, 600, 8, "a".repeat(63) + serial);
    }

    private static OnboardSystemConfigurationService.DeviceConfiguration device(
            String code, OnboardDeviceMembership.NetworkMode networkMode, java.util.Set<Role> roles,
            OnboardSystemConfigurationService.ProtocolProfiles profiles) {
        return new OnboardSystemConfigurationService.DeviceConfiguration(code, networkMode, roles, profiles);
    }

    private static OnboardSystemConfigurationService.ProtocolProfiles profiles(
            String transport, String business, String safety, String media) {
        return new OnboardSystemConfigurationService.ProtocolProfiles(transport, business, safety, media, 30, 60);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class AlwaysInsideArea {
        @Bean @Primary ServiceAreaLocationChecker compositeIngressAreaChecker() {
            return (longitude, latitude) -> true;
        }
    }
}
