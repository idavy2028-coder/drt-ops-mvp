ALTER TABLE virtual_stops
  ADD COLUMN address VARCHAR(300),
  ADD COLUMN area_name VARCHAR(120),
  ADD COLUMN coordinate_system VARCHAR(20) NOT NULL DEFAULT 'GCJ-02',
  ADD COLUMN source VARCHAR(40) NOT NULL DEFAULT 'LEGACY',
  ADD COLUMN verified_at TIMESTAMPTZ,
  ADD COLUMN verified_by UUID,
  ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

CREATE INDEX idx_virtual_stops_area_enabled ON virtual_stops(service_area_id, enabled);
