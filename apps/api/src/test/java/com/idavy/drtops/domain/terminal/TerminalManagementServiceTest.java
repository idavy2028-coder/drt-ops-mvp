package com.idavy.drtops.domain.terminal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idavy.drtops.domain.audit.AuditLogRepository;
import com.idavy.drtops.domain.fleet.Vehicle;
import com.idavy.drtops.domain.fleet.VehicleRepository;
import com.idavy.drtops.integration.jtgateway.JtGatewayControlClient;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:terminal_management;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@Import(TerminalManagementServiceTest.ControlClientConfiguration.class)
class TerminalManagementServiceTest {

    private static final UUID ACTOR_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID VEHICLE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID SECOND_VEHICLE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String INITIAL_HASH = sha256(UUID.randomUUID().toString());
    private static final String ROTATED_HASH = sha256(UUID.randomUUID().toString());

    @Autowired
    TerminalManagementService service;

    @Autowired
    JtTerminalRepository terminalRepository;

    @Autowired
    JtTerminalVehicleBindingRepository bindingRepository;

    @Autowired
    AuditLogRepository auditLogRepository;

    @Autowired
    JtGatewayAuditEventRepository gatewayAuditRepository;

    @Autowired
    VehicleRepository vehicleRepository;

    @Autowired
    FakeControlClient controlClient;

    @BeforeEach
    void setUp() {
        gatewayAuditRepository.deleteAll();
        auditLogRepository.deleteAll();
        bindingRepository.deleteAll();
        terminalRepository.deleteAll();
        vehicleRepository.deleteAll();
        controlClient.available = true;
        controlClient.requests.clear();
        controlClient.committedStateObserved = false;
        vehicleRepository.save(Vehicle.create(
                VEHICLE_ID, "浙A10001", "Microbus", 8, "IDLE",
                "POINT(120.155 30.274)", "测试车队", true));
        vehicleRepository.save(Vehicle.create(
                SECOND_VEHICLE_ID, "浙A10002", "Microbus", 8, "IDLE",
                "POINT(120.155 30.274)", "测试车队", true));
    }

    @Test
    void requiresCompletedRegistrationAndActiveBindingBeforeActivation() {
        JtTerminal terminal = preset("T-001", "PHONE-001");
        long pendingVersion = terminal.getVersion();

        assertThat(terminal.getStatus()).isEqualTo(JtTerminal.Status.PENDING);
        assertThatThrownBy(() -> service.activate(
                "T-001", pendingVersion, "上线前激活", ACTOR_ID))
                .isInstanceOf(IllegalStateException.class);

        service.bind("T-001", VEHICLE_ID, terminal.getVersion(), "首配车辆", ACTOR_ID);
        terminal = terminalRepository.findByTerminalCode("T-001").orElseThrow();
        service.completeRegistration(terminal.getId(), 1, INITIAL_HASH, "gateway-a");
        terminal = terminalRepository.findByTerminalCode("T-001").orElseThrow();
        service.activate("T-001", terminal.getVersion(), "正式启用", ACTOR_ID);

        assertThat(terminalRepository.findByTerminalCode("T-001").orElseThrow().getStatus())
                .isEqualTo(JtTerminal.Status.ACTIVE);
        assertThat(auditActions()).containsExactly(
                "JT_TERMINAL_PRESET", "JT_TERMINAL_BOUND", "JT_TERMINAL_ACTIVATED");
    }

    @Test
    void rejectsStaleVersionWithoutChangingStateOrAppendingAudit() {
        JtTerminal terminal = registeredAndBound("T-002", "PHONE-002");
        long staleVersion = terminal.getVersion();
        service.activate("T-002", staleVersion, "正式启用", ACTOR_ID);

        assertThatThrownBy(() -> service.suspend("T-002", staleVersion, "停用", ACTOR_ID))
                .isInstanceOf(TerminalConflictException.class);
        assertThat(terminalRepository.findByTerminalCode("T-002").orElseThrow().getStatus())
                .isEqualTo(JtTerminal.Status.ACTIVE);
        assertThat(auditActions()).doesNotContain("JT_TERMINAL_SUSPENDED");
    }

