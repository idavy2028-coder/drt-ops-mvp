CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE dispatch_rule_sets (
  id UUID PRIMARY KEY,
  name VARCHAR(100) NOT NULL,
  max_wait_minutes INTEGER NOT NULL CHECK (max_wait_minutes > 0),
  max_detour_minutes INTEGER NOT NULL CHECK (max_detour_minutes >= 0),
  booking_window_minutes INTEGER NOT NULL CHECK (booking_window_minutes > 0),
  auto_dispatch_score_threshold NUMERIC(5,2) NOT NULL,
  manual_review_score_threshold NUMERIC(5,2) NOT NULL,
  wait_weight NUMERIC(5,2) NOT NULL,
  detour_weight NUMERIC(5,2) NOT NULL,
  stability_weight NUMERIC(5,2) NOT NULL,
  utilization_weight NUMERIC(5,2) NOT NULL,
  insertion_policy VARCHAR(40) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE service_areas (
  id UUID PRIMARY KEY,
  name VARCHAR(100) NOT NULL,
  boundary geography(POLYGON, 4326) NOT NULL,
  service_start TIME NOT NULL,
  service_end TIME NOT NULL,
  rule_set_id UUID NOT NULL REFERENCES dispatch_rule_sets(id),
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE virtual_stops (
  id UUID PRIMARY KEY,
  service_area_id UUID NOT NULL REFERENCES service_areas(id),
  name VARCHAR(120) NOT NULL,
  location geography(POINT, 4326) NOT NULL,
  service_radius_meters INTEGER NOT NULL CHECK (service_radius_meters > 0),
  boarding_enabled BOOLEAN NOT NULL DEFAULT TRUE,
  alighting_enabled BOOLEAN NOT NULL DEFAULT TRUE,
  safety_note VARCHAR(300) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_virtual_stops_location ON virtual_stops USING GIST (location);
CREATE INDEX idx_service_areas_boundary ON service_areas USING GIST (boundary);

CREATE TABLE vehicles (
  id UUID PRIMARY KEY,
  plate_number VARCHAR(30) NOT NULL UNIQUE,
  vehicle_type VARCHAR(60) NOT NULL,
  capacity INTEGER NOT NULL CHECK (capacity > 0),
  current_status VARCHAR(40) NOT NULL,
  current_location geography(POINT, 4326),
  fleet_name VARCHAR(100) NOT NULL,
  dispatchable BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_vehicles_current_location ON vehicles USING GIST (current_location);

CREATE TABLE drivers (
  id UUID PRIMARY KEY,
  name VARCHAR(80) NOT NULL,
  phone VARCHAR(30) NOT NULL UNIQUE,
  qualification_status VARCHAR(40) NOT NULL,
  shift_start TIMESTAMPTZ,
  shift_end TIMESTAMPTZ,
  current_status VARCHAR(40) NOT NULL,
  fleet_name VARCHAR(100) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE ride_orders (
  id UUID PRIMARY KEY,
  passenger_name VARCHAR(80) NOT NULL,
  passenger_phone VARCHAR(30) NOT NULL,
  passenger_count INTEGER NOT NULL CHECK (passenger_count > 0),
  request_type VARCHAR(40) NOT NULL,
  origin_lng NUMERIC(10,7) NOT NULL,
  origin_lat NUMERIC(10,7) NOT NULL,
  destination_lng NUMERIC(10,7) NOT NULL,
  destination_lat NUMERIC(10,7) NOT NULL,
  boarding_stop_id UUID REFERENCES virtual_stops(id),
  alighting_stop_id UUID REFERENCES virtual_stops(id),
  requested_departure_at TIMESTAMPTZ NOT NULL,
  estimated_boarding_at TIMESTAMPTZ,
  estimated_arrival_at TIMESTAMPTZ,
  status VARCHAR(40) NOT NULL,
  failure_reason VARCHAR(300),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE vehicle_tasks (
  id UUID PRIMARY KEY,
  vehicle_id UUID NOT NULL REFERENCES vehicles(id),
  driver_id UUID NOT NULL REFERENCES drivers(id),
  status VARCHAR(40) NOT NULL,
  planned_start_at TIMESTAMPTZ NOT NULL,
  planned_end_at TIMESTAMPTZ,
  current_stop_id UUID,
  source_type VARCHAR(40) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE task_stops (
  id UUID PRIMARY KEY,
  vehicle_task_id UUID NOT NULL REFERENCES vehicle_tasks(id) ON DELETE CASCADE,
  virtual_stop_id UUID NOT NULL REFERENCES virtual_stops(id),
  ride_order_id UUID REFERENCES ride_orders(id),
  sequence_number INTEGER NOT NULL CHECK (sequence_number > 0),
  stop_type VARCHAR(40) NOT NULL,
  planned_arrival_at TIMESTAMPTZ NOT NULL,
  actual_arrival_at TIMESTAMPTZ,
  status VARCHAR(40) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(vehicle_task_id, sequence_number)
);

CREATE TABLE dispatch_decisions (
  id UUID PRIMARY KEY,
  ride_order_id UUID NOT NULL REFERENCES ride_orders(id),
  decision_result VARCHAR(40) NOT NULL,
  candidate_count INTEGER NOT NULL CHECK (candidate_count >= 0),
  best_vehicle_id UUID REFERENCES vehicles(id),
  best_task_id UUID REFERENCES vehicle_tasks(id),
  score NUMERIC(6,2),
  estimated_wait_minutes INTEGER,
  estimated_detour_minutes INTEGER,
  rejected_reasons_json JSONB NOT NULL DEFAULT '[]'::jsonb,
  explanation_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  algorithm_version VARCHAR(40) NOT NULL,
  actor_type VARCHAR(40) NOT NULL,
  actor_id VARCHAR(80) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_dispatch_decisions_order ON dispatch_decisions(ride_order_id);

CREATE TABLE audit_logs (
  id UUID PRIMARY KEY,
  entity_type VARCHAR(60) NOT NULL,
  entity_id UUID NOT NULL,
  action VARCHAR(80) NOT NULL,
  actor_type VARCHAR(40) NOT NULL,
  actor_id VARCHAR(80) NOT NULL,
  reason VARCHAR(300),
  metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_logs_entity ON audit_logs(entity_type, entity_id);
CREATE INDEX idx_audit_logs_created_at ON audit_logs(created_at);
