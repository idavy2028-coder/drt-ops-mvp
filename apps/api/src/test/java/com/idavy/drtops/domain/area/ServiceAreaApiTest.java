package com.idavy.drtops.domain.area;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idavy.drtops.domain.dispatch.DispatchRuleSet;
import com.idavy.drtops.domain.dispatch.DispatchRuleSetRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:service_area_api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
class ServiceAreaApiTest {

    private static final UUID DEMO_RULE_SET_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DEMO_AREA_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    DispatchRuleSetRepository ruleSetRepository;

    @Autowired
    ServiceAreaRepository serviceAreaRepository;

    @Autowired
    VirtualStopRepository virtualStopRepository;

    @BeforeEach
    void setUp() {
        virtualStopRepository.deleteAll();
        serviceAreaRepository.deleteAll();
        ruleSetRepository.deleteAll();

        ruleSetRepository.save(DispatchRuleSet.defaultRules(DEMO_RULE_SET_ID));
        serviceAreaRepository.save(ServiceArea.create(
                DEMO_AREA_ID,
                "演示服务区",
                "POLYGON((116.3000000 39.9000000,116.3600000 39.9000000,116.3600000 39.9500000,116.3000000 39.9500000,116.3000000 39.9000000))",
                "06:30:00",
                "22:30:00",
                DEMO_RULE_SET_ID));

        for (int i = 1; i <= 6; i++) {
            virtualStopRepository.save(VirtualStop.create(
                    UUID.fromString("55555555-5555-5555-5555-55555555555" + i),
                    DEMO_AREA_ID,
                    "虚拟站点" + i,
                    "POINT(116.31" + i + "0000 39.92" + i + "0000)",
                    250,
                    true,
                    true,
                    "安全候车点"));
        }
    }

    @Test
    void listsSeededVirtualStopsForArea() throws Exception {
        mockMvc.perform(get("/api/virtual-stops").param("serviceAreaId", DEMO_AREA_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(6));
    }

    @Test
    void createsServiceArea() throws Exception {
        mockMvc.perform(post("/api/service-areas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"新增服务区",
                                 "boundaryWkt":"POLYGON((116.3700000 39.9000000,116.3900000 39.9000000,116.3900000 39.9300000,116.3700000 39.9300000,116.3700000 39.9000000))",
                                 "serviceStart":"07:00:00","serviceEnd":"21:30:00",
                                 "ruleSetId":"11111111-1111-1111-1111-111111111111"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("新增服务区"));
    }

    @Test
    void rejectsVirtualStopWithInvalidRadius() throws Exception {
        mockMvc.perform(post("/api/virtual-stops")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"serviceAreaId":"22222222-2222-2222-2222-222222222222",
                                 "name":"半径错误站点","lng":116.3200000,"lat":39.9300000,
                                 "serviceRadiusMeters":0,
                                 "boardingEnabled":true,"alightingEnabled":true,
                                 "safetyNote":"安全候车点"}
                                """))
                .andExpect(status().isBadRequest());
    }
}
