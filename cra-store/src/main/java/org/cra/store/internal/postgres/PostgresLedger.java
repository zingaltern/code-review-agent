package org.cra.store.internal.postgres;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.cra.common.exception.CraLedgerException;
import org.cra.common.git.LedgerComment;
import org.cra.common.git.LedgerFindingMarker;
import org.cra.common.git.LedgerRunMarker;
import org.cra.store.api.ReviewLedger;

/**
 * PostgreSQL 账本（02§5 服务形态、05§1.5 五表；M4b）。语义与 {@code SqliteLedger} 一致：完成标记只认 DONE|DEGRADED、 commit
 * 匹配大小写不敏感、同 finding_id 重登记幂等。
 *
 * <p>表由 {@code db/postgres/ledger.sql} 的手写幂等 DDL 创建（server 启动时经 {@link #ensureSchema()}
 * 执行）。连接由组合根提供的 {@link DataSource}（服务端为 HikariCP）管理；本类不持有连接池、不感知 Spring。
 */
public final class PostgresLedger implements ReviewLedger {

  private static final String COMPLETION_STATUSES = "('DONE','DEGRADED')";
  private static final String SCHEMA_RESOURCE = "db/postgres/ledger.sql";

  private final String repoKey;
  private final DataSource dataSource;

  /**
   * @param repoOwner 仓库属主（账本实例按仓库绑定，与 GitHub/SQLite 形态一致）
   * @param repoName 仓库名
   * @param dataSource 连接池（服务端为 HikariCP；测试为 PGSimpleDataSource）
   */
  public PostgresLedger(String repoOwner, String repoName, DataSource dataSource) {
    if (repoOwner == null || repoOwner.isBlank() || repoName == null || repoName.isBlank()) {
      throw new IllegalArgumentException("owner/repo must be non-blank");
    }
    if (dataSource == null) {
      throw new IllegalArgumentException("dataSource 不能为空");
    }
    this.repoKey = repoOwner + "/" + repoName;
    this.dataSource = dataSource;
  }