    @Test
    void keepsSuspendedStateWhenForcedDisconnectCannotBeConfirmed() {
        JtTerminal terminal = activate("T-003", "PHONE-003");
        controlClient.available = false;

        TerminalManagementService.ActionResult result = service.suspend(
                "T-003", terminal.getVersion(), "安全停用", ACTOR_ID);

        assertThat(result.disconnectStatus()).isEqualTo("DISCONNECT_PENDING_CONFIRMATION");
        assertThat(terminalRepository.findByTerminalCode("T-003").orElseThrow().getStatus())
                .isEqualTo(JtTerminal.Status.SUSPENDED);
        assertThat(controlClient.requests).containsExactly(new DisconnectRequest(terminal.getId(), "TERMINAL_SUSPENDED"));
        assertThat(auditActions()).endsWith("JT_TERMINAL_SUSPENDED", "JT_TERMINAL_DISCONNECT_REQUESTED");
    }

    @Test
    void preservesBindingHistoryWhenReplacingTerminal() {
        JtTerminal oldTerminal = activate("T-004", "PHONE-004");
        JtTerminal replacement = preset("T-005", "PHONE-005");
        String oldHash = oldTerminal.getAuthTokenHash();
        int oldTokenVersion = oldTerminal.getAuthTokenVersion();
        String replacementHash = replacement.getAuthTokenHash();
        int replacementTokenVersion = replacement.getAuthTokenVersion();
        assertThat(service.verifyRegistration(
                "PHONE-005", "T-005", "MFG01", "MODEL-X", "浙A10001", "JT808_2019").approved())
                .isFalse();
        assertThat(service.verifyAuthentication(
                replacement.getId(), replacementTokenVersion, replacementHash, "gateway-a").approved())
                .isFalse();

        TerminalManagementService.ReplacementResult result = service.replace(
                "T-004", "T-005", oldTerminal.getVersion(), replacement.getVersion(), "设备换机", ACTOR_ID);

        List<JtTerminalVehicleBinding> history = bindingRepository.findByVehicleIdOrderByValidFromAsc(VEHICLE_ID);
        assertThat(result.terminal().getTerminalCode()).isEqualTo("T-005");
        assertThat(history).hasSize(2);
        assertThat(history.get(0).getStatus()).isEqualTo(JtTerminalVehicleBinding.Status.UNBOUND);
        assertThat(history.get(0).getValidTo()).isNotNull();
        assertThat(history.get(1).getStatus()).isEqualTo(JtTerminalVehicleBinding.Status.ACTIVE);
        assertThat(history.get(1).getTerminal().getTerminalCode()).isEqualTo("T-005");
        assertThat(terminalRepository.findByTerminalCode("T-004").orElseThrow().getStatus())
                .isEqualTo(JtTerminal.Status.RETIRED);
        JtTerminal retired = terminalRepository.findByTerminalCode("T-004").orElseThrow();
        JtTerminal pending = terminalRepository.findByTerminalCode("T-005").orElseThrow();
        assertThat(retired.getAuthTokenVersion()).isEqualTo(oldTokenVersion + 1);
        assertThat(retired.getAuthTokenHash()).isNotEqualTo(oldHash);
        assertThat(pending.getStatus()).isEqualTo(JtTerminal.Status.PENDING);
        assertThat(pending.getLastRegisteredAt()).isNull();
        assertThat(pending.getAuthTokenVersion()).isEqualTo(replacementTokenVersion + 1);
        assertThat(pending.getAuthTokenHash()).isNotEqualTo(replacementHash);
        assertThat(service.verifyAuthentication(
                pending.getId(), pending.getAuthTokenVersion(), pending.getAuthTokenHash(), "gateway-a").approved())
                .isFalse();
        assertThat(service.verifyRegistration(
                "PHONE-005", "T-005", "MFG01", "MODEL-X", "浙A10001", "JT808_2019").approved())
                .isTrue();
        assertThat(auditActions()).endsWith("JT_TERMINAL_REPLACED", "JT_TERMINAL_DISCONNECT_REQUESTED");
        String metadata = auditLogRepository.findAllByOrderByCreatedAtAsc().stream()
                .filter(audit -> "JT_TERMINAL_REPLACED".equals(audit.getAction()))
                .findFirst().orElseThrow().getMetadataJson();
        assertThat(metadata)
                .contains("T-004", "T-005", "浙A10001", String.valueOf(oldTokenVersion + 1),
                        String.valueOf(replacementTokenVersion + 1))
                .doesNotContain(oldTerminal.getId().toString(), replacement.getId().toString(),
                        oldHash, replacementHash, "PHONE-004", "PHONE-005");
        assertThat(gatewayAuditRepository.findAll()).singleElement().satisfies(event -> {
            assertThat(event.getEventType()).isEqualTo(JtGatewayAuditEvent.EventType.TERMINAL_REPLACED);
            assertThat(event.getResult()).isEqualTo(JtGatewayAuditEvent.Result.APPLIED);
            assertThat(event.getGatewayInstance()).isEqualTo("API_MANAGEMENT");
        });

        service.completeRegistration(pending.getId(), pending.getAuthTokenVersion(), ROTATED_HASH, "gateway-a");
        pending = terminalRepository.findByTerminalCode("T-005").orElseThrow();
        service.activate("T-005", pending.getVersion(), "完成换机上线", ACTOR_ID);
        assertThat(terminalRepository.findByTerminalCode("T-005").orElseThrow().getStatus())
                .isEqualTo(JtTerminal.Status.ACTIVE);
    }

