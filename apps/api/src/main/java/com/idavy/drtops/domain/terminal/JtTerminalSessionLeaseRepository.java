package com.idavy.drtops.domain.terminal;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JtTerminalSessionLeaseRepository
        extends JpaRepository<JtTerminalSessionLease, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select lease from JtTerminalSessionLease lease "
            + "where lease.terminalId = :terminalId")
    Optional<JtTerminalSessionLease> findLockedByTerminalId(
            @Param("terminalId") UUID terminalId);

    List<JtTerminalSessionLease> findAllByTerminalIdIn(
            Collection<UUID> terminalIds);
}
