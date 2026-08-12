CREATE TABLE jt_terminals (
  id UUID PRIMARY KEY,
  terminal_phone VARCHAR(30) NOT NULL UNIQUE,
  terminal_code VARCHAR(80) NOT NULL UNIQUE,
  manufacturer_id VARCHAR(80) NOT NULL,
  model VARCHAR(120) NOT NULL,
  protocol_version VARCHAR(40) NOT NULL,
  source_coordinate_system VARCHAR(20) NOT NULL CHECK (source_coordinate_system IN ('GCJ02', 'WGS84')),
  active_safety_standard VARCHAR(40),
  active_safety_modules JSONB NOT NULL DEFAULT '[]'::jsonb,
  jt1078_enabled BOOLEAN NOT NULL DEFAULT FALSE,
  attachment_upload_profile VARCHAR(80),
  media_server_profile_id VARCHAR(120),
  status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'ACTIVE', 'SUSPENDED', 'RETIRED')),
  auth_token_hash CHAR(64) NOT NULL CHECK (auth_token_hash ~ '^[0-9a-f]{64}$'),
  auth_token_version INTEGER NOT NULL CHECK (auth_token_version > 0),
  last_registered_at TIMESTAMPTZ,
  last_authenticated_at TIMESTAMPTZ,
  last_seen_at TIMESTAMPTZ,
  created_by UUID REFERENCES user_accounts(id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_jt_terminals_status_last_seen
  ON jt_terminals(status, last_seen_at DESC);

CREATE TABLE jt_terminal_vehicle_bindings (
  id UUID PRIMARY KEY,
  terminal_id UUID NOT NULL REFERENCES jt_terminals(id),
  vehicle_id UUID NOT NULL REFERENCES vehicles(id),
  valid_from TIMESTAMPTZ NOT NULL,
  valid_to TIMESTAMPTZ,
  status VARCHAR(20) NOT NULL CHECK (status IN ('ACTIVE', 'UNBOUND')),
  binding_reason VARCHAR(500) NOT NULL,
  unbinding_reason VARCHAR(500),
  bound_by UUID REFERENCES user_accounts(id),
  unbound_by UUID REFERENCES user_accounts(id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CHECK (valid_to IS NULL OR valid_to >= valid_from),
  CHECK ((status = 'ACTIVE' AND valid_to IS NULL) OR (status = 'UNBOUND' AND valid_to IS NOT NULL))
);

CREATE UNIQUE INDEX uq_jt_terminal_vehicle_bindings_active_terminal
  ON jt_terminal_vehicle_bindings(terminal_id)
  WHERE status = 'ACTIVE' AND valid_to IS NULL;

CREATE UNIQUE INDEX uq_jt_terminal_vehicle_bindings_active_vehicle
  ON jt_terminal_vehicle_bindings(vehicle_id)
  WHERE status = 'ACTIVE' AND valid_to IS NULL;

CREATE INDEX idx_jt_terminal_vehicle_bindings_vehicle_history
  ON jt_terminal_vehicle_bindings(vehicle_id, valid_from DESC);

CREATE TABLE jt_gateway_audit_events (
  id UUID PRIMARY KEY,
  terminal_id UUID REFERENCES jt_terminals(id),
  vehicle_id UUID REFERENCES vehicles(id),
  event_type VARCHAR(40) NOT NULL CHECK (event_type IN (
    'REGISTERED', 'AUTHENTICATED', 'ONLINE', 'OFFLINE', 'DUPLICATE_LOGIN', 'SUSPENDED',
    'TERMINAL_REPLACED', 'PROTOCOL_REJECTED', 'RATE_LIMITED', 'FORCED_DISCONNECT')),
  result VARCHAR(20) NOT NULL CHECK (result IN ('ACCEPTED', 'REJECTED', 'APPLIED')),
  reason_code VARCHAR(80),
  protocol_version VARCHAR(40),
  message_id INTEGER,
  payload_digest CHAR(64) CHECK (payload_digest IS NULL OR payload_digest ~ '^[0-9a-f]{64}$'),
  remote_address VARCHAR(80),
  occurred_at TIMESTAMPTZ NOT NULL,
  gateway_instance VARCHAR(120) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_jt_gateway_audit_events_terminal_time
  ON jt_gateway_audit_events(terminal_id, occurred_at DESC);
CREATE INDEX idx_jt_gateway_audit_events_vehicle_time
  ON jt_gateway_audit_events(vehicle_id, occurred_at DESC);
CREATE INDEX idx_jt_gateway_audit_events_occurred_at_brin
  ON jt_gateway_audit_events USING BRIN(occurred_at);
