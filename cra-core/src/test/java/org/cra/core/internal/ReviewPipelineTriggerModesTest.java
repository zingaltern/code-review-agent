package org.cra.core.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.cra.agents.api.BugHunterDefinition;
import org.cra.agents.api.JudgeAgentDefinition;
import org.cra.agents.api.ReviewCrew;
import org.cra.agents.api.VerifierAgentDefinition;
import org.cra.agents.internal.TemplateLoader;
import org.cra.common.git.DiffFile;
import org.cra.common.git.DiffHunk;
import org.cra.common.git.DiffResult;
import org.cra.common.git.FileContent;
import org.cra.common.git.LedgerRunMarker;
import org.cra.common.git.PostCommitReviewCommand;
import org.cra.common.git.PostIssueCommand;
import org.cra.common.git.ReviewKey;
import org.cra.common.run.Depth;
import org.cra.common.run.ReviewMode;
import org.cra.common.run.ReviewRequest;
import org.cra.common.run.ReviewResult;
import org.cra.common.run.RunStatus;
import org.cra.git.api.GitPlatformAdapter;
import org.cra.store.api.ReviewLedger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

/**
 * M4c push 直推（commit comment）与 M4d 夜间存量（issue）发布形态（05§2.4/§2.5）： 单载体详情渲染、无 inline 发布、DONE 标记、键控幂等。
 */
class ReviewPipelineTriggerModesTest {

  private static final String HEAD = "abc1234000000000000000000000000000000000";
  private static final String BASE = "def5678000000000000000000000000000000000";
  private static final String FILE = "src/Foo.java";
  private static final String FILE_CONTENT =
      """
      package demo;
      public class Foo {
        int get(List<Integer> items) {
          return items.get(items.size() - 1);
        }
      }
      """;
  private static final String PATCH = "@@ -1,5 +1,6 @@\n package demo;\n+import java.util.*;\n";

  @TempDir Path runRoot;

  private GitPlatformAdapter adapter;
  private ReviewLedger ledger;
  private ScriptedGateway scripted;
  private ReviewPipeline pipeline;

  @BeforeEach
  void setUp() {
    adapter = mock(GitPlatformAdapter.class);
    ledger = mock(ReviewLedger.class);
    scripted = new ScriptedGateway();
    scripted.review(findingsJson());
    when(ledger.findCompletion(any(ReviewKey.class))).thenReturn(Optional.empty());
    when(ledger.publishedFindings(any(ReviewKey.class))).thenReturn(List.of());
    when(ledger.completedRuns(any(ReviewKey.class))).thenReturn(List.of());
    when(ledger.suspectMarkerCount(any(ReviewKey.class))).thenReturn(0);
    when(adapter.fetchDiff(any(), anyString(), anyString())).thenReturn(diff());
    when(adapter.readFile(any(), anyString(), anyString()))
        .thenReturn(FileContent.ofText(FILE, FILE_CONTENT));
    when(adapter.postCommitReview(any())).thenReturn(Optional.of(500L));
    when(adapter.postIssueReview(any())).thenReturn(Optional.of(15L));
    pipeline =
        new ReviewPipeline(
            adapter,
            ledger,
            scripted,
            ReviewCrew.ofBugHunters(
                List.of(
                    new BugHunterDefinition(
                        BugHunterDefinition.Perspective.DATAFLOW, new TemplateLoader()))),
            new VerifierAgentDefinition(new TemplateLoader()),
            new JudgeAgentDefinition(new TemplateLoader()),
            runRoot,
            org.cra.core.api.PipelineOptions.defaults(),
            null);
  }

  private static DiffResult diff() {
    return new DiffResult(
        BASE,
        HEAD,
        List.of(new DiffFile(FILE, null, "modified", PATCH, List.of(new DiffHunk(1, 5, 1, 6)))),
        List.of(new org.cra.common.git.CommitSummary("c1", "fix: something")));
  }

  private static String findingsJson() {
    return """
        {"findings":[{"category":"bug","title":"空列表末尾元素越界",
          "description":"items 为空时 size()-1 为 -1",
          "failure_mode":"触发条件：items 为空；错误结果：IndexOutOfBoundsException",
          "file":"src/Foo.java","line":2,"side":"RIGHT",
          "evidence":"L2-L4: return items.get(items.size() - 1)","suggestion":"先判空",
          "severity_proposed":"important","pre_existing_suspect":false,"confidence":0.8}]}""";
  }

  private static ReviewRequest request(ReviewMode mode, int prNumber) {
    return new ReviewRequest("o", "r", prNumber, BASE, HEAD, Depth.STANDARD, false, false, mode);
  }

