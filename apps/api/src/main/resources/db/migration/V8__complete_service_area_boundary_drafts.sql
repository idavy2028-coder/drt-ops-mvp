ALTER TABLE service_areas
    ADD COLUMN draft_boundary geography(POLYGON, 4326),
    ADD COLUMN draft_boundary_source VARCHAR(40),
    ADD COLUMN draft_boundary_version INTEGER NOT NULL DEFAULT 0 CHECK (draft_boundary_version >= 0),
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE service_areas ALTER COLUMN boundary DROP NOT NULL;

UPDATE service_areas
   SET published_at = created_at,
       boundary_version = CASE WHEN boundary_version = 0 THEN 1 ELSE boundary_version END,
       coordinate_system = 'GCJ02'
 WHERE boundary IS NOT NULL;
