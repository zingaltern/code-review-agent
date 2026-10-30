package org.cra.core.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.cra.agents.api.AgentBudget;
import org.cra.agents.api.LayerContract;
import org.cra.agents.api.VerificationBatchInput;
import org.cra.agents.api.VerifierAgentDefinition;
import org.cra.agents.internal.TemplateLoader;
import org.cra.common.context.ReviewPolicy;
import org.cra.common.context.TokenMath;
import org.cra.common.finding.AnchorStatus;
import org.cra.common.finding.CandidateFinding;
import org.cra.common.finding.FindingCategory;
import org.cra.common.finding.Severity;
import org.cra.common.finding.Side;
import org.cra.common.git.DiffFile;
import org.cra.common.git.DiffHunk;
import org.cra.common.git.DiffResult;
import org.cra.common.git.FileContent;
import org.cra.common.semantic.SymbolIndexSnapshot;
import org.cra.core.api.SemanticContextOptions;
import org.cra.core.internal.pipeline.CallSiteIndex;
import org.cra.core.internal.pipeline.FindingAggregator;
import org.junit.jupiter.api.Test;

/**
 * 验证 brief 预算算术（04§2.2、ADR-0015 §2.2 修订）：verifier 16k 下 findings + 1.2k 余量 + 测试清单 + 索引（≤1000） +
 * 切片（≤6000）不越界；超预算按优先级截断并声明。
 */
class VerificationBudgetArithmeticTest {

  private static final String FILE = "src/main/java/com/demo/util/TextBuffer.java";

  private static final String FILE_TEXT =
      """
      package com.demo.util;

      public final class TextBuffer {
        public char[] getTextBuffer() {
          if (!_hasSegments)  return _currentSegment;
          return contentsAsArray();
        }
        public char[] contentsAsArray() {
          return _resultArray;
        }
      }
      """;

  private static final String CALLER = "src/main/java/com/demo/util/JsonParser.java";

  private static final String CALLER_TEXT =
      """
      package com.demo.util;

      public class JsonParser {
        char[] text() {
          return buffer.getTextBuffer();
        }
      }
      """;

  private static final AgentBudget VERIFIER_16K =
      new AgentBudget(VerifierAgentDefinition.DEFAULT_INPUT_TOKENS, 2_000, 2, 240_000);

  @Test
  void sixteenKVerifierKeepsIndexAndSlicesWithinBudget() {
    VerificationBatchInput input = assemble(VERIFIER_16K, sliceText(6_000), indexAvailable(), "");

    String brief = briefOf(input);
    long tokens = TokenMath.estimateTokens(brief);
    assertThat(VERIFIER_16K.maxInputTokens()).isEqualTo(16_000);
    assertThat(tokens).isLessThanOrEqualTo(VERIFIER_16K.maxInputTokens());
    assertThat(input.codeContext()).contains("调用点索引").contains("[caller] " + CALLER);
    assertThat(input.codeContext()).contains("L2 跨文件上下文");
    assertThat(input.codeContext()).doesNotContain("因验证预算不足未注入");
    // 分层核对契约：索引可用时随 brief 注入（可信指令段）
    assertThat(input.layerContractSection()).isEqualTo(LayerContract.SECTION);
    assertThat(brief).contains("分层核对契约").contains("controller→service→DAO");
  }

  @Test
  void oversizedSlicesAreTruncatedWithDeclarationAndBriefStaysWithinBudget() {
    VerificationBatchInput oversized =
        assemble(VERIFIER_16K, sliceText(40_000), indexAvailable(), "");

    assertThat(TokenMath.estimateTokens(briefOf(oversized)))
        .isLessThanOrEqualTo(VERIFIER_16K.maxInputTokens());
    assertThat(oversized.codeContext()).contains("超预算截断");
  }

  @Test
  void tinyBudgetDropsIndexAndSlicesWithDeclarations() {
    AgentBudget tiny = new AgentBudget(2_000, 500, 2, 240_000);

    VerificationBatchInput input = assemble(tiny, sliceText(6_000), indexAvailable(), "");

    assertThat(input.codeContext()).contains("验证预算不足，索引未注入").contains("L2 跨文件切片因验证预算不足未注入");
    // 代码上下文仍有最小配额（不让验证者彻底失明）
    assertThat(input.codeContext()).contains("文件全文（");
  }

