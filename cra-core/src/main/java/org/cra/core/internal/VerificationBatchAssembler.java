package org.cra.core.internal;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.cra.agents.api.AgentBudget;
import org.cra.agents.api.JudgeCaseInput;
import org.cra.agents.api.VerificationBatchInput;
import org.cra.agents.api.VerificationItem;
import org.cra.common.context.ReviewPolicy;
import org.cra.common.context.TokenMath;
import org.cra.common.finding.CandidateFinding;
import org.cra.common.finding.FindingCategory;
import org.cra.common.git.DiffResult;
import org.cra.common.git.FileContent;
import org.cra.core.internal.pipeline.FindingAggregator;

/**
 * 验证层 brief 组装（07 T2.4/T2.6；v1.3.1 分型组装 03§4.2）：
 *
 * <ul>
 *   <li><b>代码类</b>（bug/security/test/convention/performance）：finding 全文（含发现者论证）+ 所在文件全文 + 该文件 diff
 *       切片 + REVIEW.md 证据要求段；
 *   <li><b>context 类</b>：同上 + 被引矛盾证据原文（{@code CitedEvidenceResolver}，每 commit ≤1k token）+ REVIEW.md
 *       Product context/Known incidents 条目——context 类证据在代码之外，不注入则验证层失明、 verifier 必然 INSUFFICIENT →
 *       Judge 同样失明 → 系统性拒杀（03§4.2 v1.3.1）；
 *   <li><b>Judge 案件</b>：finding + 验证者判定与证据（UNTRUSTED 由定义包裹）+ 涉事文件上下文 ≤3 文件（03§6）。
 * </ul>
 *
 * <p>预算：codeContext 在 VerifierAgent 输入预算内裁剪（超预算截行，锚行窗口优先）；全部确定性可重放。
 */
final class VerificationBatchAssembler {

  /** 补查文件内容的 token 上限（03§6 verifier 12k 输入预算内为补查预留）。 */
  private static final long FOLLOWUP_TOKEN_CAP = 6_000;

  /** 被引 commit diff 节选上限（03§4.2：每 commit ≤1k token，至多 3 个 commit）。 */
  private static final long CITED_COMMIT_TOKEN_CAP = 1_000;

  private static final int CITED_COMMIT_MAX = 3;

  /** Judge 涉事文件单文件 token 上限（03§6：≤16k/案件，3 文件内分配）。 */
  private static final long JUDGE_FILE_TOKEN_CAP = 4_000;

  private static final int JUDGE_MAX_FILES = 3;

  /** 40 位/缩写 hex commit 引用（context 类 finding 的被引对象；M4f CrossChecker 将钉死引用格式）。 */
  private static final Pattern COMMIT_REF = Pattern.compile("\\b[0-9a-f]{7,40}\\b");

  private VerificationBatchAssembler() {}

  /**
   * 组装一个文件批次的验证 brief 输入（第一轮，无补查材料）。
   *
   * @param file 批次文件
   * @param batch 该文件的候选（Scored 含 finding）
   * @param policy base 分支 REVIEW.md 策略
   * @param headFiles head 侧文件全文预取
   * @param diff 评审范围 diff（切片来源）
   * @param budget 验证者输入预算
   * @param citedResolver 被引证据解析（context 类分型组装）
   * @return 批次输入
   */
  static VerificationBatchInput buildBatch(
      String file,
      List<FindingAggregator.Scored> batch,
      ReviewPolicy policy,
      Map<String, FileContent> headFiles,
      DiffResult diff,
      AgentBudget budget,
      CitedEvidenceResolver citedResolver) {
    List<VerificationItem> items = batch.stream().map(s -> toItem(s.finding())).toList();
    String findingsText = renderFindingsText(batch);
    long reserved = TokenMath.estimateTokens(findingsText) + 1_200;
    long contextBudget = Math.max(1_000, budget.maxInputTokens() - reserved);
    return new VerificationBatchInput(
        file,
        items,
        renderCodeContext(file, batch, headFiles, diff, contextBudget),
        renderCitedEvidence(batch, citedResolver),
        renderPolicySections(policy, batch),
        "");
  }

