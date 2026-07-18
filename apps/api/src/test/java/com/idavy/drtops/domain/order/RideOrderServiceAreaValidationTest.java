package com.idavy.drtops.domain.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idavy.drtops.domain.area.VirtualStop;
import com.idavy.drtops.domain.area.VirtualStopRepository;
import com.idavy.drtops.domain.audit.AuditLogRepository;
import com.idavy.drtops.domain.location.ServiceAreaLocationChecker;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:ride_order_area_validation;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class RideOrderServiceAreaValidationTest {

    private static final UUID SERVICE_AREA_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID BOARDING_STOP_ID = UUID.fromString("55555555-5555-5555-5555-555555555551");
    private static final UUID ALIGHTING_STOP_ID = UUID.fromString("55555555-5555-5555-5555-555555555552");
    private static final UUID INSIDE_ALIGHTING_STOP_ID = UUID.fromString("55555555-5555-5555-5555-555555555553");
    private static final UUID ACTOR_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Autowired
    RideOrderService rideOrderService;

    @Autowired
    RideOrderRepository rideOrderRepository;

    @Autowired
    VirtualStopRepository virtualStopRepository;

    @Autowired
    AuditLogRepository auditLogRepository;

    @BeforeEach
    void setUp() {
        auditLogRepository.deleteAll();
        rideOrderRepository.deleteAll();
        virtualStopRepository.deleteAll();
        virtualStopRepository.save(VirtualStop.create(
                BOARDING_STOP_ID, SERVICE_AREA_ID, "上车站", "POINT(105.2200 35.2200)", 500,
                true, false, "测试站点"));
        virtualStopRepository.save(VirtualStop.create(
                ALIGHTING_STOP_ID, SERVICE_AREA_ID, "下车站", "POINT(105.3200 35.3200)", 500,
                false, true, "测试站点"));
        virtualStopRepository.save(VirtualStop.create(
                INSIDE_ALIGHTING_STOP_ID, SERVICE_AREA_ID, "区内下车站", "POINT(105.2200 35.2200)", 500,
                false, true, "测试站点"));
    }

    @Test
    void rejectsOrderWhenDestinationIsOutsidePublishedServiceAreaAndAuditsIt() {
        assertThatThrownBy(() -> rideOrderService.create(ACTOR_ID, request("105.2200", "35.2200", "105.3200", "35.3200")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> {
                    ResponseStatusException statusException = (ResponseStatusException) exception;
                    assertThat(statusException.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(statusException.getReason()).isEqualTo("订单终点不在已发布服务区内");
                });

        assertThat(rideOrderRepository.count()).isZero();
        assertThat(auditLogRepository.findByEntityId(SERVICE_AREA_ID))
                .anyMatch(log -> log.getAction().equals("RIDE_ORDER_AREA_REJECTED")
                        && log.getActorId().equals(ACTOR_ID.toString()));
    }

    @Test
    void createsOrderWhenBothEndpointsAreInsidePublishedServiceArea() {
        RideOrder order = rideOrderService.create(ACTOR_ID, request("105.2200", "35.2200", "105.2200", "35.2200"));

        assertThat(order.getId()).isNotNull();
        assertThat(rideOrderRepository.count()).isEqualTo(1);
    }

    private RideOrderService.CreateRideOrderRequest request(
            String originLng, String originLat, String destinationLng, String destinationLat) {
        return new RideOrderService.CreateRideOrderRequest(
                "张三", "13800000000", 1, "IMMEDIATE",
                new BigDecimal(originLng), new BigDecimal(originLat),
                new BigDecimal(destinationLng), new BigDecimal(destinationLat),
                OffsetDateTime.parse("2026-07-08T02:30:00Z"));
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
                            longitude.compareTo(new BigDecimal("105.3000")) < 0,
                            SERVICE_AREA_ID,
                            0.0);
                }
            };
        }
    }
}
