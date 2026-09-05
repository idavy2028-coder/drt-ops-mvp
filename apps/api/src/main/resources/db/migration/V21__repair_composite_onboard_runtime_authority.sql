CREATE TABLE jt_terminal_session_leases (
  terminal_id UUID PRIMARY KEY REFERENCES jt_terminals(id),
  gateway_instance VARCHAR(120) NOT NULL,
  connection_id UUID NOT NULL,
  token_version INTEGER NOT NULL,
  lease_generation BIGINT NOT NULL,
  authenticated_at TIMESTAMPTZ NOT NULL,
  last_valid_message_at TIMESTAMPTZ NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  released_at TIMESTAMPTZ,
  release_reason VARCHAR(80),
  updated_at TIMESTAMPTZ NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT ck_jt_terminal_session_leases_token_version
    CHECK (token_version > 0),
  CONSTRAINT ck_jt_terminal_session_leases_generation
    CHECK (lease_generation > 0),
  CONSTRAINT ck_jt_terminal_session_leases_expiry
    CHECK (expires_at > last_valid_message_at
      AND last_valid_message_at >= authenticated_at),
  CONSTRAINT ck_jt_terminal_session_leases_release
    CHECK ((released_at IS NULL AND release_reason IS NULL)
      OR (released_at IS NOT NULL AND release_reason IS NOT NULL)),
  CONSTRAINT ck_jt_terminal_session_leases_release_reason
    CHECK (release_reason IS NULL
      OR release_reason ~ '^[A-Z][A-Z0-9_]{2,79}$')
);

CREATE INDEX idx_jt_terminal_session_leases_live_expiry
  ON jt_terminal_session_leases(expires_at)
  WHERE released_at IS NULL;

ALTER TABLE onboard_system_runtime_state
  ADD COLUMN last_primary_valid_gateway_received_at TIMESTAMPTZ,
  ADD COLUMN primary_terminal_cursor_at TIMESTAMPTZ,
  ADD COLUMN backup_terminal_cursor_at TIMESTAMPTZ;

ALTER TABLE vehicle_alarms
  ADD COLUMN onboard_system_id UUID REFERENCES onboard_systems(id);

CREATE INDEX idx_vehicle_alarms_onboard_system_received
  ON vehicle_alarms(onboard_system_id, gateway_received_at DESC)
  WHERE onboard_system_id IS NOT NULL;

UPDATE vehicles vehicle
SET current_location_onboard_system_id = event.onboard_system_id
FROM vehicle_location_events event
JOIN onboard_systems system
  ON system.id = event.onboard_system_id
WHERE vehicle.current_location_onboard_system_id IS NULL
  AND vehicle.current_location_event_id = event.id
  AND event.vehicle_id = vehicle.id
  AND event.terminal_id = vehicle.current_location_terminal_id
  AND event.onboard_system_id IS NOT NULL
  AND system.vehicle_id = vehicle.id;
