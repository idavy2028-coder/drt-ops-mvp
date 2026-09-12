CREATE TABLE video_declaration_observations (
  id UUID PRIMARY KEY,
  terminal_id UUID NOT NULL REFERENCES jt_terminals(id),
  connection_id UUID NOT NULL,
  lease_generation BIGINT NOT NULL CHECK (lease_generation > 0),
  serial_number INTEGER NOT NULL CHECK (serial_number BETWEEN 0 AND 65535),
  payload_digest VARCHAR(64) NOT NULL CHECK (payload_digest ~ '^[0-9a-f]{64}$'),
  payload JSONB NOT NULL,
  received_at TIMESTAMPTZ NOT NULL,
  resolved_at TIMESTAMPTZ,
  resolved_by UUID REFERENCES user_accounts(id),
  resolution_reason VARCHAR(500),
  resolution_evidence_ref VARCHAR(500),
  version BIGINT NOT NULL DEFAULT 0,
  outcome VARCHAR(30) NOT NULL CHECK (outcome IN
    ('DECLARED','UNCHANGED','QUARANTINED','HISTORICAL','REVIEW_REQUIRED','DISABLED_CONFLICT','NO_VIDEO_CHANNELS'))
);
CREATE INDEX idx_video_declarations_terminal_received
  ON video_declaration_observations(terminal_id, received_at DESC);
