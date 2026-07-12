package com.idavy.drtops.domain.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idavy.drtops.domain.audit.AuditLogRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:order_exception_api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
@WithMockUser(authorities = "DISPATCH_EXECUTE")
class OrderExceptionApiTest {

    private static final UUID BOARDING_STOP_ID = UUID.fromString("55555555-5555-5555-5555-555555555551");
    private static final UUID ALIGHTING_STOP_ID = UUID.fromString("55555555-5555-5555-5555-555555555552");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    RideOrderRepository rideOrderRepository;

    @Autowired
    AuditLogRepository auditLogRepository;

    @BeforeEach
    void setUp() {
        auditLogRepository.deleteAll();
        rideOrderRepository.deleteAll();
    }

    @Test
    void cancelOrderMarksCancelledAndAuditsReason() throws Exception {
        UUID orderId = createPendingOrder();

        mockMvc.perform(post("/api/orders/" + orderId + "/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"乘客取消\"}"))
                .andExpect(status().isOk());

        assertThat(rideOrderRepository.findById(orderId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CANCELLED);
        assertThat(auditLogRepository.findByEntityId(orderId))
                .anyMatch(log -> log.getAction().equals("ORDER_CANCELLED"));
    }

    @Test
    void noShowClosesOrderAsExceptionAndAuditsReason() throws Exception {
        UUID orderId = createConfirmedOrder();

        mockMvc.perform(post("/api/orders/" + orderId + "/no-show")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"乘客未上车\"}"))
                .andExpect(status().isOk());

        assertThat(rideOrderRepository.findById(orderId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.EXCEPTION_CLOSED);
        assertThat(auditLogRepository.findByEntityId(orderId))
                .anyMatch(log -> log.getAction().equals("ORDER_NO_SHOW"));
    }

    private UUID createPendingOrder() {
        return rideOrderRepository.save(newOrder()).getId();
    }

    private UUID createConfirmedOrder() {
        RideOrder order = newOrder();
        order.confirm(new RideOrder.OrderPromise(
                OffsetDateTime.parse("2026-07-08T02:36:00Z"),
                OffsetDateTime.parse("2026-07-08T02:50:00Z")));
        return rideOrderRepository.save(order).getId();
    }

    private RideOrder newOrder() {
        return RideOrder.pendingDispatch(new RideOrder.CreateOrderCommand(
                "张三",
                "13800000000",
                1,
                "IMMEDIATE",
                new BigDecimal("120.1550000"),
                new BigDecimal("30.2741000"),
                new BigDecimal("120.1688000"),
                new BigDecimal("30.2799000"),
                BOARDING_STOP_ID,
                ALIGHTING_STOP_ID,
                OffsetDateTime.parse("2026-07-08T02:30:00Z")));
    }
}
