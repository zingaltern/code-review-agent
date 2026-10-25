package org.cra.store.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import org.cra.common.git.CommentLedger;
import org.cra.common.git.CommentSource;
import org.cra.common.git.LedgerComment;
import org.cra.common.git.LedgerFindingMarker;
import org.cra.common.git.LedgerRunMarker;
import org.cra.common.git.RepoRef;
import org.junit.jupiter.api.Test;

/** GitHub 评论账本单测（ADR-0005 构造器注入端口；mock SPI 边界，08§3.1）。 */
class GitHubCommentLedgerTest {

  private static final RepoRef REPO = new RepoRef("o", "r");

  @Test
  void findCompletionAcceptsDoneAndDegraded() {
    CommentSource source = mock(CommentSource.class);
    when(source.fetchBotComments(REPO, 7))
        .thenReturn(
            new CommentLedger(
                List.of(),
                List.of(),
                List.of(
                    new LedgerRunMarker("run_1", "abc1234", "DONE", 2, 0, 11L),
                    new LedgerRunMarker("run_2", "def5678", "DEGRADED", 1, 1, 12L)),
                0));
    GitHubCommentLedger ledger = new GitHubCommentLedger(source, REPO);
    assertThat(ledger.findCompletion(7, "abc1234")).isPresent();
    assertThat(ledger.findCompletion(7, "def5678")).isPresent();
    assertThat(ledger.findCompletion(7, "0000000")).isEmpty();
  }

  @Test
  void findCompletionTakesFirstMarkerForSameCommit() {
    // 闸语义只判存在性：同 commit 多条完成标记（重跑多次发布）取首条
    CommentSource source = mock(CommentSource.class);
    when(source.fetchBotComments(REPO, 7))
        .thenReturn(
            new CommentLedger(
                List.of(),
                List.of(),
                List.of(
                    new LedgerRunMarker("run_1", "abc1234", "DONE", 1, 0, 11L),
                    new LedgerRunMarker("run_2", "abc1234", "DONE", 3, 1, 12L)),
                0));
    GitHubCommentLedger ledger = new GitHubCommentLedger(source, REPO);
    assertThat(ledger.findCompletion(7, "abc1234"))
        .hasValueSatisfying(m -> assertThat(m.runId()).isEqualTo("run_1"));
  }

  @Test
  void publishedFindingsPassThroughFromPort() {
    CommentSource source = mock(CommentSource.class);
    LedgerFindingMarker marker =
        new LedgerFindingMarker("9f2a", "src/Foo.java", 87, "bug", "f_1", "run_1", "abc1234", 42L);
    when(source.fetchBotComments(REPO, 7))
        .thenReturn(new CommentLedger(List.of(), List.of(marker), List.of(), 0));
    GitHubCommentLedger ledger = new GitHubCommentLedger(source, REPO);
    assertThat(ledger.publishedFindings(7)).containsExactly(marker);
  }

  @Test
  void completedRunsExposeAllCompletionMarkers() {
    // 过期收尾数据面（02§5）：目标 = commit 不同于当前 head 的历史轮完成标记
    CommentSource source = mock(CommentSource.class);
    when(source.fetchBotComments(REPO, 7))
        .thenReturn(
            new CommentLedger(
                List.of(),
                List.of(),
                List.of(
                    new LedgerRunMarker("run_1", "abc1234", "DONE", 2, 0, 11L),
                    new LedgerRunMarker("run_2", "def5678", "DEGRADED", 1, 1, 12L)),
                0));
    GitHubCommentLedger ledger = new GitHubCommentLedger(source, REPO);
    assertThat(ledger.completedRuns(7)).hasSize(2);
  }

  @Test
  void suspectCountAndCommentBodyPassThroughFromPort() {
    CommentSource source = mock(CommentSource.class);
    LedgerComment summary =
        new LedgerComment(
            11L, LedgerComment.KIND_ISSUE, "cra-bot", "Summary 正文", Instant.now(), null);
    when(source.fetchBotComments(REPO, 7))
        .thenReturn(new CommentLedger(List.of(summary), List.of(), List.of(), 3));
    GitHubCommentLedger ledger = new GitHubCommentLedger(source, REPO);
    assertThat(ledger.suspectMarkerCount(7)).isEqualTo(3);
    assertThat(ledger.comment(7, 11L)).hasValue(summary);
    assertThat(ledger.comment(7, 99L)).isEmpty();
  }

  @Test
  void writeSideIsNoOpForCommentLedger() {
    // 写侧 default 语义（ReviewLedger 约定）：GitHub 形态的写入由发布动作内嵌标记完成，登记无操作
    CommentSource source = mock(CommentSource.class);
    when(source.fetchBotComments(REPO, 7))
        .thenReturn(new CommentLedger(List.of(), List.of(), List.of(), 0));
    GitHubCommentLedger ledger = new GitHubCommentLedger(source, REPO);
    LedgerRunMarker marker = new LedgerRunMarker("run_9", "abc1234", "DONE", 1, 0, 91L);
    ledger.recordRunCompletion(7, marker);
    ledger.recordPublishedFinding(
        7,
        new LedgerFindingMarker("9f2a", "src/Foo.java", 87, "bug", "f_9", "run_9", "abc1234", 1L));
    assertThat(ledger.findCompletion(7, "abc1234")).isEmpty();
    assertThat(ledger.publishedFindings(7)).isEmpty();
  }
}
