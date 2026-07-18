ALTER TABLE dispatch_decisions
  ADD COLUMN map_provider VARCHAR(40),
  ADD COLUMN map_degraded BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN map_degraded_reason VARCHAR(100),
  ADD COLUMN vehicle_to_pickup_distance_meters INTEGER,
  ADD COLUMN vehicle_to_pickup_duration_seconds INTEGER,
  ADD COLUMN pickup_to_destination_distance_meters INTEGER,
  ADD COLUMN pickup_to_destination_duration_seconds INTEGER;
