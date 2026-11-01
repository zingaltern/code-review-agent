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
import org.cra.common.context.TokenMath;
import org.cra.common.git.DiffFile;
import org.cra.common.git.DiffHunk;
import org.cra.common.git.DiffResult;
import org.cra.common.git.FileContent;
import org.cra.common.git.PostReviewCommand;
import org.cra.common.run.Depth;
import org.cra.common.run.ReviewRequest;
import org.cra.common.run.ReviewResult;
import org.cra.common.run.RunStatus;
import org.cra.core.api.PipelineOptions;
import org.cra.core.internal.pipeline.PromotionEngine;
import org.cra.git.api.GitPlatformAdapter;
import org.cra.store.api.ReviewLedger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 验证层反证纪律端到端（ADR-0015）：脚本化 LLM fixture 复现 JacksonCore-8 形态—— 复审员把"结论把变更定位在 X 而非 Y"（引述/锚点不符）当作推翻 →
 * 机械校验降级 INSUFFICIENT → Judge 按失败模式本身裁决 CONFIRMED → 发布。同时覆盖：
 *
 * <ul>
 *   <li>"改前"语义对照：{@link PromotionEngine} 对无证据 REFUTED 直接 REJECT（旧路径）；
 *   <li>有可核查反证（GUARD_EXISTS + 简报内逐字 quote）时 REFUTED 成立、不调 Judge；
 *   <li>bug 类 brief 注入确定性调用点索引（同目录 peer 文件 + 符号命中）；
 *   <li>verifier 输入预算不突破（03§6 ≤32k，索引在预留内；fixture 断言沿用 ≤16k 更严锚）。
 * </ul>
 */
class VerifierAntiRefutePipelineTest {

  private static final String FILE = "src/main/java/com/demo/util/TextBuffer.java";
  private static final String PEER = "src/main/java/com/demo/util/JsonParser.java";
  private static final String HEAD = "ac6d8e22847c19b2695cbd7d1f418e07a9a3dbb2";
  private static final String BASE = "11f0b4090937b2aa998734aa2bf032ee8c428e84";

  private static final String TEXT_BUFFER =
      """
      package com.demo.util;

      public final class TextBuffer
      {
          protected char[] _currentSegment;
          protected boolean _hasSegments;
          protected char[] _resultArray;

          public char[] getTextBuffer()
          {
              if (_resultArray != null) return _resultArray;
              if (!_hasSegments)  return _currentSegment;
              return contentsAsArray();
          }

          public char[] contentsAsArray() {
              char[] result = _resultArray;
              if (result == null) {
                  _resultArray = result = resultArray();
              }
              return result;
          }

          protected char[] resultArray() {
              if (_currentSegment == null) {
                  return new char[0];
              }
              return _currentSegment;
          }
      }
      """;

  private static final String PEER_CONTENT =
      """
      package com.demo.util;

      public class JsonParser {
          private final TextBuffer buffer = new TextBuffer();

          public String getText() {
              char[] chars = buffer.getTextBuffer();
              return new String(chars);
          }
      }
      """;

  private static final String PATCH =
      """
      @@ -304,7 +304,7 @@
                   return (_resultArray = _resultString.toCharArray());
               }
               // Nope; but does it fit in just one segment?
      -        if (!_hasSegments && _currentSegment != null)  return _currentSegment;
      +        if (!_hasSegments)  return _currentSegment;
               // Nope, need to have/create a non-segmented array and return it
               return contentsAsArray();
      """;

  private static final String FINDING =
      """
      {"findings":[{"category":"bug","title":"contentsAsArray 在空缓冲区路径可能返回 null 导致调用方 NPE",
        "description":"diff 删除了空检查后，contentsAsArray 路径可能返回 null",
        "failure_mode":"触发条件：空缓冲区；错误结果：调用方 new String(char[]) 抛 NPE",
        "file":"%s","line":12,"side":"RIGHT",
        "evidence":"L12: if (!_hasSegments)  return _currentSegment; —— getTextBuffer 在空缓冲区时返回 null，contentsAsArray 的调用方会 NPE",
        "severity_proposed":"important","pre_existing_suspect":false,"confidence":0.85}]}"""
          .formatted(FILE);

