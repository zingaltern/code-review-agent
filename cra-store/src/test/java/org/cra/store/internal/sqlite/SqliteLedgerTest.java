package org.cra.store.internal.sqlite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.cra.common.git.LedgerRunMarker;
import org.cra.store.api.ReviewLedger;
import org.cra.store.api.ReviewLedgerContractTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SQLite 账本单测（08§2.2：@TempDir 临时目录，每次测试新建库，无共享状态）。契约语义由 {@link ReviewLedgerContractTest} 共享，本类只补
 * SQLite 形态特有的原文检查（模板版本落库）。
 */
class SqliteLedgerTest extends ReviewLedgerContractTest {

  @TempDir private Path tempDir;

  @Override
  protected ReviewLedger newLedger() {
    return new SqliteLedger("o", "r", tempDir.resolve("ledger.db"));
  }

  @Test
  void constructorRejectsBlankRepoAndNullFile() {
    assertThatThrownBy(() -> new SqliteLedger(" ", "r", tempDir.resolve("x.db")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("owner/repo");
    assertThatThrownBy(() -> new SqliteLedger("o", "r", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("dbFile");
  }

  @Test
  void completedRunsOrdersLegacyAndFixedTimestampFormats() throws Exception {
    // 升级前的行是变长小数（Instant.toString），新行固定 9 位；TEXT 字典序会反转（.5Z 排在 .550000000Z 之后），
    // 查询必须按 julianday 排序（SQLite 日期函数按毫秒精度解析两种格式）
    Path db = tempDir.resolve("legacy.db");
    SqliteLedger ledger = new SqliteLedger("o", "r", db);
    assertThat(ledger.completedRuns(7)).isEmpty(); // 首次读触发 schema 初始化
    try (var conn = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
        var ps =
            conn.prepareStatement(
                "INSERT INTO review_run(run_id, repo, pr_number, commit_sha, status, findings,"
                    + " suppressed, summary_comment_id, created_at)"
                    + " VALUES (?, 'o/r', 7, ?, 'DONE', 1, 0, NULL, ?)")) {
      ps.setString(1, "run_legacy");
      ps.setString(2, "sha1");
      ps.setString(3, "2026-01-01T00:00:00.5Z");
      ps.executeUpdate();
      ps.setString(1, "run_fixed");
      ps.setString(2, "sha2");
      ps.setString(3, "2026-01-01T00:00:00.550000000Z");
      ps.executeUpdate();
    }

    assertThat(ledger.completedRuns(7))
        .extracting(LedgerRunMarker::runId)
        .containsExactly("run_legacy", "run_fixed");
  }

  @Test
  void templateVersionsRoundtripAndIdempotent() throws Exception {
    // prompt 模板版本随 run 落库（05§1.2/05§1.5）；同 run 重复登记幂等（INSERT OR REPLACE）
    Path db = tempDir.resolve("ledger.db");
    SqliteLedger ledger = new SqliteLedger("o", "r", db);
    ledger.recordTemplateVersions(7, "run_1", Map.of("general-reviewer", "general@1a2b3c04"));
    ledger.recordTemplateVersions(7, "run_1", Map.of("general-reviewer", "general@1a2b3c04"));
    ledger.recordTemplateVersions(
        7, "run_2", java.util.Map.of("verifier", "balanced-review@deadbeef"));
    try (var conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
        var rs =
            conn.createStatement()
                .executeQuery(
                    "SELECT template_name, template_version FROM prompt_template_version"
                        + " WHERE repo = 'o/r' ORDER BY run_id, template_name")) {
      List<String> rows = new ArrayList<>();
      while (rs.next()) {
        rows.add(rs.getString(1) + "=" + rs.getString(2));
      }
      assertThat(rows)
          .containsExactly(
              "general-reviewer=general@1a2b3c04", "verifier=balanced-review@deadbeef");
    }
  }
}
