package org.cra.core.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.cra.common.signoff.RiskTier;
import org.cra.common.signoff.SignoffAdvice;
import org.cra.common.signoff.SignoffRuleHit;
import org.cra.common.text.Texts;

/**
 * Summary"签字建议"段渲染（07§5 M4a；01§3.4 三条签字规矩的文案落点）。
 *
 * <p>段内固定声明：agent 的赞成票不计为审批（agent 永远不是 approver）、approve 只对绑定的 commit 有效、 签字语义 =
 * 核实意图/假设/设计而非逐行读过、指挥 agent 写代码的人必须在签字名单上（PR 模板提示项）。
 */
final class SignoffSections {

  /** 段标题（stale 判定复用此常量，避免两处文案漂移）。 */
  static final String HEADING = "### 签字建议（建议，非审批）";

  private static final int MAX_EVIDENCE = 5;

  private SignoffSections() {}

  /**
   * 渲染签字建议段（disabled 时返回空列表——不占 Summary 版面）。
   *
   * @param advice 签字建议
   * @return 完整 markdown 行（含段标题；空 = 不渲染）
   */
  static List<String> lines(SignoffAdvice advice) {
    if (advice == null || !advice.advisoryOnly()) {
      return List.of();
    }
    List<String> out = new ArrayList<>();
    out.add(HEADING);
    RiskTier tier = advice.tier();
    out.add("- 风险分级：%s → 建议 %d 人签字".formatted(tier.displayName(), advice.recommendedSigners()));
    out.add(
        "- 绑定 commit：`%s`（approve 只对该 commit 有效；新 commit 出现后本建议标记 stale，须重新核实）"
            .formatted(shortSha(advice.commitSha())));
    out.add("- 触发规则：");
    for (SignoffRuleHit hit : advice.rules()) {
      out.add("  - " + ruleLine(hit));
    }
    out.add(
        "- 条件：作者 %s；测试状态 %s"
            .formatted(
                advice.context().authorKnown() ? escape(advice.context().author()) : "未知",
                advice.context().testsStatus().name().toLowerCase(Locale.ROOT)));
    out.add("- 声明：agent 的赞成票不计为审批，agent 永远不是 approver；本条仅为建议，approval 由人/平台策略决定");
    out.add("- 签字语义：核实本次改动的意图、假设、设计，而非逐行读过（01§3.4）");
    out.add("- 签字名单：指挥 agent 写代码的人必须在签字名单上（PR 模板提示项，05§5）");
    if (advice.stale()) {
      out.add("- ⚠️ 已 stale：" + escape(advice.staleReason()));
    }
    out.add("");
    return out;
  }

  /**
   * 该正文是否包含签字建议段（过期收尾据此追加 stale 声明）。
   *
   * @param body Summary 正文
   * @return true 若包含段标题
   */
  static boolean hasSignoffSection(String body) {
    return body != null && body.contains(HEADING);
  }

  /**
   * 旧建议的 stale 声明（追写到被取代的旧 Summary 顶部）。
   *
   * @param oldCommit 旧建议绑定的 commit
   * @param newCommit 新 commit
   * @return stale 声明文本
   */
  static String staleNotice(String oldCommit, String newCommit) {
    return "> ⚠️ 签字建议已 stale：绑定 commit `"
        + shortSha(oldCommit)
        + "` 的建议在 `"
        + shortSha(newCommit)
        + "` 出现后失效，须重新核实（approve 只对特定 commit 有效，05§1.3）。";
  }

  /**
   * 正文是否已带 stale 声明（幂等：不重复追加）。
   *
   * @param body Summary 正文
   * @return true 若已声明
   */
  static boolean hasStaleNotice(String body) {
    return body != null && body.contains("签字建议已 stale");
  }

  private static String ruleLine(SignoffRuleHit hit) {
    StringBuilder line = new StringBuilder();
    line.append('`')
        .append(hit.id())
        .append("`（")
        .append(hit.tier().displayName())
        .append("）：")
        .append(escape(hit.reason()));
    if (!hit.evidence().isEmpty()) {
      List<String> evidence = hit.evidence();
      String shown =
          String.join(
              "、",
              evidence.subList(0, Math.min(MAX_EVIDENCE, evidence.size())).stream()
                  .map(e -> "`" + escape(e) + "`")
                  .toList());
      line.append("——证据：").append(shown);
      if (evidence.size() > MAX_EVIDENCE) {
        line.append(" 等 ").append(evidence.size()).append(" 个");
      }
    }
    return line.toString();
  }

  private static String escape(String text) {
    return ReviewBodyRenderer.escape(text);
  }

  private static String shortSha(String sha) {
    return Texts.shortSha(sha, 7, "");
  }
}