  /** bad-JacksonCore-8 r1 形态的 test 类候选：主张"空缓冲区路径无测试覆盖"。 */
  private static final String FINDING_TEST =
      """
      {"findings":[{"category":"test","title":"空缓冲区路径（getTextBuffer 返回 null）缺少测试覆盖",
        "description":"diff 移除空检查后，现有测试未断言 _currentSegment 为 null 时的行为",
        "failure_mode":"触发条件：空缓冲区；缺失：没有测试覆盖调用方收到 null 的分支",
        "file":"%s","line":12,"side":"RIGHT",
        "evidence":"L12: if (!_hasSegments)  return _currentSegment; —— 测试清单中无覆盖该分支的用例",
        "severity_proposed":"important","pre_existing_suspect":false,"confidence":0.80}]}"""
          .formatted(FILE);

  @TempDir Path runRoot;

  private GitPlatformAdapter adapter;
  private ReviewLedger ledger;
  private ScriptedGateway scripted;

  @BeforeEach
  void setUp() {
    adapter = mock(GitPlatformAdapter.class);
    ledger = mock(ReviewLedger.class);
    scripted = new ScriptedGateway();
    when(ledger.findCompletion(anyInt(), anyString())).thenReturn(Optional.empty());
    when(ledger.publishedFindings(anyInt())).thenReturn(List.of());
    when(ledger.completedRuns(anyInt())).thenReturn(List.of());
    bridgeKeyedLedgerToIntStubs();
    when(adapter.fetchDiff(any(), anyString(), anyString())).thenReturn(diff());
    when(adapter.readFile(any(), anyString(), eq(FILE)))
        .thenReturn(FileContent.ofText(FILE, TEXT_BUFFER));
    when(adapter.readFile(any(), anyString(), eq(PEER)))
        .thenReturn(FileContent.ofText(PEER, PEER_CONTENT));
    when(adapter.readFile(any(), anyString(), eq("REVIEW.md")))
        .thenReturn(FileContent.ofText("REVIEW.md", "# Review Policy: demo\n"));
    when(adapter.listFiles(any(), anyString(), anyString())).thenReturn(List.of(FILE, PEER));
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
  }

  @Test
  void citationMismatchRefutationIsDowngradedAndJudgeConfirmsTheRealFailureMode() throws Exception {
    // 复现基线 run 40536205522 的 JacksonCore-8 形态：REFUTED 只说明"变更在 getTextBuffer 而非
    // contentsAsArray"，quote 逐字可定位——但这不是对失败模式的否定
    scripted.review(FINDING);
    scripted.verifierScript(
        brief ->
            """
            {"verdicts":[{"finding_id":"%s","verdict":"REFUTED",
              "rationale":"结论把变更定位在 contentsAsArray，但 diff 实际修改的是 getTextBuffer（L307）：contentsAsArray 本身未被修改。",
              "refutation":{"kind":"CITATION_MISMATCH","anchor":"%s:12",
                "quote":"if (!_hasSegments)  return _currentSegment;","reason":"结论引述的方法名与实际变更不符"}}]}"""
                .formatted(firstIdIn(brief), FILE));
    scripted.judge(
        "{\"judgement\":{\"verdict\":\"CONFIRMED\",\"rationale\":\"引述不符不构成反证；L307 处空缓冲区返回 null 的失败模式成立\"}}");

    ReviewResult result = pipeline(0).run(request());

    // 改后：降级 → Judge 按失败模式本身裁决 → 发布
    assertThat(result.status()).isEqualTo(RunStatus.SUCCESS);
    assertThat(scripted.judgeCalls).hasSize(1);
    assertThat(result.stats().confirmed()).isEqualTo(1);
    assertThat(result.stats().findingsPublished()).isEqualTo(1);

    String trace = traceOf(result);
    assertThat(trace)
        .contains("\"event\":\"refute_evidence_check\"")
        .contains("\"reason\":\"CITATION_MISMATCH\"")
        .contains("\"downgraded\":true")
        .contains("\"refute_downgraded\":1");

    // Judge 收到的是降级后的判定文本 + 系统校验说明（不是原始 REFUTED 的理由孤证）
    String judgeBrief = scripted.judgeCalls.get(0).userPrompt();
    assertThat(judgeBrief)
        .contains("INSUFFICIENT_EVIDENCE")
        .contains("系统校验")
        .contains("CITATION_MISMATCH");

    // 改前语义对照：同一 REFUTED 在 PromotionEngine 旧路径下会被直接丢弃
    PromotionEngine.Promotion before =
        PromotionEngine.promote(VerdictJsonCodec.VerifierVerdict.REFUTED, scriptedFinding(), false);
    assertThat(before.decision()).isEqualTo(PromotionEngine.Decision.REJECT);
  }