  @Test
  void followupRoundRespectsVerifierBudget() {
    VerificationBatchInput base = assemble(VERIFIER_16K, sliceText(6_000), indexAvailable(), "");
    String followup = "followup 文件全文\n" + "x".repeat(40_000);

    VerificationBatchInput second =
        VerificationBatchAssembler.withFollowup(base, followup, VERIFIER_16K);

    assertThat(second.followupContext()).isNotBlank();
    assertThat(TokenMath.estimateTokens(briefOf(second)))
        .isLessThanOrEqualTo(VERIFIER_16K.maxInputTokens());
    assertThat(second.layerContractSection()).isEqualTo(LayerContract.SECTION);
  }

  @Test
  void layerContractOnlyWhenCallSiteIndexAvailable() {
    VerificationBatchInput noIndex = assemble(VERIFIER_16K, "", null, "");
    assertThat(noIndex.layerContractSection()).isEmpty();
    assertThat(briefOf(noIndex)).doesNotContain("分层核对契约");

    VerificationBatchInput unavailableIndex =
        assemble(VERIFIER_16K, "", CallSiteIndex.unavailable("repo-tree-unavailable"), "");
    assertThat(unavailableIndex.layerContractSection()).isEmpty();
    assertThat(briefOf(unavailableIndex)).doesNotContain("分层核对契约").contains("索引不可得");
  }

  @Test
  void defaultsAreRelaxedForAiCodingEra() {
    // 04§1/04§2.2：渲染 700→1000、切片 4000→6000（默认关闭不变）
    assertThat(CallSiteIndex.RENDER_TOKEN_CAP).isEqualTo(1_000);
    assertThat(SemanticContextOptions.DEFAULT_SLICE_TOKENS).isEqualTo(6_000);
    assertThat(SemanticContextOptions.disabled().enabled()).isFalse();
    assertThat(SemanticContextOptions.disabled().sliceTokens()).isEqualTo(6_000);
  }

  // ---------- fixture ----------

  private static VerificationBatchInput assemble(
      AgentBudget budget, String slices, CallSiteIndex index, String followupIgnored) {
    List<FindingAggregator.Scored> batch = List.of(scored());
    Map<String, FileContent> headFiles =
        Map.of(
            FILE,
            FileContent.ofText(FILE, FILE_TEXT),
            CALLER,
            FileContent.ofText(CALLER, CALLER_TEXT));
    DiffResult diff =
        new DiffResult(
            "b",
            "h",
            List.of(
                new DiffFile(
                    FILE,
                    null,
                    "modified",
                    "@@ -1,3 +1,3 @@\n+  if (!_hasSegments)  return _currentSegment;\n",
                    List.of(new DiffHunk(1, 3, 1, 3)))),
            List.of());
    return VerificationBatchAssembler.buildBatch(
        FILE,
        batch,
        ReviewPolicy.empty(),
        headFiles,
        diff,
        budget,
        null,
        List.of(),
        "o/r",
        index,
        slices);
  }

  private static FindingAggregator.Scored scored() {
    CandidateFinding finding =
        new CandidateFinding(
            "f_1",
            "run_1",
            FindingCategory.BUG,
            "getTextBuffer 移除空检查后可能返回 null",
            "空缓冲区路径返回 null",
            "触发条件：空缓冲区；错误结果：调用方 NPE",
            FILE,
            4,
            Side.RIGHT,
            "L4: if (!_hasSegments)  return _currentSegment; 空缓冲区时 getTextBuffer 返回 null",
            "",
            Severity.IMPORTANT,
            false,
            0.9,
            "bug-hunter/dataflow",
            "glm-x");
    return new FindingAggregator.Scored(finding, "fp", AnchorStatus.INLINE, null);
  }

  private static CallSiteIndex indexAvailable() {
    Map<String, String> files = Map.of(FILE, FILE_TEXT, CALLER, CALLER_TEXT);
    return CallSiteIndex.build(
        FILE,
        List.of(scored()),
        null,
        List.copyOf(files.keySet()),
        path -> Optional.ofNullable(files.get(path)),
        new SymbolIndexSnapshot(
            true, "h", "", 2, 0, false, List.of(), List.of(), List.of(), List.of(), List.of()));
  }

  private static String sliceText(int approximateTokens) {
    StringBuilder sb = new StringBuilder("L2 跨文件上下文（ADR-0014；仅 Java）：\n");
    int line = 0;
    while (TokenMath.estimateTokens(sb.toString()) < approximateTokens) {
      sb.append("- cross-file evidence line ").append(line++).append(" keepAlive(age)\n");
    }
    return sb.toString();
  }

  private static String briefOf(VerificationBatchInput input) {
    var brief = new VerifierAgentDefinition(new TemplateLoader()).buildBrief(input);
    return brief.systemPrompt() + brief.userPrompt();
  }
}
