package com.idavy.drtops.domain.onboard;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OnboardDeviceMembershipRepository
        extends JpaRepository<OnboardDeviceMembership, UUID> {

    @Query(value = """
            select * from onboard_device_memberships
            where terminal_id = :terminalId and status = 'ACTIVE' and valid_to is null
            """, nativeQuery = true)
    Optional<OnboardDeviceMembership> findActiveByTerminalId(
            @Param("terminalId") UUID terminalId);

    @Query(value = """
            select * from onboard_device_memberships
            where onboard_system_id = :onboardSystemId
              and status = 'ACTIVE' and valid_to is null
            order by valid_from asc
            """, nativeQuery = true)
    List<OnboardDeviceMembership> findActiveByOnboardSystemIdOrderByValidFromAsc(
            @Param("onboardSystemId") UUID onboardSystemId);

    List<OnboardDeviceMembership> findHistoryByTerminalIdOrderByValidFromAsc(UUID terminalId);
}