  @Test
  void pushPublishesSingleCommitCommentWithDetailsAndDoneMarker() {
    ReviewResult result = pipeline.run(request(ReviewMode.COMMIT, 0));

    assertThat(result.status()).isEqualTo(RunStatus.SUCCESS);
    assertThat(result.stats().findingsPublished()).isEqualTo(1);
    ArgumentCaptor<PostCommitReviewCommand> captor =
        ArgumentCaptor.forClass(PostCommitReviewCommand.class);
    verify(adapter).postCommitReview(captor.capture());
    PostCommitReviewCommand cmd = captor.getValue();
    assertThat(cmd.commitSha()).isEqualTo(HEAD);
    // commit comment 无行级锚：全部 finding 以详情块承载，DONE 标记在末位
    assertThat(cmd.body())
        .contains("### 发现详情")
        .contains("空列表末尾元素越界")
        .contains("**失败模式**")
        .contains("**证据**")
        .contains("载体无行级锚")
        .contains("| commit=" + HEAD)
        .contains("status=DONE");
    // 不发布 PR inline review / Summary issue comment
    verify(adapter, never()).postReview(any());
    verify(adapter, never()).postIssueReview(any());

    ArgumentCaptor<ReviewKey> keyCaptor = ArgumentCaptor.forClass(ReviewKey.class);
    ArgumentCaptor<LedgerRunMarker> markerCaptor = ArgumentCaptor.forClass(LedgerRunMarker.class);
    verify(ledger).recordRunCompletion(keyCaptor.capture(), markerCaptor.capture());
    assertThat(keyCaptor.getValue()).isEqualTo(ReviewKey.commit("o", "r", HEAD));
    assertThat(markerCaptor.getValue().commentId()).isEqualTo(500L);
    assertThat(markerCaptor.getValue().status()).isEqualTo(LedgerRunMarker.STATUS_DONE);
  }

  @Test
  void pushRerunWithDoneMarkerSkipsWithoutPublishing() {
    when(ledger.findCompletion(any(ReviewKey.class)))
        .thenReturn(Optional.of(new LedgerRunMarker("run_1", HEAD, "DONE", 1, 0, 500L)));

    ReviewResult result = pipeline.run(request(ReviewMode.COMMIT, 0));

    assertThat(result.status()).isEqualTo(RunStatus.SKIPPED);
    verify(adapter, never()).postCommitReview(any());
    verify(adapter, never()).postReview(any());
    ArgumentCaptor<ReviewKey> keyCaptor = ArgumentCaptor.forClass(ReviewKey.class);
    verify(ledger).findCompletion(keyCaptor.capture());
    assertThat(keyCaptor.getValue().mode()).isEqualTo(ReviewMode.COMMIT);
    assertThat(keyCaptor.getValue().prNumber()).isZero();
  }

  @Test
  void nightlyPublishesIssueWithNightlyTitleAndDetails() {
    ReviewResult result = pipeline.run(request(ReviewMode.ISSUE, 42));

    assertThat(result.status()).isEqualTo(RunStatus.SUCCESS);
    ArgumentCaptor<PostIssueCommand> captor = ArgumentCaptor.forClass(PostIssueCommand.class);
    verify(adapter).postIssueReview(captor.capture());
    PostIssueCommand cmd = captor.getValue();
    assertThat(cmd.title()).startsWith("[cra-nightly] PR #42");
    assertThat(cmd.title()).endsWith("存量评审");
    assertThat(cmd.labels()).containsExactly("cra-nightly");
    assertThat(cmd.body())
        .contains("夜间存量评审")
        .contains("### 发现详情")
        .contains("空列表末尾元素越界")
        .contains("status=DONE");
    verify(adapter, never()).postReview(any());
    verify(adapter, never()).postCommitReview(any());

    ArgumentCaptor<ReviewKey> keyCaptor = ArgumentCaptor.forClass(ReviewKey.class);
    verify(ledger).recordRunCompletion(keyCaptor.capture(), any(LedgerRunMarker.class));
    assertThat(keyCaptor.getValue()).isEqualTo(ReviewKey.issue("o", "r", 42, HEAD));
  }

  @Test
  void nightlyRerunWithDoneMarkerSkipsWithoutIssue() {
    when(ledger.findCompletion(any(ReviewKey.class)))
        .thenReturn(Optional.of(new LedgerRunMarker("run_1", HEAD, "DONE", 1, 0, 15L)));

    ReviewResult result = pipeline.run(request(ReviewMode.ISSUE, 42));

    assertThat(result.status()).isEqualTo(RunStatus.SKIPPED);
    verify(adapter, never()).postIssueReview(any());
  }
}
