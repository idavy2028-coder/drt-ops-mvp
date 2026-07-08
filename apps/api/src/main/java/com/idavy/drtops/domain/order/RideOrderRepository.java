package com.idavy.drtops.domain.order;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RideOrderRepository extends JpaRepository<RideOrder, UUID> {
}
