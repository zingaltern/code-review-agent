package org.cra.server.attest;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.cra.server.store.ServerSchemaMigrator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * PG 已知 commit 检查契约（ADR-0009 决策 2：按 {@code (repo, commit)} 判定；08§3.1：CI service 容器经 {@code
 * CRA_TEST_PG_URL}，本地无 PG 时跳过并给出原因）。
 *
 * <p>覆盖：repo 维度隔离（A 仓登记的 commit 对 B 仓不可见）、commit 大小写不敏感、DEGRADED 同 DONE 计入、 空参/未知 commit 拒绝。
 */
@EnabledIfEnvironmentVariable(named = "CRA_TEST_PG_URL", matches = ".+")
class PostgresKnownCommitCheckerTest {

  private static JdbcTemplate jdbc;
  private static PostgresKnownCommitChecker checker;

  @BeforeAll
  static void migrate() {
    org.postgresql.ds.PGSimpleDataSource ds = new org.postgresql.ds.PGSimpleDataSource();
    ds.setUrl(System.getenv("CRA_TEST_PG_URL"));
    ds.setUser(System.getenv().getOrDefault("CRA_TEST_PG_USER", "cra"));
    ds.setPassword(System.getenv().getOrDefault("CRA_TEST_PG_PASSWORD", "cra"));
    DataSource dataSource = ds;
    jdbc = new JdbcTemplate(ds);
    new ServerSchemaMigrator(dataSource).migrate();
    checker = new PostgresKnownCommitChecker(dataSource);
  }

  @BeforeEach
  void clean() {
    jdbc.execute("TRUNCATE review_run");
  }

  @Test
  void knownCommitIsScopedByRepo() {
    insertRun("run-a-1", "owner/repo-a", 1, "AbC123", "DONE");
    insertRun("run-a-2", "owner/repo-a", 2, "DEADBEEF2", "DEGRADED");

    // 本仓命中：DONE/DEGRADED 均计入，commit 大小写不敏感
    assertThat(checker.exists("owner/repo-a", "AbC123")).isTrue();
    assertThat(checker.exists("owner/repo-a", "abc123")).isTrue();
    assertThat(checker.exists("owner/repo-a", "DEADBEEF2")).isTrue();

    // 跨仓隔离：B 仓查 A 仓已登记的 commit 不得命中（ADR-0009 的多租户串号防护）
    assertThat(checker.exists("owner/repo-b", "AbC123")).isFalse();
    assertThat(checker.exists("owner/repo-b", "deadbeef2")).isFalse();

    // 未知 commit / 空参拒绝
    assertThat(checker.exists("owner/repo-a", "unknown-commit")).isFalse();
    assertThat(checker.exists("", "AbC123")).isFalse();
    assertThat(checker.exists("   ", "AbC123")).isFalse();
    assertThat(checker.exists(null, "AbC123")).isFalse();
    assertThat(checker.exists("owner/repo-a", "")).isFalse();
    assertThat(checker.exists("owner/repo-a", null)).isFalse();
  }

  private void insertRun(String runId, String repo, int prNumber, String commitSha, String status) {
    jdbc.update(
        "INSERT INTO review_run (run_id, repo, pr_number, commit_sha, status, findings, suppressed,"
            + " created_at) VALUES (?, ?, ?, ?, ?, 0, 0, now())",
        runId,
        repo,
        prNumber,
        commitSha,
        status);
  }
}
