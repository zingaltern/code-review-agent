-- 05§1.5 五表的 PostgreSQL 形态（M4b 服务账本）。
-- 手写幂等 DDL（不使用 Flyway）：与本仓库 SQLite 形态的 IF NOT EXISTS 同思路，
-- server 启动/测试构造时执行；语句以分号分隔，注释中不得出现分号。

CREATE TABLE IF NOT EXISTS review_run (
  run_id TEXT NOT NULL,
  repo TEXT NOT NULL,
  pr_number INTEGER NOT NULL,
  commit_sha TEXT NOT NULL,
  status TEXT NOT NULL,
  findings INTEGER NOT NULL,
  suppressed INTEGER NOT NULL,
  summary_comment_id BIGINT,
  created_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (repo, pr_number, run_id)
);

CREATE INDEX IF NOT EXISTS idx_review_run_commit
  ON review_run (repo, pr_number, commit_sha);

CREATE TABLE IF NOT EXISTS finding (
  finding_id TEXT NOT NULL,
  repo TEXT NOT NULL,
  pr_number INTEGER NOT NULL,
  fingerprint TEXT NOT NULL,
  file TEXT NOT NULL,
  line INTEGER NOT NULL,
  category TEXT NOT NULL,
  run_id TEXT NOT NULL,
  commit_sha TEXT NOT NULL,
  comment_id BIGINT,
  created_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (repo, pr_number, finding_id)
);

CREATE INDEX IF NOT EXISTS idx_finding_fingerprint
  ON finding (repo, pr_number, fingerprint);

CREATE TABLE IF NOT EXISTS verdict (
  repo TEXT NOT NULL,
  pr_number INTEGER NOT NULL,
  finding_id TEXT NOT NULL,
  run_id TEXT NOT NULL,
  verdict TEXT NOT NULL,
  rationale TEXT,
  created_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (repo, pr_number, finding_id)
);

CREATE TABLE IF NOT EXISTS published_comment (
  repo TEXT NOT NULL,
  pr_number INTEGER NOT NULL,
  comment_id BIGINT NOT NULL,
  run_id TEXT NOT NULL,
  commit_sha TEXT NOT NULL,
  kind TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (repo, pr_number, comment_id)
);

CREATE TABLE IF NOT EXISTS prompt_template_version (
  repo TEXT NOT NULL,
  pr_number INTEGER NOT NULL,
  run_id TEXT NOT NULL,
  template_name TEXT NOT NULL,
  template_version TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (repo, pr_number, run_id, template_name)
);
