package org.cra.core.internal;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.cra.agents.api.AgentBudget;
import org.cra.agents.api.JudgeCaseInput;
import org.cra.agents.api.VerificationBatchInput;
import org.cra.agents.api.VerificationItem;
import org.cra.common.context.ReviewPolicy;
import org.cra.common.context.TokenMath;
import org.cra.common.finding.CandidateFinding;
import org.cra.common.finding.ContextCitation;
import org.cra.common.finding.FindingCategory;
import org.cra.common.git.DiffResult;
import org.cra.common.git.FileContent;
import org.cra.core.internal.pipeline.CallSiteIndex;
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

  /** 补查文件内容的 token 上限（verifier 输入预算内为补查预留；按预算二次收缩）。 */
  private static final long FOLLOWUP_TOKEN_CAP = 6_000;

  /** 固定预留：系统提示、UNTRUSTED 定界、输出契约与估算误差余量（不可裁剪）。 */
  private static final long MISC_RESERVE = 1_200;

  /** 代码上下文最小配额（低于此值验证者无从核对代码行——预留超限时优先让代码可见）。 */
  private static final long MIN_CODE_CONTEXT = 1_000;

  /** 可裁剪段（调用点索引/切片）的最小注入配额（更小只够写截断声明，不如明确声明未注入）。 */
  private static final long MIN_APPEND_BUDGET = 200;

  /** 被引 commit diff 节选上限（03§4.2：每 commit ≤1k token，至多 3 个 commit）。 */
  private static final long CITED_COMMIT_TOKEN_CAP = 1_000;

  private static final int CITED_COMMIT_MAX = 3;

  /** 被引事件史条目渲染上限（ADR-0012：事件摘要 ≤2k 进 CrossChecker，验证层每批 ≤1.5k）。 */
  private static final long CITED_EVENT_TOKEN_CAP = 1_500;

  private static final int CITED_EVENT_MAX = 5;

  /** Judge 涉事文件单文件 token 上限（03§6：≤40k/案件，3 文件内分配；2026-10-31 预算翻倍 4k→8k，3×8k ≤ 40k）。 */
  private static final long JUDGE_FILE_TOKEN_CAP = 8_000;

  private static final int JUDGE_MAX_FILES = 3;

  /** Judge 案件调用点索引渲染上限（ADR-0015：40k 案件预算内的预留）。 */
  private static final long JUDGE_CALL_SITE_TOKEN_CAP = 500;

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
    return buildBatch(file, batch, policy, headFiles, diff, budget, citedResolver, List.of());
  }

  /**
   * 组装验证批次输入（v1.3.2 test 类分型：注入仓库测试文件清单）。
   *
   * <p>背景（M3 取证）：test 类 finding 的核心主张是"某行为无测试覆盖"——负向/全局主张， 而验证上下文只有 main 文件时无法证否 → 必然 INSUFFICIENT
   * → Judge 按证据不足拒 （L3 实测 judge 53 拒中 48 条为此）。注入测试文件清单后，验证者可据清单判断"是否 已有覆盖"，未列出的测试行为可用 followup_file
   * 补查（03§4.2 受控补查）。
   *
   * @param testFileIndex 仓库测试文件清单（head 侧；空 = 不可得或未采集，按原形态验证）
   */
  static VerificationBatchInput buildBatch(
      String file,
      List<FindingAggregator.Scored> batch,
      ReviewPolicy policy,
      Map<String, FileContent> headFiles,
      DiffResult diff,
      AgentBudget budget,
      CitedEvidenceResolver citedResolver,
      List<String> testFileIndex) {
    return buildBatch(
        file, batch, policy, headFiles, diff, budget, citedResolver, testFileIndex, null);
  }

  /**
   * 组装验证批次输入（M4f-full：context 类事件引用按 repo 解析）。
   *
   * @param repo 仓库（owner/name；事件引用解析用；null = 不解析）
   */
  static VerificationBatchInput buildBatch(
      String file,
      List<FindingAggregator.Scored> batch,
      ReviewPolicy policy,
      Map<String, FileContent> headFiles,
      DiffResult diff,
      AgentBudget budget,
      CitedEvidenceResolver citedResolver,
      List<String> testFileIndex,
      String repo) {
    return buildBatch(
        file, batch, policy, headFiles, diff, budget, citedResolver, testFileIndex, repo, null, "");
  }

  /**
   * 组装验证批次输入（M4e 语义上下文：L2 跨文件切片注入 Verifier brief；ADR-0014）。
   *
   * <p>切片与代码上下文同属不可信内容，占用同一 {@code codeContext} 槽；切片 token 从代码上下文预算中扣除， 保证验证 brief
   * 不越单代理输入硬限（04§2.2、ADR-0014 决策 7）。
   *
   * @param crossFileContext L2 跨文件切片文本（{@code CrossFileSlices.text()}；未启用/不可得为空串）
   */
  static VerificationBatchInput buildBatch(
      String file,
      List<FindingAggregator.Scored> batch,
      ReviewPolicy policy,
      Map<String, FileContent> headFiles,
      DiffResult diff,
      AgentBudget budget,
      CitedEvidenceResolver citedResolver,
      List<String> testFileIndex,
      String repo,
      String crossFileContext) {
    return buildBatch(
        file,
        batch,
        policy,
        headFiles,
        diff,
        budget,
        citedResolver,
        testFileIndex,
        repo,
        null,
        crossFileContext);
  }

  /**
   * 组装验证批次输入（最终形态：ADR-0015 调用点索引 + ADR-0014 跨文件切片并存；预算合并预留）。
   *
   * <p>索引渲染 token 从 codeContext 预算中预留（不突破 03§6 verifier 输入预算）；不可得时如实声明。
   *
   * @param callSiteIndex 调用点索引（null = 不适用/未采集）
   * @param crossFileContext L2 跨文件切片文本（未启用/不可得为空串）
   */
  static VerificationBatchInput buildBatch(
      String file,
      List<FindingAggregator.Scored> batch,
      ReviewPolicy policy,
      Map<String, FileContent> headFiles,
      DiffResult diff,
      AgentBudget budget,
      CitedEvidenceResolver citedResolver,
      List<String> testFileIndex,
      String repo,
      CallSiteIndex callSiteIndex,
      String crossFileContext) {
    List<VerificationItem> items = batch.stream().map(s -> toItem(s.finding())).toList();
    String findingsText = renderItemsText(items);
    String testIndex = renderTestFileIndex(batch, testFileIndex);
    String slices = crossFileContext == null ? "" : crossFileContext;
    String citedEvidence = renderCitedEvidence(batch, citedResolver, repo);
    String policySections = renderPolicySections(policy, batch);
    // 预留必须覆盖最终 brief 的全部非代码段（含被引证据、策略、测试清单与系统提示余量），
    // 否则 context 类批次会在 AgentRunner 发送前被输入硬限杀掉 → UNVERIFIED 不发布。
    // 索引（≤1000）与切片（≤6000 默认）是"可裁剪"预留：合计不超过 预算 − 固定段 − 最小代码上下文，
    // 保证 verifier 32k 下不越界（ADR-0015 §2.2 修订、04§2.2；2026-10-31 预算翻倍）。
    // 分层核对契约：仅调用点索引可用时注入（可信指令段，不进 UNTRUSTED 定界；04§2.6）
    String layerContract =
        callSiteIndex != null && callSiteIndex.available()
            ? org.cra.agents.api.LayerContract.SECTION
            : "";
    long fixedReserved =
        fixedReserved(findingsText, citedEvidence, policySections, testIndex, layerContract);
    long discretionary = Math.max(0, budget.maxInputTokens() - fixedReserved - MIN_CODE_CONTEXT);
    String indexText = callSiteIndex != null ? renderIndex(callSiteIndex, discretionary) : "";
    long indexTokens = TokenMath.estimateTokens(indexText);
    SliceSelection sliceSelection = selectSlice(slices, discretionary - indexTokens);
    String sliceText = sliceSelection.text();
    long sliceTokens = sliceSelection.tokens();
    long contextBudget =
        Math.max(
            MIN_CODE_CONTEXT, budget.maxInputTokens() - fixedReserved - indexTokens - sliceTokens);
    String codeContext = renderCodeContext(file, batch, headFiles, diff, contextBudget);
    if (callSiteIndex != null) {
      codeContext = codeContext.isBlank() ? indexText : codeContext + "\n" + indexText;
    }
    if (!sliceText.isBlank()) {
      codeContext = codeContext.isBlank() ? sliceText : codeContext + "\n\n" + sliceText;
    }
    return new VerificationBatchInput(
        file, items, codeContext, citedEvidence, policySections, "", testIndex, layerContract);
  }

  /**
   * 固定段 token 预留（findings/被引证据/策略/测试清单/分层契约 + 系统余量）。
   *
   * @param findingsText findings 渲染
   * @param citedEvidence 被引证据
   * @param policySections 策略段
   * @param testIndex 测试清单
   * @param layerContract 分层契约段
   * @return 预留 token
   */
  private static long fixedReserved(
      String findingsText,
      String citedEvidence,
      String policySections,
      String testIndex,
      String layerContract) {
    return TokenMath.estimateTokens(findingsText)
        + TokenMath.estimateTokens(citedEvidence)
        + TokenMath.estimateTokens(policySections)
        + TokenMath.estimateTokens(testIndex)
        + TokenMath.estimateTokens(layerContract)
        + MISC_RESERVE;
  }

  /**
   * 调用点索引渲染（预算不足时给不可得声明）。
   *
   * @param callSiteIndex 索引
   * @param discretionary 可裁剪预算
   * @return 索引段
   */
  private static String renderIndex(CallSiteIndex callSiteIndex, long discretionary) {
    return discretionary >= MIN_APPEND_BUDGET
        ? callSiteIndex.render(Math.min(CallSiteIndex.RENDER_TOKEN_CAP, discretionary))
        : "## 受影响符号的调用点索引\n（验证预算不足，索引未注入；如需调用方证据可用 followup_file 补查）\n";
  }

  /**
   * 跨文件切片按剩余预算选取（整体 / 截断 / 不可得声明）。
   *
   * @param slices 切片文本
   * @param availableForSlice 可用预算
   * @return 选取结果
   */
  private static SliceSelection selectSlice(String slices, long availableForSlice) {
    if (slices.isBlank()) {
      return new SliceSelection("", 0);
    }
    long sliceTokens = TokenMath.estimateTokens(slices);
    if (sliceTokens <= availableForSlice) {
      return new SliceSelection(slices, sliceTokens);
    }
    if (availableForSlice >= MIN_APPEND_BUDGET) {
      String capped = capLines(slices, availableForSlice);
      return new SliceSelection(capped, TokenMath.estimateTokens(capped));
    }
    String unavailable = "（L2 跨文件切片因验证预算不足未注入）";
    return new SliceSelection(unavailable, TokenMath.estimateTokens(unavailable));
  }

  /**
   * 切片选取结果。
   *
   * @param text 切片文本
   * @param tokens token 数
   */
  private record SliceSelection(String text, long tokens) {}

  /**
   * 测试文件清单渲染（仅当批内含 test 类 finding；≤80 行，超出注明截断计数）。
   *
   * @param batch 本批候选（含类别）
   * @param testFileIndex 仓库测试文件清单（可空）
   * @return 渲染段；不适用时为空串
   */
  private static String renderTestFileIndex(
      List<FindingAggregator.Scored> batch, List<String> testFileIndex) {
    boolean hasTestFinding =
        batch.stream().anyMatch(s -> s.finding().category() == FindingCategory.TEST);
    if (!hasTestFinding || testFileIndex == null || testFileIndex.isEmpty()) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    sb.append("## 仓库测试文件清单（供核对'是否已有测试覆盖'——未列出的测试行为可用 followup_file 补查）\n");
    int shown = Math.min(testFileIndex.size(), 80);
    for (int i = 0; i < shown; i++) {
      sb.append("- ").append(testFileIndex.get(i)).append('\n');
    }
    if (testFileIndex.size() > shown) {
      sb.append("…（共 ")
          .append(testFileIndex.size())
          .append(" 个测试文件，仅列前 ")
          .append(shown)
          .append(" 个；可按需 followup_file 补查）\n");
    }
    return sb.toString();
  }

  /**
   * 组装第二轮补查输入（03§4.2 受控补查：至多一次）。
   *
   * <p>补查文本追加在既有代码上下文之上；代码上下文按剩余预算等量收缩，保证第二轮 brief 仍在验证者输入硬限内 （预算可取覆盖值， 不再按固定 9k 估算——05§5
   * agents.budget.verifier.*）。
   *
   * @param base 第一轮输入
   * @param followupText 补查文件全文（先截到 {@link #FOLLOWUP_TOKEN_CAP}）
   * @param budget 验证者输入预算
   * @return 第二轮输入
   */
  static VerificationBatchInput withFollowup(
      VerificationBatchInput base, String followupText, AgentBudget budget) {
    String followup = capLines(followupText, FOLLOWUP_TOKEN_CAP);
    long followupTokens = TokenMath.estimateTokens(followup);
    long fixedReserved =
        TokenMath.estimateTokens(renderItemsText(base.items()))
            + TokenMath.estimateTokens(base.citedEvidence())
            + TokenMath.estimateTokens(base.policySections())
            + TokenMath.estimateTokens(base.testFileIndex())
            + TokenMath.estimateTokens(base.layerContractSection())
            + MISC_RESERVE
            + followupTokens;
    long contextCap = Math.max(MIN_CODE_CONTEXT, budget.maxInputTokens() - fixedReserved);
    return new VerificationBatchInput(
        base.file(),
        base.items(),
        capLines(base.codeContext(), contextCap),
        base.citedEvidence(),
        base.policySections(),
        followup,
        base.testFileIndex(),
        base.layerContractSection());
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
    return buildJudgeCase(finding, verifierVerdictText, caseReason, headFiles, null, null);
  }

  /**
   * 组装 Judge 案件输入（context 类分型：追加被引矛盾证据原文与 REVIEW.md 产品段——03§4.2 v1.3.1 "Judge brief
   * 同规则分型"，否则仲裁者对代码外证据失明）。
   *
   * @param finding 被仲裁的候选
   * @param verifierVerdictText 验证者判定原文（verdict + rationale）
   * @param caseReason 情形说明（可信）
   * @param headFiles head 侧文件全文（涉事文件来源）
   * @param policy base 分支 REVIEW.md 策略（context 类产品段来源；null = 不注入）
   * @param resolver 被引证据解析（context 类；null = 不注入）
   * @return 案件输入
   */
  static JudgeCaseInput buildJudgeCase(
      CandidateFinding finding,
      String verifierVerdictText,
      String caseReason,
      Map<String, FileContent> headFiles,
      ReviewPolicy policy,
      CitedEvidenceResolver resolver) {
    return buildJudgeCase(
        finding, verifierVerdictText, caseReason, headFiles, policy, resolver, null);
  }

  /**
   * 组装 Judge 案件输入（M4f-full：事件引用按 repo 解析）。
   *
   * @param repo 仓库（owner/name；null = 不解析事件）
   * @return 案件输入
   */
  static JudgeCaseInput buildJudgeCase(
      CandidateFinding finding,
      String verifierVerdictText,
      String caseReason,
      Map<String, FileContent> headFiles,
      ReviewPolicy policy,
      CitedEvidenceResolver resolver,
      String repo) {
    return buildJudgeCase(
        new JudgeCaseContent(finding, verifierVerdictText, caseReason),
        new JudgeCaseSources(headFiles, policy, resolver),
        new JudgeCaseContext(repo, null));
  }

  /**
   * 组装 Judge 案件输入（ADR-0015：bug 类案件附带调用点索引，≤500 token）。
   *
   * @param content 案件内容（finding/验证结论/原因）
   * @param sources 上下文来源（head 文件/策略/被引证据解析）
   * @param context 案件上下文（repo/调用点索引）
   * @return 案件输入
   */
  static JudgeCaseInput buildJudgeCase(
      JudgeCaseContent content, JudgeCaseSources sources, JudgeCaseContext context) {
    CandidateFinding finding = content.finding();
    String verifierVerdictText = content.verifierVerdictText();
    String caseReason = content.caseReason();
    Map<String, FileContent> headFiles = sources.headFiles();
    ReviewPolicy policy = sources.policy();
    CitedEvidenceResolver resolver = sources.resolver();
    String repo = context.repo();
    CallSiteIndex callSiteIndex = context.callSiteIndex();
    String contextText = renderCaseContext(finding, verifierVerdictText, headFiles);
    if (finding.category() == FindingCategory.BUG && callSiteIndex != null) {
      contextText = contextText + "\n" + callSiteIndex.render(JUDGE_CALL_SITE_TOKEN_CAP);
    }
    if (finding.category() == FindingCategory.CONTEXT && (policy != null || resolver != null)) {
      StringBuilder ext = new StringBuilder(contextText);
      ext.append("\n\n");
      if (resolver != null) {
        ext.append(renderCitedFor(finding, resolver, repo));
      }
      if (policy != null) {
        appendContextPolicySections(ext, policy);
      }
      contextText = ext.toString();
    }
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
        contextText,
        caseReason);
  }

  /**
   * Judge 案件内容。
   *
   * @param finding 候选
   * @param verifierVerdictText 验证结论文本
   * @param caseReason 案件原因
   */
  record JudgeCaseContent(
      CandidateFinding finding, String verifierVerdictText, String caseReason) {}

  /**
   * Judge 案件上下文来源。
   *
   * @param headFiles head 文件
   * @param policy 评审策略（可空）
   * @param resolver 被引证据解析（可空）
   */
  record JudgeCaseSources(
      Map<String, FileContent> headFiles, ReviewPolicy policy, CitedEvidenceResolver resolver) {}

  /**
   * Judge 案件上下文定位。
   *
   * @param repo 仓库（可空）
   * @param callSiteIndex 调用点索引（可空）
   */
  record JudgeCaseContext(String repo, CallSiteIndex callSiteIndex) {}

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

  /** 待复核结论渲染（批次组装与预算回算共用同一形态，避免两处格式漂移）。 */
  private static String renderItemsText(List<VerificationItem> items) {
    return items.stream()
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
                    + "\n描述："
                    + item.description()
                    + "\n失败模式："
                    + item.failureMode()
                    + "\n证据："
                    + item.evidence()
                    + "\n建议："
                    + item.suggestion()
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

  /** context 类被引矛盾证据原文（03§4.2 v1.3.1：编排器预取内联；引用格式见 ContextCitation）。 */
  private static String renderCitedEvidence(
      List<FindingAggregator.Scored> batch, CitedEvidenceResolver resolver, String repo) {
    return batch.stream()
        .filter(s -> s.finding().category() == FindingCategory.CONTEXT)
        .map(s -> renderCitedFor(s.finding(), resolver, repo))
        .filter(text -> !text.isBlank())
        .collect(Collectors.joining("\n\n"));
  }

  /** 单条 context finding 的被引证据：结构化 commit 引用（含 path）+ 事件史条目（ADR-0012）+ REVIEW.md 条目锚，逐条渲染。 */
  private static String renderCitedFor(
      CandidateFinding finding, CitedEvidenceResolver resolver, String repo) {
    CitedEvidenceResolver.Citation citation = resolver.citationOf(finding, repo);
    StringBuilder sb = new StringBuilder();
    sb.append("结论 ").append(finding.id()).append(" 引用的矛盾证据：\n");
    boolean resolved = false;
    int rendered = 0;
    for (ContextCitation.CommitRef ref : citation.commits()) {
      if (rendered >= CITED_COMMIT_MAX) {
        break;
      }
      rendered++;
      resolved |= appendCommitEvidence(sb, resolver, finding, ref);
    }
    if (!citation.events().isEmpty()) {
      StringBuilder events = new StringBuilder("被引事件史条目（ADR-0012；事件摘要属不可信输入）：\n");
      int eventCount = 0;
      for (CitedEvidenceResolver.EventEvidence evidence : citation.events()) {
        if (eventCount >= CITED_EVENT_MAX) {
          break;
        }
        eventCount++;
        if (evidence.resolved()) {
          appendEventEvidence(events, evidence.event());
          resolved = true;
        } else {
          events
              .append("- event ")
              .append(evidence.ref().eventId())
              .append("（事件史未装配或未找到——按结论自带证据核对）\n");
        }
      }
      sb.append(capLines(events.toString(), CITED_EVENT_TOKEN_CAP)).append('\n');
    }
    if (!citation.reviewMdRefs().isEmpty()) {
      appendReviewMdRefs(sb, citation.reviewMdRefs());
      resolved = true;
    }
    if (!resolved) {
      // 引用不可得（无本地仓库/commit 不存在/事件史未装配）：finding 自带证据原文兜底
      sb.append("（被引证据不可得，以下为结论自带证据原文）\n").append(finding.evidence()).append('\n');
    }
    return sb.toString();
  }

  /**
   * 追加单条 commit 证据（patch 不可得时不追加）。
   *
   * @param sb 输出
   * @param resolver 被引证据解析
   * @param finding 候选（路径回退）
   * @param ref commit 引用
   * @return true 若 patch 可得（resolved）
   */
  private static boolean appendCommitEvidence(
      StringBuilder sb,
      CitedEvidenceResolver resolver,
      CandidateFinding finding,
      ContextCitation.CommitRef ref) {
    String path = ref.effectivePath(finding.file());
    String patch = resolver.commitPatch(ref.sha(), path);
    if (patch.isBlank()) {
      return false;
    }
    sb.append("--- commit ")
        .append(ref.sha())
        .append(" path ")
        .append(path)
        .append(" ---\n")
        .append(capLines(patch, CITED_COMMIT_TOKEN_CAP))
        .append('\n');
    return true;
  }

  /**
   * 追加单条已解析事件史证据。
   *
   * @param events 事件段
   * @param event 事件
   */
  private static void appendEventEvidence(
      StringBuilder events, org.cra.common.event.ProductEvent event) {
    events
        .append("- event ")
        .append(event.eventId())
        .append(" | type=")
        .append(event.type())
        .append(" | occurred_at=")
        .append(event.occurredAt());
    if (event.service() != null) {
      events.append(" | service=").append(capLine(event.service()));
    }
    if (event.tenant() != null) {
      events.append(" | tenant=").append(capLine(event.tenant()));
    }
    if (event.sourceUrl() != null) {
      events.append(" | source=").append(capLine(event.sourceUrl()));
    }
    events.append("\n  摘要：").append(capLine(event.summary())).append('\n');
    if (!event.commitRefs().isEmpty()) {
      events.append("  commit_refs: ").append(String.join(", ", event.commitRefs())).append('\n');
    }
    if (!event.paths().isEmpty()) {
      events.append("  paths: ").append(String.join(", ", event.paths())).append('\n');
    }
  }

  /**
   * 追加 REVIEW.md 被引条目段。
   *
   * @param sb 输出
   * @param refs 条目引用
   */
  private static void appendReviewMdRefs(StringBuilder sb, List<ContextCitation.ReviewMdRef> refs) {
    sb.append("被引 REVIEW.md 条目：\n");
    refs.forEach(
        ref ->
            sb.append("- ")
                .append(ref.section())
                .append(": ")
                .append(ref.entry().isBlank() ? "（整段，见下方 Review.md 段）" : capLine(ref.entry()))
                .append('\n'));
  }

  /** REVIEW.md 证据要求 + context 类相关条目（Product context/Known incidents/Sniff tests；可信内容，不定界）。 */
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
      appendContextPolicySections(sb, policy);
    }
    return sb.toString();
  }

  /** context 类 REVIEW.md 产品段（03§4.2 v1.3.1：Product context / Known incidents / Sniff tests）。 */
  private static void appendContextPolicySections(StringBuilder sb, ReviewPolicy policy) {
    if (!policy.productContext().isEmpty()) {
      sb.append("Product context（产品背景与隐含假设）：\n");
      policy.productContext().forEach(line -> sb.append("- ").append(capLine(line)).append('\n'));
    }
    if (!policy.knownIncidents().isEmpty()) {
      sb.append("Known incidents（历史事故/复盘）：\n");
      policy.knownIncidents().forEach(line -> sb.append("- ").append(capLine(line)).append('\n'));
    }
    if (!policy.sniffTests().isEmpty()) {
      sb.append("Sniff tests（经验性嗅探检查）：\n");
      policy.sniffTests().forEach(line -> sb.append("- ").append(capLine(line)).append('\n'));
    }
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
        .limit((long) JUDGE_MAX_FILES - 1)
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
    return sb.isEmpty() ? "" : sb.toString();
  }

  // ---------- 工具 ----------

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
