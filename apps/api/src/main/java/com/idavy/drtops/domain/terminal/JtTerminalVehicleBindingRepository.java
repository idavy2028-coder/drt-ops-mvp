package com.idavy.drtops.domain.terminal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JtTerminalVehicleBindingRepository
        extends JpaRepository<JtTerminalVehicleBinding, UUID> {
    // V20 freezes this repository for production compatibility reads. JpaRepository
    // remains temporarily available only for isolated pre-V20 test fixture setup.
    Optional<JtTerminalVehicleBinding> findByTerminalIdAndStatus(
            UUID terminalId, JtTerminalVehicleBinding.Status status);
    List<JtTerminalVehicleBinding> findByVehicleIdOrderByValidFromAsc(UUID vehicleId);
    List<JtTerminalVehicleBinding> findByTerminalIdOrderByValidFromDesc(UUID terminalId);
}
