package com.idavy.drtops.domain.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RideOrderStateTest {

    @Test
    void newOrderStartsPendingDispatch() {
        RideOrder order = RideOrder.pendingDispatch(sampleCreateOrder());

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING_DISPATCH);
    }

    @Test
    void confirmedOrderCanStartAndComplete() {
        RideOrder order = RideOrder.pendingDispatch(sampleCreateOrder());

        order.confirm(samplePromise());
        order.startExecution();
        order.complete();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.COMPLETED);
    }

    @Test
    void cancelledOrderCannotBeConfirmed() {
        RideOrder order = RideOrder.pendingDispatch(sampleCreateOrder());

        order.cancel("乘客取消");

        assertThatThrownBy(() -> order.confirm(samplePromise()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CANCELLED");
    }

    @Test
    void unserviceableOrderCannotStartExecution() {
        RideOrder order = RideOrder.pendingDispatch(sampleCreateOrder());

        order.markUnserviceable("超出服务区域");

        assertThatThrownBy(order::startExecution)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("UNSERVICEABLE");
    }

    private static RideOrder.CreateOrderCommand sampleCreateOrder() {
        return new RideOrder.CreateOrderCommand(
                "张三",
                "13800000000",
                2,
                "IMMEDIATE",
                new BigDecimal("116.3120000"),
                new BigDecimal("39.9400000"),
                new BigDecimal("116.3510000"),
                new BigDecimal("39.9210000"),
                UUID.fromString("55555555-5555-5555-5555-555555555551"),
                UUID.fromString("55555555-5555-5555-5555-555555555554"),
                OffsetDateTime.parse("2026-07-08T09:00:00+08:00"));
    }

    private static RideOrder.OrderPromise samplePromise() {
        return new RideOrder.OrderPromise(
                OffsetDateTime.parse("2026-07-08T09:08:00+08:00"),
                OffsetDateTime.parse("2026-07-08T09:26:00+08:00"));
    }
}
