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
 * <p>当前按 commit 全局判定（不按 repo 限定）——单租户部署假设；多租户需在报告/端点增加 repo 绑定并收紧查询（见 docs/deployment-server.md
 * 已知限制）。
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
  public boolean exists(String commitSha) {
    if (commitSha == null || commitSha.isBlank()) {
      return false;
    }
    String sql =
        "SELECT 1 FROM review_run WHERE UPPER(commit_sha) = UPPER(?)"
            + " AND status IN ('DONE','DEGRADED') LIMIT 1";
    try (Connection conn = dataSource.getConnection();
        PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, commitSha.strip());
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next();
      }
    } catch (SQLException e) {
      throw new CraLedgerException("已知 commit 检查失败: " + commitSha, e);
    }
  }
}
