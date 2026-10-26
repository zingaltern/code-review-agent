package org.cra.store.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.cra.common.git.LedgerFindingMarker;
import org.cra.common.git.LedgerRunMarker;
import org.junit.jupiter.api.Test;

/**
 * ReviewLedger 契约测试（可持久化形态的共享语义）：写侧往返、完成标记（DONE|DEGRADED 同权，02§4.1）、commit 大小写不敏感、指纹级幂等、跨实例持久化。
 *
 * <p>子类提供具体形态（SQLite/PG）；本类不假设存储实现，只断言 SPI 语义（11§4 第 1 条：三形态同契约）。
 */
public abstract class ReviewLedgerContractTest {

  /**
   * 新建一个指向同一账本的 SPI 实例（模拟新 run/重开连接）。
   *
   * @return 账本实例
   */
  protected abstract ReviewLedger newLedger();

  @Test
  void recordRunCompletionRoundtrip() {
    ReviewLedger ledger = newLedger();
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
    ReviewLedger ledger = newLedger();
    ledger.recordRunCompletion(7, new LedgerRunMarker("run_1", "abc1234", "DEGRADED", 0, 0, 12L));
    assertThat(ledger.findCompletion(7, "abc1234")).isPresent();
    assertThat(ledger.completedRuns(7)).hasSize(1);
  }

  @Test
  void commitShaMatchIsCaseInsensitive() {
    ReviewLedger ledger = newLedger();
    ledger.recordRunCompletion(7, new LedgerRunMarker("run_1", "ABC1234", "DONE", 1, 0, 0L));
    assertThat(ledger.findCompletion(7, "abc1234")).isPresent();
  }

  @Test
  void publishedFindingsRoundtrip() {
    ReviewLedger ledger = newLedger();
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
    ReviewLedger ledger = newLedger();
    LedgerFindingMarker marker =
        new LedgerFindingMarker("9f2a", "src/Foo.java", 87, "bug", "f_1", "run_1", "abc1234", 42L);
    ledger.recordPublishedFinding(7, marker);
    ledger.recordPublishedFinding(7, marker);
    assertThat(ledger.publishedFindings(7)).hasSize(1);
  }

  @Test
  void dataPersistsAcrossReopen() {
    ReviewLedger first = newLedger();
    first.recordRunCompletion(7, new LedgerRunMarker("run_1", "abc1234", "DONE", 2, 0, 11L));
    first.recordPublishedFinding(
        7,
        new LedgerFindingMarker("9f2a", "src/Foo.java", 87, "bug", "f_1", "run_1", "abc1234", 42L));
    ReviewLedger second = newLedger();
    assertThat(second.findCompletion(7, "abc1234")).isPresent();
    assertThat(second.publishedFindings(7)).hasSize(1);
    assertThat(second.completedRuns(7)).hasSize(1);
  }

  @Test
  void fallbackFindingWithoutCommentIdRoundtrips() {
    // 位置非法降级进 Summary 的 finding（commentId=0）也须登记（02§4.1 补发语义）
    ReviewLedger ledger = newLedger();
    ledger.recordPublishedFinding(
        7,
        new LedgerFindingMarker("beef", "src/Foo.java", 500, "bug", "f_3", "run_1", "abc1234", 0L));
    assertThat(ledger.publishedFindings(7))
        .first()
        .satisfies(m -> assertThat(m.commentId()).isEqualTo(0L));
  }

  @Test
  void persistentFormHasNoSuspectsAndNoCommentBodies() {
    // 持久化账本无可信评论流：suspect 恒 0、原文不落库（不假装支持，11§4 第 5 条）
    ReviewLedger ledger = newLedger();
    assertThat(ledger.suspectMarkerCount(7)).isZero();
    assertThat(ledger.comment(7, 11L)).isEmpty();
  }
}
