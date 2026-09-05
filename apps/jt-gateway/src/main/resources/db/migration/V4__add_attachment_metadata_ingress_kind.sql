ALTER TABLE gateway_outbox DROP CONSTRAINT chk_gateway_outbox_kind;

ALTER TABLE gateway_outbox ADD CONSTRAINT chk_gateway_outbox_kind
  CHECK (kind IN ('LOCATION', 'ALARM', 'PROTOCOL_AUDIT', 'ATTACHMENT_METADATA', 'ATTACHMENT_CONTROL'));
