ALTER TABLE jt_gateway_audit_events
  ADD COLUMN idempotency_key UUID;

UPDATE jt_gateway_audit_events
SET idempotency_key = id
WHERE idempotency_key IS NULL;

ALTER TABLE jt_gateway_audit_events
  ALTER COLUMN idempotency_key SET NOT NULL;

ALTER TABLE jt_gateway_audit_events
  ADD CONSTRAINT uq_jt_gateway_audit_events_idempotency_key
  UNIQUE (idempotency_key);

ALTER TABLE jt_gateway_ingress_receipts
  ADD COLUMN terminal_id UUID;

ALTER TABLE jt_gateway_ingress_receipts
  ADD COLUMN vehicle_id UUID;

ALTER TABLE jt_gateway_ingress_receipts
  ADD COLUMN ingress_kind VARCHAR(32);
