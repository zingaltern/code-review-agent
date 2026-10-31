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
    appendChangeHeader(body, request);
    if (!suspectWarning.isEmpty()) {
      // 05§1.3 规则 2：可疑标记 ≥3 → Summary 警告疑似注入尝试
      body.append("> ⚠️ ").append(suspectWarning).append("\n\n");
    }
    List<FindingAggregator.Scored> important =
        ordered.stream().filter(s -> s.finding().severityProposed() == Severity.IMPORTANT).toList();
    List<FindingAggregator.Scored> nits =
        ordered.stream().filter(s -> s.finding().severityProposed() == Severity.NIT).toList();
    List<FindingAggregator.Scored> preExisting =
        ordered.stream()
            .filter(s -> s.verdict() == PreExistingBlameChecker.Verdict.CONFIRMED_PRE_EXISTING)
            .toList();
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
      if (ordered.isEmpty() && skippedDuplicate == 0) {
        // 02§4.2：验证不可得 ≠ 无问题——计数声明，不把 UNVERIFIED 静默当空结果
        if (noReviewableChanges) {
          body.append("本次无可评审变更（仅测试文件或全部变更被策略跳过），按策略不评审。\n\n");
        } else if (unverifiedUnpublished > 0) {
          body.append("本次无已确认发现；")
              .append(unverifiedUnpublished)
              .append(" 条候选验证不可得（UNVERIFIED，默认不发布）。\n\n");
        } else {
          body.append("本次未发现问题（评审返回空结果）。\n\n");
        }
      } else {
        body.append("本次无新发现（候选均被指纹去重抑制 ").append(skippedDuplicate).append(" 条）。\n\n");
      }
    } else {
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
    if (convergenceAdvice != null && !convergenceAdvice.isBlank()) {
      // T2.7 轮次超限（03§5）：停止一切新 finding，建议打回重新设计——人在这一关踩刹车
      body.append("### 收敛控制\n> ⚠️ ").append(convergenceAdvice).append("\n\n");
    }
    // 07§5 M4a：签字建议（建议型，非审批；绑定 head commit）——覆盖声明之前独立成段
    for (String line : SignoffSections.lines(signoff)) {
      body.append(line).append("\n");
    }
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
    // run 完成标记（05§1.3）：DEGRADED 也是完整发布（02§4.1）；suppressed = 指纹抑制计数
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
    return body.toString();
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
      // 02§4.2：验证不可得 ≠ 无问题——计数声明，不把 UNVERIFIED 静默当空结果
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
      return;
    }
    body.append("### 发现详情\n\n");
    for (FindingAggregator.Scored s : ordered) {
      CandidateFinding f = s.finding();
      boolean unverified = unverifiedIds.contains(f.id());
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
