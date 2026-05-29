-- Payload-type schema fingerprints for drift detection (DESIGN.md 22.9).
--
-- A worker records the serialization-schema hash of each payload type it handles at
-- startup. When the hash for a type changes between deploys, in-flight jobs of that type
-- (serialized with the OLD schema) may no longer deserialize — so the worker fires a
-- schema-drift alert. One row per payload type; the hash is the latest seen.
CREATE TABLE payload_schema (
    payload_type   TEXT PRIMARY KEY,
    schema_hash    TEXT        NOT NULL,
    first_seen_at  TIMESTAMPTZ NOT NULL,
    updated_at     TIMESTAMPTZ NOT NULL
);
