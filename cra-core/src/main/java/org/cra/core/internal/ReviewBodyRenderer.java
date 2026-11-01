package org.cra.core.internal;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.cra.common.finding.CandidateFinding;
import org.cra.common.finding.Severity;
import org.cra.common.run.ReviewRequest;
import org.cra.common.signoff.SignoffAdvice;
import org.cra.common.text.Texts;
import org.cra.core.internal.pipeline.FindingAggregator;
import org.cra.core.internal.pipeline.PreExistingBlameChecker;

/**
 * 评审正文渲染（05§1.3 标记规则 / 05§2.2 发布顺序）：inline 评论、Summary 正文与转义。
 *
 * <p>从 {@link ReviewPipeline} 拆出：渲染是纯函数，不触达平台/账本；标记版本与转义规则集中在此，避免与发布流程漂移。
 *
 * <p>Summary 判决前置（02§4.2 护栏的阅读面落地）：标题之后紧跟判决头（结论行 + 状态表），读报告的人第一屏即得 有没有问题/问题在哪/还有哪些不确定；统计与签字建议收纳进
 * {@code <details>} 折叠块（只收噪音，不收发现），空结果散文与账本标记语义不变。
 */
final class ReviewBodyRenderer {

  /** 评论标记版本（05§1.3；发布器在正文末尾追加）。 */
  static final String MARKER_VERSION = "2.1";

  private ReviewBodyRenderer() {}

  static String inlineBody(
      FindingAggregator.Scored s, String runId, String commitSha, Set<String> unverifiedIds) {
    CandidateFinding f = s.finding();
    boolean unverified = unverifiedIds.contains(f.id());
    StringBuilder body = new StringBuilder();
    body.append(unverified ? "**[UNVERIFIED][" : "**[")
        .append(f.severityProposed().name().toUpperCase(Locale.ROOT))
        .append("] ")
        .append(escape(f.title()))
        .append("** — ")
        .append(codeSpan(f.file() + ":" + f.line()))
        .append("\n\n");
    if (unverified) {
      body.append("> ⚠️ 验证不可得（复核者超时/预算击穿），本条未经独立复核即发布（publish-unverified=labeled）。\n\n");
    }
    body.append(escape(f.description())).append("\n\n");
    body.append("**失败模式**：").append(escape(f.failureMode())).append("\n\n");
    body.append("**证据**：\n").append(codeBlock(f.evidence())).append("\n");
    if (f.suggestion() != null && !f.suggestion().isBlank()) {
      body.append("\n**建议**：\n").append(codeBlock(f.suggestion())).append("\n");
    }
    // 规则 3（05§1.3）：标记行由发布器在全部正文之后追加（正文已全量转义 <!--）
    body.append("\n<!-- cra:v=")
        .append(MARKER_VERSION)
        .append(" | fp=")
        .append(s.fingerprint())
        .append(" | file=")
        .append(f.file())
        .append(" | cat=")
        .append(categoryOf(f))
        .append(" | id=")
        .append(f.id())
        .append(" | run=")
        .append(runId)
        .append(" | commit=")
        .append(commitSha)
        .append(" | line=")
        .append(f.line())
        .append(" -->");
    return body.toString();
  }

