package org.cra.runner.cli;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.cra.common.run.ReviewResult;
import org.cra.common.run.ReviewSuggestion;
import org.cra.common.signoff.SignoffAdvice;
import org.cra.common.signoff.SignoffRuleHit;
import org.cra.common.text.Texts;

/**
 * 本地 pre-PR 报告渲染（07 T2.10）：构建/测试实跑段（退出码 + 失败摘录置顶——对齐"必须展示输出而非 声称完成"）+ 本地评审段（建议清单）。纯函数返回文本， 由命令层打印到
 * stdout，无状态可单测。
 */
final class LocalPrePrReport {

  /** 签字证据折叠上限（超出显示"等 N 个"，避免单条规则刷屏）。 */
  private static final int MAX_EVIDENCE_ITEMS = 5;

  private LocalPrePrReport() {}

  /**
   * 渲染完整报告。
   *
   * @param config 运行配置（已回填本地身份）
   * @param exec 实跑结果
   * @param repoIdentity 仓库身份（owner/name 或 local/&lt;dir&gt;）
   * @param baseRef 基线 ref 名（如 main）
   * @param review 评审结果（跳过时 null）
   * @param reviewSkipReason 评审跳过原因（未跳过为 null）
   * @param reviewError 评审自身故障（正常为 null）
   * @return 报告文本
   */
  static String render(
      CraConfig config,
      BuildVerifyRunner.ExecResult exec,
      String repoIdentity,
      String baseRef,
      ReviewResult review,
      String reviewSkipReason,
      String reviewError) {
    StringBuilder out = new StringBuilder();
    out.append("\n================ cra pre-PR 本地评审报告 ================\n");
    out.append("仓库: ")
        .append(repoIdentity)
        .append("  区间: ")
        .append(baseRef)
        .append('@')
        .append(shortSha(config.baseSha()))
        .append("..")
        .append(shortSha(config.headSha()))
        .append("（仅已提交变更，未提交工作区改动不在评审内）\n\n");

    out.append("【1】构建/测试实跑（07 T2.10：展示真实输出，不以声称完成代替）\n");
    out.append("命令: ").append(config.execCommand()).append('\n');
    out.append("退出码: ")
        .append(exec.exitCode())
        .append(exec.failed() ? "（非零——构建/测试失败）" : "（通过）")
        .append("  耗时: ")
        .append(exec.durationMs() / 1000.0)
        .append("s\n");
    out.append(exec.logComplete() ? "完整输出: " : "输出日志（可能不完整）: ")
        .append(exec.logFile() == null ? "（日志落盘失败，输出见上方回显/内存留存）" : exec.logFile())
        .append('\n');
    if (exec.failed()) {
      out.append("\n失败摘录（真实输出节选，置顶）:\n");
      List<String> excerpts = BuildVerifyRunner.failureExcerpts(exec.output());
      if (excerpts.isEmpty()) {
        out.append("  （无 [ERROR] 行且输出过短，见完整输出日志）\n");
      } else {
        for (String line : excerpts) {
          out.append("  ").append(line).append('\n');
        }
        if (BuildVerifyRunner.excerptTruncated(exec.output())) {
          out.append("  …（摘录已截断，完整输出见日志文件）\n");
        }
      }
    }

    out.append("\n【2】本地评审\n");
    out.append("模式: dry-run=")
        .append(config.dryRun())
        .append("  账本: ")
        .append(config.ledgerDb())
        .append("  PR 编号: ")
        .append(config.prNumber())
        .append(config.prNumber() == 0 ? "（本地形态无 PR）" : "")
        .append('\n');
    if (reviewSkipReason != null) {
      out.append("状态: 已跳过——").append(reviewSkipReason).append('\n');
      out.append("建议清单: 未生成（评审未运行）\n");
    } else if (reviewError != null) {
      out.append("状态: 评审故障——").append(reviewError).append('\n');
      out.append("建议清单: 未生成（评审故障）\n");
    } else if (review != null) {
      out.append("状态: ")
          .append(review.status())
          .append("  ")
          .append(review.summaryLine())
          .append('\n');
      renderSuggestions(out, review.suggestions());
      renderSignoff(out, review.signoffAdvice());
    }
    out.append("trace: ")
        .append(review == null ? "（未运行评审，无 trace）" : review.traceRef())
        .append('\n');
    out.append("=========================================================\n");
    return out.toString();
  }

  /** 建议清单（无建议时如实声明——不编造）。 */
  static void renderSuggestions(StringBuilder out, List<ReviewSuggestion> suggestions) {
    if (suggestions.isEmpty()) {
      out.append("建议清单: 无（本次未发现新问题）\n");
      return;
    }
    out.append("建议清单（").append(suggestions.size()).append(" 条）:\n");
    for (int i = 0; i < suggestions.size(); i++) {
      ReviewSuggestion s = suggestions.get(i);
      out.append(' ')
          .append(i + 1)
          .append(". [")
          .append(s.severity())
          .append("] ")
          .append(s.title())
          .append(" — `")
          .append(s.file())
          .append(':')
          .append(s.line())
          .append("`\n");
      if (!s.description().isBlank()) {
        out.append("    ").append(singleLine(s.description())).append('\n');
      }
      if (!s.suggestion().isBlank()) {
        out.append("    建议: ").append(singleLine(s.suggestion())).append('\n');
      }
    }
  }

  /** 签字建议（M4a；advisory 非审批——agent 永远不是 approver）。 */
  static void renderSignoff(StringBuilder out, SignoffAdvice advice) {
    if (advice == null) {
      return;
    }
    if (!advice.advisoryOnly()) {
      out.append("签字建议: 已关闭（agents.signoff.enabled=false）\n");
      return;
    }
    out.append("签字建议（advisory，非审批）: ")
        .append(advice.tier().displayName())
        .append(" → 建议 ")
        .append(advice.recommendedSigners())
        .append(" 人签字；绑定 commit ")
        .append(shortSha(advice.commitSha()))
        .append("（approve 只对该 commit 有效；新 commit 出现即 stale）\n");
    out.append("  触发规则:\n");
    for (SignoffRuleHit hit : advice.rules()) {
      out.append("  - ")
          .append(hit.id())
          .append("（")
          .append(hit.tier().displayName())
          .append("）：")
          .append(singleLine(hit.reason()));
      if (!hit.evidence().isEmpty()) {
        out.append("——证据：").append(String.join("、", fold(hit.evidence())));
      }
      out.append('\n');
    }
    out.append("  条件: 作者 ")
        .append(advice.context().authorKnown() ? advice.context().author() : "未知")
        .append("；测试状态 ")
        .append(advice.context().testsStatus().name().toLowerCase(Locale.ROOT))
        .append('\n');
    out.append("  声明: agent 的赞成票不计为审批（agent 永远不是 approver）；签字语义 = 核实意图/假设/设计，不是逐行读过\n");
    out.append("  名单: 指挥 agent 写代码的人必须在签字名单上（PR 模板提示项，05§5）\n");
    if (advice.stale()) {
      out.append("  ⚠️ 已 stale: ").append(singleLine(advice.staleReason())).append('\n');
    }
  }

  private static List<String> fold(List<String> values) {
    if (values.size() <= MAX_EVIDENCE_ITEMS) {
      return values;
    }
    List<String> folded = new ArrayList<>(values.subList(0, MAX_EVIDENCE_ITEMS));
    folded.add("等 " + values.size() + " 个");
    return folded;
  }

  private static String singleLine(String text) {
    return text.strip().replaceAll("\\s+", " ");
  }

  private static String shortSha(String sha) {
    return Texts.shortSha(sha, 7, "?");
  }
}
