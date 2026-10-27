-- server 自有表（M4b）：delivery 幂等登记、报告签发审计、迁移版本。
-- 手写幂等 DDL（ADR-0008），由 ServerSchemaMigrator 启动时执行；注释中不得出现分号。

CREATE TABLE IF NOT EXISTS webhook_delivery (
  delivery_id TEXT PRIMARY KEY,
  repo TEXT NOT NULL,
  pr_number INTEGER NOT NULL,
  head_sha TEXT,
  event_action TEXT NOT NULL,
  status TEXT NOT NULL,
  run_id TEXT,
  detail TEXT,
  received_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_webhook_delivery_status
  ON webhook_delivery (status);

CREATE TABLE IF NOT EXISTS report_attestation (
  attestation_id TEXT PRIMARY KEY,
  report_sha256 TEXT NOT NULL,
  repo TEXT NOT NULL DEFAULT '',
  commit_sha TEXT,
  tier TEXT,
  key_id TEXT NOT NULL,
  issued_at TIMESTAMPTZ NOT NULL,
  requested_by TEXT NOT NULL,
  signature TEXT NOT NULL
);

ALTER TABLE report_attestation ADD COLUMN IF NOT EXISTS repo TEXT NOT NULL DEFAULT '';

CREATE INDEX IF NOT EXISTS idx_report_attestation_commit
  ON report_attestation (commit_sha);

CREATE INDEX IF NOT EXISTS idx_report_attestation_repo
  ON report_attestation (repo, commit_sha);

CREATE TABLE IF NOT EXISTS schema_migration (
  version TEXT PRIMARY KEY,
  applied_at TIMESTAMPTZ NOT NULL
);