  /**
   * Summary 正文渲染。
   *
   * <p>结构（04§4.3）：标题 → 判决头（结论行 + 状态表，02§4.2 三态护栏）→ 变更概述 → 可疑注入警告 → 发现（详情块或分级列表，永远展开） → 收敛控制 →
   * 签字建议（折叠）→ 覆盖与统计（折叠，含复核晋升/降级清单）→ run 完成标记（末位，05§1.3）。
   *
   * @param detailed 载体无行级锚时为 true（M4c/M4d）：全部 finding 以详情块列出（描述/失败模式/证据/建议）， 不依赖 inline 评论承载正文；PR
   *     形态为 false（既有分级列表 + 位置非法段）
   * @param noReviewableChanges 评审计划为空（纯测试变更或全部被策略跳过）：空结果不得写成"评审返回空结果"（02§4.4）
   */
  static String summaryBody(
      ReviewRequest request,
      String runId,
      List<FindingAggregator.Scored> ordered,
      List<FindingAggregator.Scored> fallbacks,
      int skippedDuplicate,
      int suppressedByTruncation,
      List<String> degradations,
      boolean degraded,
      String suspectWarning,
      List<String> coverageLines,
      String convergenceAdvice,
      Set<String> unverifiedIds,
      int unverifiedUnpublished,
      boolean detailed,
      boolean noReviewableChanges,
      SignoffAdvice signoff) {
    StringBuilder body = new StringBuilder();
    body.append("## cra 评审 Summary（").append(runId).append("）\n\n");
    List<FindingAggregator.Scored> important =
        ordered.stream().filter(s -> s.finding().severityProposed() == Severity.IMPORTANT).toList();
    List<FindingAggregator.Scored> nits =
        ordered.stream().filter(s -> s.finding().severityProposed() == Severity.NIT).toList();
    List<FindingAggregator.Scored> preExisting =
        ordered.stream()
            .filter(s -> s.verdict() == PreExistingBlameChecker.Verdict.CONFIRMED_PRE_EXISTING)
            .toList();
    appendVerdictHeader(
        body,
        ordered,
        important,
        nits,
        fallbacks,
        skippedDuplicate,
        suppressedByTruncation,
        degradations,
        degraded,
        unverifiedIds,
        unverifiedUnpublished,
        noReviewableChanges);
    appendChangeHeader(body, request);
    appendSuspectWarning(body, suspectWarning);
    if (detailed) {
      appendDetailedFindings(
          body,
          ordered,
          unverifiedIds,
          skippedDuplicate,
          unverifiedUnpublished,
          noReviewableChanges);
    } else if (noReviewableChanges && ordered.isEmpty()) {
      body.append("本次无可评审变更（仅测试文件或全部变更被策略跳过），按策略不评审。\n\n");
    } else if (important.isEmpty()
        && nits.isEmpty()
        && fallbacks.isEmpty()
        && preExisting.isEmpty()) {
      appendEmptyFindingsNote(
          body, ordered, skippedDuplicate, unverifiedUnpublished, noReviewableChanges);
    } else {
      appendListSections(body, important, nits, preExisting, fallbacks);
    }
    appendConvergenceAndSignoff(body, convergenceAdvice, signoff);
    appendCoverageAndStats(
        body,
        coverageLines,
        detailed,
        skippedDuplicate,
        fallbacks,
        ordered,
        unverifiedIds,
        unverifiedUnpublished,
        degradations);
    appendRunMarker(body, request, runId, ordered, suppressedByTruncation, degraded);
    return body.toString();
  }