  /**
   * 组装第二轮补查输入（03§4.2 受控补查：至多一次）。
   *
   * @param base 第一轮输入
   * @param followupText 补查文件全文（截到 FOLLOWUP_TOKEN_CAP）
   * @return 第二轮输入
   */
  static VerificationBatchInput withFollowup(VerificationBatchInput base, String followupText) {
    return new VerificationBatchInput(
        base.file(),
        base.items(),
        base.codeContext(),
        base.citedEvidence(),
        base.policySections(),
        capLines(followupText, FOLLOWUP_TOKEN_CAP));
  }

  /**
   * 组装 Judge 案件输入（03§4.4：brief = finding + 验证者判定与证据 + 更大配额的代码上下文）。
   *
   * @param finding 被仲裁的候选
   * @param verifierVerdictText 验证者判定原文（verdict + rationale）
   * @param caseReason 情形说明（可信）
   * @param headFiles head 侧文件全文（涉事文件来源）
   * @return 案件输入
   */
  static JudgeCaseInput buildJudgeCase(
      CandidateFinding finding,
      String verifierVerdictText,
      String caseReason,
      Map<String, FileContent> headFiles) {
    return new JudgeCaseInput(
        finding.id(),
        finding.category().name().toLowerCase(Locale.ROOT),
        finding.severityProposed().name().toLowerCase(Locale.ROOT),
        finding.title(),
        finding.description(),
        finding.failureMode(),
        finding.evidence(),
        finding.suggestion(),
        verifierVerdictText,
        renderCaseContext(finding, verifierVerdictText, headFiles),
        caseReason);
  }

  // ---------- 渲染 ----------

  private static VerificationItem toItem(CandidateFinding finding) {
    return new VerificationItem(
        finding.id(),
        finding.category().name().toLowerCase(Locale.ROOT),
        finding.severityProposed().name().toLowerCase(Locale.ROOT),
        finding.title(),
        finding.description(),
        finding.failureMode(),
        finding.evidence(),
        finding.suggestion() == null ? "" : finding.suggestion());
  }

  private static String renderFindingsText(List<FindingAggregator.Scored> batch) {
    return batch.stream()
        .map(s -> toItem(s.finding()))
        .map(
            item ->
                "### id="
                    + item.findingId()
                    + " category="
                    + item.category()
                    + " severity="
                    + item.severity()
                    + "\n标题："
                    + item.title()
                    + "\n失败模式："
                    + item.failureMode()
                    + "\n证据："
                    + item.evidence()
                    + "\n")
        .collect(Collectors.joining("\n"));
  }

  /** 文件全文（超预算截行）+ 该文件 diff 切片（≤2k token）。 */
  private static String renderCodeContext(
      String file,
      List<FindingAggregator.Scored> batch,
      Map<String, FileContent> headFiles,
      DiffResult diff,
      long contextBudget) {
    StringBuilder sb = new StringBuilder();
    Optional<FileContent> content = Optional.ofNullable(headFiles.get(file));
    String fullText =
        content.filter(c -> !c.binary() && c.text() != null).map(FileContent::text).orElse(null);
    long sliceBudget = Math.min(2_000, Math.max(200, contextBudget / 4));
    long textBudget = Math.max(200, contextBudget - sliceBudget);
    if (fullText != null) {
      sb.append("文件全文（")
          .append(file)
          .append("，head 侧）：\n")
          .append(capLines(fullText, textBudget))
          .append("\n\n");
    } else {
      sb.append("文件全文不可得（").append(file).append("）。\n\n");
    }
    String patch =
        diff.files().stream()
            .filter(f -> f.path().equals(file))
            .map(f -> f.patch())
            .filter(p -> p != null && !p.isBlank())
            .findFirst()
            .orElse(null);
    if (patch != null) {
      sb.append("该文件 diff 切片：\n").append(capLines(patch, sliceBudget)).append("\n");
    }
    return sb.toString();
  }

  /** context 类被引矛盾证据原文（03§4.2 v1.3.1：编排器预取内联；M2 经 CitedEvidenceResolver best effort）。 */
  private static String renderCitedEvidence(
      List<FindingAggregator.Scored> batch, CitedEvidenceResolver resolver) {
    return batch.stream()
        .filter(s -> s.finding().category() == FindingCategory.CONTEXT)
        .map(s -> resolveCitedFor(s.finding(), resolver))
        .filter(text -> !text.isBlank())
        .collect(Collectors.joining("\n\n"));
  }

