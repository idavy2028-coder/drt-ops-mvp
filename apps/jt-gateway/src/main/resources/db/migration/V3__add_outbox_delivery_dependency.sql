ALTER TABLE gateway_outbox
  ADD COLUMN dependency_idempotency_key UUID;

ALTER TABLE gateway_outbox
  ADD CONSTRAINT fk_gateway_outbox_dependency
  FOREIGN KEY (dependency_idempotency_key) REFERENCES gateway_outbox(idempotency_key);

CREATE INDEX idx_gateway_outbox_dependency
  ON gateway_outbox(dependency_idempotency_key, status);
