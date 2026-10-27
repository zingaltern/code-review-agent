package org.cra.server.attest;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.cra.common.exception.CraLedgerException;

/**
 * PostgreSQL 已知 commit 检查（review_run 完成标记，DONE|DEGRADED，02§4.1）。
 *
 * <p>按 (repo, commit) 判定：报告必须携带 repo，避免多租户下 A 仓库的 commit 签名被 B 仓库复用（2026-10-27 owner 决策 4A）。
 */
public final class PostgresKnownCommitChecker implements KnownCommitChecker {

  private final DataSource dataSource;

  /**
   * @param dataSource 连接池
   */
  public PostgresKnownCommitChecker(DataSource dataSource) {
    if (dataSource == null) {
      throw new IllegalArgumentException("dataSource 不能为空");
    }
    this.dataSource = dataSource;
  }

  @Override
  public boolean exists(String repo, String commitSha) {
    if (repo == null || repo.isBlank() || commitSha == null || commitSha.isBlank()) {
      return false;
    }
    String sql =
        "SELECT 1 FROM review_run WHERE repo = ? AND UPPER(commit_sha) = UPPER(?)"
            + " AND status IN ('DONE','DEGRADED') LIMIT 1";
    try (Connection conn = dataSource.getConnection();
        PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, repo.strip());
      ps.setString(2, commitSha.strip());
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next();
      }
    } catch (SQLException e) {
      throw new CraLedgerException("已知 commit 检查失败: " + commitSha, e);
    }
  }
}
