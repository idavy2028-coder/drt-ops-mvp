ALTER TABLE service_areas
    ADD COLUMN boundary_source VARCHAR(40) NOT NULL DEFAULT 'LEGACY',
    ADD COLUMN boundary_version INTEGER NOT NULL DEFAULT 0 CHECK (boundary_version >= 0),
    ADD COLUMN published_at TIMESTAMPTZ,
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN coordinate_system VARCHAR(20) NOT NULL DEFAULT 'GCJ02';

CREATE INDEX idx_service_areas_published_boundary
    ON service_areas USING GIST (boundary)
    WHERE enabled AND published_at IS NOT NULL;
