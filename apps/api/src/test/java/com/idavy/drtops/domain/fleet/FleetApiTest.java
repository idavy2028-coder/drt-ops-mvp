package com.idavy.drtops.domain.fleet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.idavy.drtops.domain.audit.AuditLogRepository;
import com.idavy.drtops.domain.location.LocationEventType;
import com.idavy.drtops.domain.location.LocationSource;
import com.idavy.drtops.domain.location.IdempotencyKeyLock;
import com.idavy.drtops.domain.location.ServiceAreaLocationChecker;
import com.idavy.drtops.domain.location.VehicleLocationEventRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:fleet_api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
@WithMockUser(username = "11111111-1111-1111-1111-111111111111", authorities = "RESOURCE_MANAGE")
@Import(FleetApiTest.LocationTestConfiguration.class)
class FleetApiTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    VehicleRepository vehicleRepository;

    @Autowired
    DriverRepository driverRepository;

    @Autowired
    VehicleLocationEventRepository locationEventRepository;

    @Autowired
    AuditLogRepository auditLogRepository;

    @BeforeEach
    void setUp() {
        auditLogRepository.deleteAll();
        locationEventRepository.deleteAll();
        vehicleRepository.deleteAll();
        driverRepository.deleteAll();
    }

    @Test
    void persistsVehicleCreationAuditReasonWhenProvided() throws Exception {
        mockMvc.perform(post("/api/vehicles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"plateNumber":"DRT-PRE-101","vehicleType":"Microbus","capacity":8,
                                 "currentStatus":"IDLE","lng":116.3180000,"lat":39.9290000,
                                 "fleetName":"P6-2 PRE-ACCEPTANCE","dispatchable":false,
                                 "reason":"PRE_ACCEPTANCE"}
                                """))
                .andExpect(status().isCreated());

        assertThat(auditLogRepository.findAll())
                .filteredOn(audit -> "VEHICLE_CREATED".equals(audit.getAction()))
                .singleElement()
                .satisfies(audit -> assertThat(audit.getReason()).isEqualTo("PRE_ACCEPTANCE"));
    }

    @Test
    void createsAndListsVehicles() throws Exception {
        mockMvc.perform(post("/api/vehicles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"plateNumber":"DRT-101","vehicleType":"Microbus","capacity":12,
                                 "currentStatus":"IDLE","lng":116.3180000,"lat":39.9290000,
                                 "fleetName":"演示车队","dispatchable":true}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.plateNumber").value("DRT-101"))
                .andExpect(jsonPath("$.data.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.data.latestLocation.longitude").value(116.3180000))
                .andExpect(jsonPath("$.data.latestLocation.latitude").value(39.9290000))
                .andExpect(jsonPath("$.data.latestLocation.source").value("MANUAL_DISPATCHER"))
                .andExpect(jsonPath("$.data.latestLocation.eventId").isNotEmpty());

        assertThat(locationEventRepository.findAll())
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.getEventType()).isEqualTo(LocationEventType.MANUAL_REPORT);
                    assertThat(event.getSource()).isEqualTo(LocationSource.MANUAL_DISPATCHER);
                    assertThat(event.getRecordedBy()).isEqualTo(java.util.UUID.fromString("11111111-1111-1111-1111-111111111111"));
                    assertThat(event.isSnapshotApplied()).isTrue();
                });

        mockMvc.perform(get("/api/vehicles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].createdAt").isNotEmpty());
    }

    @Test
    void rejectsVehicleWithZeroCapacity() throws Exception {
        mockMvc.perform(post("/api/vehicles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"plateNumber":"DRT-102","vehicleType":"Microbus","capacity":0,
                                 "currentStatus":"IDLE","lng":116.3180000,"lat":39.9290000,
                                 "fleetName":"演示车队","dispatchable":true}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createsAndListsDrivers() throws Exception {
        mockMvc.perform(post("/api/drivers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"王师傅","phone":"13900001001","qualificationStatus":"QUALIFIED",
                                 "currentStatus":"AVAILABLE","fleetName":"演示车队"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("王师傅"));

        mockMvc.perform(get("/api/drivers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @TestConfiguration
    static class LocationTestConfiguration {

        @Bean
        @Primary
        IdempotencyKeyLock idempotencyKeyLock() {
            return idempotencyKey -> { };
        }

        @Bean
        @Primary
        ServiceAreaLocationChecker serviceAreaLocationChecker() {
            return (longitude, latitude) -> true;
        }
    }
}