  /**
   * 判决头（紧跟标题、先于一切内容）：结论行 + 状态表，从 available 计数推导，不改签名不硬造。
   *
   * <p>三态护栏（02§4.2）必须一眼可分——真无问题（✅）/ 有问题但未验证（⚠️ 计数或"无已确认问题，N 条候选未验证"）/ 评审不完整（降级标记行）；
   * noReviewableChanges 是策略性豁免，不写成"无问题"（02§4.4）。
   *
   * @param body 输出
   * @param ordered 已排序已发布候选（"发现"列与完成标记 findings= 同口径）
   * @param important IMPORTANT 级候选（ordered 按 severity 拆分，与 appendListSections 同源）
   * @param nits NIT 级候选
   * @param fallbacks 位置非法降级进 Summary 的候选（ordered 子集）
   * @param skippedDuplicate 指纹去重抑制数
   * @param suppressedByTruncation 截断抑制数
   * @param degradations 降级事件清单
   * @param degraded run 是否 DEGRADED 收尾
   * @param unverifiedIds 以 UNVERIFIED 标签发布的 finding id
   * @param unverifiedUnpublished 验证不可得未发布数
   * @param noReviewableChanges 是否无评审范围
   */
  private static void appendVerdictHeader(
      StringBuilder body,
      List<FindingAggregator.Scored> ordered,
      List<FindingAggregator.Scored> important,
      List<FindingAggregator.Scored> nits,
      List<FindingAggregator.Scored> fallbacks,
      int skippedDuplicate,
      int suppressedByTruncation,
      List<String> degradations,
      boolean degraded,
      Set<String> unverifiedIds,
      int unverifiedUnpublished,
      boolean noReviewableChanges) {
    int unverified = unverifiedIds.size() + unverifiedUnpublished;
    if (noReviewableChanges) {
      body.append("**ℹ️ 按策略无可评审变更**\n\n");
    } else if (!important.isEmpty() || !nits.isEmpty()) {
      body.append("**结论：⚠️ ");
      if (!important.isEmpty()) {
        body.append(important.size()).append(" Important");
      }
      if (!important.isEmpty() && !nits.isEmpty()) {
        body.append(" + ");
      }
      if (!nits.isEmpty()) {
        body.append(nits.size()).append(" Nit");
      }
      body.append("**")
          .append(
              verdictUncertainty(
                  unverified, skippedDuplicate, suppressedByTruncation, fallbacks.size()))
          .append("\n\n");
    } else if (unverifiedUnpublished > 0) {
      body.append("**结论：⚠️ 无已确认问题，").append(unverifiedUnpublished).append(" 条候选未验证**\n\n");
    } else {
      body.append("**结论：✅ 未发现问题**\n\n");
    }
    if (degraded || !degradations.isEmpty()) {
      body.append("**⚠️ 评审不完整（降级）**\n\n");
    }
    body.append("| 发现 | 未验证 | 去重抑制 | 截断 | 位置非法 |\n")
        .append("|---|---|---|---|---|\n")
        .append("| ")
        .append(ordered.size())
        .append(" | ")
        .append(unverified)
        .append(" | ")
        .append(skippedDuplicate)
        .append(" | ")
        .append(suppressedByTruncation)
        .append(" | ")
        .append(fallbacks.size())
        .append(" |\n\n");
  }

  /**
   * 判决结论行的"不确定项"括注：未验证/去重抑制/截断恒列（0 也列，与设计样例一致），位置非法降级非零时追加—— fallback 是 ordered
   * 子集，只作限定语不做加数，避免与完成标记 findings= 口径冲突。
   *
   * @param unverified 未验证候选数（UNVERIFIED 标签发布 + 未发布之和）
   * @param skippedDuplicate 指纹去重抑制数
   * @param suppressedByTruncation 截断抑制数
   * @param fallbackCount 位置非法降级数
   * @return 形如 {@code （2 条候选未验证 · 1 条被去重抑制 · 0 条被截断 · 1 条位置非法降级）}
   */
  private static String verdictUncertainty(
      int unverified, int skippedDuplicate, int suppressedByTruncation, int fallbackCount) {
    StringBuilder parenthetical =
        new StringBuilder("（")
            .append(unverified)
            .append(" 条候选未验证 · ")
            .append(skippedDuplicate)
            .append(" 条被去重抑制 · ")
            .append(suppressedByTruncation)
            .append(" 条被截断");
    if (fallbackCount > 0) {
      parenthetical.append(" · ").append(fallbackCount).append(" 条位置非法降级");
    }
    return parenthetical.append("）").toString();
  }

  /**
   * 可疑注入警告段（05§1.3 规则 2：可疑标记 ≥3 → Summary 警告疑似注入尝试）。
   *
   * @param body 输出
   * @param suspectWarning 可疑标记聚合警告（空串 = 无警告）
   */
  private static void appendSuspectWarning(StringBuilder body, String suspectWarning) {
    if (!suspectWarning.isEmpty()) {
      body.append("> ⚠️ ").append(suspectWarning).append("\n\n");
    }
  }

