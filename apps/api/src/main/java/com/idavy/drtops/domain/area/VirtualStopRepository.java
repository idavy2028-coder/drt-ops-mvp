package com.idavy.drtops.domain.area;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VirtualStopRepository extends JpaRepository<VirtualStop, UUID> {

    List<VirtualStop> findByServiceAreaId(UUID serviceAreaId);
}
