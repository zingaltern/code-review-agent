package org.cra.store.internal.sqlite;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.cra.common.git.LedgerFindingMarker;
import org.cra.common.git.LedgerRunMarker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SQLite 账本单测（08§2.2：@TempDir 临时目录，每次测试新建库，无共享状态）。锁定写侧往返、 完成标记语义（DONE|DEGRADED
 * 同权，02§4.1）与跨连接持久化（账本跨 run 事实源）。
 */
class SqliteLedgerTest {

  @TempDir private Path tempDir;

  private SqliteLedger newLedger() {
    return new SqliteLedger("o", "r", tempDir.resolve("ledger.db"));
  }

  @Test
  void recordRunCompletionRoundtrip() {
    SqliteLedger ledger = newLedger();
    ledger.recordRunCompletion(7, new LedgerRunMarker("run_1", "abc1234", "DONE", 3, 1, 11L));
    var found = ledger.findCompletion(7, "abc1234");
    assertThat(found)
        .hasValueSatisfying(
            m -> {
              assertThat(m.runId()).isEqualTo("run_1");
              assertThat(m.status()).isEqualTo("DONE");
              assertThat(m.findings()).isEqualTo(3);
              assertThat(m.suppressed()).isEqualTo(1);
              assertThat(m.commentId()).isEqualTo(11L);
            });
    assertThat(ledger.findCompletion(7, "def5678")).isEmpty();
    assertThat(ledger.findCompletion(8, "abc1234")).isEmpty();
  }

  @Test
  void degradedCountsAsCompletion() {
    // 降级完成也是完整发布（02§4.1 v1 基线修正）
    SqliteLedger ledger = newLedger();
    ledger.recordRunCompletion(7, new LedgerRunMarker("run_1", "abc1234", "DEGRADED", 0, 0, 12L));
    assertThat(ledger.findCompletion(7, "abc1234")).isPresent();
    assertThat(ledger.completedRuns(7)).hasSize(1);
  }

  @Test
  void commitShaMatchIsCaseInsensitive() {
    SqliteLedger ledger = newLedger();
    ledger.recordRunCompletion(7, new LedgerRunMarker("run_1", "ABC1234", "DONE", 1, 0, 0L));
    assertThat(ledger.findCompletion(7, "abc1234")).isPresent();
  }

  @Test
  void publishedFindingsRoundtrip() {
    SqliteLedger ledger = newLedger();
    ledger.recordPublishedFinding(
        7,
        new LedgerFindingMarker("9f2a", "src/Foo.java", 87, "bug", "f_1", "run_1", "abc1234", 42L));
    ledger.recordPublishedFinding(
        7,
        new LedgerFindingMarker(
            "00aa", "src/Bar.java", 3, "security", "f_2", "run_1", "abc1234", 43L));
    List<LedgerFindingMarker> markers = ledger.publishedFindings(7);
    assertThat(markers).hasSize(2);
    assertThat(markers)
        .extracting(LedgerFindingMarker::fingerprint)
        .containsExactlyInAnyOrder("9f2a", "00aa");
    assertThat(markers)
        .extracting(LedgerFindingMarker::category)
        .containsExactlyInAnyOrder("bug", "security");
    assertThat(ledger.publishedFindings(8)).isEmpty();
  }

  @Test
  void recordingSameFindingIdIsIdempotent() {
    // 补发重登记（run 重放）不产生重复指纹行
    SqliteLedger ledger = newLedger();
    LedgerFindingMarker marker =
        new LedgerFindingMarker("9f2a", "src/Foo.java", 87, "bug", "f_1", "run_1", "abc1234", 42L);
    ledger.recordPublishedFinding(7, marker);
    ledger.recordPublishedFinding(7, marker);
    assertThat(ledger.publishedFindings(7)).hasSize(1);
  }

  @Test
  void dataPersistsAcrossReopen() {
    // 账本跨 run 事实源：新实例（新连接）读到旧实例写入的数据
    SqliteLedger first = newLedger();
    first.recordRunCompletion(7, new LedgerRunMarker("run_1", "abc1234", "DONE", 2, 0, 11L));
    first.recordPublishedFinding(
        7,
        new LedgerFindingMarker("9f2a", "src/Foo.java", 87, "bug", "f_1", "run_1", "abc1234", 42L));
    SqliteLedger second = newLedger();
    assertThat(second.findCompletion(7, "abc1234")).isPresent();
    assertThat(second.publishedFindings(7)).hasSize(1);
    assertThat(second.completedRuns(7)).hasSize(1);
  }

  @Test
  void fallbackFindingWithoutCommentIdRoundtrips() {
    // 位置非法降级进 Summary 的 finding（commentId=0）也须登记（02§4.1 补发语义）
    SqliteLedger ledger = newLedger();
    ledger.recordPublishedFinding(
        7,
        new LedgerFindingMarker("beef", "src/Foo.java", 500, "bug", "f_3", "run_1", "abc1234", 0L));
    assertThat(ledger.publishedFindings(7))
        .first()
        .satisfies(m -> assertThat(m.commentId()).isEqualTo(0L));
  }

  @Test
  void sqliteFormHasNoSuspectsAndNoCommentBodies() {
    // 本地库无可信评论流：suspect 恒 0、原文不落库（comment 为 empty）——不假装支持（11§4 第 5 条）
    SqliteLedger ledger = newLedger();
    assertThat(ledger.suspectMarkerCount(7)).isZero();
    assertThat(ledger.comment(7, 11L)).isEmpty();
  }

  @Test
  void templateVersionsRoundtripAndIdempotent() throws Exception {
    // T2.11：prompt 模板版本随 run 落库（05§1.2/05§1.5）；同 run 重复登记幂等（INSERT OR REPLACE）
    Path db = tempDir.resolve("ledger.db");
    SqliteLedger ledger = newLedger();
    ledger.recordTemplateVersions(
        7, "run_1", java.util.Map.of("general-reviewer", "general@1a2b3c04"));
    ledger.recordTemplateVersions(
        7, "run_1", java.util.Map.of("general-reviewer", "general@1a2b3c04"));
    ledger.recordTemplateVersions(
        7, "run_2", java.util.Map.of("verifier", "balanced-review@deadbeef"));
    try (var conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
        var rs =
            conn.createStatement()
                .executeQuery(
                    "SELECT template_name, template_version FROM prompt_template_version"
                        + " WHERE repo = 'o/r' ORDER BY run_id, template_name")) {
      List<String> rows = new java.util.ArrayList<>();
      while (rs.next()) {
        rows.add(rs.getString(1) + "=" + rs.getString(2));
      }
      assertThat(rows)
          .containsExactly(
              "general-reviewer=general@1a2b3c04", "verifier=balanced-review@deadbeef");
    }
  }
}
