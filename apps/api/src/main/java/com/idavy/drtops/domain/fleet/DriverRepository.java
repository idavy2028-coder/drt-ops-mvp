package com.idavy.drtops.domain.fleet;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DriverRepository extends JpaRepository<Driver, UUID> {
}
