-- Foundation schema v1. This idempotent file initializes only; future schema changes need numbered migrations.
CREATE TABLE IF NOT EXISTS app_config (
  id INTEGER PRIMARY KEY CHECK (id = 1),
  version INTEGER NOT NULL,
  content_json TEXT NOT NULL,
  updated_at TEXT NOT NULL
);
