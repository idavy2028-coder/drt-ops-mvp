ALTER TABLE vehicle_location_events
  ALTER COLUMN standardized_address DROP NOT NULL,
  ALTER COLUMN recorded_by DROP NOT NULL,
  ADD COLUMN terminal_id UUID REFERENCES jt_terminals(id),
  ADD COLUMN protocol_version VARCHAR(40),
  ADD COLUMN message_serial_no INTEGER,
  ADD COLUMN raw_longitude NUMERIC(10,7) CHECK (raw_longitude BETWEEN -180 AND 180),
  ADD COLUMN raw_latitude NUMERIC(10,7) CHECK (raw_latitude BETWEEN -90 AND 90),
  ADD COLUMN raw_coordinate_system VARCHAR(20) CHECK (raw_coordinate_system IN ('GCJ02', 'WGS84')),
  ADD COLUMN gateway_received_at TIMESTAMPTZ,
  ADD COLUMN payload_digest CHAR(64) CHECK (payload_digest IS NULL OR payload_digest ~ '^[0-9a-f]{64}$'),
  ADD COLUMN speed_kph NUMERIC(6,2) CHECK (speed_kph >= 0),
  ADD COLUMN direction_degrees INTEGER CHECK (direction_degrees BETWEEN 0 AND 359),
  ADD COLUMN altitude_meters NUMERIC(8,2),
  ADD COLUMN satellite_count INTEGER CHECK (satellite_count >= 0),
  ADD COLUMN alarm_bits BIGINT CHECK (alarm_bits >= 0),
  ADD COLUMN status_bits BIGINT CHECK (status_bits >= 0),
  ADD COLUMN coordinate_transform_version VARCHAR(80) NOT NULL DEFAULT 'LEGACY_NONE',
  ADD COLUMN quality_status VARCHAR(20) NOT NULL DEFAULT 'GOOD'
    CHECK (quality_status IN ('GOOD', 'WARNING', 'QUARANTINED', 'REJECTED')),
  ADD COLUMN quality_reasons JSONB NOT NULL DEFAULT '[]'::jsonb;

ALTER TABLE vehicle_location_events DISABLE TRIGGER prevent_vehicle_location_event_mutation;

UPDATE vehicle_location_events
SET raw_longitude = longitude,
    raw_latitude = latitude,
    raw_coordinate_system = coordinate_system,
    coordinate_transform_version = 'LEGACY_NONE',
    quality_status = 'GOOD',
    quality_reasons = '[]'::jsonb
WHERE source = 'MANUAL_DISPATCHER';

ALTER TABLE vehicle_location_events ENABLE TRIGGER prevent_vehicle_location_event_mutation;

ALTER TABLE vehicle_location_events
  ADD CONSTRAINT vehicle_location_events_source_actor_terminal_check
  CHECK (
    (source = 'MANUAL_DISPATCHER'
      AND recorded_by IS NOT NULL
      AND terminal_id IS NULL
      AND standardized_address IS NOT NULL)
    OR (source = 'GPS_DEVICE'
      AND terminal_id IS NOT NULL
      AND protocol_version IS NOT NULL
      AND message_serial_no IS NOT NULL
      AND raw_longitude IS NOT NULL
      AND raw_latitude IS NOT NULL
      AND raw_coordinate_system IS NOT NULL
      AND gateway_received_at IS NOT NULL
      AND payload_digest IS NOT NULL
      AND coordinate_transform_version IS NOT NULL
      AND quality_status IS NOT NULL)
  );

CREATE INDEX idx_vehicle_location_events_terminal_time
  ON vehicle_location_events(terminal_id, driver_reported_at DESC)
  WHERE terminal_id IS NOT NULL;
CREATE INDEX idx_vehicle_location_events_quality_vehicle_time
  ON vehicle_location_events(vehicle_id, quality_status, driver_reported_at DESC);
CREATE INDEX idx_vehicle_location_events_reported_at_brin
  ON vehicle_location_events USING BRIN(driver_reported_at);

ALTER TABLE vehicles
  ADD COLUMN current_location_terminal_id UUID REFERENCES jt_terminals(id),
  ADD COLUMN current_location_quality_status VARCHAR(20) NOT NULL DEFAULT 'GOOD'
    CHECK (current_location_quality_status IN ('GOOD', 'WARNING', 'QUARANTINED', 'REJECTED')),
  ADD COLUMN current_location_quality_reasons JSONB NOT NULL DEFAULT '[]'::jsonb,
  ADD COLUMN current_location_gateway_received_at TIMESTAMPTZ,
  ADD COLUMN current_location_speed_kph NUMERIC(6,2) CHECK (current_location_speed_kph >= 0),
  ADD COLUMN current_location_direction_degrees INTEGER CHECK (current_location_direction_degrees BETWEEN 0 AND 359),
  ADD COLUMN current_location_stale BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE vehicles
SET current_location_quality_status = 'GOOD',
    current_location_quality_reasons = '[]'::jsonb
WHERE current_location_source = 'MANUAL_DISPATCHER';

CREATE TABLE jt_gateway_ingress_receipts (
  idempotency_key UUID PRIMARY KEY,
  final_status VARCHAR(20) NOT NULL
    CHECK (final_status IN ('PROCESSING', 'ACCEPTED', 'REJECTED')),
  reason_codes JSONB NOT NULL DEFAULT '[]'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  completed_at TIMESTAMPTZ
);
