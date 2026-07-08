package com.idavy.drtops.domain.fleet;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
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
class FleetApiTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    VehicleRepository vehicleRepository;

    @Autowired
    DriverRepository driverRepository;

    @BeforeEach
    void setUp() {
        vehicleRepository.deleteAll();
        driverRepository.deleteAll();
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
                .andExpect(jsonPath("$.data.plateNumber").value("DRT-101"));

        mockMvc.perform(get("/api/vehicles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
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
}
