ALTER TABLE service_areas
    ADD COLUMN boundary_source VARCHAR(40) NOT NULL DEFAULT 'LEGACY',
    ADD COLUMN boundary_version INTEGER NOT NULL DEFAULT 0 CHECK (boundary_version >= 0),
    ADD COLUMN draft_boundary geography(POLYGON, 4326),
    ADD COLUMN draft_boundary_source VARCHAR(40),
    ADD COLUMN draft_boundary_version INTEGER NOT NULL DEFAULT 0 CHECK (draft_boundary_version >= 0),
    ADD COLUMN published_at TIMESTAMPTZ,
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN coordinate_system VARCHAR(20) NOT NULL DEFAULT 'GCJ02';

ALTER TABLE service_areas ALTER COLUMN boundary DROP NOT NULL;

UPDATE service_areas
   SET published_at = created_at,
       boundary_version = CASE WHEN boundary_version = 0 THEN 1 ELSE boundary_version END,
       coordinate_system = 'GCJ02'
 WHERE boundary IS NOT NULL;

CREATE INDEX idx_service_areas_published_boundary
    ON service_areas USING GIST (boundary)
    WHERE enabled AND published_at IS NOT NULL;