  /**
   * 执行幂等 DDL（server 启动时调用；重复调用安全）。
   *
   * <p>串行化是为了避开 PG 并发 {@code CREATE TABLE IF NOT EXISTS} 的 pg_type 唯一键竞争；锁内 I/O 只发生在启动路径（09§6 建议的
   * 例外理由）。
   */
  public void ensureSchema() {
    synchronized (PostgresLedger.class) {
      String script = readSchema();
      try (Connection conn = dataSource.getConnection()) {
        for (String statement : script.split(";")) {
          String sql = statement.strip();
          if (sql.isEmpty()) {
            continue;
          }
          try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.execute();
          }
        }
      } catch (SQLException e) {
        throw new CraLedgerException("PostgreSQL 账本 schema 初始化失败", e);
      }
    }
  }

  @Override
  public Optional<LedgerRunMarker> findCompletion(int prNumber, String commitSha) {
    List<LedgerRunMarker> hits =
        queryRun(
            "SELECT run_id, commit_sha, status, findings, suppressed, summary_comment_id FROM"
                + " review_run WHERE repo = ? AND pr_number = ? AND UPPER(commit_sha) = UPPER(?)"
                + " AND status IN "
                + COMPLETION_STATUSES
                + " ORDER BY created_at, run_id LIMIT 1",
            prNumber,
            commitSha);
    return hits.stream().findFirst();
  }

  @Override
  public List<LedgerRunMarker> completedRuns(int prNumber) {
    return queryRun(
        "SELECT run_id, commit_sha, status, findings, suppressed, summary_comment_id FROM"
            + " review_run WHERE repo = ? AND pr_number = ? AND status IN "
            + COMPLETION_STATUSES
            + " ORDER BY created_at, run_id",
        prNumber,
        null);
  }

  @Override
  public List<LedgerFindingMarker> publishedFindings(int prNumber) {
    String sql =
        "SELECT finding_id, fingerprint, file, line, category, run_id, commit_sha, comment_id FROM"
            + " finding WHERE repo = ? AND pr_number = ? ORDER BY created_at, finding_id";
    return query(
        sql,
        ps -> {
          ps.setString(1, repoKey);
          ps.setInt(2, prNumber);
        },
        rs -> {
          List<LedgerFindingMarker> out = new ArrayList<>();
          while (rs.next()) {
            out.add(
                new LedgerFindingMarker(
                    rs.getString("fingerprint"),
                    rs.getString("file"),
                    rs.getInt("line"),
                    rs.getString("category"),
                    rs.getString("finding_id"),
                    rs.getString("run_id"),
                    rs.getString("commit_sha"),
                    rs.getLong("comment_id")));
          }
          return out;
        });
  }

  @Override
  public int suspectMarkerCount(int prNumber) {
    // 服务账本只登记经 record 构造器校验的标记，不承载不可信评论流（与 SQLite 形态同语义）
    return 0;
  }

  @Override
  public Optional<LedgerComment> comment(int prNumber, long commentId) {
    // 评论原文不落库；过期收尾 PATCH 是 GitHub 形态语义
    return Optional.empty();
  }

  @Override
  public void recordRunCompletion(int prNumber, LedgerRunMarker marker) {
    update(
        "INSERT INTO review_run(run_id, repo, pr_number, commit_sha, status, findings, suppressed,"
            + " summary_comment_id, created_at) VALUES (?,?,?,?,?,?,?,?,?)"
            + " ON CONFLICT (repo, pr_number, run_id) DO UPDATE SET"
            + " commit_sha = EXCLUDED.commit_sha, status = EXCLUDED.status,"
            + " findings = EXCLUDED.findings, suppressed = EXCLUDED.suppressed,"
            + " summary_comment_id = EXCLUDED.summary_comment_id,"
            + " created_at = EXCLUDED.created_at",
        marker.runId(),
        repoKey,
        prNumber,
        marker.commitSha(),
        marker.status(),
        marker.findings(),
        marker.suppressed(),
        marker.commentId() > 0 ? marker.commentId() : null,
        Timestamp.from(Instant.now()));
  }

  @Override
  public void recordPublishedFinding(int prNumber, LedgerFindingMarker marker) {
    update(
        "INSERT INTO finding(finding_id, repo, pr_number, fingerprint, file, line, category, run_id,"
            + " commit_sha, comment_id, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)"
            + " ON CONFLICT (repo, pr_number, finding_id) DO UPDATE SET"
            + " fingerprint = EXCLUDED.fingerprint, file = EXCLUDED.file, line = EXCLUDED.line,"
            + " category = EXCLUDED.category, run_id = EXCLUDED.run_id,"
            + " commit_sha = EXCLUDED.commit_sha, comment_id = EXCLUDED.comment_id,"
            + " created_at = EXCLUDED.created_at",
        marker.findingId(),
        repoKey,
        prNumber,
        marker.fingerprint(),
        marker.file(),
        marker.line(),
        marker.category(),
        marker.runId(),
        marker.commitSha(),
        marker.commentId() > 0 ? marker.commentId() : null,
        Timestamp.from(Instant.now()));
  }

  @Override
  public void recordTemplateVersions(int prNumber, String runId, Map<String, String> versions) {
    for (Map.Entry<String, String> entry : versions.entrySet()) {
      update(
          "INSERT INTO prompt_template_version(repo, pr_number, run_id, template_name,"
              + " template_version, created_at) VALUES (?,?,?,?,?,?)"
              + " ON CONFLICT (repo, pr_number, run_id, template_name) DO UPDATE SET"
              + " template_version = EXCLUDED.template_version,"
              + " created_at = EXCLUDED.created_at",
          repoKey,
          prNumber,
          runId,
          entry.getKey(),
          entry.getValue(),
          Timestamp.from(Instant.now()));
    }
  }

  // ---------- JDBC 骨架 ----------

  private interface RowMapper<T> {
    T map(ResultSet rs) throws SQLException;
  }

  private interface SqlConsumer<T> {
    void accept(T value) throws SQLException;
  }

  private List<LedgerRunMarker> queryRun(String sql, int prNumber, String commitSha) {
    return query(
        sql,
        ps -> {
          ps.setString(1, repoKey);
          ps.setInt(2, prNumber);
          if (commitSha != null) {
            ps.setString(3, commitSha);
          }
        },
        rs -> {
          List<LedgerRunMarker> out = new ArrayList<>();
          while (rs.next()) {
            out.add(
                new LedgerRunMarker(
                    rs.getString("run_id"),
                    rs.getString("commit_sha"),
                    rs.getString("status"),
                    rs.getInt("findings"),
                    rs.getInt("suppressed"),
                    rs.getLong("summary_comment_id")));
          }
          return out;
        });
  }

  private <T> T query(String sql, SqlConsumer<PreparedStatement> binder, RowMapper<T> mapper) {
    try (Connection conn = dataSource.getConnection();
        PreparedStatement ps = conn.prepareStatement(sql)) {
      binder.accept(ps);
      try (ResultSet rs = ps.executeQuery()) {
        return mapper.map(rs);
      }
    } catch (SQLException e) {
      throw new CraLedgerException("PostgreSQL 账本查询失败: " + sql, e);
    }
  }

  private void update(String sql, Object... args) {
    try (Connection conn = dataSource.getConnection();
        PreparedStatement ps = conn.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) {
        ps.setObject(i + 1, args[i]);
      }
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new CraLedgerException("PostgreSQL 账本写入失败: " + sql, e);
    }
  }

  private static String readSchema() {
    try (InputStream in =
        PostgresLedger.class.getClassLoader().getResourceAsStream(SCHEMA_RESOURCE)) {
      if (in == null) {
        throw new CraLedgerException("缺少 PostgreSQL schema 资源: " + SCHEMA_RESOURCE);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new CraLedgerException("PostgreSQL schema 读取失败: " + SCHEMA_RESOURCE, e);
    }
  }
}
