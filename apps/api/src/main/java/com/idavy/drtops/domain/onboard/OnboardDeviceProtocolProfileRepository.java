package com.idavy.drtops.domain.onboard;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OnboardDeviceProtocolProfileRepository
        extends JpaRepository<OnboardDeviceProtocolProfile, UUID> {

    @Query(value = """
            select * from onboard_device_protocol_profiles
            where terminal_id = :terminalId and status = 'ACTIVE' and valid_to is null
            """, nativeQuery = true)
    Optional<OnboardDeviceProtocolProfile> findActiveByTerminalId(
            @Param("terminalId") UUID terminalId);

    List<OnboardDeviceProtocolProfile> findHistoryByTerminalIdOrderByValidFromAsc(UUID terminalId);
}