    @Test
    void verifiesAuthenticationOnlyWhileAnActiveBindingExists() {
        JtTerminal terminal = activate("T-010", "PHONE-010");
        assertThat(service.verifyAuthentication(terminal.getId(), 1, INITIAL_HASH, "gateway-a").approved()).isTrue();

        JtTerminalVehicleBinding binding = bindingRepository
                .findByTerminalIdAndStatus(terminal.getId(), JtTerminalVehicleBinding.Status.ACTIVE)
                .orElseThrow();
        binding.unbind("解除绑定", ACTOR_ID);
        bindingRepository.saveAndFlush(binding);

        assertThat(service.verifyAuthentication(terminal.getId(), 1, INITIAL_HASH, "gateway-a").approved()).isFalse();
    }

    @Test
    void rejectsAReplacementThatHasAlreadyCompletedRegistration() {
        JtTerminal oldTerminal = activate("T-014", "PHONE-014");
        JtTerminal replacement = preset("T-015", "PHONE-015");
        service.bind("T-015", SECOND_VEHICLE_ID, replacement.getVersion(), "临时绑定", ACTOR_ID);
        replacement = terminalRepository.findByTerminalCode("T-015").orElseThrow();
        service.completeRegistration(replacement.getId(), replacement.getAuthTokenVersion(), ROTATED_HASH, "gateway-a");
        replacement = terminalRepository.findByTerminalCode("T-015").orElseThrow();
        JtTerminalVehicleBinding temporaryBinding = bindingRepository
                .findByTerminalIdAndStatus(replacement.getId(), JtTerminalVehicleBinding.Status.ACTIVE)
                .orElseThrow();
        temporaryBinding.unbind("结束临时绑定", ACTOR_ID);
        bindingRepository.saveAndFlush(temporaryBinding);
        long replacementVersion = replacement.getVersion();

        assertThatThrownBy(() -> service.replace(
                "T-014", "T-015", oldTerminal.getVersion(), replacementVersion, "设备换机", ACTOR_ID))
                .isInstanceOf(TerminalConflictException.class);
        assertThat(controlClient.requests).isEmpty();
    }

    @Test
    void commitsSafeStateAndAuditBeforeCallingGateway() {
        JtTerminal terminal = activate("T-011", "PHONE-011");

        service.suspend("T-011", terminal.getVersion(), "安全停用", ACTOR_ID);

        assertThat(controlClient.committedStateObserved).isTrue();
    }

