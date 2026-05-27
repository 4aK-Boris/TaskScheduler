-- Per-queue in-flight breakdown for the workers dashboard. The legacy
-- in_flight_count column stays as the sum (cheaper for ORDER BY / quick filtering);
-- this map adds the per-queue split that lets ops see "node X is saturated on the
-- heavy queue but idle on default".
--
-- JSONB so we can index on specific queues later (`(in_flight_by_queue->>'heavy')`)
-- if a per-queue ORDER BY becomes useful.
ALTER TABLE worker
    ADD COLUMN in_flight_by_queue JSONB NOT NULL DEFAULT '{}'::jsonb;
