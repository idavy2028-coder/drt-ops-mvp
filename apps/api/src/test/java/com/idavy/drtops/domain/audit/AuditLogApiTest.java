package com.idavy.drtops.domain.audit;

import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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

    @BeforeEach
    void setUp() {
        auditLogRepository.deleteAll();
    }

    @Test
    void listsAuditLogsByEntityId() throws Exception {
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
        auditLogRepository.save(AuditLog.record(
                "RIDE_ORDER",
                OTHER_ORDER_ID,
                "ORDER_CANCELLED",
                "SYSTEM",
                "order-exception",
                "passenger cancelled",
                "{}"));

        mockMvc.perform(get("/api/audit-logs").param("entityId", ORDER_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[*].entityId", hasItems(ORDER_ID.toString())))
                .andExpect(jsonPath("$.data[*].action", hasItems(
                        "DISPATCH_DECISION",
                        "MANUAL_REVIEW_APPROVED")));
    }
}