  /**
   * 空结果声明段（无 Important/Nit/Pre-existing/fallback 可列时）：有去重抑制 → 抑制计数声明；否则按 02§4.2
   * 区分无评审范围/验证不可得/确无发现——验证不可得 ≠ 无问题，不把 UNVERIFIED 静默当空结果。
   *
   * @param body 输出
   * @param ordered 已排序候选
   * @param skippedDuplicate 指纹去重抑制数
   * @param unverifiedUnpublished 验证不可得未发布数
   * @param noReviewableChanges 是否无评审范围
   */
  private static void appendEmptyFindingsNote(
      StringBuilder body,
      List<FindingAggregator.Scored> ordered,
      int skippedDuplicate,
      int unverifiedUnpublished,
      boolean noReviewableChanges) {
    if (ordered.isEmpty() && skippedDuplicate == 0) {
      appendEmptyResultNote(body, unverifiedUnpublished, noReviewableChanges);
    } else {
      body.append("本次无新发现（候选均被指纹去重抑制 ").append(skippedDuplicate).append(" 条）。\n\n");
    }
  }

  /**
   * 确无候选时的空结果说明（02§4.2：无评审范围/验证不可得/确无发现三态可区分）。
   *
   * @param body 输出
   * @param unverifiedUnpublished 验证不可得未发布数
   * @param noReviewableChanges 是否无评审范围
   */
  private static void appendEmptyResultNote(
      StringBuilder body, int unverifiedUnpublished, boolean noReviewableChanges) {
    if (noReviewableChanges) {
      body.append("本次无可评审变更（仅测试文件或全部变更被策略跳过），按策略不评审。\n\n");
    } else if (unverifiedUnpublished > 0) {
      body.append("本次无已确认发现；")
          .append(unverifiedUnpublished)
          .append(" 条候选验证不可得（UNVERIFIED，默认不发布）。\n\n");
    } else {
      body.append("本次未发现问题（评审返回空结果）。\n\n");
    }
  }

  /**
   * 分级列表段（Important/Nit/Pre-existing/位置非法 fallback 四节，各节空则整体缺席；节间以空行分隔）。
   *
   * @param body 输出
   * @param important IMPORTANT 级候选
   * @param nits NIT 级候选
   * @param preExisting jgit blame 确证变更前已存在的候选
   * @param fallbacks 位置非法降级进 Summary 的候选
   */
  private static void appendListSections(
      StringBuilder body,
      List<FindingAggregator.Scored> important,
      List<FindingAggregator.Scored> nits,
      List<FindingAggregator.Scored> preExisting,
      List<FindingAggregator.Scored> fallbacks) {
    if (!important.isEmpty()) {
      body.append("### Important\n");
      for (FindingAggregator.Scored s : important) {
        body.append(listLine(s));
      }
      body.append("\n");
    }
    if (!nits.isEmpty()) {
      body.append("### Nit\n");
      for (FindingAggregator.Scored s : nits) {
        body.append(listLine(s));
      }
      body.append("\n");
    }
    if (!preExisting.isEmpty()) {
      body.append("### Pre-existing（jgit blame 确证变更前已存在）\n");
      for (FindingAggregator.Scored s : preExisting) {
        body.append(listLine(s));
      }
      body.append("\n");
    }
    if (!fallbacks.isEmpty()) {
      body.append("### 位置非法（降级进 Summary）\n");
      for (FindingAggregator.Scored s : fallbacks) {
        body.append("- **")
            .append(escape(s.finding().title()))
            .append("** — `")
            .append(s.finding().file())
            .append(":")
            .append(s.finding().line())
            .append("`：")
            .append(escape(s.finding().description()))
            .append("\n");
      }
      body.append("\n");
    }
  }

