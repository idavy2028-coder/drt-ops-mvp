package com.idavy.drtops.domain.order;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idavy.drtops.domain.area.VirtualStop;
import com.idavy.drtops.domain.area.VirtualStopRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:ride_order_api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
class RideOrderApiTest {

    private static final UUID DEMO_AREA_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID BOARDING_STOP_ID = UUID.fromString("55555555-5555-5555-5555-555555555551");
    private static final UUID ALIGHTING_STOP_ID = UUID.fromString("55555555-5555-5555-5555-555555555552");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    RideOrderRepository rideOrderRepository;

    @Autowired
    VirtualStopRepository virtualStopRepository;

    @BeforeEach
    void setUp() {
        rideOrderRepository.deleteAll();
        virtualStopRepository.deleteAll();
        virtualStopRepository.save(VirtualStop.create(
                BOARDING_STOP_ID,
                DEMO_AREA_ID,
                "上车虚拟站",
                "POINT(120.1550000 30.2741000)",
                600,
                true,
                false,
                "路侧安全候车"));
        virtualStopRepository.save(VirtualStop.create(
                ALIGHTING_STOP_ID,
                DEMO_AREA_ID,
                "下车虚拟站",
                "POINT(120.1688000 30.2799000)",
                600,
                false,
                true,
                "地铁口附近落客"));
    }

    @Test
    void createsPendingDispatchOrderWhenStopsMatch() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"passengerName":"张三","passengerPhone":"13800000000","passengerCount":1,
                                 "originLng":120.1550,"originLat":30.2741,
                                 "destinationLng":120.1688,"destinationLat":30.2799,
                                 "requestType":"IMMEDIATE","requestedDepartureAt":"2026-07-08T02:30:00Z"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("PENDING_DISPATCH"))
                .andExpect(jsonPath("$.data.boardingStopId").value(BOARDING_STOP_ID.toString()))
                .andExpect(jsonPath("$.data.alightingStopId").value(ALIGHTING_STOP_ID.toString()));
    }

    @Test
    void listsCreatedOrders() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"passengerName":"李四","passengerPhone":"13800000001","passengerCount":2,
                                 "originLng":120.1550,"originLat":30.2741,
                                 "destinationLng":120.1688,"destinationLat":30.2799,
                                 "requestType":"IMMEDIATE","requestedDepartureAt":"2026-07-08T02:30:00Z"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
    }
}
