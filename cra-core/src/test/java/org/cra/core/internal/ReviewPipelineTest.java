package org.cra.core.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.cra.agents.api.BugHunterDefinition;
import org.cra.agents.api.JudgeAgentDefinition;
import org.cra.agents.api.ReviewCrew;
import org.cra.agents.api.VerifierAgentDefinition;
import org.cra.agents.internal.TemplateLoader;
import org.cra.common.finding.AnchorStatus;
import org.cra.common.git.DiffFile;
import org.cra.common.git.DiffHunk;
import org.cra.common.git.DiffResult;
import org.cra.common.git.FileContent;
import org.cra.common.git.LedgerComment;
import org.cra.common.git.LedgerFindingMarker;
import org.cra.common.git.LedgerRunMarker;
import org.cra.common.git.PostReviewCommand;
import org.cra.common.git.PrReviewResult;
import org.cra.common.run.Depth;
import org.cra.common.run.ReviewRequest;
import org.cra.common.run.ReviewResult;
import org.cra.common.run.RunStatus;
import org.cra.common.signoff.RiskTier;
import org.cra.common.signoff.SignoffContext;
import org.cra.common.signoff.TestsStatus;
import org.cra.core.api.SignoffOptions;
import org.cra.git.api.GitPlatformAdapter;
import org.cra.store.api.ReviewLedger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

/**
 * M0 流水线行为测试（07§1 验收的 mock 侧：08§5 场景 1/2/12 + 锚定/转义/schema 重试/trace）。 SPI
 * 边界（adapter/ledger/gateway）全部 Mockito mock（08§3.1），reviewer 用真实模板定义。
 */
