package com.idavy.drtops.domain.order;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idavy.drtops.domain.area.VirtualStop;
import com.idavy.drtops.domain.area.VirtualStopRepository;
import com.idavy.drtops.domain.location.ServiceAreaLocationChecker;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:ride_order_address_api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
@Import(RideOrderAddressApiTest.ServiceAreaCheckerConfiguration.class)
class RideOrderAddressApiTest {

    private static final UUID SERVICE_AREA_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID BOARDING_STOP_ID = UUID.fromString("55555555-5555-5555-5555-555555555561");
    private static final UUID ALIGHTING_STOP_ID = UUID.fromString("55555555-5555-5555-5555-555555555562");

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
                BOARDING_STOP_ID, SERVICE_AREA_ID, "通渭县人民医院上车点", "POINT(105.2200 35.2200)", 600,
                true, false, "医院门口安全候车"));
        virtualStopRepository.save(VirtualStop.create(
                ALIGHTING_STOP_ID, SERVICE_AREA_ID, "通渭县文化广场下车点", "POINT(105.2300 35.2300)", 600,
                false, true, "广场东侧下车"));
    }

    @Test
    @WithMockUser(authorities = {"ORDER_CREATE", "ORDER_READ"})
    void createsOrderWithStandardizedAddressesAndCoordinateSystem() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"passengerName":"张三","passengerPhone":"13800000000","passengerCount":1,
                                 "requestType":"IMMEDIATE","originAddress":"通渭县人民医院门诊楼",
                                 "originLng":105.2200,"originLat":35.2200,"originVirtualStopId":"55555555-5555-5555-5555-555555555561",
                                 "destinationAddress":"通渭县文化广场东门",
                                 "destinationLng":105.2300,"destinationLat":35.2300,"destinationVirtualStopId":"55555555-5555-5555-5555-555555555562",
                                 "coordinateSystem":"GCJ02","requestedDepartureAt":"2026-07-18T02:30:00Z"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.originAddress").value("通渭县人民医院门诊楼"))
                .andExpect(jsonPath("$.data.destinationAddress").value("通渭县文化广场东门"))
                .andExpect(jsonPath("$.data.coordinateSystem").value("GCJ02"))
                .andExpect(jsonPath("$.data.boardingStopId").value(BOARDING_STOP_ID.toString()))
                .andExpect(jsonPath("$.data.alightingStopId").value(ALIGHTING_STOP_ID.toString()));
    }

    @Test
    @WithMockUser(authorities = "ORDER_CREATE")
    void rejectsOutsideEndpointWithChineseMessageAndDoesNotCreateOrder() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"passengerName":"李四","passengerPhone":"13800000001","passengerCount":1,
                                 "requestType":"IMMEDIATE","originAddress":"通渭县人民医院门诊楼",
                                 "originLng":105.2200,"originLat":35.2200,
                                 "destinationAddress":"服务区外地点","destinationLng":105.3200,"destinationLat":35.3200,
                                 "coordinateSystem":"GCJ02","requestedDepartureAt":"2026-07-18T02:30:00Z"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.message").value("订单终点不在已发布服务区内"));

        org.assertj.core.api.Assertions.assertThat(rideOrderRepository.count()).isZero();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ServiceAreaCheckerConfiguration {

        @Bean
        @Primary
        ServiceAreaLocationChecker serviceAreaLocationChecker() {
            return new ServiceAreaLocationChecker() {
                @Override
                public boolean isInsideEnabledArea(BigDecimal longitude, BigDecimal latitude) {
                    return longitude.compareTo(new BigDecimal("105.3000")) < 0;
                }

                @Override
                public PublishedAreaCheck checkPublishedArea(BigDecimal longitude, BigDecimal latitude) {
                    return new PublishedAreaCheck(
                            isInsideEnabledArea(longitude, latitude), SERVICE_AREA_ID, 0.0);
                }
            };
        }
    }
}
