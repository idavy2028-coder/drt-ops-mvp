package com.idavy.drtops.domain.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VehicleTaskStateTest {

    @Test
    void newTaskStartsPendingDeparture() {
        VehicleTask task = sampleTask();

        assertThat(task.getStatus()).isEqualTo(TaskStatus.PENDING_DEPARTURE);
    }

    @Test
    void dispatchedTaskCanStartPauseResumeAndComplete() {
        VehicleTask task = sampleTask();

        task.dispatch();
        task.startExecution();
        task.pause("车辆临停");
        task.resume();
        task.complete();

        assertThat(task.getStatus()).isEqualTo(TaskStatus.COMPLETED);
    }

    @Test
    void cancelledTaskCannotStartExecution() {
        VehicleTask task = sampleTask();

        task.cancel("运营员取消");

        assertThatThrownBy(task::startExecution)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CANCELLED");
    }

    @Test
    void taskKeepsStopsInPlannedSequence() {
        VehicleTask task = sampleTask();

        task.addStop(TaskStop.planned(
                UUID.fromString("55555555-5555-5555-5555-555555555551"),
                null,
                1,
                "BOARDING",
                OffsetDateTime.parse("2026-07-08T09:08:00+08:00")));
        task.addStop(TaskStop.planned(
                UUID.fromString("55555555-5555-5555-5555-555555555554"),
                null,
                2,
                "ALIGHTING",
                OffsetDateTime.parse("2026-07-08T09:26:00+08:00")));

        assertThat(task.getStops()).extracting(TaskStop::getSequenceNumber).containsExactly(1, 2);
    }

    @Test
    void cancelledOrderStopsNoLongerBlockTaskCompletion() {
        UUID orderId = UUID.fromString("66666666-6666-6666-6666-666666666661");
        VehicleTask task = sampleTask();
        task.addStop(TaskStop.planned(
                UUID.fromString("55555555-5555-5555-5555-555555555551"),
                orderId, 1, "BOARDING", OffsetDateTime.now()));
        task.addStop(TaskStop.planned(
                UUID.fromString("55555555-5555-5555-5555-555555555554"),
                orderId, 2, "ALIGHTING", OffsetDateTime.now().plusMinutes(10)));

        task.cancelStopsForOrder(orderId);

        assertThat(task.getStops()).allMatch(TaskStop::isExecutionComplete);
        assertThat(task.getStops()).extracting(TaskStop::getStatus)
                .containsOnly("CANCELLED");
    }

    private static VehicleTask sampleTask() {
        return VehicleTask.pendingDeparture(
                UUID.fromString("33333333-3333-3333-3333-333333333331"),
                UUID.fromString("44444444-4444-4444-4444-444444444441"),
                OffsetDateTime.parse("2026-07-08T09:00:00+08:00"),
                "ALGORITHM");
    }
}
