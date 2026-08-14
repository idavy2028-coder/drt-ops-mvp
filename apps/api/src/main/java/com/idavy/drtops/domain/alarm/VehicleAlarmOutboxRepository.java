package com.idavy.drtops.domain.alarm;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface VehicleAlarmOutboxRepository extends JpaRepository<VehicleAlarmOutboxEvent, UUID> {
    @Query(value = """
            select * from vehicle_alarm_outbox
            where status = 'PENDING'
            order by created_at asc, id asc
            limit :batchSize
            for update skip locked
            """, nativeQuery = true)
    List<VehicleAlarmOutboxEvent> claimPending(@Param("batchSize") int batchSize);

    @Query("""
            select event from VehicleAlarmOutboxEvent event
            where event.status = 'PUBLISHED'
              and (event.createdAt > :createdAt
                   or (event.createdAt = :createdAt and event.id > :id))
            order by event.createdAt asc, event.id asc
            """)
    List<VehicleAlarmOutboxEvent> findPublishedAfter(
            @Param("createdAt") Instant createdAt, @Param("id") UUID id);

    @Modifying
    @Query("delete from VehicleAlarmOutboxEvent event where event.status = 'PUBLISHED' and event.createdAt < :cutoff")
    int deletePublishedBefore(@Param("cutoff") Instant cutoff);
}