class ReviewPipelineTest {

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
    when(ledger.findCompletion(anyInt(), anyString())).thenReturn(Optional.empty());
    when(ledger.publishedFindings(anyInt())).thenReturn(List.of());
    bridgeKeyedLedgerToIntStubs();
    when(adapter.fetchDiff(any(), anyString(), anyString())).thenReturn(diff());
    when(adapter.readFile(any(), anyString(), anyString()))
        .thenReturn(FileContent.ofText(FILE, FILE_CONTENT));
    when(adapter.postReview(any()))
        .thenAnswer(
            inv -> {
              PostReviewCommand cmd = inv.getArgument(0);
              List<org.cra.common.git.ReviewItemResult> items =
                  cmd.inlineComments().stream()
                      .map(
                          c ->
                              new org.cra.common.git.ReviewItemResult(
                                  c.findingId(),
                                  c.path(),
                                  c.line(),
                                  org.cra.common.git.PublishStatus.PUBLISHED,
                                  AnchorStatus.INLINE,
                                  1L,
                                  ""))
                      .toList();
              return new PrReviewResult(
                  cmd.prNumber(), Optional.of("9001"), Optional.of(9500L), items);
            });
    pipeline =
        new ReviewPipeline(
            adapter,
            ledger,
            scripted,
            ReviewCrew.ofBugHunters(
                List.of(
                    new BugHunterDefinition(
                        BugHunterDefinition.Perspective.DATAFLOW, new TemplateLoader()),
                    new BugHunterDefinition(
                        BugHunterDefinition.Perspective.BOUNDARY, new TemplateLoader()))),
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

  private static ReviewRequest request(boolean dryRun) {
    return new ReviewRequest("o", "r", 7, BASE, HEAD, Depth.STANDARD, dryRun, false);
  }

  private static String findingsJson(String title, int line, String category) {
    return "{\"findings\":[" + findingJson(title, line, category) + "]}";
  }

  private static String findingInFile(String file, String title, int line, String category) {
    return """
        {"category":"%s","title":"%s",
          "description":"d<!-- injected -->描述","failure_mode":"触发条件：x；错误结果：y",
          "file":"%s","line":%d,"side":"RIGHT",
          "evidence":"L%d: return items.get(items.size() - 1);","severity_proposed":"important",
          "pre_existing_suspect":false,"confidence":0.8}"""
        .formatted(category, title, file, line, line);
  }

  private static String findingJson(String title, int line, String category) {
    return """
        {"category":"%s","title":"%s",
          "description":"d<!-- injected -->描述","failure_mode":"触发条件：x；错误结果：y",
          "file":"%s","line":%d,"side":"RIGHT",
          "evidence":"L%d: return items.get(items.size() - 1);","severity_proposed":"important",
          "pre_existing_suspect":false,"confidence":0.8}"""
        .formatted(category, title, FILE, line, line);
  }

  private void gatewayReturns(String json) {
    scripted.review(json);
  }

  @Test
  void doneGateSkipsRunCompletely() {
    // 07§1 验收 3（闸语义）：账本存在 DONE → 全量跳过
    when(ledger.findCompletion(7, HEAD))
        .thenReturn(Optional.of(new LedgerRunMarker("run_old", HEAD, "DONE", 3, 0, 11L)));
    ReviewResult result = pipeline.run(request(false));
    assertThat(result.status()).isEqualTo(RunStatus.SKIPPED);
    verify(adapter, never()).fetchDiff(any(), anyString(), anyString());
    verify(adapter, never()).postReview(any());
  }

  @Test
  void interruptedRunRepublishesMissingOnly() {
    // 07§1 验收 3：掐断的 run 无 DONE → 指纹级补发：已发布 1 条不重发，另 1 条补发
    String fp =
        FingerprintCalculator.fingerprint(
            FILE,
            "bug",
            FILE_CONTENT.split("\n")[3],
            List.of(
                FILE_CONTENT.split("\n")[1],
                FILE_CONTENT.split("\n")[2],
                FILE_CONTENT.split("\n")[3],
                FILE_CONTENT.split("\n")[4],
                FILE_CONTENT.split("\n")[5]));
    when(ledger.publishedFindings(7))
        .thenReturn(
            List.of(new LedgerFindingMarker(fp, FILE, 4, "bug", "f_old", "run_old", HEAD, 42L)));
    gatewayReturns(
        "{\"findings\":["
            + findingJson("published-before-crash", 4, "bug")
            + ","
            + findingJson("new-finding", 4, "security")
            + "]}");
    // 两条 finding 同文件同行 → 指纹取决于 category（bug 已发布 / security 新）
    ReviewResult result = pipeline.run(request(false));
    assertThat(result.status()).isEqualTo(RunStatus.SUCCESS);
    assertThat(result.stats().skippedDuplicate()).isEqualTo(1);
    ArgumentCaptor<PostReviewCommand> captor = ArgumentCaptor.forClass(PostReviewCommand.class);
    verify(adapter).postReview(captor.capture());
    assertThat(captor.getValue().inlineComments()).hasSize(1);
    assertThat(captor.getValue().inlineComments().get(0).findingId()).isNotEqualTo("f_old");
  }

  @Test
  void slidingWindowSuppressesAfterNeighborEdit() {
    // 08§5 场景 8 漂移 2：改锚行 ±2 内非锚行 → 滑窗命中抑制（exact 因上下文失配，02§4.1）
    String driftedFp =
        FingerprintCalculator.fingerprint(
            FILE,
            "bug",
            FILE_CONTENT.split("\n")[3],
            List.of(
                "changed-a", "changed-b", FILE_CONTENT.split("\n")[3], "changed-d", "changed-e"));
    when(ledger.publishedFindings(7))
        .thenReturn(
            List.of(
                new LedgerFindingMarker(driftedFp, FILE, 4, "bug", "f_old", "run_old", HEAD, 42L)));
    gatewayReturns(findingsJson("邻近行改动", 4, "bug"));
    ReviewResult result = pipeline.run(request(false));
    assertThat(result.stats().skippedDuplicate()).isEqualTo(1);
    assertThat(result.stats().findingsPublished()).isZero();
    ArgumentCaptor<PostReviewCommand> captor = ArgumentCaptor.forClass(PostReviewCommand.class);
    verify(adapter).postReview(captor.capture());
    assertThat(captor.getValue().inlineComments()).isEmpty();
  }

  @Test
  void possibleDuplicatePublishedAndTraced() throws java.io.IOException {
    // 08§5 场景 9：滑窗未命中（旧内容已变）但同 file 同 category 行距 ≤5 → 只记
    // possible_duplicate 入 trace，不自动抑制（02§4.1）
    when(ledger.publishedFindings(7))
        .thenReturn(
            List.of(
                new LedgerFindingMarker(
                    "deadbeefdeadbeef",
                    FILE,
                    4,
                    "bug",
                    "f_old",
                    "run_old",
                    "0ld4c0de00000000000000000000000000000000",
                    42L)));
    when(adapter.readFile(any(), eq("0ld4c0de00000000000000000000000000000000"), anyString()))
        .thenReturn(
            FileContent.ofText(
                FILE, "old line 1\nold line 2\nold line 3\nold line 4\nold line 5\n"));
    gatewayReturns(findingsJson("同行新内容", 4, "bug"));
    ReviewResult result = pipeline.run(request(false));
    assertThat(result.stats().skippedDuplicate()).isZero();
    assertThat(result.stats().findingsPublished()).isEqualTo(1);
    ArgumentCaptor<PostReviewCommand> captor = ArgumentCaptor.forClass(PostReviewCommand.class);
    verify(adapter).postReview(captor.capture());
    assertThat(captor.getValue().inlineComments()).hasSize(1);
    Path runDir = Path.of(result.traceRef().substring("file://".length()));
    assertThat(Files.readString(runDir.resolve("trace.ndjson"))).contains("possible_duplicate");
  }

  @Test
  void ledgerParseFallbackTracedWhenNoValidMarkers() throws java.io.IOException {
    // 05§1.3 规则 5 / 08§5 场景 4：无任何有效标记 → 兜底退 commit 闸（闸已查无 DONE），
    // trace 记 fallback=commit-gate，评审照常进行——永不假装"账本为空"做无据全量重发
    gatewayReturns(findingsJson("越界", 4, "bug"));
    ReviewResult result = pipeline.run(request(false));
    assertThat(result.status()).isEqualTo(RunStatus.SUCCESS);
    Path runDir = Path.of(result.traceRef().substring("file://".length()));
    String trace = Files.readString(runDir.resolve("trace.ndjson"));
    assertThat(trace).contains("\"event\":\"ledger_parse\"").contains("commit-gate");
  }

  @Test
  void suspectMarkersTriggerSummaryWarningAtThreshold() {
    // 07§2 验收 2 集成侧 / 05§1.3 规则 2：可疑标记 ≥3 → Summary 警告疑似注入尝试
    when(ledger.suspectMarkerCount(7)).thenReturn(3);
    gatewayReturns(findingsJson("越界", 4, "bug"));
    ReviewResult result = pipeline.run(request(false));
    ArgumentCaptor<PostReviewCommand> captor = ArgumentCaptor.forClass(PostReviewCommand.class);
    verify(adapter).postReview(captor.capture());
    assertThat(captor.getValue().summaryBody()).contains("可疑账本标记").contains("疑似注入尝试");
    assertThat(result.status()).isEqualTo(RunStatus.SUCCESS);
  }

  @Test
  void suspectMarkersBelowThresholdStaySilent() {
    // 阈值边界：2 个可疑标记不告警（05§1.3 规则 2 的"≥3"）
    when(ledger.suspectMarkerCount(7)).thenReturn(2);
    gatewayReturns(findingsJson("越界", 4, "bug"));
    pipeline.run(request(false));
    ArgumentCaptor<PostReviewCommand> captor = ArgumentCaptor.forClass(PostReviewCommand.class);
    verify(adapter).postReview(captor.capture());
    assertThat(captor.getValue().summaryBody()).doesNotContain("疑似注入尝试");
  }

  @Test
  void recordsCompletionAndFindingsAfterPublish() {
    // T1.2 账本化：发布成功 → recordRunCompletion（DONE，suppressed=指纹抑制数）+ 逐条
    // recordPublishedFinding（SQLite 形态的事实源；GitHub 形态为 no-op）
    gatewayReturns(findingsJson("越界", 4, "bug"));
    pipeline.run(request(false));
    ArgumentCaptor<LedgerRunMarker> runCaptor = ArgumentCaptor.forClass(LedgerRunMarker.class);
    verify(ledger).recordRunCompletion(eq(7), runCaptor.capture());
    assertThat(runCaptor.getValue().commitSha()).isEqualTo(HEAD);
    assertThat(runCaptor.getValue().status()).isEqualTo("DONE");
    assertThat(runCaptor.getValue().findings()).isEqualTo(1);
    assertThat(runCaptor.getValue().suppressed()).isZero();
    assertThat(runCaptor.getValue().commentId()).isEqualTo(9500L);
    String expectedFp =
        FingerprintCalculator.fingerprint(
            FILE,
            "bug",
            FILE_CONTENT.split("\n")[3],
            List.of(
                FILE_CONTENT.split("\n")[1],
                FILE_CONTENT.split("\n")[2],
                FILE_CONTENT.split("\n")[3],
                FILE_CONTENT.split("\n")[4],
                FILE_CONTENT.split("\n")[5]));
    ArgumentCaptor<LedgerFindingMarker> fpCaptor =
        ArgumentCaptor.forClass(LedgerFindingMarker.class);
    verify(ledger).recordPublishedFinding(eq(7), fpCaptor.capture());
    assertThat(fpCaptor.getValue().fingerprint()).isEqualTo(expectedFp);
    assertThat(fpCaptor.getValue().commentId()).isEqualTo(1L);
  }

  @Test
  @SuppressWarnings("unchecked")
  void recordsTemplateVersionsWithRunAndTraceEvent() throws java.io.IOException {
    // T2.11：模板版本与 run 关联（05§1.2 prompt_template_versions）——trace 事件（05§1.4）+
    // 发布成功后随 run 落库（05§1.5 prompt_template_version，SQLite 形态）
    gatewayReturns(findingsJson("越界", 4, "bug"));
    ReviewResult result = pipeline.run(request(false));
    ArgumentCaptor<java.util.Map<String, String>> versionsCaptor =
        ArgumentCaptor.forClass(java.util.Map.class);
    verify(ledger).recordTemplateVersions(eq(7), eq(result.runId()), versionsCaptor.capture());
    assertThat(versionsCaptor.getValue()).containsKey("bug-hunter/dataflow");
    assertThat(versionsCaptor.getValue().get("bug-hunter/dataflow"))
        .matches("dataflow@[0-9a-f]{8}");
    Path runDir = Path.of(result.traceRef().substring("file://".length()));
    String trace = Files.readString(runDir.resolve("trace.ndjson"));
    assertThat(trace)
        .contains("\"event\":\"template_versions\"")
        .contains("\"bug-hunter/dataflow\":\"dataflow@");
  }

  @Test
  void dryRunNeverRecordsTemplateVersions() {
    // dry-run 零写（05§6）：模板版本只在 trace，不落账本
    gatewayReturns(findingsJson("越界", 4, "bug"));
    pipeline.run(request(true));
    verify(ledger, never()).recordTemplateVersions(anyInt(), anyString(), any());
  }

  @Test
  void suggestionsCarriedInResultForLocalReport() {
    // T2.10：聚合后建议快照进 ReviewResult（本地 pre-PR 报告建议清单的数据源，02§5 本地形态）
    gatewayReturns(findingsJson("空列表越界", 4, "bug"));
    ReviewResult result = pipeline.run(request(false));
    assertThat(result.suggestions()).hasSize(1);
    var s = result.suggestions().get(0);
    assertThat(s.severity()).isEqualTo("IMPORTANT");
    assertThat(s.title()).isEqualTo("空列表越界");
    assertThat(s.file()).isEqualTo(FILE);
    assertThat(s.line()).isEqualTo(4);
    assertThat(s.description()).isNotEmpty();
  }

  @Test
  void recordsDegradedCompletionWhenSchemaDropsAll() {
    // 降级完成也是完整发布（02§4.1）：完成标记以 DEGRADED 入账
    scripted.review("a").review("b").review("c");
    pipeline.run(request(false));
    ArgumentCaptor<LedgerRunMarker> runCaptor = ArgumentCaptor.forClass(LedgerRunMarker.class);
    verify(ledger).recordRunCompletion(eq(7), runCaptor.capture());
    assertThat(runCaptor.getValue().status()).isEqualTo("DEGRADED");
  }

  @Test
  void supersedesPreviousRoundSummaryAfterPublish() {
    // 07§2 验收 3 编排侧 / 02§5：新 run 发布成功 → PATCH 上一轮 Summary issue comment
    // 加 [已被 run_xxx 取代] 前缀（inline 不删——无删除调用）
    when(ledger.completedRuns(7))
        .thenReturn(
            List.of(
                new LedgerRunMarker(
                    "run_old", "9999999000000000000000000000000000000000", "DONE", 1, 0, 88L)));
    when(ledger.comment(7, 88L))
        .thenReturn(
            Optional.of(
                new LedgerComment(
                    88L,
                    LedgerComment.KIND_ISSUE,
                    "cra-bot",
                    "旧 Summary 正文",
                    Instant.now(),
                    null)));
    gatewayReturns(findingsJson("越界", 4, "bug"));
    ReviewResult result = pipeline.run(request(false));
    assertThat(result.status()).isEqualTo(RunStatus.SUCCESS);
    ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
    verify(adapter).updateSummaryComment(any(), eq(7), eq(88L), bodyCaptor.capture());
    assertThat(bodyCaptor.getValue()).startsWith("[已被 run_").contains("已被 ").contains("取代");
    assertThat(bodyCaptor.getValue()).contains("旧 Summary 正文");
  }

  @Test
  void supersedeSkipsSameCommitPrefixedAndUnavailable() {
    // 幂等与降级：同 commit 轮、已带前缀轮、body 不可得轮——均不 PATCH
    when(ledger.completedRuns(7))
        .thenReturn(
            List.of(
                new LedgerRunMarker("run_same", HEAD, "DONE", 1, 0, 90L),
                new LedgerRunMarker(
                    "run_prefixed", "9999999000000000000000000000000000000000", "DONE", 1, 0, 91L),
                new LedgerRunMarker(
                    "run_gone", "8888888000000000000000000000000000000000", "DONE", 1, 0, 92L)));
    when(ledger.comment(7, 90L))
        .thenReturn(
            Optional.of(
                new LedgerComment(
                    90L, LedgerComment.KIND_ISSUE, "cra-bot", "同 commit 轮", Instant.now(), null)));
    when(ledger.comment(7, 91L))
        .thenReturn(
            Optional.of(
                new LedgerComment(
                    91L,
                    LedgerComment.KIND_ISSUE,
                    "cra-bot",
                    "[已被 run_z 取代]\n\n旧",
                    Instant.now(),
                    null)));
    when(ledger.comment(7, 92L)).thenReturn(Optional.empty());
    gatewayReturns(findingsJson("越界", 4, "bug"));
    ReviewResult result = pipeline.run(request(false));
    assertThat(result.status()).isEqualTo(RunStatus.SUCCESS);
    verify(adapter, never()).updateSummaryComment(any(), anyInt(), anyLong(), anyString());
  }

  @Test
  void supersedeFailureDegradesButRunCompletes() {
    // 收尾 PATCH 失败不回滚完成标记（run 已完整发布，02§5）：run SUCCESS + degradation 可见
    when(ledger.completedRuns(7))
        .thenReturn(
            List.of(
                new LedgerRunMarker(
                    "run_old", "9999999000000000000000000000000000000000", "DONE", 1, 0, 88L)));
    when(ledger.comment(7, 88L))
        .thenReturn(
            Optional.of(
                new LedgerComment(
                    88L,
                    LedgerComment.KIND_ISSUE,
                    "cra-bot",
                    "旧 Summary 正文",
                    Instant.now(),
                    null)));
    doThrow(new org.cra.common.exception.CraIntegrationException("github 5xx"))
        .when(adapter)
        .updateSummaryComment(any(), anyInt(), anyLong(), anyString());
    gatewayReturns(findingsJson("越界", 4, "bug"));
    ReviewResult result = pipeline.run(request(false));
    assertThat(result.status()).isEqualTo(RunStatus.SUCCESS);
    assertThat(result.stats().degradations())
        .anyMatch(d -> d.startsWith("summary-supersede-failed"));
    verify(ledger).recordRunCompletion(eq(7), any());
  }

  @Test
  void noLedgerWritesOnDryRun() {
    // dry-run 零写（05§6）扩到账本写侧
    gatewayReturns(findingsJson("越界", 4, "bug"));
    pipeline.run(request(true));
    verify(ledger, never()).recordRunCompletion(anyInt(), any());
    verify(ledger, never()).recordPublishedFinding(anyInt(), any());
    verify(adapter, never()).updateSummaryComment(any(), anyInt(), anyLong(), anyString());
  }

  @Test
  void noLedgerWritesWhenSummaryPublishFails() {
    // Summary 发布失败 = 无完成标记 = 不完整 run：不回写账本、不做过期收尾（重跑走补发，02§4.1）
    doReturn(new PrReviewResult(7, Optional.of("9001"), Optional.empty(), List.of()))
        .when(adapter)
        .postReview(any());
    when(ledger.completedRuns(7))
        .thenReturn(
            List.of(
                new LedgerRunMarker(
                    "run_old", "9999999000000000000000000000000000000000", "DONE", 1, 0, 88L)));
    gatewayReturns(findingsJson("越界", 4, "bug"));
    pipeline.run(request(false));
    verify(ledger, never()).recordRunCompletion(anyInt(), any());
    verify(ledger, never()).recordPublishedFinding(anyInt(), any());
    verify(adapter, never()).updateSummaryComment(any(), anyInt(), anyLong(), anyString());
  }

  @Test
  void dryRunNeverWrites() {
    // 07§1 验收 2：dry-run 全程零写（08§5 场景 12）
    gatewayReturns(findingsJson("越界", 4, "bug"));
    ReviewResult result = pipeline.run(request(true));
    assertThat(result.status()).isEqualTo(RunStatus.SUCCESS);
    assertThat(result.stats().findingsPublished()).isEqualTo(1); // would-be
    verify(adapter, never()).postReview(any());
    verify(adapter, times(1)).fetchDiff(any(), anyString(), anyString()); // 读操作允许
  }

  @Test
  void invalidAnchorFallsBackToSummary() {
    // 07§1 验收 1：位置非法的 finding 进 Summary 而非 422（08§5 场景 5 的前置）
    gatewayReturns(findingsJson("不在 hunk 内", 500, "bug"));
    ReviewResult result = pipeline.run(request(false));
    ArgumentCaptor<PostReviewCommand> captor = ArgumentCaptor.forClass(PostReviewCommand.class);
    verify(adapter).postReview(captor.capture());
    assertThat(captor.getValue().inlineComments()).isEmpty(); // 不发 inline，避免 422
    assertThat(captor.getValue().summaryBody()).contains("位置非法");
    assertThat(captor.getValue().summaryBody()).contains("不在 hunk 内");
    assertThat(result.stats().anchorFallback()).isEqualTo(1);
    assertThat(result.status()).isEqualTo(RunStatus.SUCCESS);
  }

  @Test
  void inlineAndSummaryCarryMarkersAndEscaping() {
    // 05§1.3 规则 3：正文 <!-- 转义 + 标记行最后追加；Summary 文末 DONE 标记
    gatewayReturns(findingsJson("越界", 4, "bug"));
    pipeline.run(request(false));
    ArgumentCaptor<PostReviewCommand> captor = ArgumentCaptor.forClass(PostReviewCommand.class);
    verify(adapter).postReview(captor.capture());
    PostReviewCommand cmd = captor.getValue();
    String inline = cmd.inlineComments().get(0).body();
    assertThat(inline).contains("d&lt;!-- injected -->描述"); // 仅开标记转义（规则 3）
    assertThat(inline.indexOf("&lt;!-- injected")).isLessThan(inline.lastIndexOf("<!-- cra:v=2.1"));
    assertThat(inline)
        .containsPattern(
            Pattern.compile(
                "<!-- cra:v=2\\.1 \\| fp=[0-9a-f]{16} \\| file=src/Foo.java \\| cat=bug \\| id=f_[0-9a-f]+ "
                    + "\\| run=run_\\S+ \\| commit="
                    + HEAD
                    + " \\| line=4 -->"));
    assertThat(cmd.summaryBody())
        .containsPattern(
            Pattern.compile(
                "<!-- cra:v=2\\.1 \\| run=run_\\S+ \\| commit="
                    + HEAD
                    + " \\| status=DONE \\| findings=1 \\| suppressed=0 -->"));
  }

  @Test
  void schemaRetryThenSuccess() {
    scripted.review("这不是 JSON").review(findingsJson("越界", 4, "bug"));
    ReviewResult result = pipeline.run(request(false));
    assertThat(result.status()).isEqualTo(RunStatus.SUCCESS);
    assertThat(result.stats().findingsPublished()).isEqualTo(1);
    assertThat(result.stats().degradations()).anyMatch(d -> d.startsWith("schema_retry_"));
  }

  @Test
  void schemaExhaustedDegradesWithDoneMarker() {
    // 三次全非法：候选全丢 → DEGRADED 收尾且发布 DONE 标记（降级完成也是完整发布，02§4.1）
    scripted.review("a").review("b").review("c");
    ReviewResult result = pipeline.run(request(false));
    assertThat(result.status()).isEqualTo(RunStatus.DEGRADED);
    assertThat(result.stats().findingsPublished()).isZero();
    ArgumentCaptor<PostReviewCommand> captor = ArgumentCaptor.forClass(PostReviewCommand.class);
    verify(adapter).postReview(captor.capture());
    assertThat(captor.getValue().summaryBody())
        .contains("DEGRADED")
        .contains("schema-violation-all-dropped");
  }

  @Test
  void llmUnavailableFailsRunWithoutPublish() {
    scripted.reviewUnavailable();
    ReviewResult result = pipeline.run(request(false));
    assertThat(result.status()).isEqualTo(RunStatus.FAILED);
    verify(adapter, never()).postReview(any());
  }

  @Test
  void briefAndTracePersistedWithFullText() {
    // 07§1 验收 4（本地侧）：trace 可读且含 brief 全文
    gatewayReturns(findingsJson("越界", 4, "bug"));
    ReviewResult result = pipeline.run(request(false));
    Path runDir = Path.of(result.traceRef().substring("file://".length()));
    assertThat(Files.exists(runDir.resolve("trace.ndjson"))).isTrue();
    try {
      List<Path> briefs;
      try (java.util.stream.Stream<Path> listed = Files.list(runDir.resolve("briefs"))) {
        briefs = listed.filter(p -> p.getFileName().toString().startsWith("bug-hunter")).toList();
      }
      assertThat(briefs).isNotEmpty();
      String brief = Files.readString(briefs.get(0));
      assertThat(brief).contains("UNTRUSTED_CONTENT").contains("# user");
      String trace = Files.readString(runDir.resolve("trace.ndjson"));
      assertThat(trace)
          .contains("\"event\":\"run_start\"")
          .contains("\"event\":\"agent_start\"")
          .contains("\"event\":\"agent_output\"")
          .contains("\"event\":\"anchor_check\"")
          .contains("\"event\":\"publish\"")
          .contains("\"event\":\"run_end\"");
    } catch (java.io.IOException e) {
      throw new AssertionError(e);
    }
  }

  @Test
  void adapterFailureDegradesToFailedRun() {
    when(adapter.fetchDiff(any(), anyString(), anyString()))
        .thenThrow(new org.cra.common.exception.CraIntegrationException("github down"));
    ReviewResult result = pipeline.run(request(false));
    assertThat(result.status()).isEqualTo(RunStatus.FAILED);
    assertThat(result.summaryLine()).contains("run failed");
    verify(adapter, never()).postReview(any());
  }

  @Test
  void emptyFindingsStillPublishSummaryWithDoneMarker() {
    // 空结果合法且被鼓励（04§5 反模式 2）——仍发 Summary + DONE（验收 1 的空结果陈述）
    gatewayReturns("{\"findings\":[]}");
    ReviewResult result = pipeline.run(request(false));
    assertThat(result.status()).isEqualTo(RunStatus.SUCCESS);
    ArgumentCaptor<PostReviewCommand> captor = ArgumentCaptor.forClass(PostReviewCommand.class);
    verify(adapter).postReview(captor.capture());
    assertThat(captor.getValue().summaryBody()).contains("本次未发现问题");
    assertThat(captor.getValue().summaryBody()).contains("status=DONE");
    assertThat(captor.getValue().inlineComments()).isEmpty();
  }

  // ---------- M1：T1.3–T1.7（07§2 验收 4 与配套机制） ----------

  private static String bigPatch(int lines) {
    StringBuilder sb = new StringBuilder("@@ -1," + lines + " +" + lines + " @@\n");
    // 大 diff 判据是 +/- 内容行（02§4.4）：context 行不计，这里必须造真实新增行
    for (int i = 0; i < lines; i++) {
      sb.append("+line ").append(i).append("\n");
    }
    return sb.toString();
  }

  @Test
  void largeDiffDowngradedAndDeclaredInSummary() {
    // 07§2 验收 4：4000+ 行 diff 正常完成且降档、覆盖范围在 Summary 如实声明（AI Coding 放宽档）
    String patch = bigPatch(4100);
    when(adapter.fetchDiff(any(), anyString(), anyString()))
        .thenReturn(
            new DiffResult(
                BASE,
                HEAD,
                List.of(
                    new DiffFile(
                        FILE, null, "modified", patch, List.of(new DiffHunk(1, 4100, 1, 4100)))),
                List.of()));
    when(adapter.readFile(any(), anyString(), anyString()))
        .thenAnswer(
            inv -> {
              String path = inv.getArgument(2);
              if ("REVIEW.md".equals(path)) {
                throw new org.cra.common.exception.CraIntegrationException("404 no review md");
              }
              return FileContent.ofText(FILE, "x\n".repeat(4100));
            });
    gatewayReturns(findingsJson("越界", 4, "bug"));
    ReviewResult result = pipeline.run(request(false));
    assertThat(result.status()).isEqualTo(RunStatus.SUCCESS);
    assertThat(result.summaryLine()).contains("depth=standard");
    ArgumentCaptor<PostReviewCommand> captor = ArgumentCaptor.forClass(PostReviewCommand.class);
    verify(adapter).postReview(captor.capture());
    String summary = captor.getValue().summaryBody();
    assertThat(summary).contains("降为 Quick 质量").contains("覆盖：评审 1/1 个变更文件").contains("窗口 ±15");
    assertThat(result.stats().coverage().filesReviewed()).isEqualTo(1);
    assertThat(result.stats().suppressedByTruncation()).isZero();
  }

  @Test
  void hardCapRestrictsToPriorityPathsWithDeclaration() {
    // 02§4.4：>16k 行只评审 priority_paths 并声明覆盖范围（AI Coding 放宽档）
    String patch = bigPatch(16100);
    DiffFile billing =
        new DiffFile(
            "billing/Charge.java",
            null,
            "modified",
            patch,
            List.of(new DiffHunk(1, 16100, 1, 16100)));
    DiffFile other =
        new DiffFile(
            "misc/Other.java", null, "modified", patch, List.of(new DiffHunk(1, 16100, 1, 16100)));
    when(adapter.fetchDiff(any(), anyString(), anyString()))
        .thenReturn(new DiffResult(BASE, HEAD, List.of(billing, other), List.of()));
    when(adapter.readFile(any(), anyString(), anyString()))
        .thenAnswer(
            inv -> {
              String path = inv.getArgument(2);
              if ("REVIEW.md".equals(path)) {
                return FileContent.ofText("REVIEW.md", "## Priority paths\n- billing/**\n");
              }
              return FileContent.ofText(path, "x\n");
            });
    gatewayReturns(findingsJson("越界", 4, "bug"));
    ReviewResult result = pipeline.run(request(false));
    ArgumentCaptor<PostReviewCommand> captor = ArgumentCaptor.forClass(PostReviewCommand.class);
    verify(adapter).postReview(captor.capture());
    String summary = captor.getValue().summaryBody();
    assertThat(summary).contains("仅评审 priority_paths").contains("跳过").contains("misc/Other.java");
    assertThat(result.stats().coverage().filesReviewed()).isEqualTo(1);
    assertThat(result.stats().coverage().filesChanged()).isEqualTo(2);
  }

  @Test
  void budgetLadderTruncatesAndCountsSuppressed() {
    // 02§4.4：≥85% → 候选按 severity 只取 top-10，丢弃计数进 stats/Summary/完成标记
    // T2.4 算术适配：评审两批 720 token，验证两批各 36（auto-confirm 压小）；预算 830——
    // 评审后 720/830=87% ≥85% 触发发布截断，验证后 792/830=95% <100% 验证波两批全过
    scripted.autoTokensIn = 30;
    scripted.autoTokensOut = 6;
    org.cra.core.api.PipelineOptions tiny =
        new org.cra.core.api.PipelineOptions(830, 0.70, 0.85, 2000, 8000, 3, "never", 20, false);
    pipeline =
        new ReviewPipeline(
            adapter,
            ledger,
            scripted,
            ReviewCrew.ofBugHunters(
                List.of(
                    new BugHunterDefinition(
                        BugHunterDefinition.Perspective.DATAFLOW, new TemplateLoader()),
                    new BugHunterDefinition(
                        BugHunterDefinition.Perspective.BOUNDARY, new TemplateLoader()))),
            new VerifierAgentDefinition(new TemplateLoader()),
            new JudgeAgentDefinition(new TemplateLoader()),
            runRoot,
            tiny,
            null);
    // 两个不同模块文件 → 2 个 chunk；每次评审调用 300/60 token → 两批后 720/830 = 87% ≥85%
    DiffFile a =
        new DiffFile("mod-a/A.java", null, "modified", PATCH, List.of(new DiffHunk(1, 5, 1, 6)));
    DiffFile b =
        new DiffFile("mod-b/B.java", null, "modified", PATCH, List.of(new DiffHunk(1, 5, 1, 6)));
    when(adapter.fetchDiff(any(), anyString(), anyString()))
        .thenReturn(new DiffResult(BASE, HEAD, List.of(a, b), List.of()));
    // T2.8 语义适配：同 file ±3 行同 category 会合并——12 条候选须分文件且行距 >3 才能保住截断场景
    StringBuilder findingsA = new StringBuilder("{\"findings\":[");
    StringBuilder findingsB = new StringBuilder("{\"findings\":[");
    for (int i = 0; i < 6; i++) {
      int line = 1 + i * 4;
      if (i > 0) {
        findingsA.append(",");
        findingsB.append(",");
      }
      findingsA.append(findingInFile("mod-a/A.java", "问题" + i, line, "bug"));
      findingsB.append(findingInFile("mod-b/B.java", "问题" + i, line, "bug"));
    }
    findingsA.append("]}");
    findingsB.append("]}");
    scripted.review(findingsA.toString(), 300, 60).review(findingsB.toString(), 300, 60);
    ReviewResult result = pipeline.run(request(false));
    // 12 条候选 → top-10 发布，suppressed=2；截断计数进 Summary 与完成标记
    assertThat(result.stats().findingsCandidate()).isEqualTo(12);
    assertThat(result.stats().suppressedByTruncation()).isEqualTo(2);
    ArgumentCaptor<PostReviewCommand> captor = ArgumentCaptor.forClass(PostReviewCommand.class);
    verify(adapter).postReview(captor.capture());
    String summary = captor.getValue().summaryBody();
    assertThat(summary)
        .contains("因预算截断丢弃 2 条候选（未验证、未发布；02§4.4）")
        .containsPattern(Pattern.compile("status=DONE[^\n]*suppressed=2"));
    assertThat(result.stats().degradations()).contains("budget-truncate-85");
  }

  @Test
  void reviewMdModifiedInPrWarnsInSummary() {
    // 04§3.2 规则 2：PR 含 REVIEW.md 变更 → 按 base 策略评审 + Summary 醒目警告
    DiffFile policy =
        new DiffFile(
            "REVIEW.md",
            null,
            "modified",
            "@@ -1 +1,2 @@\n+x\n",
            List.of(new DiffHunk(1, 1, 1, 2)));
    when(adapter.fetchDiff(any(), anyString(), anyString()))
        .thenReturn(
            new DiffResult(
                BASE,
                HEAD,
                List.of(
                    policy,
                    new DiffFile(FILE, null, "modified", PATCH, List.of(new DiffHunk(1, 5, 1, 6)))),
                List.of()));
    when(adapter.readFile(any(), anyString(), anyString()))
        .thenAnswer(
            inv -> {
              String path = inv.getArgument(2);
              if ("REVIEW.md".equals(path)) {
                return FileContent.ofText("REVIEW.md", "## Conventions\n- 用 Jackson\n");
              }
              return FileContent.ofText(FILE, FILE_CONTENT);
            });
    gatewayReturns(findingsJson("越界", 4, "bug"));
    ReviewResult result = pipeline.run(request(false));
    ArgumentCaptor<PostReviewCommand> captor = ArgumentCaptor.forClass(PostReviewCommand.class);
    verify(adapter).postReview(captor.capture());
    assertThat(captor.getValue().summaryBody())
        .contains("REVIEW.md 在本 PR 中被修改")
        .contains("策略变更需 owner 审批");
    assertThat(result.status()).isEqualTo(RunStatus.SUCCESS);
  }

  @Test
  void skipPathsExcludedFromScopeWithDeclaration() {
    // 07§2 验收 5 的策略面：base REVIEW.md Skip paths 生效，被跳过文件如实声明
    when(adapter.readFile(any(), anyString(), anyString()))
        .thenAnswer(
            inv -> {
              String path = inv.getArgument(2);
              if ("REVIEW.md".equals(path)) {
                return FileContent.ofText("REVIEW.md", "## Skip paths\n- generated/**\n");
              }
              return FileContent.ofText(FILE, FILE_CONTENT);
            });
    DiffFile generated =
        new DiffFile(
            "generated/Gen.java", null, "modified", PATCH, List.of(new DiffHunk(1, 5, 1, 6)));
    when(adapter.fetchDiff(any(), anyString(), anyString()))
        .thenReturn(
            new DiffResult(
                BASE,
                HEAD,
                List.of(
                    new DiffFile(FILE, null, "modified", PATCH, List.of(new DiffHunk(1, 5, 1, 6))),
                    generated),
                List.of()));
    gatewayReturns(findingsJson("越界", 4, "bug"));
    ReviewResult result = pipeline.run(request(false));
    ArgumentCaptor<PostReviewCommand> captor = ArgumentCaptor.forClass(PostReviewCommand.class);
    verify(adapter).postReview(captor.capture());
    String summary = captor.getValue().summaryBody();
    assertThat(summary).contains("跳过").contains("generated/Gen.java");
    assertThat(result.stats().coverage().filesReviewed()).isEqualTo(1);
    // skipped 文件不进任何 brief（只有 src/Foo.java 一个 chunk）
    assertThat(scripted.reviewCalls).hasSize(1);
  }

  @Test
  void nitBudgetFromPolicyCapsNitPublication() {
    // 07 T1.7：REVIEW.md Nit budget 优先于默认；超预算 nit 丢弃并声明
    when(adapter.readFile(any(), anyString(), anyString()))
        .thenAnswer(
            inv -> {
              String path = inv.getArgument(2);
              if ("REVIEW.md".equals(path)) {
                return FileContent.ofText("REVIEW.md", "## Nit budget: 1\n");
              }
              return FileContent.ofText(FILE, FILE_CONTENT);
            });
    gatewayReturns(
        "{\"findings\":["
            + findingJson("nit-1", 4, "bug")
            + ","
            + findingJson("nit-2", 4, "convention")
            + "]}");
    // T2.8 语义适配：同 file 同行必须跨 category 才不会被 run 内合并（±3 行同 category）；
    // findingJson 硬编码 important——nit 预算机制由 FindingAggregatorTest 覆盖，本用例只验证合并语义
    ReviewResult result = pipeline.run(request(false));
    assertThat(result.stats().findingsCandidate()).isEqualTo(2);
  }

  @Test
  void blameRecheckGroupsConfirmedPreExisting() {
    // 07 T1.7：嫌疑 finding 经 blame 确证（锚行最后修改 commit 不在 PR 集内）→ Pre-existing 分组
    org.cra.git.api.LocalRepoContext repo = mock(org.cra.git.api.LocalRepoContext.class);
    when(repo.localRepoRoot()).thenReturn(Optional.of("/w"));
    when(repo.blame(anyString(), anyInt(), anyString()))
        .thenReturn(new org.cra.common.git.BlameResult(FILE, 4, "old-commit-outside-pr", "alice"));
    pipeline =
        new ReviewPipeline(
            adapter,
            ledger,
            scripted,
            ReviewCrew.ofBugHunters(
                List.of(
                    new BugHunterDefinition(
                        BugHunterDefinition.Perspective.DATAFLOW, new TemplateLoader()),
                    new BugHunterDefinition(
                        BugHunterDefinition.Perspective.BOUNDARY, new TemplateLoader()))),
            new VerifierAgentDefinition(new TemplateLoader()),
            new JudgeAgentDefinition(new TemplateLoader()),
            runRoot,
            org.cra.core.api.PipelineOptions.defaults(),
            repo);
    gatewayReturns(
        """
        {"findings":[{"category":"bug","title":"旧问题","description":"d","failure_mode":"f",
          "file":"src/Foo.java","line":4,"side":"RIGHT","evidence":"e",
          "severity_proposed":"nit","pre_existing_suspect":true,"confidence":0.7}]}"""
            .formatted());
    ReviewResult result = pipeline.run(request(false));
    assertThat(result.stats().preExisting()).isEqualTo(1);
    ArgumentCaptor<PostReviewCommand> captor = ArgumentCaptor.forClass(PostReviewCommand.class);
    verify(adapter).postReview(captor.capture());
    assertThat(captor.getValue().summaryBody()).contains("### Pre-existing").contains("旧问题");
  }

  @Test
  void blameUnavailableDeclaredInSummary() {
    // 05§2.1：无本地仓库 → 复核不可得，不标注 + Summary 声明
    gatewayReturns(
        """
        {"findings":[{"category":"bug","title":"嫌疑","description":"d","failure_mode":"f",
          "file":"src/Foo.java","line":4,"side":"RIGHT","evidence":"e",
          "severity_proposed":"important","pre_existing_suspect":true}]}"""
            .formatted());
    ReviewResult result = pipeline.run(request(false));
    ArgumentCaptor<PostReviewCommand> captor = ArgumentCaptor.forClass(PostReviewCommand.class);
    verify(adapter).postReview(captor.capture());
    assertThat(captor.getValue().summaryBody()).contains("pre-existing 复核不可得：无本地仓库");
    assertThat(result.stats().preExisting()).isZero();
  }

  @Test
  void smallDiffAutoQuickTierDeclared() {
    // 02§4.3：默认小 diff（<300 行）自动 quick 档
    gatewayReturns(findingsJson("越界", 4, "bug"));
    ReviewResult result = pipeline.run(request(false));
    assertThat(result.summaryLine()).contains("depth=quick");
  }

  @Test
  void anchorStatusHelperUsesDiffHunksOnly() {
    // 非法侧 LEFT 一律降级（05§2.2 校验按 PR diff hunk）
    gatewayReturns(
        """
        {"findings":[{"category":"bug","title":"t","description":"d","failure_mode":"f",
          "file":"src/Foo.java","line":2,"side":"LEFT","evidence":"e",
          "severity_proposed":"nit","pre_existing_suspect":false}]}"""
            .formatted());
    ReviewResult result = pipeline.run(request(false));
    assertThat(result.stats().anchorFallback()).isEqualTo(1);
    assertThat(AnchorStatus.SUMMARY_FALLBACK.name()).isEqualTo("SUMMARY_FALLBACK");
  }

  /**
   * 键控扩展（05§1.3）兼容桩：pipeline 走 ReviewKey 方法，测试的既有 int 键 stub 通过委托继续生效 （Mockito 不调用 default
   * 方法，必须显式桥接）。
   */
  private void bridgeKeyedLedgerToIntStubs() {
    when(ledger.findCompletion(any(org.cra.common.git.ReviewKey.class)))
        .thenAnswer(
            inv -> {
              org.cra.common.git.ReviewKey k = inv.getArgument(0);
              return ledger.findCompletion(k.prNumber(), k.commitSha());
            });
    when(ledger.publishedFindings(any(org.cra.common.git.ReviewKey.class)))
        .thenAnswer(
            inv ->
                ledger.publishedFindings(
                    ((org.cra.common.git.ReviewKey) inv.getArgument(0)).prNumber()));
    when(ledger.completedRuns(any(org.cra.common.git.ReviewKey.class)))
        .thenAnswer(
            inv ->
                ledger.completedRuns(
                    ((org.cra.common.git.ReviewKey) inv.getArgument(0)).prNumber()));
    when(ledger.suspectMarkerCount(any(org.cra.common.git.ReviewKey.class)))
        .thenAnswer(
            inv ->
                ledger.suspectMarkerCount(
                    ((org.cra.common.git.ReviewKey) inv.getArgument(0)).prNumber()));
    when(ledger.comment(any(org.cra.common.git.ReviewKey.class), anyLong()))
        .thenAnswer(
            inv ->
                ledger.comment(
                    ((org.cra.common.git.ReviewKey) inv.getArgument(0)).prNumber(),
                    inv.getArgument(1)));
    doAnswer(
            inv -> {
              org.cra.common.git.ReviewKey k = inv.getArgument(0);
              ledger.recordRunCompletion(k.prNumber(), inv.getArgument(1));
              return null;
            })
        .when(ledger)
        .recordRunCompletion(
            any(org.cra.common.git.ReviewKey.class), any(org.cra.common.git.LedgerRunMarker.class));
    doAnswer(
            inv -> {
              org.cra.common.git.ReviewKey k = inv.getArgument(0);
              ledger.recordPublishedFinding(k.prNumber(), inv.getArgument(1));
              return null;
            })
        .when(ledger)
        .recordPublishedFinding(
            any(org.cra.common.git.ReviewKey.class),
            any(org.cra.common.git.LedgerFindingMarker.class));
    doAnswer(
            inv -> {
              org.cra.common.git.ReviewKey k = inv.getArgument(0);
              ledger.recordTemplateVersions(k.prNumber(), inv.getArgument(1), inv.getArgument(2));
              return null;
            })
        .when(ledger)
        .recordTemplateVersions(any(org.cra.common.git.ReviewKey.class), anyString(), anyMap());
  }

  // ---------- M4a：风险分级与签字建议（07§5；01§3.4） ----------

  private static DiffResult highRiskDiff() {
    return new DiffResult(
        BASE,
        HEAD,
        List.of(
            new DiffFile(
                "src/main/java/com/acme/auth/AuthService.java",
                null,
                "modified",
                PATCH,
                List.of(new DiffHunk(1, 5, 1, 6)))),
        List.of(new org.cra.common.git.CommitSummary("c1", "fix: auth")));
  }

  private ReviewPipeline signoffPipeline(SignoffOptions options) {
    return new ReviewPipeline(
        adapter,
        ledger,
        scripted,
        ReviewCrew.ofBugHunters(
            List.of(
                new BugHunterDefinition(
                    BugHunterDefinition.Perspective.DATAFLOW, new TemplateLoader()),
                new BugHunterDefinition(
                    BugHunterDefinition.Perspective.BOUNDARY, new TemplateLoader()))),
        new VerifierAgentDefinition(new TemplateLoader()),
        new JudgeAgentDefinition(new TemplateLoader()),
        runRoot,
        org.cra.core.api.PipelineOptions.defaults(),
        null,
        org.cra.core.api.CrossCheckerOptions.disabled(),
        options);
  }

  @Test
  void signoffAdviceIsAdvisoryAndBoundToHeadCommit() throws java.io.IOException {
    // 07§5 M4a 验收：高风险 diff → 建议 2 人签 + 绑定 commit_sha；报告字段显式声明非审批
    when(adapter.fetchDiff(any(), anyString(), anyString())).thenReturn(highRiskDiff());
    pipeline = signoffPipeline(SignoffOptions.defaults(List.of("zingaltern")));
    gatewayReturns("{\"findings\":[]}");
    ReviewResult result =
        pipeline.run(
            new ReviewRequest(
                "o",
                "r",
                7,
                BASE,
                HEAD,
                Depth.STANDARD,
                false,
                false,
                new SignoffContext("zingaltern", TestsStatus.PASSED)));

    assertThat(result.signoffAdvice().advisoryOnly()).isTrue();
    assertThat(result.signoffAdvice().tier()).isEqualTo(RiskTier.HIGH);
    assertThat(result.signoffAdvice().recommendedSigners()).isEqualTo(2);
    assertThat(result.signoffAdvice().commitSha()).isEqualTo(HEAD);
    assertThat(result.signoffAdvice().decision()).isEqualTo("ADVISORY_ONLY");
    assertThat(result.signoffAdvice().approval()).isFalse();
    assertThat(result.signoffAdvice().agentApprover()).isFalse();

    ArgumentCaptor<PostReviewCommand> captor = ArgumentCaptor.forClass(PostReviewCommand.class);
    verify(adapter).postReview(captor.capture());
    String summary = captor.getValue().summaryBody();
    assertThat(summary)
        .contains("### 签字建议（建议，非审批）")
        .contains("风险分级：高风险 → 建议 2 人签字")
        .contains("绑定 commit：`abc1234`")
        .contains("approve 只对该 commit 有效")
        .contains("high-risk-path")
        .contains("agent 的赞成票不计为审批")
        .contains("agent 永远不是 approver")
        .contains("核实本次改动的意图、假设、设计")
        .contains("指挥 agent 写代码的人必须在签字名单上");
    Path runDir = Path.of(result.traceRef().substring("file://".length()));
    assertThat(Files.readString(runDir.resolve("trace.ndjson")))
        .contains("\"event\":\"signoff_advice\"")
        .contains("\"agent_approver\":false")
        .contains("\"recommended_signers\":2");
  }

  @Test
  void newCommitMarksPreviousSummarySignoffAdviceStale() {
    // 07§5 M4a 规矩 2：approve 只对特定 commit 有效——新 commit 取代旧 Summary 时旧建议标 stale（账本比对）
    String oldCommit = "9999999000000000000000000000000000000000";
    when(ledger.completedRuns(7))
        .thenReturn(List.of(new LedgerRunMarker("run_old", oldCommit, "DONE", 1, 0, 88L)));
    when(ledger.comment(7, 88L))
        .thenReturn(
            Optional.of(
                new LedgerComment(
                    88L,
                    LedgerComment.KIND_ISSUE,
                    "cra-bot",
                    """
                    ## cra 评审 Summary（run_old）

                    ### 签字建议（建议，非审批）
                    - 风险分级：高风险 → 建议 2 人签字
                    """,
                    Instant.now(),
                    null)));
    pipeline = signoffPipeline(SignoffOptions.defaults(List.of("zingaltern")));
    gatewayReturns("{\"findings\":[]}");
    ReviewResult result =
        pipeline.run(
            new ReviewRequest(
                "o",
                "r",
                7,
                BASE,
                HEAD,
                Depth.STANDARD,
                false,
                false,
                new SignoffContext("zingaltern", TestsStatus.PASSED)));
    assertThat(result.signoffAdvice().stale()).isFalse();

    ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
    verify(adapter).updateSummaryComment(any(), eq(7), eq(88L), bodyCaptor.capture());
    assertThat(bodyCaptor.getValue())
        .startsWith("[已被 run_")
        .contains("签字建议已 stale")
        .contains("`9999999`")
        .contains("`abc1234`")
        .contains("须重新核实");
  }

  @Test
  void signoffDisabledLeavesSummaryUnchanged() {
    // 默认（旧构造）关闭签字建议：既有 Summary 行为不变（M0–M3 基线）
    gatewayReturns("{\"findings\":[]}");
    ReviewResult result = pipeline.run(request(false));
    assertThat(result.signoffAdvice().advisoryOnly()).isFalse();
    ArgumentCaptor<PostReviewCommand> captor = ArgumentCaptor.forClass(PostReviewCommand.class);
    verify(adapter).postReview(captor.capture());
    assertThat(captor.getValue().summaryBody()).doesNotContain("### 签字建议");
  }

  // ---------- 评审范围默认不含测试文件（02§4.4） ----------

  private static DiffResult diffOf(String... paths) {
    return new DiffResult(
        BASE,
        HEAD,
        java.util.Arrays.stream(paths)
            .map(
                path ->
                    new DiffFile(path, null, "modified", PATCH, List.of(new DiffHunk(1, 5, 1, 6))))
            .toList(),
        List.of(new org.cra.common.git.CommitSummary("c1", "fix: something")));
  }

  private static org.cra.core.api.PipelineOptions optionsWithTestFiles(boolean includeTestFiles) {
    return new org.cra.core.api.PipelineOptions(
        500_000, 0.70, 0.85, 2000, 8000, 3, "never", 20, includeTestFiles);
  }

  private ReviewPipeline pipelineWith(org.cra.core.api.PipelineOptions options, ReviewCrew crew) {
    return new ReviewPipeline(
        adapter,
        ledger,
        scripted,
        crew,
        new VerifierAgentDefinition(new TemplateLoader()),
        new JudgeAgentDefinition(new TemplateLoader()),
        runRoot,
        options,
        null);
  }

  private static ReviewCrew crewWithTestReviewer() {
    return new ReviewCrew(
        List.of(
            new BugHunterDefinition(BugHunterDefinition.Perspective.DATAFLOW, new TemplateLoader()),
            new BugHunterDefinition(
                BugHunterDefinition.Perspective.BOUNDARY, new TemplateLoader())),
        null,
        new org.cra.agents.api.SpecialistReviewers.TestReviewer(new TemplateLoader()),
        null,
        null,
        null);
  }

  @Test
  void pureTestChangeDeclaredNotReviewableAndPublishesDoneWithoutLlmCalls() {
    // 02§4.4：纯测试 PR = 无可评审变更——Summary 如实声明、SUCCESS、DONE 标记照旧（幂等），零 LLM 调用
    when(adapter.fetchDiff(any(), anyString(), anyString()))
        .thenReturn(diffOf("src/test/java/demo/FooTest.java"));
    ReviewResult result = pipeline.run(request(false));
    assertThat(result.status()).isEqualTo(RunStatus.SUCCESS);
    assertThat(scripted.totalCalls()).isZero();
    ArgumentCaptor<PostReviewCommand> captor = ArgumentCaptor.forClass(PostReviewCommand.class);
    verify(adapter).postReview(captor.capture());
    String summary = captor.getValue().summaryBody();
    assertThat(summary)
        .contains("仅测试文件/无可评审变更，按策略不评审")
        .contains("测试文件按策略不评审：1 个")
        .contains("本次无可评审变更")
        .doesNotContain("本次未发现问题")
        .contains("status=DONE");
    assertThat(captor.getValue().inlineComments()).isEmpty();
    assertThat(result.stats().coverage().filesReviewed()).isZero();
    assertThat(result.stats().coverage().filesChanged()).isEqualTo(1);
  }

  @Test
  void emptyPlanNoteDistinguishesMixedTestAndPolicySkips() {
    // 02§4.4：测试文件跳过与 skip_paths/hard-cap 裁剪并存时，声明必须给出两个真实原因
    when(adapter.fetchDiff(any(), anyString(), anyString()))
        .thenReturn(diffOf("src/test/java/demo/FooTest.java", "generated/Gen.java"));
    when(adapter.readFile(any(), anyString(), anyString()))
        .thenAnswer(
            inv -> {
              String path = inv.getArgument(2);
              if ("REVIEW.md".equals(path)) {
                return FileContent.ofText("REVIEW.md", "## Skip paths\n- generated/**\n");
              }
              return FileContent.ofText(path, "class X {}");
            });
    ReviewResult result = pipeline.run(request(false));
    assertThat(result.status()).isEqualTo(RunStatus.SUCCESS);
    assertThat(scripted.totalCalls()).isZero();
    ArgumentCaptor<PostReviewCommand> captor = ArgumentCaptor.forClass(PostReviewCommand.class);
    verify(adapter).postReview(captor.capture());
    String summary = captor.getValue().summaryBody();
    assertThat(summary)
        .contains("测试文件 1 个按默认策略跳过")
        .contains("另 1 个被 skip_paths/hard-cap 裁剪")
        .doesNotContain("仅测试文件/无可评审变更");
  }

  @Test
  void reviewTestsSwitchRestoresTestFileScope() {
    // --review-tests / CRA_REVIEW_TESTS=true：测试文件恢复评审，覆盖声明不再出现"按策略不评审"
    pipeline =
        pipelineWith(
            optionsWithTestFiles(true),
            ReviewCrew.ofBugHunters(
                List.of(
                    new BugHunterDefinition(
                        BugHunterDefinition.Perspective.DATAFLOW, new TemplateLoader()),
                    new BugHunterDefinition(
                        BugHunterDefinition.Perspective.BOUNDARY, new TemplateLoader()))));
    when(adapter.fetchDiff(any(), anyString(), anyString()))
        .thenReturn(diffOf("src/main/java/demo/A.java", "src/test/java/demo/ATest.java"));
    gatewayReturns("{\"findings\":[]}");
    ReviewResult result = pipeline.run(request(false));
    assertThat(result.status()).isEqualTo(RunStatus.SUCCESS);
    ArgumentCaptor<PostReviewCommand> captor = ArgumentCaptor.forClass(PostReviewCommand.class);
    verify(adapter).postReview(captor.capture());
    String summary = captor.getValue().summaryBody();
    assertThat(summary).contains("覆盖：评审 2/2 个变更文件").doesNotContain("测试文件按策略不评审");
    assertThat(result.stats().coverage().filesReviewed()).isEqualTo(2);
  }

  @Test
  void testReviewerTriggerAndBriefUseFullDiffNotFilteredPlan() {
    // 02§4.4：2 生产 + 1 测试（比例 1/3 < 0.5）→ 触发；brief 仍列出被策略跳过的测试文件（"缺测试覆盖"的正向证据面不变）
    when(adapter.fetchDiff(any(), anyString(), anyString()))
        .thenReturn(
            diffOf(
                "src/main/java/demo/A.java",
                "src/main/java/demo/B.java",
                "src/test/java/demo/ATest.java"));
    pipeline = pipelineWith(optionsWithTestFiles(false), crewWithTestReviewer());
    gatewayReturns("{\"findings\":[]}");
    ReviewResult result = pipeline.run(request(false));
    assertThat(result.status()).isEqualTo(RunStatus.SUCCESS);
    List<org.cra.llm.api.LlmRequest> testCalls =
        scripted.reviewCalls.stream()
            .filter(call -> "test-reviewer".equals(call.caller()))
            .toList();
    assertThat(testCalls).hasSize(1);
    assertThat(testCalls.get(0).userPrompt())
        .contains("src/test/java/demo/ATest.java")
        .contains("按策略不评审");
    // 生产文件只评审 2 个（测试文件不进 chunk）
    assertThat(result.stats().coverage().filesReviewed()).isEqualTo(2);
  }

  @Test
  void testReviewerTriggerKeepsFullDiffRatioWhenTestsAreOutsideScope() {
    // 02§4.4：1 生产 + 1 测试（比例 0.5 ≥ 阈值）→ 不触发。
    // 若改用过滤后计划（只剩生产文件）比例恒为 0，会误触发 TestReviewer
    when(adapter.fetchDiff(any(), anyString(), anyString()))
        .thenReturn(diffOf("src/main/java/demo/A.java", "src/test/java/demo/ATest.java"));
    pipeline = pipelineWith(optionsWithTestFiles(false), crewWithTestReviewer());
    gatewayReturns("{\"findings\":[]}");
    ReviewResult result = pipeline.run(request(false));
    assertThat(result.status()).isEqualTo(RunStatus.SUCCESS);
    assertThat(scripted.reviewCalls)
        .extracting(org.cra.llm.api.LlmRequest::caller)
        .doesNotContain("test-reviewer");
    // 混合变更的覆盖声明：只评审生产文件，测试文件跳过数可见
    ArgumentCaptor<PostReviewCommand> captor = ArgumentCaptor.forClass(PostReviewCommand.class);
    verify(adapter).postReview(captor.capture());
    assertThat(captor.getValue().summaryBody())
        .contains("覆盖：评审 1/2 个变更文件")
        .contains("测试文件按策略不评审：1 个");
    assertThat(result.stats().coverage().filesReviewed()).isEqualTo(1);
  }
}
