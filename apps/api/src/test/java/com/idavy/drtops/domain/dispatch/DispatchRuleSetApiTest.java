package com.idavy.drtops.domain.dispatch;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:rule_set_api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
class DispatchRuleSetApiTest {

    private static final UUID DEMO_RULE_SET_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    DispatchRuleSetRepository repository;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        repository.save(DispatchRuleSet.defaultRules(DEMO_RULE_SET_ID));
    }

    @Test
    void listsDispatchRuleSets() throws Exception {
        mockMvc.perform(get("/api/dispatch-rule-sets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(DEMO_RULE_SET_ID.toString()));
    }

    @Test
    void rejectsRuleSetWithNegativeWaitTime() throws Exception {
        mockMvc.perform(put("/api/dispatch-rule-sets/" + DEMO_RULE_SET_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"maxWaitMinutes":-1,"maxDetourMinutes":8,"bookingWindowMinutes":120,
                                 "autoDispatchScoreThreshold":80,"manualReviewScoreThreshold":60,
                                 "waitWeight":0.35,"detourWeight":0.25,"stabilityWeight":0.30,"utilizationWeight":0.10,
                                 "insertionPolicy":"SAME_DIRECTION_ONLY"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updatesDispatchRuleSet() throws Exception {
        mockMvc.perform(put("/api/dispatch-rule-sets/" + DEMO_RULE_SET_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"maxWaitMinutes":12,"maxDetourMinutes":8,"bookingWindowMinutes":90,
                                 "autoDispatchScoreThreshold":82,"manualReviewScoreThreshold":62,
                                 "waitWeight":0.35,"detourWeight":0.20,"stabilityWeight":0.30,"utilizationWeight":0.15,
                                 "insertionPolicy":"DYNAMIC_INSERTION"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.maxWaitMinutes").value(12));
    }
}