  private static String resolveCitedFor(CandidateFinding finding, CitedEvidenceResolver resolver) {
    StringBuilder sb = new StringBuilder();
    sb.append("结论 ").append(finding.id()).append(" 引用的矛盾证据：\n");
    boolean resolved = false;
    for (String sha : extractCommitRefs(finding, CITED_COMMIT_MAX)) {
      String patch = resolver.commitPatch(sha, finding.file());
      if (!patch.isBlank()) {
        sb.append("--- commit ")
            .append(sha)
            .append(" ---\n")
            .append(capLines(patch, CITED_COMMIT_TOKEN_CAP))
            .append('\n');
        resolved = true;
      }
    }
    if (!resolved) {
      // 引用不可得（无本地仓库/commit 不存在/引用格式未按 M4f 约定）：finding 自带证据原文兜底
      sb.append("（被引 commit diff 不可得，以下为结论自带证据原文）\n").append(finding.evidence()).append('\n');
    }
    return sb.toString();
  }

  /** REVIEW.md 证据要求 + context 类相关条目（Product context/Known incidents；可信内容，不定界）。 */
  private static String renderPolicySections(
      ReviewPolicy policy, List<FindingAggregator.Scored> batch) {
    StringBuilder sb = new StringBuilder();
    if (!policy.evidenceRequirements().isEmpty()) {
      sb.append("证据要求：\n");
      policy.evidenceRequirements().forEach(line -> sb.append("- ").append(line).append('\n'));
    }
    boolean hasContext =
        batch.stream().anyMatch(s -> s.finding().category() == FindingCategory.CONTEXT);
    if (hasContext) {
      if (!policy.productContext().isEmpty()) {
        sb.append("Product context（产品背景与隐含假设）：\n");
        policy.productContext().forEach(line -> sb.append("- ").append(capLine(line)).append('\n'));
      }
      if (!policy.knownIncidents().isEmpty()) {
        sb.append("Known incidents（历史事故/复盘）：\n");
        policy.knownIncidents().forEach(line -> sb.append("- ").append(capLine(line)).append('\n'));
      }
    }
    return sb.toString();
  }

  /** Judge 涉事上下文：finding 文件优先 + 验证者判定中出现的其他已变更文件，合计 ≤3 文件（03§6）。 */
  private static String renderCaseContext(
      CandidateFinding finding, String verifierVerdictText, Map<String, FileContent> headFiles) {
    LinkedHashSet<String> files = new LinkedHashSet<>();
    files.add(finding.file());
    headFiles.keySet().stream()
        .filter(path -> !path.equals(finding.file()))
        .sorted()
        .filter(verifierVerdictText::contains)
        .limit(JUDGE_MAX_FILES - 1)
        .forEach(files::add);
    StringBuilder sb = new StringBuilder();
    for (String path : files) {
      FileContent content = headFiles.get(path);
      if (content == null || content.binary() || content.text() == null) {
        continue;
      }
      sb.append("--- ")
          .append(path)
          .append("（head 侧）---\n")
          .append(capLines(content.text(), JUDGE_FILE_TOKEN_CAP))
          .append('\n');
    }
    return sb.length() == 0 ? "" : sb.toString();
  }

  // ---------- 工具 ----------

  private static Set<String> extractCommitRefs(CandidateFinding finding, int limit) {
    Set<String> refs = new LinkedHashSet<>();
    Matcher matcher = COMMIT_REF.matcher(finding.evidence() + "\n" + finding.description());
    while (matcher.find() && refs.size() < limit) {
      refs.add(matcher.group());
    }
    return refs;
  }

  /** 按 token 预算截行（保留头部；确定性——同输入同输出，可重放）。 */
  static String capLines(String text, long tokenBudget) {
    if (text == null || text.isBlank()) {
      return "";
    }
    if (TokenMath.estimateTokens(text) <= tokenBudget) {
      return text;
    }
    List<String> lines = text.lines().collect(Collectors.toList());
    StringBuilder sb = new StringBuilder();
    long used = 0;
    for (String line : lines) {
      long cost = TokenMath.estimateTokens(line) + 1;
      if (used + cost > tokenBudget) {
        sb.append("…（超预算截断）");
        break;
      }
      sb.append(line).append('\n');
      used += cost;
    }
    return sb.toString();
  }

  private static String capLine(String line) {
    return line.length() <= 300 ? line : line.substring(0, 300) + "…";
  }
}
