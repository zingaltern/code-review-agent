package org.cra.store.internal.sqlite;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.cra.common.exception.CraLedgerException;
import org.cra.common.git.LedgerComment;
import org.cra.common.git.LedgerFindingMarker;
import org.cra.common.git.LedgerRunMarker;
import org.cra.store.api.ReviewLedger;

/**
 * 本地 SQLite 账本（02§5 本地形态、05§1.5 存储子集；M1 T1.1，M2 本地 pre-PR 模式的账本事实源）。
 *
 * <p>表结构（05§1.5 中已有读写双方数据的表；verdict/published_comment 随各自数据面——M2 验证器与 M3 处置采集——落地，避免无读取方的投机
 * schema）：
 *
 * <ul>
 *   <li><b>review_run</b>：run 完成标记（status ∈ DONE|DEGRADED，即 05§1.3 run 完成标记的持久化形态）；
 *   <li><b>finding</b>：已发布 finding 标记（指纹级去重与补发的事实源，02§4.1）；
 *   <li><b>prompt_template_version</b>：run 实际使用的 prompt 模板版本（05§1.2 prompt_template_versions
 *       的落库形态，T2.11）。
 * </ul>
 *
 * <p>与 GitHub 评论账本的分工（02§5）：CI 形态无本地库（账本 = 评论本身，写入由发布内嵌标记完成）； 本地形态每次 run 显式登记（{@code
 * recordRunCompletion}/{@code recordPublishedFinding}）。 JDBC 连接按操作 打开（进程内 SQLite 无连接池需求），schema 以
 * IF NOT EXISTS 幂等创建。
 */
public final class SqliteLedger implements ReviewLedger {

  private static final String COMPLETION_STATUSES = "('DONE','DEGRADED')";

  private final String repoKey;
  private final String jdbcUrl;

  /**
   * @param repoOwner 仓库属主（账本实例按仓库绑定，与 GitHub 形态一致）
   * @param repoName 仓库名
   * @param dbFile SQLite 数据库文件（如 ~/.cra/ledger.db；不存在时由驱动自动创建）
   */
  public SqliteLedger(String repoOwner, String repoName, Path dbFile) {
    this.repoKey = repoOwner + "/" + repoName;
    this.jdbcUrl = "jdbc:sqlite:" + dbFile.toAbsolutePath();
  }

  @Override
  public void recordRunCompletion(int prNumber, LedgerRunMarker marker) {
    update(
        "INSERT OR REPLACE INTO review_run(run_id, repo, pr_number, commit_sha, status, findings,"
            + " suppressed, summary_comment_id, created_at) VALUES (?,?,?,?,?,?,?,?,?)",
        marker.runId(),
        repoKey,
        prNumber,
        marker.commitSha(),
        marker.status(),
        marker.findings(),
        marker.suppressed(),
        marker.commentId() > 0 ? marker.commentId() : null,
        Instant.now().toString());
  }

  @Override
  public void recordPublishedFinding(int prNumber, LedgerFindingMarker marker) {
    update(
        "INSERT OR REPLACE INTO finding(finding_id, repo, pr_number, fingerprint, file, line,"
            + " category, run_id, commit_sha, comment_id, created_at)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?)",
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
        Instant.now().toString());
  }

  @Override
  public void recordTemplateVersions(int prNumber, String runId, Map<String, String> versions) {
    for (Map.Entry<String, String> entry : versions.entrySet()) {
      update(
          "INSERT OR REPLACE INTO prompt_template_version(repo, pr_number, run_id, template_name,"
              + " template_version, created_at) VALUES (?,?,?,?,?,?)",
          repoKey,
          prNumber,
          runId,
          entry.getKey(),
          entry.getValue(),
          Instant.now().toString());
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
    // 本地库不承载不可信评论流：登记值经 record 构造器校验（指纹十六进制/状态合法），无可疑来源
    return 0;
  }

  @Override
  public Optional<LedgerComment> comment(int prNumber, long commentId) {
    // 评论原文不落库（账本登记的是解析后的标记）；过期收尾 PATCH 是 GitHub 形态语义，本地形态不做
    return Optional.empty();
  }

  // ---------- JDBC 骨架 ----------

  private interface RowMapper<T> {
    T map(ResultSet rs) throws SQLException;
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
    try (Connection conn = connection();
        PreparedStatement ps = conn.prepareStatement(sql)) {
      binder.accept(ps);
      try (ResultSet rs = ps.executeQuery()) {
        return mapper.map(rs);
      }
    } catch (SQLException e) {
      throw new CraLedgerException("SQLite 账本查询失败: " + sql, e);
    }
  }

  private void update(String sql, Object... args) {
    try (Connection conn = connection();
        PreparedStatement ps = conn.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) {
        ps.setObject(i + 1, args[i]);
      }
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new CraLedgerException("SQLite 账本写入失败: " + sql, e);
    }
  }

  private Connection connection() {
    try {
      Connection conn = DriverManager.getConnection(jdbcUrl);
      try (Statement stmt = conn.createStatement()) {
        stmt.execute(
            "CREATE TABLE IF NOT EXISTS review_run ("
                + "run_id TEXT NOT NULL, repo TEXT NOT NULL, pr_number INTEGER NOT NULL,"
                + " commit_sha TEXT NOT NULL, status TEXT NOT NULL, findings INTEGER NOT NULL,"
                + " suppressed INTEGER NOT NULL, summary_comment_id INTEGER,"
                + " created_at TEXT NOT NULL, PRIMARY KEY (repo, pr_number, run_id))");
        stmt.execute(
            "CREATE INDEX IF NOT EXISTS idx_review_run_commit"
                + " ON review_run (repo, pr_number, commit_sha)");
        stmt.execute(
            "CREATE TABLE IF NOT EXISTS finding ("
                + "finding_id TEXT NOT NULL, repo TEXT NOT NULL, pr_number INTEGER NOT NULL,"
                + " fingerprint TEXT NOT NULL, file TEXT NOT NULL, line INTEGER NOT NULL,"
                + " category TEXT NOT NULL, run_id TEXT NOT NULL, commit_sha TEXT NOT NULL,"
                + " comment_id INTEGER, created_at TEXT NOT NULL,"
                + " PRIMARY KEY (repo, pr_number, finding_id))");
        stmt.execute(
            "CREATE INDEX IF NOT EXISTS idx_finding_fingerprint"
                + " ON finding (repo, pr_number, fingerprint)");
        stmt.execute(
            "CREATE TABLE IF NOT EXISTS prompt_template_version ("
                + "repo TEXT NOT NULL, pr_number INTEGER NOT NULL, run_id TEXT NOT NULL,"
                + " template_name TEXT NOT NULL, template_version TEXT NOT NULL,"
                + " created_at TEXT NOT NULL, PRIMARY KEY (repo, pr_number, run_id, template_name))");
      }
      return conn;
    } catch (SQLException e) {
      throw new CraLedgerException("SQLite 账本连接失败: " + jdbcUrl, e);
    }
  }

  /** java.sql 函数式接口的统一包装（SQLException → CraLedgerException 由外层统一转换）。 */
  @FunctionalInterface
  private interface SqlConsumer<T> {
    void accept(T value) throws SQLException;
  }
}
