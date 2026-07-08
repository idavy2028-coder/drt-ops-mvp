package com.idavy.drtops.domain.task;

import static org.assertj.core.api.Assertions.assertThat;

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
class VehicleTaskRepositoryTest {

    @Autowired
    VehicleTaskRepository repository;

    @Autowired
    TestEntityManager entityManager;

    @Test
    void savesAndReloadsTaskWithStops() {
        VehicleTask task = VehicleTask.pendingDeparture(
                UUID.fromString("33333333-3333-3333-3333-333333333331"),
                UUID.fromString("44444444-4444-4444-4444-444444444441"),
                OffsetDateTime.parse("2026-07-08T09:00:00+08:00"),
                "ALGORITHM");
        task.addStop(TaskStop.planned(
                UUID.fromString("55555555-5555-5555-5555-555555555551"),
                null,
                1,
                "BOARDING",
                OffsetDateTime.parse("2026-07-08T09:08:00+08:00")));

        UUID taskId = repository.save(task).getId();
        entityManager.flush();
        entityManager.clear();

        VehicleTask reloaded = repository.findById(taskId).orElseThrow();

        assertThat(reloaded.getStatus()).isEqualTo(TaskStatus.PENDING_DEPARTURE);
        assertThat(reloaded.getStops()).hasSize(1);
        assertThat(reloaded.getStops().getFirst().getSequenceNumber()).isEqualTo(1);
    }
}
