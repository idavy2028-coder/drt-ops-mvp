package com.idavy.drtops.domain.terminal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JtTerminalVehicleBindingRepository
        extends JpaRepository<JtTerminalVehicleBinding, UUID> {
    Optional<JtTerminalVehicleBinding> findByTerminalIdAndStatus(
            UUID terminalId, JtTerminalVehicleBinding.Status status);
    Optional<JtTerminalVehicleBinding> findByVehicleIdAndStatus(
            UUID vehicleId, JtTerminalVehicleBinding.Status status);
    List<JtTerminalVehicleBinding> findByVehicleIdOrderByValidFromAsc(UUID vehicleId);
    List<JtTerminalVehicleBinding> findByTerminalIdOrderByValidFromDesc(UUID terminalId);
}
