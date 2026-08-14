package com.idavy.drtops.domain.alarm;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface VehicleAlarmAttachmentRepository extends JpaRepository<VehicleAlarmAttachment, UUID> { }
