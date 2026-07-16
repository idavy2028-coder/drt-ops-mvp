CREATE TABLE vehicle_location_events (
  id UUID PRIMARY KEY,
  vehicle_id UUID NOT NULL REFERENCES vehicles(id),
  vehicle_task_id UUID REFERENCES vehicle_tasks(id),
  task_stop_id UUID REFERENCES task_stops(id),
  virtual_stop_id UUID REFERENCES virtual_stops(id),
  event_type VARCHAR(40) NOT NULL,
  source VARCHAR(40) NOT NULL CHECK (source IN ('MANUAL_DISPATCHER', 'GPS_DEVICE')),
  location geography(POINT, 4326) NOT NULL,
  longitude NUMERIC(10,7) NOT NULL CHECK (longitude BETWEEN -180 AND 180),
  latitude NUMERIC(10,7) NOT NULL CHECK (latitude BETWEEN -90 AND 90),
  coordinate_system VARCHAR(20) NOT NULL CHECK (coordinate_system = 'GCJ02'),
  standardized_address VARCHAR(300) NOT NULL,
  driver_reported_at TIMESTAMPTZ NOT NULL,
  recorded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  recorded_by UUID NOT NULL REFERENCES user_accounts(id),
  note VARCHAR(500),
  correction_reason VARCHAR(500),
  corrects_event_id UUID REFERENCES vehicle_location_events(id),
  idempotency_key UUID NOT NULL UNIQUE,
  request_fingerprint VARCHAR(64) NOT NULL,
  snapshot_applied BOOLEAN NOT NULL,
  outside_service_area BOOLEAN NOT NULL
);

CREATE INDEX idx_vehicle_location_vehicle_time
  ON vehicle_location_events(vehicle_id, driver_reported_at DESC);
CREATE INDEX idx_vehicle_location_task_time
  ON vehicle_location_events(vehicle_task_id, driver_reported_at ASC);
CREATE INDEX idx_vehicle_location_recorded_at
  ON vehicle_location_events(recorded_at DESC);
CREATE INDEX idx_vehicle_location_point
  ON vehicle_location_events USING GIST(location);

CREATE FUNCTION prevent_vehicle_location_event_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  RAISE EXCEPTION 'vehicle location events are immutable';
END;
$$;

CREATE TRIGGER prevent_vehicle_location_event_mutation
BEFORE UPDATE OR DELETE ON vehicle_location_events
FOR EACH ROW EXECUTE FUNCTION prevent_vehicle_location_event_mutation();

ALTER TABLE vehicles
  ADD COLUMN current_location_address VARCHAR(300),
  ADD COLUMN current_location_source VARCHAR(40) CHECK (current_location_source IN ('MANUAL_DISPATCHER', 'GPS_DEVICE')),
  ADD COLUMN current_location_coordinate_system VARCHAR(20) CHECK (current_location_coordinate_system = 'GCJ02'),
  ADD COLUMN current_location_reported_at TIMESTAMPTZ,
  ADD COLUMN current_location_recorded_at TIMESTAMPTZ,
  ADD COLUMN current_location_event_id UUID REFERENCES vehicle_location_events(id),
  ADD COLUMN current_location_task_id UUID REFERENCES vehicle_tasks(id);
