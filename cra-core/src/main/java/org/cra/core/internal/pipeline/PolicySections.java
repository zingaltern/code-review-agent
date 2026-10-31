package org.cra.core.internal.pipeline;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.cra.common.context.ReviewPolicy;

/**
 * REVIEW.md 适用段渲染（04§4.1 brief 的"仓库评审策略"槽）。仅渲染 reviewer 适用段（失败标准/证据要求/约定/nit 预算）；三新段落（Product
 * context 等）是 CrossChecker 输入，M1 不进 reviewer brief。策略来自 base 分支，属信任模型内可信内容（04§3.2），不定界。
 */
public final class PolicySections {

  private PolicySections() {}

  /**
   * 渲染适用段。
   *
   * @param policy 解析后的策略
   * @param modifiedInPr true 时前置"REVIEW.md 在本 PR 中被修改"警告（04§3.2 规则 2）
   * @return 渲染文本（空策略返回占位说明）
   */
  public static String render(ReviewPolicy policy, boolean modifiedInPr) {
    StringBuilder out = new StringBuilder();
    if (modifiedInPr) {
      out.append("**注意**：REVIEW.md 在本 PR 中被修改，评审按 base 版策略执行；策略变更需 owner 审批。（04§3.2）\n\n");
    }
    if (!policy.present()) {
      out.append("（本仓库未配置 REVIEW.md 评审策略）\n");
      return out.toString().stripTrailing();
    }
    if (!policy.warnings().isEmpty()) {
      out.append("（策略解析警告：").append(String.join("；", policy.warnings())).append("）\n\n");
    }
    if (policy.nitBudget() != null) {
      out.append("### Nit budget：").append(policy.nitBudget()).append("（0 = 禁止 nit）\n\n");
    }
    appendItems(out, "What counts as failure", policy.failureCriteria());
    appendItems(out, "Evidence requirements", policy.evidenceRequirements());
    appendItems(out, "Conventions", policy.conventions());
    return out.toString().stripTrailing();
  }

  /** Summary 用的策略警告集合（REVIEW.md 变更 + 解析警告）。 */
  public static Set<String> warnings(ReviewPolicy policy, boolean modifiedInPr) {
    Set<String> out = new LinkedHashSet<>();
    if (modifiedInPr) {
      out.add("REVIEW.md 在本 PR 中被修改，评审按 base 版策略执行；策略变更需 owner 审批（04§3.2）");
    }
    out.addAll(policy.warnings());
    return out;
  }

  private static void appendItems(StringBuilder out, String title, List<String> items) {
    if (items.isEmpty()) {
      return;
    }
    out.append("### ").append(title).append("\n");
    for (String item : items) {
      out.append("- ").append(item).append("\n");
    }
    out.append("\n");
  }
}
