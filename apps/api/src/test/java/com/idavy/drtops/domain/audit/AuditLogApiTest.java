package com.idavy.drtops.domain.audit;

import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idavy.drtops.auth.JwtTokenService;
import com.idavy.drtops.auth.RoleCode;
import com.idavy.drtops.auth.UserAccount;
import com.idavy.drtops.auth.UserAccountRepository;
import java.util.UUID;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:audit_log_api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
class AuditLogApiTest {

    private static final UUID ORDER_ID = UUID.fromString("11111111-1111-1111-1111-111111111101");
    private static final UUID OTHER_ORDER_ID = UUID.fromString("11111111-1111-1111-1111-111111111102");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    AuditLogRepository auditLogRepository;

    @Autowired
    UserAccountRepository userAccountRepository;

    @Autowired
    JwtTokenService jwtTokenService;

    private String auditorToken;

    @BeforeEach
    void setUp() {
        auditLogRepository.deleteAll();
        userAccountRepository.deleteAll();
        UserAccount auditor = UserAccount.create("auditor01", "auditor01", "not-used-in-audit-test");
        auditor.assignRoles(Set.of(RoleCode.AUDITOR));
        auditorToken = jwtTokenService.issue(userAccountRepository.save(auditor)).value();
    }

    @Test
    void listsAuditLogsByEntityId() throws Exception {
        UserAccount dispatcher = UserAccount.create("dispatcher01", "dispatcher01", "not-used-in-audit-test");
        dispatcher.assignRoles(Set.of(RoleCode.DISPATCHER));
        dispatcher = userAccountRepository.save(dispatcher);
        auditLogRepository.save(AuditLog.record(
                "RIDE_ORDER",
                ORDER_ID,
                "DISPATCH_DECISION",
                "SYSTEM",
                "dispatch-orchestrator",
                "AUTO_DISPATCH",
                "{\"decision\":\"AUTO_DISPATCH\"}"));
        auditLogRepository.save(AuditLog.record(
                "RIDE_ORDER",
                ORDER_ID,
                "MANUAL_REVIEW_APPROVED",
                "SYSTEM",
                "manual-review",
                null,
                "{}"));
        auditLogRepository.save(AuditLog.record("RIDE_ORDER", ORDER_ID, "TASK_STARTED", "USER", dispatcher.getId().toString(), null, "{}"));
        auditLogRepository.save(AuditLog.record(
                "RIDE_ORDER",
                OTHER_ORDER_ID,
                "ORDER_CANCELLED",
                "SYSTEM",
                "order-exception",
                "passenger cancelled",
                "{}"));

        mockMvc.perform(get("/api/audit-logs")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + auditorToken)
                        .param("entityId", ORDER_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[*].entityId", hasItems(ORDER_ID.toString())))
                .andExpect(jsonPath("$.data[*].action", hasItems(
                        "DISPATCH_DECISION",
                        "MANUAL_REVIEW_APPROVED",
                        "TASK_STARTED")))
                .andExpect(jsonPath("$.data[2].actorDisplayName").value("dispatcher01"));
    }
}