  /**
   * 收敛控制与签字建议段：T2.7 轮次超限建议（03§5，停止一切新 finding——人在这一关踩刹车，操作指令保持展开）+ 签字建议（07§5 M4a， 建议型非审批，绑定 head
   * commit）——建议明细属参考噪音，收纳进 {@code <details>} 折叠块； {@code ### 签字建议} 段标题原样保留在块内（过期收尾
   * hasSignoffSection 依赖该标题判定，05§1.3/07§5）。
   *
   * @param body 输出
   * @param convergenceAdvice 收敛建议（null/空白 = 无）
   * @param signoff 签字建议
   */
  private static void appendConvergenceAndSignoff(
      StringBuilder body, String convergenceAdvice, SignoffAdvice signoff) {
    if (convergenceAdvice != null && !convergenceAdvice.isBlank()) {
      body.append("### 收敛控制\n> ⚠️ ").append(convergenceAdvice).append("\n\n");
    }
    List<String> signoffLines = SignoffSections.lines(signoff);
    if (signoffLines.isEmpty()) {
      return;
    }
    body.append("<details>\n<summary>✍️ 签字建议（advisory，非审批）</summary>\n\n");
    for (String line : signoffLines) {
      body.append(line).append("\n");
    }
    body.append("</details>\n\n");
  }

  /**
   * 覆盖与统计折叠块（{@code <details><summary>📊 覆盖与统计</summary>}）："### 覆盖与统计" 声明清单 + 复核与晋升统计 + 降级事件清单
   * 同块收纳——三者同为统计类内容，合并一个折叠块；行文案逐字节保持原有契约（SummarySections/02§4.2/02§4.4）。
   *
   * @param body 输出
   * @param coverageLines 覆盖声明行
   * @param detailed 是否详情形态（载体无行级锚）
   * @param skippedDuplicate 指纹去重抑制数
   * @param fallbacks 位置非法降级候选（计数用）
   * @param ordered 已排序候选（复核与晋升统计）
   * @param unverifiedIds 以 UNVERIFIED 标签发布的 finding id
   * @param unverifiedUnpublished 验证不可得未发布数
   * @param degradations 降级事件（空 = 无清单行）
   */
  private static void appendCoverageAndStats(
      StringBuilder body,
      List<String> coverageLines,
      boolean detailed,
      int skippedDuplicate,
      List<FindingAggregator.Scored> fallbacks,
      List<FindingAggregator.Scored> ordered,
      Set<String> unverifiedIds,
      int unverifiedUnpublished,
      List<String> degradations) {
    body.append("<details>\n<summary>📊 覆盖与统计</summary>\n\n");
    body.append("### 覆盖与统计\n");
    for (String line : coverageLines) {
      body.append("- ").append(line).append("\n");
    }
    if (detailed) {
      body.append("- 载体无行级锚：全部 finding 以详情列出（commit comment / issue 不支持行级评论）\n");
    } else {
      body.append("- 指纹去重抑制 ")
          .append(skippedDuplicate)
          .append(" 条；位置非法降级 ")
          .append(fallbacks.size())
          .append(" 条\n");
    }
    appendUnverifiedStats(body, ordered, unverifiedIds, unverifiedUnpublished, degradations);
    body.append("\n</details>\n");
  }

