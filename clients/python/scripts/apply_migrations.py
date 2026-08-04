#!/usr/bin/env python
"""Apply the project's Flyway migrations to a database, without needing a JVM.

`scheduler-infra` owns the schema in production — this is a shortcut for development and
CI, where standing up the Kotlin process (a full Gradle build, Wasm dashboard and all) just
to create tables is a poor trade.

It runs `storage-postgres/src/main/resources/scheduler/migration/V*.sql` in order and writes
matching `flyway_schema_history` rows, because the Python client refuses to start against a
schema it cannot verify. Re-running is safe: versions already recorded are skipped.

    python scripts/apply_migrations.py postgresql://scheduler:scheduler@localhost:5432/scheduler

The checksums are left NULL, so a real Flyway run against the same database would flag a
validation mismatch. That is fine for a throwaway CI database and wrong for anything you
care about — do not point this at a deployment.
"""

from __future__ import annotations

import argparse
import pathlib
import sys

import psycopg

#: clients/python/scripts/apply_migrations.py -> repo root
REPO_ROOT = pathlib.Path(__file__).resolve().parents[3]
MIGRATIONS = REPO_ROOT / "storage-postgres/src/main/resources/scheduler/migration"

_HISTORY_DDL = """
CREATE TABLE IF NOT EXISTS flyway_schema_history (
    installed_rank INT NOT NULL PRIMARY KEY,
    version VARCHAR(50),
    description VARCHAR(200) NOT NULL,
    type VARCHAR(20) NOT NULL,
    script VARCHAR(1000) NOT NULL,
    checksum INTEGER,
    installed_by VARCHAR(100) NOT NULL,
    installed_on TIMESTAMP NOT NULL DEFAULT now(),
    execution_time INTEGER NOT NULL,
    success BOOLEAN NOT NULL
)
"""


def _version_of(path: pathlib.Path) -> int:
    return int(path.name.split("__")[0][1:])


def _description_of(path: pathlib.Path) -> str:
    return path.name.split("__")[1][: -len(".sql")].replace("_", " ")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("dsn", help="libpq connection string of the target database")
    args = parser.parse_args()

    if not MIGRATIONS.is_dir():
        print(f"migration directory not found: {MIGRATIONS}", file=sys.stderr)
        return 1

    migrations = sorted(MIGRATIONS.glob("V*.sql"), key=_version_of)
    if not migrations:
        print(f"no migrations found in {MIGRATIONS}", file=sys.stderr)
        return 1

    with psycopg.connect(args.dsn, autocommit=True) as conn:
        conn.execute(_HISTORY_DDL)
        for rank, path in enumerate(migrations, start=1):
            version = str(_version_of(path))
            cur = conn.execute(
                "SELECT 1 FROM flyway_schema_history WHERE version = %s AND success", (version,)
            )
            if cur.fetchone():
                print(f"  skip    V{version} ({path.name})")
                continue
            conn.execute(path.read_text(encoding="utf-8"))
            conn.execute(
                """
                INSERT INTO flyway_schema_history
                    (installed_rank, version, description, type, script,
                     installed_by, execution_time, success)
                VALUES (%s, %s, %s, 'SQL', %s, 'apply_migrations.py', 0, TRUE)
                """,
                (rank, version, _description_of(path), path.name),
            )
            print(f"  applied V{version} ({path.name})")

        cur = conn.execute(
            """
            SELECT max(version::int) FROM flyway_schema_history
            WHERE success AND version ~ '^[0-9]+$'
            """
        )
        row = cur.fetchone()
        print(f"schema is at V{row[0] if row else 0}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
