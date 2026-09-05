package com.idavy.drtops.domain.onboard;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OnboardDeviceRoleAssignmentRepository
        extends JpaRepository<OnboardDeviceRoleAssignment, UUID> {

    @Query(value = """
            select * from onboard_device_role_assignments
            where onboard_system_id = :onboardSystemId
              and status = 'ACTIVE' and valid_to is null
            order by valid_from asc
            """, nativeQuery = true)
    List<OnboardDeviceRoleAssignment> findActiveByOnboardSystemIdOrderByValidFromAsc(
            @Param("onboardSystemId") UUID onboardSystemId);

    @Query(value = """
            select * from onboard_device_role_assignments
            where onboard_system_id = :onboardSystemId and role = :#{#role.name()}
              and status = 'ACTIVE' and valid_to is null
            """, nativeQuery = true)
    Optional<OnboardDeviceRoleAssignment> findActiveByOnboardSystemIdAndRole(
            @Param("onboardSystemId") UUID onboardSystemId,
            @Param("role") OnboardDeviceRoleAssignment.Role role);

    @Query(value = """
            select * from onboard_device_role_assignments
            where terminal_id = :terminalId and status = 'ACTIVE' and valid_to is null
            order by valid_from asc
            """, nativeQuery = true)
    List<OnboardDeviceRoleAssignment> findActiveByTerminalIdOrderByValidFromAsc(
            @Param("terminalId") UUID terminalId);

    List<OnboardDeviceRoleAssignment> findHistoryByOnboardSystemIdAndRoleOrderByValidFromAsc(
            UUID onboardSystemId, OnboardDeviceRoleAssignment.Role role);

    List<OnboardDeviceRoleAssignment> findHistoryByTerminalIdOrderByValidFromAsc(UUID terminalId);
}