  /**
   * 复核与晋升统计行（确认/UNVERIFIED 标签发布/验证不可得未发布计数 + 降级事件清单）：渲染进"覆盖与统计"折叠块末尾。
   *
   * @param body 输出
   * @param ordered 已排序候选
   * @param unverifiedIds 以 UNVERIFIED 标签发布的 finding id
   * @param unverifiedUnpublished 验证不可得未发布数
   * @param degradations 降级事件（空 = 无清单行）
   */
  private static void appendUnverifiedStats(
      StringBuilder body,
      List<FindingAggregator.Scored> ordered,
      Set<String> unverifiedIds,
      int unverifiedUnpublished,
      List<String> degradations) {
    body.append("- 复核与晋升：确认 ")
        .append(ordered.size() - unverifiedIds.size())
        .append(" 条")
        .append(
            unverifiedIds.isEmpty()
                ? ""
                : "（含 UNVERIFIED 标签发布 " + unverifiedIds.size() + " 条——验证不可得）")
        .append(
            unverifiedUnpublished > 0
                ? "；验证不可得未发布 " + unverifiedUnpublished + " 条（UNVERIFIED，02§4.2）"
                : "")
        .append("\n");
    if (!degradations.isEmpty()) {
      body.append("- 降级事件：").append(String.join("、", degradations)).append("\n");
    }
  }

  /**
   * run 完成标记段（05§1.3：DEGRADED 也是完整发布，02§4.1；suppressed = 指纹抑制计数）。
   *
   * @param body 输出
   * @param request 评审请求（head commit 来源）
   * @param runId run 标识
   * @param ordered 已排序候选（findings 计数）
   * @param suppressedByTruncation 截断抑制计数
   * @param degraded run 是否 DEGRADED 收尾
   */
  private static void appendRunMarker(
      StringBuilder body,
      ReviewRequest request,
      String runId,
      List<FindingAggregator.Scored> ordered,
      int suppressedByTruncation,
      boolean degraded) {
    body.append("\n<!-- cra:v=")
        .append(MARKER_VERSION)
        .append(" | run=")
        .append(runId)
        .append(" | commit=")
        .append(request.headSha())
        .append(" | status=")
        .append(degraded ? "DEGRADED" : "DONE")
        .append(" | findings=")
        .append(ordered.size())
        .append(" | suppressed=")
        .append(suppressedByTruncation)
        .append(" -->");
  }

  private static void appendChangeHeader(StringBuilder body, ReviewRequest request) {
    switch (request.mode()) {
      case COMMIT ->
          body.append("变更：push 直推 ")
              .append(shortSha(request.baseSha()))
              .append("..")
              .append(shortSha(request.headSha()))
              .append("（commit comment 载体，无行级锚）\n\n");
      case ISSUE ->
          body.append("变更：PR #")
              .append(request.prNumber())
              .append(" ")
              .append(shortSha(request.baseSha()))
              .append("..")
              .append(shortSha(request.headSha()))
              .append("（夜间存量评审，issue 载体，无行级锚）\n\n");
      case PULL_REQUEST ->
          body.append("变更：PR #")
              .append(request.prNumber())
              .append(" ")
              .append(shortSha(request.baseSha()))
              .append("..")
              .append(shortSha(request.headSha()))
              .append("\n\n");
    }
  }

  private static void appendDetailedFindings(
      StringBuilder body,
      List<FindingAggregator.Scored> ordered,
      Set<String> unverifiedIds,
      int skippedDuplicate,
      int unverifiedUnpublished,
      boolean noReviewableChanges) {
    if (ordered.isEmpty()) {
      appendEmptyDetailMessage(body, skippedDuplicate, unverifiedUnpublished, noReviewableChanges);
      return;
    }
    body.append("### 发现详情\n\n");
    for (FindingAggregator.Scored s : ordered) {
      appendFindingDetail(body, s, unverifiedIds.contains(s.finding().id()));
    }
  }

