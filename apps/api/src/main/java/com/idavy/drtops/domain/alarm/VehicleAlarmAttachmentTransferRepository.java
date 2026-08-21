package com.idavy.drtops.domain.alarm;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface VehicleAlarmAttachmentTransferRepository extends JpaRepository<VehicleAlarmAttachmentTransfer, UUID> {
    Optional<VehicleAlarmAttachmentTransfer> findByExternalTargetReference(String externalTargetReference);
}
