package com.idavy.drtops.domain.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

@DataJpaTest(showSql = false, properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class RideOrderRepositoryTest {

    @Autowired
    RideOrderRepository repository;

    @Autowired
    TestEntityManager entityManager;

    @Test
    void savesAndReloadsOrderStatus() {
        RideOrder order = RideOrder.pendingDispatch(sampleCreateOrder());
        order.markPendingManualReview("低置信度");

        UUID orderId = repository.save(order).getId();
        entityManager.flush();
        entityManager.clear();

        RideOrder reloaded = repository.findById(orderId).orElseThrow();

        assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.PENDING_MANUAL_REVIEW);
    }

    private static RideOrder.CreateOrderCommand sampleCreateOrder() {
        return new RideOrder.CreateOrderCommand(
                "李四",
                "13800000001",
                1,
                "RESERVATION",
                new BigDecimal("116.3120000"),
                new BigDecimal("39.9400000"),
                new BigDecimal("116.3510000"),
                new BigDecimal("39.9210000"),
                UUID.fromString("55555555-5555-5555-5555-555555555551"),
                UUID.fromString("55555555-5555-5555-5555-555555555554"),
                OffsetDateTime.parse("2026-07-08T10:00:00+08:00"));
    }
}
