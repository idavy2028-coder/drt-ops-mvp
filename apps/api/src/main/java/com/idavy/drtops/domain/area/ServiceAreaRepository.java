package com.idavy.drtops.domain.area;

import java.util.UUID;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ServiceAreaRepository extends JpaRepository<ServiceArea, UUID> {

    Optional<ServiceArea> findFirstByName(String name);
}
