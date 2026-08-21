package com.idavy.drtops.domain.alarm;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import org.junit.jupiter.api.Test;

class VehicleAlarmJpaMappingTest {

    @Test
    void mapsVehicleAlarmActionColumnsToV15() throws Exception {
        assertColumn(VehicleAlarmAction.class, "vehicleAlarmId", "vehicle_alarm_id", false);
        assertStringColumn(VehicleAlarmAction.class, "actionType", "action_type", false, 40);
        assertStringColumn(VehicleAlarmAction.class, "fromStatus", "from_status", true, 20);
        assertStringColumn(VehicleAlarmAction.class, "toStatus", "to_status", true, 20);
        assertStringColumn(VehicleAlarmAction.class, "reason", "reason", true, 500);
        assertColumn(VehicleAlarmAction.class, "actorId", "actor_id", true);
        assertColumn(VehicleAlarmAction.class, "occurredAt", "occurred_at", false);
        assertColumn(VehicleAlarmAction.class, "createdAt", "created_at", false);
    }

    @Test
    void mapsVehicleAlarmAttachmentColumnsToV15() throws Exception {
        assertColumn(VehicleAlarmAttachment.class, "vehicleAlarmId", "vehicle_alarm_id", false);
        assertStringColumn(VehicleAlarmAttachment.class, "attachmentType", "attachment_type", false, 40);
        assertStringColumn(VehicleAlarmAttachment.class, "channel", "channel", false, 40);
        assertStringColumn(VehicleAlarmAttachment.class, "mediaFormat", "media_format", false, 40);
        assertStringColumn(VehicleAlarmAttachment.class, "sanitizedFilename", "sanitized_filename", true, 255);
        assertColumn(VehicleAlarmAttachment.class, "sizeBytes", "size_bytes", true);
        assertStringColumn(VehicleAlarmAttachment.class, "payloadDigest", "payload_digest", true, 64);
        assertStringColumn(VehicleAlarmAttachment.class, "externalMediaReference", "external_media_reference", true, 255);
        assertStringColumn(VehicleAlarmAttachment.class, "status", "status", false, 30);
        assertEnumeratedAsString(VehicleAlarmAttachment.class, "status");
        assertColumn(VehicleAlarmAttachment.class, "createdAt", "created_at", false);
    }

    @Test
    void mapsVehicleAlarmAttachmentTransferColumnsToV15() throws Exception {
        assertColumn(VehicleAlarmAttachmentTransfer.class, "vehicleAlarmAttachmentId",
                "vehicle_alarm_attachment_id", false);
        assertStringColumn(VehicleAlarmAttachmentTransfer.class, "controlMessageType", "control_message_type", false, 20);
        assertColumn(VehicleAlarmAttachmentTransfer.class, "platformSerialNo", "platform_serial_no", true);
        assertColumn(VehicleAlarmAttachmentTransfer.class, "terminalSerialNo", "terminal_serial_no", true);
        assertStringColumn(VehicleAlarmAttachmentTransfer.class, "externalTargetReference",
                "external_target_reference", true, 255);
        assertColumn(VehicleAlarmAttachmentTransfer.class, "retryCount", "retry_count", false);
        assertStringColumn(VehicleAlarmAttachmentTransfer.class, "status", "status", false, 30);
        assertEnumeratedAsString(VehicleAlarmAttachmentTransfer.class, "status");
        assertStringColumn(VehicleAlarmAttachmentTransfer.class, "errorCode", "error_code", true, 80);
        assertColumn(VehicleAlarmAttachmentTransfer.class, "createdAt", "created_at", false);
        assertColumn(VehicleAlarmAttachmentTransfer.class, "updatedAt", "updated_at", false);
    }

    private static void assertColumn(
            Class<?> entityType, String fieldName, String name, boolean nullable) throws Exception {
        Column column = entityType.getDeclaredField(fieldName).getAnnotation(Column.class);
        assertThat(column).as(entityType.getSimpleName() + "." + fieldName).isNotNull();
        assertThat(column.name()).isEqualTo(name);
        assertThat(column.nullable()).isEqualTo(nullable);
    }

    private static void assertStringColumn(
            Class<?> entityType, String fieldName, String name, boolean nullable, int length) throws Exception {
        Column column = entityType.getDeclaredField(fieldName).getAnnotation(Column.class);
        assertThat(column).as(entityType.getSimpleName() + "." + fieldName).isNotNull();
        assertThat(column.name()).isEqualTo(name);
        assertThat(column.nullable()).isEqualTo(nullable);
        assertThat(column.length()).isEqualTo(length);
    }

    private static void assertEnumeratedAsString(Class<?> entityType, String fieldName) throws Exception {
        Enumerated enumerated = entityType.getDeclaredField(fieldName).getAnnotation(Enumerated.class);
        assertThat(enumerated).as(entityType.getSimpleName() + "." + fieldName).isNotNull();
        assertThat(enumerated.value()).isEqualTo(EnumType.STRING);
    }
}
