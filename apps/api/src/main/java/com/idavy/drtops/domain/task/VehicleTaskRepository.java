package com.idavy.drtops.domain.task;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VehicleTaskRepository extends JpaRepository<VehicleTask, UUID> {
}
