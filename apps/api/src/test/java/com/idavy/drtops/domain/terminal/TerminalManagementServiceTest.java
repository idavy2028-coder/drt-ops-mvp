package com.idavy.drtops.domain.terminal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idavy.drtops.domain.audit.AuditLogRepository;
import com.idavy.drtops.domain.fleet.Vehicle;
import com.idavy.drtops.domain.fleet.VehicleRepository;
import com.idavy.drtops.integration.jtgateway.JtGatewayControlClient;
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
    VehicleRepository vehicleRepository;

    @Autowired
    FakeControlClient controlClient;

    @BeforeEach
    void setUp() {
        auditLogRepository.deleteAll();
        bindingRepository.deleteAll();
        terminalRepository.deleteAll();
        vehicleRepository.deleteAll();
        controlClient.available = true;
        controlClient.requests.clear();
        vehicleRepository.save(Vehicle.create(
                VEHICLE_ID, "浙A10001", "Microbus", 8, "IDLE",
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

        service.completeRegistration(terminal.getId(), 1, INITIAL_HASH, "gateway-a");
        terminal = terminalRepository.findByTerminalCode("T-001").orElseThrow();
        service.bind("T-001", VEHICLE_ID, terminal.getVersion(), "首配车辆", ACTOR_ID);
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
        service.completeRegistration(replacement.getId(), 1, ROTATED_HASH, "gateway-a");
        replacement = terminalRepository.findByTerminalCode("T-005").orElseThrow();

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
        assertThat(auditActions()).endsWith("JT_TERMINAL_REPLACED");
    }

    @Test
    void rotatesAuthenticationAndSupportsAllAllowedStateTransitions() {
        JtTerminal terminal = activate("T-006", "PHONE-006");
        service.rotateAuthentication("T-006", terminal.getVersion(), 2, ROTATED_HASH, "例行轮换", ACTOR_ID);
        terminal = terminalRepository.findByTerminalCode("T-006").orElseThrow();
        assertThat(terminal.getAuthTokenVersion()).isEqualTo(2);
        assertThat(terminal.getAuthTokenHash()).isEqualTo(ROTATED_HASH);

        service.suspend("T-006", terminal.getVersion(), "暂停运营", ACTOR_ID);
        terminal = terminalRepository.findByTerminalCode("T-006").orElseThrow();
        service.activate("T-006", terminal.getVersion(), "恢复运营", ACTOR_ID);
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

    private JtTerminal preset(String code, String phone) {
        return service.preset(new TerminalManagementService.PresetCommand(
                phone, code, "MFG01", "MODEL-X", "JT808_2019", "GCJ02", ACTOR_ID, "设备预置"));
    }

    private JtTerminal registeredAndBound(String code, String phone) {
        JtTerminal terminal = preset(code, phone);
        service.completeRegistration(terminal.getId(), 1, INITIAL_HASH, "gateway-a");
        terminal = terminalRepository.findByTerminalCode(code).orElseThrow();
        service.bind(code, VEHICLE_ID, terminal.getVersion(), "首配车辆", ACTOR_ID);
        return terminalRepository.findByTerminalCode(code).orElseThrow();
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
        FakeControlClient fakeControlClient() {
            return new FakeControlClient();
        }
    }

    static final class FakeControlClient implements JtGatewayControlClient {
        boolean available = true;
        final java.util.ArrayList<DisconnectRequest> requests = new java.util.ArrayList<>();

        @Override
        public boolean disconnect(UUID terminalId, String reasonCode) {
            requests.add(new DisconnectRequest(terminalId, reasonCode));
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
