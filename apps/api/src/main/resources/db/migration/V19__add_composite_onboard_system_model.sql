CREATE TABLE onboard_systems (
  id UUID PRIMARY KEY,
  vehicle_id UUID NOT NULL REFERENCES vehicles(id),
  status VARCHAR(20) NOT NULL,
  operating_mode VARCHAR(30) NOT NULL,
  created_by UUID REFERENCES user_accounts(id),
  updated_by UUID REFERENCES user_accounts(id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT ck_onboard_systems_status
    CHECK (status IN ('ACTIVE', 'SUSPENDED', 'RETIRED')),
  CONSTRAINT ck_onboard_systems_operating_mode
    CHECK (operating_mode IN ('DISPATCH_SERVICE', 'SAFETY_MONITOR_ONLY'))
);

CREATE UNIQUE INDEX uq_onboard_systems_active_vehicle
  ON onboard_systems(vehicle_id)
  WHERE status = 'ACTIVE';

CREATE FUNCTION onboard_warning_codes_are_valid(candidate JSONB)
RETURNS BOOLEAN
LANGUAGE SQL
IMMUTABLE
STRICT
PARALLEL SAFE
AS $$
  SELECT CASE
    WHEN jsonb_typeof(candidate) <> 'array' THEN FALSE
    WHEN jsonb_array_length(candidate) > 32 THEN FALSE
    ELSE NOT EXISTS (
      SELECT 1
      FROM jsonb_array_elements(candidate) AS warning_code
      WHERE jsonb_typeof(warning_code) <> 'string'
         OR char_length(warning_code #>> '{}') NOT BETWEEN 1 AND 80
    )
  END
$$;

CREATE TABLE onboard_system_runtime_state (
  onboard_system_id UUID PRIMARY KEY REFERENCES onboard_systems(id),
  active_location_terminal_id UUID REFERENCES jt_terminals(id),
  primary_recovery_streak INTEGER NOT NULL DEFAULT 0,
  primary_eligible BOOLEAN NOT NULL DEFAULT TRUE,
  last_primary_valid_at TIMESTAMPTZ,
  last_location_switch_at TIMESTAMPTZ,
  warning_codes JSONB NOT NULL DEFAULT '[]'::jsonb,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  runtime_version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT ck_onboard_system_runtime_state_primary_recovery_streak
    CHECK (primary_recovery_streak >= 0),
  CONSTRAINT ck_onboard_system_runtime_state_warning_codes
    CHECK (jsonb_typeof(warning_codes) = 'array'),
  CONSTRAINT ck_onboard_system_runtime_state_warning_codes_content
    CHECK (onboard_warning_codes_are_valid(warning_codes))
);

CREATE TABLE onboard_device_memberships (
  id UUID PRIMARY KEY,
  onboard_system_id UUID NOT NULL REFERENCES onboard_systems(id),
  terminal_id UUID NOT NULL REFERENCES jt_terminals(id),
  network_mode VARCHAR(30) NOT NULL,
  status VARCHAR(20) NOT NULL,
  valid_from TIMESTAMPTZ NOT NULL,
  valid_to TIMESTAMPTZ,
  added_reason VARCHAR(500) NOT NULL,
  removed_reason VARCHAR(500),
  added_by UUID REFERENCES user_accounts(id),
  removed_by UUID REFERENCES user_accounts(id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT ck_onboard_device_memberships_network_mode
    CHECK (network_mode IN ('DIRECT_CELLULAR', 'SHARED_LAN_CLIENT')),
  CONSTRAINT ck_onboard_device_memberships_status
    CHECK (status IN ('ACTIVE', 'REMOVED')),
  CONSTRAINT ck_onboard_device_memberships_time_range
    CHECK (valid_to IS NULL OR valid_to >= valid_from),
  CONSTRAINT ck_onboard_device_memberships_lifecycle
    CHECK ((status = 'ACTIVE' AND valid_to IS NULL)
      OR (status = 'REMOVED' AND valid_to IS NOT NULL))
);

CREATE UNIQUE INDEX uq_onboard_device_memberships_active_terminal
  ON onboard_device_memberships(terminal_id)
  WHERE status = 'ACTIVE' AND valid_to IS NULL;

CREATE INDEX idx_onboard_device_memberships_system_history
  ON onboard_device_memberships(onboard_system_id, valid_from DESC);

CREATE TABLE onboard_device_capabilities (
  id UUID PRIMARY KEY,
  terminal_id UUID NOT NULL REFERENCES jt_terminals(id),
  capability VARCHAR(30) NOT NULL,
  status VARCHAR(20) NOT NULL,
  evidence_ref VARCHAR(500),
  verified_at TIMESTAMPTZ,
  verified_by UUID REFERENCES user_accounts(id),
  reason VARCHAR(500) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT ck_onboard_device_capabilities_capability
    CHECK (capability IN (
      'JT808_LOCATION', 'GBT28787_DISPATCH', 'VENDOR_DISPATCH',
      'ADAS', 'DMS', 'VIDEO', 'JT1078_MEDIA')),
  CONSTRAINT ck_onboard_device_capabilities_status
    CHECK (status IN ('DECLARED', 'VERIFIED', 'DISABLED')),
  CONSTRAINT ck_onboard_device_capabilities_verification
    CHECK (status <> 'VERIFIED'
      OR (evidence_ref IS NOT NULL AND verified_at IS NOT NULL AND verified_by IS NOT NULL))
);

CREATE UNIQUE INDEX uq_onboard_device_capabilities_active_terminal_capability
  ON onboard_device_capabilities(terminal_id, capability)
  WHERE status IN ('DECLARED', 'VERIFIED');

CREATE INDEX idx_onboard_device_capabilities_terminal
  ON onboard_device_capabilities(terminal_id, created_at DESC);

CREATE TABLE onboard_device_protocol_profiles (
  id UUID PRIMARY KEY,
  terminal_id UUID NOT NULL REFERENCES jt_terminals(id),
  transport_profile VARCHAR(30) NOT NULL,
  business_profile VARCHAR(30) NOT NULL,
  safety_profile VARCHAR(30) NOT NULL,
  media_profile VARCHAR(30) NOT NULL,
  active_position_interval_seconds INTEGER NOT NULL,
  idle_position_interval_seconds INTEGER NOT NULL,
  status VARCHAR(20) NOT NULL,
  valid_from TIMESTAMPTZ NOT NULL,
  valid_to TIMESTAMPTZ,
  reason VARCHAR(500) NOT NULL,
  actor_id UUID REFERENCES user_accounts(id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT ck_onboard_device_protocol_profiles_transport
    CHECK (transport_profile IN ('JT808_2019', 'JT808_2013')),
  CONSTRAINT ck_onboard_device_protocol_profiles_business
    CHECK (business_profile IN ('GBT28787_2023', 'VENDOR_DISPATCH', 'NONE')),
  CONSTRAINT ck_onboard_device_protocol_profiles_safety
    CHECK (safety_profile IN ('GBT28787_2023', 'JSATL12_2017', 'NONE')),
  CONSTRAINT ck_onboard_device_protocol_profiles_media
    CHECK (media_profile IN ('JT1078_2016', 'NONE')),
  CONSTRAINT ck_onboard_device_protocol_profiles_status
    CHECK (status IN ('ACTIVE', 'SUPERSEDED')),
  CONSTRAINT ck_onboard_device_protocol_profiles_positive_intervals
    CHECK (active_position_interval_seconds > 0 AND idle_position_interval_seconds > 0),
  CONSTRAINT ck_onboard_device_protocol_profiles_time_range
    CHECK (valid_to IS NULL OR valid_to >= valid_from),
  CONSTRAINT ck_onboard_device_protocol_profiles_lifecycle
    CHECK ((status = 'ACTIVE' AND valid_to IS NULL)
      OR (status = 'SUPERSEDED' AND valid_to IS NOT NULL))
);

CREATE UNIQUE INDEX uq_onboard_device_protocol_profiles_active_terminal
  ON onboard_device_protocol_profiles(terminal_id)
  WHERE status = 'ACTIVE' AND valid_to IS NULL;

CREATE INDEX idx_onboard_device_protocol_profiles_terminal_history
  ON onboard_device_protocol_profiles(terminal_id, valid_from DESC);

CREATE TABLE onboard_device_role_assignments (
  id UUID PRIMARY KEY,
  onboard_system_id UUID NOT NULL REFERENCES onboard_systems(id),
  terminal_id UUID NOT NULL REFERENCES jt_terminals(id),
  role VARCHAR(30) NOT NULL,
  status VARCHAR(20) NOT NULL,
  valid_from TIMESTAMPTZ NOT NULL,
  valid_to TIMESTAMPTZ,
  assigned_reason VARCHAR(500) NOT NULL,
  revoked_reason VARCHAR(500),
  assigned_by UUID REFERENCES user_accounts(id),
  revoked_by UUID REFERENCES user_accounts(id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT ck_onboard_device_role_assignments_role
    CHECK (role IN (
      'DISPATCH', 'LOCATION_PRIMARY', 'LOCATION_BACKUP',
      'ACTIVE_SAFETY', 'VIDEO', 'WAN_UPLINK')),
  CONSTRAINT ck_onboard_device_role_assignments_status
    CHECK (status IN ('ACTIVE', 'REVOKED')),
  CONSTRAINT ck_onboard_device_role_assignments_time_range
    CHECK (valid_to IS NULL OR valid_to >= valid_from),
  CONSTRAINT ck_onboard_device_role_assignments_lifecycle
    CHECK ((status = 'ACTIVE' AND valid_to IS NULL)
      OR (status = 'REVOKED' AND valid_to IS NOT NULL))
);

CREATE UNIQUE INDEX uq_onboard_device_role_assignments_active_system_role
  ON onboard_device_role_assignments(onboard_system_id, role)
  WHERE status = 'ACTIVE' AND valid_to IS NULL;

CREATE INDEX idx_onboard_device_role_assignments_terminal_history
  ON onboard_device_role_assignments(terminal_id, valid_from DESC);

ALTER TABLE vehicle_location_events
  ADD COLUMN onboard_system_id UUID REFERENCES onboard_systems(id),
  ADD COLUMN source_role VARCHAR(30),
  ADD CONSTRAINT ck_vehicle_location_events_source_role
    CHECK (source_role IS NULL OR source_role IN ('LOCATION_PRIMARY', 'LOCATION_BACKUP'));

ALTER TABLE vehicles
  ADD COLUMN current_location_onboard_system_id UUID REFERENCES onboard_systems(id);

INSERT INTO onboard_systems (
  id, vehicle_id, status, operating_mode, created_by, updated_by,
  created_at, updated_at, version
)
SELECT
  gen_random_uuid(), binding.vehicle_id, 'ACTIVE',
  CASE WHEN vehicle.dispatchable THEN 'DISPATCH_SERVICE' ELSE 'SAFETY_MONITOR_ONLY' END,
  binding.bound_by, binding.bound_by,
  binding.created_at, binding.updated_at, 0
FROM jt_terminal_vehicle_bindings binding
JOIN vehicles vehicle ON vehicle.id = binding.vehicle_id
WHERE binding.status = 'ACTIVE' AND binding.valid_to IS NULL;

INSERT INTO onboard_system_runtime_state (
  onboard_system_id, active_location_terminal_id, primary_recovery_streak,
  primary_eligible, warning_codes, updated_at, runtime_version
)
SELECT system.id, NULL, 0, TRUE, '[]'::jsonb, system.updated_at, 0
FROM onboard_systems system;

INSERT INTO onboard_device_memberships (
  id, onboard_system_id, terminal_id, network_mode, status,
  valid_from, valid_to, added_reason, removed_reason,
  added_by, removed_by, created_at, updated_at, version
)
SELECT
  gen_random_uuid(), system.id, binding.terminal_id, 'DIRECT_CELLULAR', 'ACTIVE',
  binding.valid_from, NULL, 'Legacy active terminal binding V19 backfill', NULL,
  binding.bound_by, NULL, binding.created_at, binding.updated_at, 0
FROM jt_terminal_vehicle_bindings binding
JOIN onboard_systems system ON system.vehicle_id = binding.vehicle_id
WHERE binding.status = 'ACTIVE' AND binding.valid_to IS NULL;
