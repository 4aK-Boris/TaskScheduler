-- Rollup edges: parent watches the aggregate progress of a set of children.
-- Different from `job_dependency` (which gates child execution on parent completion) —
-- a rollup is observational, not blocking. A job can be both a blocker AND a rollup
-- target for the same children; the two relationships are stored independently so the
-- distinct semantics stay clear.
--
-- See DESIGN.md "DAG progress propagation — variant 3".
CREATE TABLE job_rollup (
    parent_id   UUID  NOT NULL REFERENCES job(id) ON DELETE CASCADE,
    child_id    UUID  NOT NULL REFERENCES job(id) ON DELETE CASCADE,
    PRIMARY KEY (parent_id, child_id)
);

-- Reverse lookup: "who's rolling me up?" — used by the propagation hook after a child's
-- progress or terminal transition to find which parents need re-aggregation.
CREATE INDEX job_rollup_child_id_idx ON job_rollup (child_id);
