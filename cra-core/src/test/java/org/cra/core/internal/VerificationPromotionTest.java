package org.cra.core.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
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
import org.cra.common.git.PostReviewCommand;
import org.cra.common.run.Depth;
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
 * M2 验证与晋升线行为测试（07§3 验收 3/5 的 mock 侧）：VerifierAgent 三值判定与晋升规则、重要级 REFUTED 确定性抽查保险丝、INSUFFICIENT
 * 受控补查、Judge 仲裁与抽查复核、验证不可得 UNVERIFIED 不发布、 反锚定质控信号 PASS/FAIL 入 trace 不阻断（03§4.2/§4.3、02§4.2）。LLM
 * 调用全部走 {@link ScriptedGateway} 脚本（08§3.1：真实 LLM 不进单测）。
 */
class VerificationPromotionTest {

  private static final String HEAD = "abc1234000000000000000000000000000000000";
  private static final String BASE = "def5678000000000000000000000000000000000";
  private static final String LAST_HEAD = "7777777000000000000000000000000000000000";
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
    when(ledger.completedRuns(anyInt())).thenReturn(List.of());
    bridgeKeyedLedgerToIntStubs();
    when(adapter.fetchDiff(any(), anyString(), anyString())).thenReturn(diff(BASE));
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
                                  org.cra.common.finding.AnchorStatus.INLINE,
                                  1L,
                                  ""))
                      .toList();
              return new org.cra.common.git.PrReviewResult(
                  cmd.prNumber(), Optional.of("9001"), Optional.of(9500L), items);
            });
    pipeline = newPipeline(org.cra.core.api.PipelineOptions.defaults());
  }

  private ReviewPipeline newPipeline(org.cra.core.api.PipelineOptions options) {
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
        options,
        null);
  }

  private static DiffResult diff(String base) {
    return new DiffResult(
        base,
        HEAD,
        List.of(new DiffFile(FILE, null, "modified", PATCH, List.of(new DiffHunk(1, 5, 1, 6)))),
        List.of(new org.cra.common.git.CommitSummary("c1", "fix: something")));
  }

  private static ReviewRequest request() {
    return new ReviewRequest("o", "r", 7, BASE, HEAD, Depth.STANDARD, false, false);
  }

  private static String oneFinding(String category, String severity) {
    return """
        {"findings":[{"category":"%s","title":"空列表越界","description":"当 items 为空时取末元素越界",
          "failure_mode":"触发条件：空列表；错误结果：IndexOutOfBoundsException",
          "file":"src/Foo.java","line":4,"side":"RIGHT",
          "evidence":"L4: return items.get(items.size() - 1);","severity_proposed":"%s",
          "pre_existing_suspect":false,"confidence":0.8}]}"""
        .formatted(category, severity);
  }

  private String traceOf(ReviewResult result) throws java.io.IOException {
    return Files.readString(
        Path.of(result.traceRef().substring("file://".length())).resolve("trace.ndjson"));
  }

  // ---------- T2.4：验证波与三值判定 ----------

  @Test
  void confirmedVerdictPublishesWithVerifierBriefPersisted() throws Exception {
    scripted.review(oneFinding("bug", "important"));
    ReviewResult result = pipeline.run(request());
    assertThat(result.status()).isEqualTo(RunStatus.SUCCESS);
    assertThat(result.stats().confirmed()).isEqualTo(1);
    assertThat(result.stats().findingsPublished()).isEqualTo(1);
    assertThat(scripted.verifierCalls).hasSize(1);
    // 验证 brief 含文件全文与待复核结论（分型组装：代码类 = finding 全文 + 文件全文 + 切片）
    String brief = scripted.verifierCalls.get(0).userPrompt();
    assertThat(brief).contains("src/Foo.java").contains("待复核结论").contains("空列表越界");
    String trace = traceOf(result);
    assertThat(trace)
        .contains("\"event\":\"verify\"")
        .contains("\"event\":\"anti_anchor_check\"")
        .contains("\"event\":\"promotion\"");
  }

  @Test
  void refutedVerdictDropsNitFindingWithoutJudge() throws Exception {
    // 03§4.3：REFUTED → 丢弃；nit 级不进抽查保险丝（仅 important/security 级）
    scripted.review(oneFinding("bug", "nit")).verifierRefuteAll();
    ReviewResult result = pipeline.run(request());
    assertThat(result.stats().rejected()).isEqualTo(1);
    assertThat(result.stats().findingsPublished()).isZero();
    assertThat(scripted.judgeCalls).isEmpty();
    ArgumentCaptor<PostReviewCommand> captor = ArgumentCaptor.forClass(PostReviewCommand.class);
    verify(adapter).postReview(captor.capture());
    assertThat(captor.getValue().inlineComments()).isEmpty();
    String trace = traceOf(result);
    assertThat(trace).contains("\"reason\":\"refuted-nit\"");
  }

  @Test
  void testCategoryFindingBriefCarriesTestFileIndex() {
    // v1.3.2 test 类分型：test 类候选的验证 brief 注入仓库测试文件清单（供核对是否已有覆盖）
    when(adapter.listFiles(any(), anyString(), anyString()))
        .thenReturn(List.of("src/test/java/FooTest.java", "src/test/java/BarTest.java"));
    scripted.review(
        "{\"findings\":[{\"category\":\"test\",\"title\":\"缺测试\",\"description\":\"d\","
            + "\"failure_mode\":\"f\",\"file\":\"src/Foo.java\",\"line\":4,\"side\":\"RIGHT\","
            + "\"evidence\":\"L4: x\",\"severity_proposed\":\"important\","
            + "\"pre_existing_suspect\":false}]}");
    pipeline.run(request());
    org.assertj.core.api.Assertions.assertThat(scripted.verifierCalls).hasSize(1);
    org.assertj.core.api.Assertions.assertThat(scripted.verifierCalls.get(0).userPrompt())
        .contains("仓库测试文件清单")
        .contains("FooTest.java");
  }

  @Test
  void verifierUnavailableRetriesOnceThenUnverifiedNotPublished() {
    // 02§4.2 验证不可得：缩上下文重试 1 次 → 仍失败 → UNVERIFIED 默认不发布，Summary 计数声明
    scripted.review(oneFinding("bug", "important")).verifierUnavailable();
    ReviewResult result = pipeline.run(request());
    assertThat(scripted.verifierCalls).hasSize(2); // 首轮 + 缩上下文重试各一次
    assertThat(result.stats().unverified()).isEqualTo(1);
    assertThat(result.stats().findingsPublished()).isZero();
    ArgumentCaptor<PostReviewCommand> captor = ArgumentCaptor.forClass(PostReviewCommand.class);
    verify(adapter).postReview(captor.capture());
    assertThat(captor.getValue().summaryBody())
        .contains("UNVERIFIED")
        // 判决头三态护栏（02§4.2）：验证不可得 ≠ 真无问题——不得出现 ✅
        .contains("**结论：⚠️ 无已确认问题，1 条候选未验证**")
        .doesNotContain("✅");
    assertThat(result.stats().degradations()).contains("verifier-unavailable");
  }

  @Test
  void publishUnverifiedLabeledMarksInlineComment() {
    // 05§5 review.publish-unverified=labeled：带 UNVERIFIED 标签发布
    pipeline =
        newPipeline(
            new org.cra.core.api.PipelineOptions(
                500_000, 0.70, 0.85, 2000, 8000, 3, "labeled", 20, false));
    scripted.review(oneFinding("bug", "important")).verifierUnavailable();
    ReviewResult result = pipeline.run(request());
    assertThat(result.stats().unverified()).isZero(); // labeled 发布的不计"未发布"
    assertThat(result.stats().findingsPublished()).isEqualTo(1);
    ArgumentCaptor<PostReviewCommand> captor = ArgumentCaptor.forClass(PostReviewCommand.class);
    verify(adapter).postReview(captor.capture());
    assertThat(captor.getValue().inlineComments().get(0).body())
        .contains("[UNVERIFIED]")
        .contains("未经独立复核");
    // 判决头（04§4.3）：labeled 发布的未验证条目计入"候选未验证"总数（标签发布 1 + 未发布 0）
    assertThat(captor.getValue().summaryBody())
        .contains("**结论：⚠️ 1 Important**（1 条候选未验证 · 0 条被去重抑制 · 0 条被截断）");
  }

  @Test
  void insufficientTriggersOneControlledFollowupThenJudge() {
    // 03§4.2 受控补查：INSUFFICIENT + followup_file → 注入该文件全文发起第二轮；仍 INSUFFICIENT → Judge
    java.util.concurrent.atomic.AtomicInteger round =
        new java.util.concurrent.atomic.AtomicInteger();
    scripted
        .review(oneFinding("bug", "important"))
        .verifierScript(
            brief -> {
              String id = firstIdIn(brief);
              return round.getAndIncrement() == 0
                  ? verdictJson(id, "INSUFFICIENT_EVIDENCE", "需要补查", "src/Foo.java")
                  : verdictJson(id, "INSUFFICIENT_EVIDENCE", "补查后仍无法判断", null);
            });
    scripted.judge(
        "{\"judgement\":{\"verdict\":\"CONFIRMED\",\"rationale\":\"仲裁：证据链成立，L4 处取末元素在空列表必越界\"}}");
    ReviewResult result = pipeline.run(request());
    assertThat(scripted.verifierCalls).hasSize(2);
    // 第二轮 brief 含补查材料段（followup 全文注入）
    assertThat(scripted.verifierCalls.get(1).userPrompt()).contains("补查材料");
    assertThat(scripted.judgeCalls).hasSize(1);
    assertThat(scripted.judgeCalls.get(0).systemPrompt()).contains("终裁仲裁员");
    assertThat(result.stats().confirmed()).isEqualTo(1); // Judge 翻案成立
    assertThat(result.stats().findingsPublished()).isEqualTo(1);
    // 门禁与验证波共享 contentCache：HEAD/FILE 只应在预取与门禁各读一次，补查轮复用缓存
    verify(adapter, times(2)).readFile(any(), eq(HEAD), eq(FILE));
  }

  @Test
  void tokenAccountingSumsReviewVerificationAndJudge() {
    // 拆分 ReviewPipeline 后的关键不变量：评审 + 验证波 + Judge 三段 token 必须全部累加
    pipeline =
        newPipeline(
            new org.cra.core.api.PipelineOptions(
                500_000, 0.70, 0.85, 2000, 8000, 3, "never", 100, false)); // 100% 命中抽查
    scripted
        .review(oneFinding("bug", "important"))
        .verifierScript(brief -> verdictJson(firstIdIn(brief), "REFUTED", "L3 已有防护", null));
    scripted.judge(
        "{\"judgement\":{\"verdict\":\"CONFIRMED\",\"rationale\":\"抽查复核：反证不成立，L4 处无防护\"}}");
    ReviewResult result =
        pipeline.run(new ReviewRequest("o", "r", 7, BASE, HEAD, Depth.QUICK, false, false));

    // QUICK = 1 评审代理（100/20）+ 验证波（200/60）+ Judge（80/15）
    assertThat(result.stats().tokensIn()).isEqualTo(100 + 200 + 80);
    assertThat(result.stats().tokensOut()).isEqualTo(20 + 60 + 15);
  }

  // ---------- T2.5：重要级 REFUTED 确定性抽查保险丝 ----------

  @Test
  void spotCheckFuseIsDeterministicAndReproducible() {
    // 同 run 同 finding 重放一致；0-100 边界语义正确（03§4.3 v1.2 可复现定义）
    boolean first = org.cra.core.internal.pipeline.SpotCheckFuse.hit("run_x", "f_abc", 20);
    for (int i = 0; i < 5; i++) {
      assertThat(org.cra.core.internal.pipeline.SpotCheckFuse.hit("run_x", "f_abc", 20))
          .isEqualTo(first);
    }
    assertThat(org.cra.core.internal.pipeline.SpotCheckFuse.hit("run_x", "f_abc", 0)).isFalse();
    assertThat(org.cra.core.internal.pipeline.SpotCheckFuse.hit("run_x", "f_abc", 100)).isTrue();
    // 长期频率锚定：10000 个 id 命中率应接近 20%（确定性哈希的统计性质）
    long hits =
        java.util.stream.IntStream.range(0, 10_000)
            .filter(i -> org.cra.core.internal.pipeline.SpotCheckFuse.hit("run_x", "f_" + i, 20))
            .count();
    assertThat(hits).isBetween(1_800L, 2_200L);
  }

  @Test
  void importantRefutedOnFuseGoesJudgeAndJudgeCanOverturn() {
    // 保险丝命中：important REFUTED → Judge 抽查复核 → CONFIRMED 翻案发布（防单个验证者错杀）
    pipeline =
        newPipeline(
            new org.cra.core.api.PipelineOptions(
                500_000, 0.70, 0.85, 2000, 8000, 3, "never", 100, false)); // 100% 必命中
    scripted
        .review(oneFinding("bug", "important"))
        .verifierScript(brief -> verdictJson(firstIdIn(brief), "REFUTED", "L3 已有防护", null));
    scripted.judge(
        "{\"judgement\":{\"verdict\":\"CONFIRMED\",\"rationale\":\"抽查复核：反证不成立，L4 处无防护\"}}");
    ReviewResult result = pipeline.run(request());
    assertThat(scripted.judgeCalls).hasSize(1);
    assertThat(scripted.judgeCalls.get(0).userPrompt()).contains("抽查复核");
    assertThat(result.stats().confirmed()).isEqualTo(1);
    assertThat(result.stats().rejected()).isZero();
    assertThat(result.stats().findingsPublished()).isEqualTo(1);
  }

  @Test
  void importantRefutedOffFuseDroppedWithoutJudge() {
    // 保险丝未命中（0%）：important REFUTED 直接丢弃，不调 Judge
    pipeline =
        newPipeline(
            new org.cra.core.api.PipelineOptions(
                500_000, 0.70, 0.85, 2000, 8000, 3, "never", 0, false));
    scripted
        .review(oneFinding("bug", "important"))
        .verifierScript(brief -> verdictJson(firstIdIn(brief), "REFUTED", "L3 已有防护", null));
    ReviewResult result = pipeline.run(request());
    assertThat(scripted.judgeCalls).isEmpty();
    assertThat(result.stats().rejected()).isEqualTo(1);
    assertThat(result.stats().findingsPublished()).isZero();
  }

  @Test
  void spotCheckJudgeUnavailableKeepsRefuted() {
    // 抽查复核的 judge 不可得 → 维持 REFUTED（保守不发布，02§4.2 同侧语义）
    pipeline =
        newPipeline(
            new org.cra.core.api.PipelineOptions(
                500_000, 0.70, 0.85, 2000, 8000, 3, "never", 100, false));
    scripted
        .review(oneFinding("bug", "important"))
        .verifierScript(brief -> verdictJson(firstIdIn(brief), "REFUTED", "L3 已有防护", null))
        .judgeUnavailable();
    ReviewResult result = pipeline.run(request());
    assertThat(scripted.judgeCalls).hasSize(1);
    assertThat(result.stats().rejected()).isEqualTo(1);
    assertThat(result.stats().unverified()).isZero();
    assertThat(result.stats().findingsPublished()).isZero();
  }

  // ---------- T2.4：反锚定质控信号（PASS/FAIL 入 trace 不阻断） ----------

  @Test
  void antiAnchorFailDoesNotBlockConfirmedPublication() throws Exception {
    // 07§3 验收 3：FAIL 不阻断晋升——CONFIRMED 照常发布，trace 可查 FAIL（03§4.2 v1.2）
    // rationale 完全复述 finding 原文引用（L4 + 原证据行内容）→ 重叠率 1.0 → FAIL
    scripted
        .review(oneFinding("bug", "important"))
        .verifierScript(
            brief ->
                verdictJson(
                    firstIdIn(brief),
                    "CONFIRMED",
                    "确认：L4 处 return items.get(items.size() - 1); 越界",
                    null));
    ReviewResult result = pipeline.run(request());
    assertThat(result.stats().confirmed()).isEqualTo(1);
    assertThat(result.stats().findingsPublished()).isEqualTo(1); // FAIL 不阻断
    String trace = traceOf(result);
    assertThat(trace).contains("\"event\":\"anti_anchor_check\"").contains("\"status\":\"FAIL\"");
  }

  @Test
  void antiAnchorPassRecordedForNovelRationale() throws Exception {
    // rationale 含原文未出现的引用 → PASS
    scripted
        .review(oneFinding("bug", "important"))
        .verifierScript(
            brief ->
                verdictJson(
                    firstIdIn(brief), "CONFIRMED", "确认：L3 与 L5 组合下空集合必然越界，参考 L2 的方法契约", null));
    ReviewResult result = pipeline.run(request());
    assertThat(traceOf(result)).contains("\"status\":\"PASS\"");
    assertThat(result.stats().confirmed()).isEqualTo(1);
  }

  // ---------- T2.7：收敛控制 ----------

  @Test
  void roundLimitBreachedStopsReviewWithRedesignAdvice() {
    // 03§5 v1.3 语义强化：第 4 轮（>3）→ 停止一切新 finding，Summary 建议打回重新设计
    when(ledger.completedRuns(7))
        .thenReturn(
            List.of(
                new LedgerRunMarker(
                    "run_1", "1111111000000000000000000000000000000000", "DONE", 1, 0, 1L),
                new LedgerRunMarker(
                    "run_2", "2222222000000000000000000000000000000000", "DONE", 1, 0, 2L),
                new LedgerRunMarker("run_3", LAST_HEAD, "DONE", 1, 0, 3L)));
    scripted.review(oneFinding("bug", "important"));
    ReviewResult result = pipeline.run(request());
    assertThat(scripted.reviewCalls).isEmpty(); // 不再执行评审
    assertThat(scripted.verifierCalls).isEmpty();
    assertThat(scripted.judgeCalls).isEmpty();
    ArgumentCaptor<PostReviewCommand> captor = ArgumentCaptor.forClass(PostReviewCommand.class);
    verify(adapter).postReview(captor.capture());
    assertThat(captor.getValue().summaryBody()).contains("打回重新设计").contains("收敛控制");
    assertThat(result.stats().degradations()).contains("convergence-round-limit");
  }

  @Test
  void diffOnlyRereviewUsesIncrementSinceLastCompletedHead() {
    // 03§5.2：非首轮只评审自上一轮完成 head 以来的增量 diff
    when(ledger.completedRuns(7))
        .thenReturn(List.of(new LedgerRunMarker("run_1", LAST_HEAD, "DONE", 1, 0, 3L)));
    when(adapter.fetchDiff(any(), eq(LAST_HEAD), eq(HEAD))).thenReturn(diff(LAST_HEAD));
    scripted.review(oneFinding("bug", "important"));
    ReviewResult result = pipeline.run(request());
    // 增量 diff 被采用（fetchDiff 以 lastHead 为 base 调用过）
    verify(adapter).fetchDiff(any(), eq(LAST_HEAD), eq(HEAD));
    ArgumentCaptor<PostReviewCommand> captor = ArgumentCaptor.forClass(PostReviewCommand.class);
    verify(adapter).postReview(captor.capture());
    assertThat(captor.getValue().summaryBody()).contains("diff-only 复审");
    assertThat(result.status()).isEqualTo(RunStatus.SUCCESS);
  }

  @Test
  void diffOnlyFallsBackToFullDiffWhenIncrementUnavailable() {
    // 增量 diff 取不到（force-push 等）→ 回退全量并声明（不静默）
    when(ledger.completedRuns(7))
        .thenReturn(List.of(new LedgerRunMarker("run_1", LAST_HEAD, "DONE", 1, 0, 3L)));
    when(adapter.fetchDiff(any(), eq(LAST_HEAD), eq(HEAD)))
        .thenThrow(new org.cra.common.exception.CraIntegrationException("422 no common ancestor"));
    scripted.review(oneFinding("bug", "important"));
    ReviewResult result = pipeline.run(request());
    assertThat(result.stats().degradations()).contains("increment-diff-fallback-full");
    assertThat(result.status()).isEqualTo(RunStatus.SUCCESS);
  }

  // ---------- T2.8：run 内精确去重 merge ----------

  @Test
  void sameSpotCandidatesMergedBeforeVerification() throws Exception {
    // 03§4.5：同 file ±3 行同 category 合并为一条代表；verifier 只收到合并后的代表
    scripted.review(
        """
        {"findings":[
          {"category":"bug","title":"越界A","description":"dA","failure_mode":"fA",
           "file":"src/Foo.java","line":4,"side":"RIGHT","evidence":"L4: a","severity_proposed":"important",
           "pre_existing_suspect":false},
          {"category":"bug","title":"越界B","description":"dB","failure_mode":"fB",
           "file":"src/Foo.java","line":6,"side":"RIGHT","evidence":"L6: b","severity_proposed":"important",
           "pre_existing_suspect":false},
          {"category":"security","title":"注入C","description":"dC","failure_mode":"fC",
           "file":"src/Foo.java","line":4,"side":"RIGHT","evidence":"L4: c","severity_proposed":"important",
           "pre_existing_suspect":false}]}""");
    ReviewResult result = pipeline.run(request());
    // ReviewStage 跨视角汇聚时已按 03§4.5 精确合并（bug@L4 + bug@L6 行距 2 ≤3 合并；
    // security@L4 同行但类别不同不合并）→ 2 条进验证；pipeline 层 ExactDedupMerger 幂等复核
    assertThat(scripted.verifierCalls).hasSize(1);
    assertThat(scripted.verifierCalls.get(0).userPrompt()).contains("越界").doesNotContain("越界B");
    assertThat(result.stats().findingsCandidate()).isEqualTo(2);
    assertThat(result.stats().confirmed()).isEqualTo(2);
    assertThat(traceOf(result)).contains("\"match\":\"in-run-exact\"");
  }

  // ---------- 模板与 brief（分型组装） ----------

  @Test
  void verifierBriefCarriesUntrustedDelimitersAndPolicySection() {
    when(adapter.readFile(any(), anyString(), eq("REVIEW.md")))
        .thenReturn(
            FileContent.ofText(
                "REVIEW.md", "# Review Policy: demo\n\n## Evidence requirements\n- 必须给出触发输入\n"));
    scripted.review(oneFinding("bug", "important"));
    pipeline.run(request());
    String brief = scripted.verifierCalls.get(0).userPrompt();
    // 不可信内容定界（04§3.3：finding/代码上下文一律 UNTRUSTED）+ 证据要求段（可信）
    assertThat(brief)
        .contains(ScriptedVerifierDelimiters.OPEN)
        .contains(ScriptedVerifierDelimiters.CLOSE)
        .contains("必须给出触发输入");
  }

  @Test
  void contextFindingBriefIncludesProductContextAndIncidents() {
    // v1.3.1 分型组装：context 类注入 REVIEW.md Product context/Known incidents 条目
    when(adapter.readFile(any(), anyString(), eq("REVIEW.md")))
        .thenReturn(
            FileContent.ofText(
                "REVIEW.md",
                """
                # Review Policy: demo

                ## Product context
                - 会话超时上限 30 分钟，由网关统一续期

                ## Known incidents
                - 2026-08 会话超时配置回退事故（INC-42）
                """));
    scripted.review(oneFinding("context", "important"));
    pipeline.run(request());
    String brief = scripted.verifierCalls.get(0).userPrompt();
    assertThat(brief).contains("会话超时上限 30 分钟").contains("INC-42");
  }

  @Test
  void judgeCaseContextCappedAtThreeFiles() {
    // 03§6：Judge 上下文按案件界定 ≤3 文件
    pipeline =
        newPipeline(
            new org.cra.core.api.PipelineOptions(
                500_000, 0.70, 0.85, 2000, 8000, 3, "never", 100, false));
    scripted
        .review(oneFinding("bug", "important"))
        .verifierScript(brief -> verdictJson(firstIdIn(brief), "REFUTED", "L3 已有防护", null));
    scripted.judge("{\"judgement\":{\"verdict\":\"REJECTED\",\"rationale\":\"维持推翻：确有防护\"}}");
    pipeline.run(request());
    String brief = scripted.judgeCalls.get(0).userPrompt();
    long fileSections = brief.lines().filter(l -> l.startsWith("--- ")).count();
    assertThat(fileSections).isLessThanOrEqualTo(3);
  }

  // ---------- 工具 ----------

  /** 从 brief 抽首个 finding id（verifier 脚本按 id 生成 verdict）。 */
  private static String firstIdIn(String userPrompt) {
    java.util.regex.Matcher matcher =
        java.util.regex.Pattern.compile("f_[0-9a-f]+").matcher(userPrompt);
    return matcher.find() ? matcher.group() : "f_missing";
  }

  private static String verdictJson(String id, String verdict, String rationale, String followup) {
    String refutation =
        "REFUTED".equals(verdict)
            ? ",\"refutation\":{\"kind\":\"GUARD_EXISTS\",\"anchor\":\"src/Foo.java:3\","
                + "\"quote\":\"return items.get(items.size() - 1);\",\"reason\":\"脚本桩反证\"}"
            : "";
    return followup == null
        ? "{\"verdicts\":[{\"finding_id\":\"%s\",\"verdict\":\"%s\",\"rationale\":\"%s\"%s}]}"
            .formatted(id, verdict, rationale, refutation)
        : "{\"verdicts\":[{\"finding_id\":\"%s\",\"verdict\":\"%s\",\"rationale\":\"%s\",\"followup_file\":\"%s\"%s}]}"
            .formatted(id, verdict, rationale, followup, refutation);
  }

  /** UNTRUSTED 定界标记镜像（04§3.3 规定原文，不依赖实现常量）。 */
  static final class ScriptedVerifierDelimiters {
    static final String OPEN = "<<<UNTRUSTED_CONTENT";
    static final String CLOSE = "<<<END_UNTRUSTED_CONTENT>>>";
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
}