  @Test
  void checkableGuardEvidenceKeepsRefutedWithoutJudge() throws Exception {
    // 有可核查反证（GUARD_EXISTS + 简报内逐字 quote）→ REFUTED 成立，不降级、不调 Judge
    scripted.review(FINDING);
    scripted.verifierScript(
        brief ->
            """
            {"verdicts":[{"finding_id":"%s","verdict":"REFUTED",
              "rationale":"resultArray() 在 _currentSegment == null 时返回 new char[0]，空缓冲区不会得到 null。",
              "refutation":{"kind":"GUARD_EXISTS","anchor":"%s:20",
                "quote":"if (_currentSegment == null) {  return new char[0];","reason":"上游已处理空缓冲区"}}]}"""
                .formatted(firstIdIn(brief), FILE));

    ReviewResult result = pipeline(0).run(request());

    assertThat(scripted.judgeCalls).isEmpty();
    assertThat(result.stats().rejected()).isEqualTo(1);
    assertThat(result.stats().findingsPublished()).isZero();
    String trace = traceOf(result);
    assertThat(trace)
        .contains("\"event\":\"refute_evidence_check\"")
        .contains("\"status\":\"OK\"")
        .doesNotContain("\"downgraded\":true");
  }

  @Test
  void testClassCitationMismatchIsDowngradedAndJudgeSeesTheCoverageClaim() throws Exception {
    // ADR-0015 §6：test 类纳入纪律——"引述/锚点不符"不再能直接拒掉"缺少覆盖"的主张
    scripted.review(FINDING_TEST);
    scripted.verifierScript(
        brief ->
            """
            {"verdicts":[{"finding_id":"%s","verdict":"REFUTED",
              "rationale":"结论引述的 getCurrentSegment 在 diff 中没有变更，实际修改的是 getTextBuffer（L307），因此该 test 主张不成立。",
              "refutation":{"kind":"CITATION_MISMATCH","anchor":"%s:12",
                "quote":"if (!_hasSegments)  return _currentSegment;","reason":"结论锚定的符号与实际变更不符"}}]}"""
                .formatted(firstIdIn(brief), FILE));
    scripted.judge(
        "{\"judgement\":{\"verdict\":\"CONFIRMED\",\"rationale\":\"引述不符不构成反证；空缓冲区路径（getTextBuffer 返回 null）确实没有测试覆盖，主张成立\"}}");

    ReviewResult result = pipeline(0).run(request());

    // 降级 → Judge 按主张本身裁决 → 发布
    assertThat(result.status()).isEqualTo(RunStatus.SUCCESS);
    assertThat(scripted.judgeCalls).hasSize(1);
    assertThat(result.stats().confirmed()).isEqualTo(1);
    assertThat(result.stats().findingsPublished()).isEqualTo(1);

    String trace = traceOf(result);
    assertThat(trace)
        .contains("\"event\":\"refute_evidence_check\"")
        .contains("\"status\":\"DOWNGRADE\"")
        .contains("\"reason\":\"CITATION_MISMATCH\"")
        .contains("\"downgraded\":true")
        .contains("\"verdict\":\"INSUFFICIENT_EVIDENCE\"")
        .contains("\"refute_downgraded\":1");

    // Judge 看到的是降级后的判定 + 系统校验说明（test 类主张进入仲裁，而非被静默丢弃）
    String judgeBrief = scripted.judgeCalls.get(0).userPrompt();
    assertThat(judgeBrief)
        .contains("INSUFFICIENT_EVIDENCE")
        .contains("系统校验")
        .contains("CITATION_MISMATCH")
        .contains("缺少测试覆盖");
  }