    @Test
    void neverCallsGatewayWhenDatabaseCommitFails() {
        JtTerminal terminal = activate("T-012", "PHONE-012");
        JtTerminal replacement = preset("T-013", "PHONE-013");
        String invalidAuditReason = "R".repeat(301);

        assertThatThrownBy(() -> service.suspend(
                "T-012", terminal.getVersion(), invalidAuditReason, ACTOR_ID))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> service.retire(
                "T-012", terminal.getVersion(), invalidAuditReason, ACTOR_ID))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> service.disconnect(
                "T-012", terminal.getVersion(), invalidAuditReason, ACTOR_ID))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> service.replace(
                "T-012", "T-013", terminal.getVersion(), replacement.getVersion(), invalidAuditReason, ACTOR_ID))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> service.rotateAuthentication(
                "T-012", terminal.getVersion(), invalidAuditReason, ACTOR_ID))
                .isInstanceOf(RuntimeException.class);

        assertThat(controlClient.requests).isEmpty();
        assertThat(terminalRepository.findByTerminalCode("T-012").orElseThrow().getStatus())
                .isEqualTo(JtTerminal.Status.ACTIVE);
    }

    @Test
    void rotatesAuthenticationAndSupportsAllAllowedStateTransitions() {
        JtTerminal terminal = activate("T-006", "PHONE-006");
        String oldHash = terminal.getAuthTokenHash();
        service.rotateAuthentication("T-006", terminal.getVersion(), "例行轮换", ACTOR_ID);
        terminal = terminalRepository.findByTerminalCode("T-006").orElseThrow();
        assertThat(terminal.getAuthTokenVersion()).isEqualTo(2);
        assertThat(terminal.getAuthTokenHash()).isNotEqualTo(oldHash);
        assertThat(terminal.getStatus()).isEqualTo(JtTerminal.Status.SUSPENDED);
        assertThat(terminal.getLastRegisteredAt()).isNull();

        service.completeRegistration(terminal.getId(), 2, ROTATED_HASH, "gateway-a");
        terminal = terminalRepository.findByTerminalCode("T-006").orElseThrow();
        service.activate("T-006", terminal.getVersion(), "恢复运营", ACTOR_ID);
        terminal = terminalRepository.findByTerminalCode("T-006").orElseThrow();
        service.suspend("T-006", terminal.getVersion(), "暂停运营", ACTOR_ID);
        terminal = terminalRepository.findByTerminalCode("T-006").orElseThrow();
        service.activate("T-006", terminal.getVersion(), "再次恢复", ACTOR_ID);
        terminal = terminalRepository.findByTerminalCode("T-006").orElseThrow();
        service.retire("T-006", terminal.getVersion(), "永久退役", ACTOR_ID);
        terminal = terminalRepository.findByTerminalCode("T-006").orElseThrow();

        assertThat(terminal.getStatus()).isEqualTo(JtTerminal.Status.RETIRED);
        long retiredVersion = terminal.getVersion();
        assertThatThrownBy(() -> service.activate("T-006", retiredVersion, "非法恢复", ACTOR_ID))
                .isInstanceOf(IllegalStateException.class);
        assertThat(auditActions()).contains(
                "JT_TERMINAL_AUTH_ROTATED", "JT_TERMINAL_SUSPENDED",
                "JT_TERMINAL_ACTIVATED", "JT_TERMINAL_RETIRED");
    }

    @Test
    void limitsRegistrationVerificationToAnUnregisteredPendingTerminalWithActiveBinding() {
        JtTerminal terminal = preset("T-007", "PHONE-007");

        assertThat(service.verifyRegistration(
                "PHONE-007", "T-007", "MFG01", "MODEL-X", "浙A10001", "JT808_2019").approved())
                .isFalse();

        service.bind("T-007", VEHICLE_ID, terminal.getVersion(), "首配车辆", ACTOR_ID);
        assertThat(service.verifyRegistration(
                "PHONE-007", "T-007", "MFG01", "MODEL-X", "浙A10001", "JT808_2019").approved())
                .isTrue();

        terminal = terminalRepository.findByTerminalCode("T-007").orElseThrow();
        service.completeRegistration(terminal.getId(), terminal.getAuthTokenVersion(), INITIAL_HASH, "gateway-a");
        assertThat(service.verifyRegistration(
                "PHONE-007", "T-007", "MFG01", "MODEL-X", "浙A10001", "JT808_2019").approved())
                .isFalse();
    }

    @Test
    void rejectsRegistrationCompletionWithoutBindingOrWithAnyOtherTokenVersion() {
        JtTerminal terminal = preset("T-008", "PHONE-008");
        String unavailableHash = terminal.getAuthTokenHash();

        assertThatThrownBy(() -> service.completeRegistration(
                terminal.getId(), terminal.getAuthTokenVersion(), INITIAL_HASH, "gateway-a"))
                .isInstanceOf(TerminalConflictException.class);

        service.bind("T-008", VEHICLE_ID, terminal.getVersion(), "首配车辆", ACTOR_ID);
        JtTerminal bound = terminalRepository.findByTerminalCode("T-008").orElseThrow();
        assertThatThrownBy(() -> service.completeRegistration(bound.getId(), 0, INITIAL_HASH, "gateway-a"))
                .isInstanceOf(TerminalConflictException.class);
        assertThatThrownBy(() -> service.completeRegistration(bound.getId(), 2, INITIAL_HASH, "gateway-a"))
                .isInstanceOf(TerminalConflictException.class);
        assertThat(terminalRepository.findByTerminalCode("T-008").orElseThrow().getAuthTokenHash())
                .isEqualTo(unavailableHash);
    }

    @Test
    void rejectsRepeatedRegistrationCompletionAndEveryNonPendingStateOverwrite() {
        JtTerminal terminal = registeredAndBound("T-009", "PHONE-009");
        UUID terminalId = terminal.getId();
        int currentTokenVersion = terminal.getAuthTokenVersion();

        assertThatThrownBy(() -> service.completeRegistration(
                terminalId, currentTokenVersion, ROTATED_HASH, "gateway-a"))
                .isInstanceOf(TerminalConflictException.class);

        service.activate("T-009", terminal.getVersion(), "正式启用", ACTOR_ID);
        terminal = terminalRepository.findByTerminalCode("T-009").orElseThrow();
        assertRegistrationOverwriteRejected(terminal);

        service.suspend("T-009", terminal.getVersion(), "暂停运营", ACTOR_ID);
        terminal = terminalRepository.findByTerminalCode("T-009").orElseThrow();
        assertRegistrationOverwriteRejected(terminal);

        service.retire("T-009", terminal.getVersion(), "永久退役", ACTOR_ID);
        assertRegistrationOverwriteRejected(terminalRepository.findByTerminalCode("T-009").orElseThrow());
    }

    private JtTerminal preset(String code, String phone) {
        return service.preset(new TerminalManagementService.PresetCommand(
                phone, code, "MFG01", "MODEL-X", "JT808_2019", "GCJ02", ACTOR_ID, "设备预置"));
    }

    private JtTerminal registeredAndBound(String code, String phone) {
        JtTerminal terminal = preset(code, phone);
        service.bind(code, VEHICLE_ID, terminal.getVersion(), "首配车辆", ACTOR_ID);
        terminal = terminalRepository.findByTerminalCode(code).orElseThrow();
        service.completeRegistration(terminal.getId(), terminal.getAuthTokenVersion(), INITIAL_HASH, "gateway-a");
        return terminalRepository.findByTerminalCode(code).orElseThrow();
    }

    private void assertRegistrationOverwriteRejected(JtTerminal terminal) {
        assertThatThrownBy(() -> service.completeRegistration(
                terminal.getId(), terminal.getAuthTokenVersion(), ROTATED_HASH, "gateway-a"))
                .isInstanceOf(TerminalConflictException.class);
        assertThat(terminalRepository.findById(terminal.getId()).orElseThrow().getAuthTokenHash())
                .isEqualTo(INITIAL_HASH);
    }

    private JtTerminal activate(String code, String phone) {
        JtTerminal terminal = registeredAndBound(code, phone);
        service.activate(code, terminal.getVersion(), "正式启用", ACTOR_ID);
        return terminalRepository.findByTerminalCode(code).orElseThrow();
    }

    private List<String> auditActions() {
        return auditLogRepository.findAllByOrderByCreatedAtAsc().stream()
                .map(audit -> audit.getAction())
                .toList();
    }

    @TestConfiguration
    static class ControlClientConfiguration {
        @Bean
        @Primary
        FakeControlClient fakeControlClient(DataSource dataSource) {
            return new FakeControlClient(dataSource);
        }
    }

    static final class FakeControlClient implements JtGatewayControlClient {
        private final DataSource dataSource;
        boolean available = true;
        boolean committedStateObserved;
        final java.util.ArrayList<DisconnectRequest> requests = new java.util.ArrayList<>();

        FakeControlClient(DataSource dataSource) {
            this.dataSource = dataSource;
        }

        @Override
        public boolean disconnect(UUID terminalId, String reasonCode) {
            requests.add(new DisconnectRequest(terminalId, reasonCode));
            try (var connection = dataSource.getConnection();
                    var statement = connection.prepareStatement(
                            "select count(*) from audit_logs where entity_id = ? and action = 'JT_TERMINAL_DISCONNECT_REQUESTED'")) {
                statement.setObject(1, terminalId);
                try (var result = statement.executeQuery()) {
                    result.next();
                    committedStateObserved = result.getInt(1) == 1;
                }
            } catch (java.sql.SQLException exception) {
                throw new IllegalStateException(exception);
            }
            return available;
        }
    }

    record DisconnectRequest(UUID terminalId, String reasonCode) {
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