  /**
   * 无可发布详情时的空结果说明（区分不可评审/验证不可得/去重抑制/确无发现）。
   *
   * @param body 输出
   * @param skippedDuplicate 去重跳过数
   * @param unverifiedUnpublished 未验证未发布数
   * @param noReviewableChanges 是否无评审范围
   */
  private static void appendEmptyDetailMessage(
      StringBuilder body,
      int skippedDuplicate,
      int unverifiedUnpublished,
      boolean noReviewableChanges) {
    if (noReviewableChanges) {
      body.append("本次无可评审变更（仅测试文件或全部变更被策略跳过），按策略不评审。\n\n");
    } else if (unverifiedUnpublished > 0) {
      body.append("本次无已确认发现；")
          .append(unverifiedUnpublished)
          .append(" 条候选验证不可得（UNVERIFIED，默认不发布）。\n\n");
    } else if (skippedDuplicate > 0) {
      body.append("本次无新发现（候选均被指纹去重抑制 ").append(skippedDuplicate).append(" 条）。\n\n");
    } else {
      body.append("本次未发现问题（评审返回空结果）。\n\n");
    }
  }

  /**
   * 单条 finding 的详情块（标题/位置/未验证告警/描述/失败模式/证据/建议）。
   *
   * @param body 输出
   * @param s 候选
   * @param unverified 是否未验证
   */
  private static void appendFindingDetail(
      StringBuilder body, FindingAggregator.Scored s, boolean unverified) {
    CandidateFinding f = s.finding();
    body.append("#### ")
        .append(unverified ? "[UNVERIFIED][" : "[")
        .append(f.severityProposed().name().toUpperCase(Locale.ROOT))
        .append("] ")
        .append(escape(f.title()))
        .append(" — ")
        .append(codeSpan(f.file() + ":" + f.line()))
        .append("\n\n");
    if (unverified) {
      body.append("> ⚠️ 验证不可得（复核者超时/预算击穿），本条未经独立复核即发布（publish-unverified=labeled）。\n\n");
    }
    body.append(escape(f.description())).append("\n\n");
    body.append("**失败模式**：").append(escape(f.failureMode())).append("\n\n");
    body.append("**证据**：\n").append(codeBlock(f.evidence())).append("\n\n");
    if (f.suggestion() != null && !f.suggestion().isBlank()) {
      body.append("**建议**：\n").append(codeBlock(f.suggestion())).append("\n\n");
    }
  }

  private static String listLine(FindingAggregator.Scored s) {
    return "- **"
        + escape(s.finding().title())
        + "** — "
        + codeSpan(s.finding().file() + ":" + s.finding().line())
        + "\n";
  }

  static String categoryOf(CandidateFinding finding) {
    return finding.category().name().toLowerCase(Locale.ROOT);
  }

  static String escape(String text) {
    // 05§1.3 规则 3：正文 <!-- → &lt;!--（正文永远不可能再产生未转义 HTML 注释）
    return text.replace("<!--", "&lt;!--");
  }

  /** 代码块包裹（04§3.3 规则 3）：围栏长度按内容中最长反引号串动态加一—— 否则 finding 文本里自带 ``` 即可提前闭合围栏，在 bot 评论里注入链接/提及/图片。 */
  static String codeBlock(String text) {
    String content = text == null ? "" : text;
    int longest = 0;
    int run = 0;
    for (int i = 0; i < content.length(); i++) {
      if (content.charAt(i) == '`') {
        run++;
        longest = Math.max(longest, run);
      } else {
        run = 0;
      }
    }
    String fence = "`".repeat(Math.max(3, longest + 1));
    return fence + "\n" + content + "\n" + fence;
  }

  /** 行内代码（路径/行号）：反引号与换行会被中和，避免闭合 code span 或拆行。 */
  static String codeSpan(String text) {
    return "`"
        + (text == null ? "" : text.replace("`", "'").replace("\r", " ").replace("\n", " "))
        + "`";
  }

  /** 账本标记的 file 值是否可无歧义解析（字段分隔 `|`、注释闭合 `-->`、换行都会破坏标记格式）。 */
  static boolean markerSafePath(String path) {
    return path != null
        && !path.contains("|")
        && !path.contains("<!--")
        && !path.contains("-->")
        && !path.contains("\n")
        && !path.contains("\r");
  }

  static String shortSha(String sha) {
    return Texts.shortSha(sha, 7, "");
  }
}