  @Test
  void testClassCheckableRefutationKeepsRefutedWithoutJudge() throws Exception {
    // test 类 + 合法反证 → 保留 REFUTED（纪律扩展不是"test 类一律送仲裁"）
    scripted.review(FINDING_TEST);
    scripted.verifierScript(
        brief ->
            """
            {"verdicts":[{"finding_id":"%s","verdict":"REFUTED",
              "rationale":"resultArray() 在 _currentSegment == null 时返回 new char[0]，空缓冲区路径不会返回 null，无需该测试。",
              "refutation":{"kind":"GUARD_EXISTS","anchor":"%s:20",
                "quote":"if (_currentSegment == null) {  return new char[0];","reason":"上游已处理空缓冲区"}}]}"""
                .formatted(firstIdIn(brief), FILE));

    ReviewResult result = pipeline(0).run(request());

    assertThat(scripted.judgeCalls).isEmpty();
    assertThat(result.stats().rejected()).isEqualTo(1);
    assertThat(result.stats().findingsPublished()).isZero();
    String trace = traceOf(result);
    assertThat(trace)
        .contains("\"event\":\"refute_evidence_check\"")
        .contains("\"status\":\"OK\"")
        .doesNotContain("\"downgraded\":true");
  }

  @Test
  void bugBriefCarriesDeterministicCallSiteIndexWithinInputBudget() throws Exception {
    scripted.review(FINDING);
    ReviewResult result = pipeline(0).run(request());

    String brief = scripted.verifierCalls.get(0).userPrompt();
    assertThat(brief)
        .contains("调用点索引")
        .contains("JsonParser.java:7")
        .contains("getTextBuffer")
        .contains("调用链不完整");
    long inputTokens =
        TokenMath.estimateTokens(
            scripted.verifierCalls.get(0).systemPrompt()
                + scripted.verifierCalls.get(0).userPrompt());
    assertThat(inputTokens)
        .as("measured verifier input tokens (bug brief + call-site index) = %s", inputTokens)
        .isLessThanOrEqualTo(16_000);
    assertThat(traceOf(result))
        .contains("\"event\":\"call_site_index\"")
        .contains("\"available\":true");
  }

  // ---------- fixture ----------

  private ReviewPipeline pipeline(int spotcheckPercent) {
    ReviewCrew crew =
        ReviewCrew.ofBugHunters(
            List.of(
                new BugHunterDefinition(
                    BugHunterDefinition.Perspective.DATAFLOW, new TemplateLoader()),
                new BugHunterDefinition(
                    BugHunterDefinition.Perspective.BOUNDARY, new TemplateLoader())));
    return new ReviewPipeline(
        adapter,
        ledger,
        scripted,
        crew,
        new VerifierAgentDefinition(new TemplateLoader()),
        new JudgeAgentDefinition(new TemplateLoader()),
        runRoot,
        new PipelineOptions(500_000, 0.70, 0.85, 2000, 8000, 3, "never", spotcheckPercent, false),
        null);
  }

  private ReviewRequest request() {
    return new ReviewRequest("o", "r", 7, BASE, HEAD, Depth.STANDARD, false, false);
  }

  private static DiffResult diff() {
    return new DiffResult(
        BASE,
        HEAD,
        List.of(new DiffFile(FILE, null, "modified", PATCH, List.of(new DiffHunk(9, 7, 9, 7)))),
        List.of(new org.cra.common.git.CommitSummary(HEAD, "fix: TextBuffer")));
  }

  private String traceOf(ReviewResult result) throws Exception {
    return Files.readString(
        Path.of(result.traceRef().substring("file://".length())).resolve("trace.ndjson"));
  }

  private static String firstIdIn(String userPrompt) {
    java.util.regex.Matcher matcher =
        java.util.regex.Pattern.compile("f_[0-9a-f]+").matcher(userPrompt);
    return matcher.find() ? matcher.group() : "f_missing";
  }

  private static org.cra.common.finding.CandidateFinding scriptedFinding() {
    return new org.cra.common.finding.CandidateFinding(
        "f_000000000001",
        "run_1",
        org.cra.common.finding.FindingCategory.BUG,
        "t",
        "d",
        "f",
        FILE,
        12,
        org.cra.common.finding.Side.RIGHT,
        "e",
        "",
        org.cra.common.finding.Severity.IMPORTANT,
        false,
        0.8,
        "bug-hunter/dataflow",
        "glm-x");
  }

  /** 键控扩展（05§1.3）兼容桩（与 VerificationPromotionTest 同构）。 */
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
