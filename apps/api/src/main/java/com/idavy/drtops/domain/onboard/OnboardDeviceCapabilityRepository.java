package com.idavy.drtops.domain.onboard;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OnboardDeviceCapabilityRepository
        extends JpaRepository<OnboardDeviceCapability, UUID> {

    @Query(value = """
            select * from onboard_device_capabilities
            where terminal_id = :terminalId and capability = :#{#capability.name()}
              and status in ('DECLARED', 'VERIFIED')
            """, nativeQuery = true)
    Optional<OnboardDeviceCapability> findCurrentByTerminalIdAndCapability(
            @Param("terminalId") UUID terminalId,
            @Param("capability") OnboardDeviceCapability.Capability capability);

    @Query(value = """
            select * from onboard_device_capabilities
            where terminal_id = :terminalId and status in ('DECLARED', 'VERIFIED')
            order by created_at asc
            """, nativeQuery = true)
    List<OnboardDeviceCapability> findCurrentByTerminalIdOrderByCreatedAtAsc(
            @Param("terminalId") UUID terminalId);

    List<OnboardDeviceCapability> findHistoryByTerminalIdAndCapabilityOrderByCreatedAtAsc(
            UUID terminalId, OnboardDeviceCapability.Capability capability);

    List<OnboardDeviceCapability> findHistoryByTerminalIdOrderByCreatedAtAsc(UUID terminalId);
}
