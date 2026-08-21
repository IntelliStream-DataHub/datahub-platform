-- Geographic location for asset nodes.
--
-- Assets carry an optional GeoJSON geometry (Point, Polygon, …). The node table uses single-table
-- inheritance, so the column lives on `node` but is only ever written for asset rows (discriminator
-- node_type = 1); see AssetEntity. Nullable — most nodes have no geography.
--
-- Stored as jsonb (core Postgres, no extension) holding the raw GeoJSON verbatim, matching the
-- GeoLocation DTO's opaque-string carrier. When real geodata lands and PostGIS is provisioned per
-- tenant database, this migrates with NO wire-contract change:
--
--   CREATE EXTENSION IF NOT EXISTS postgis;   -- pre-provisioned out-of-band per tenant DB
--   ALTER TABLE node ADD COLUMN geo geography(Geometry, 4326);
--   UPDATE node SET geo = ST_SetSRID(ST_GeomFromGeoJSON(geo_location), 4326)::geography
--    WHERE geo_location IS NOT NULL;
--   CREATE INDEX node_geo_gix ON node USING GIST (geo);
--   ALTER TABLE node DROP COLUMN geo_location;

ALTER TABLE node ADD COLUMN IF NOT EXISTS geo_location jsonb;
