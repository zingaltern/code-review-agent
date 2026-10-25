package org.cra.agents.api;

import java.util.Objects;
import java.util.Set;

/**
 * M2 评审子代理统一输入（03§3 角色化 Reviewer；各角色模板取所需段，模板未引用的可空段不参与渲染）。
 * 不可信内容（prTitle/commitMessages/diff/window/slices/extraSection）在模板渲染时 UNTRUSTED 定界（04§3.3）；
 * reviewPolicySection 来自 base 分支 REVIEW.md，属信任模型内的可信内容（04§3.2）。
 *
 * @param prTitle PR 标题（不可信，04§3.3）
 * @param commitMessages commit message 全文拼接（不可信，04§3.3）
 * @param diffText L0 unified diff（不可信，04§3.3）
 * @param windowText L1 变更上下文（hunk 窗口/条件全文，04§1；不可信，可空）
 * @param slicesText L2 依赖切片（04§1：BH-dataflow/Security 默认注入；不可信，可空）
 * @param extraSection 角色专属段（Security=敏感文件全文与依赖清单；Test=测试目录结构；不可信，可空）
 * @param reviewPolicySection base 分支 REVIEW.md 适用段（可信，04§3.2）
 * @param scopedPaths 受限工具可触达的文件集合（03§2.1：白名单在 brief 生成时确定；空集 = 无工具）
 */
public record ReviewAgentInput(
    String prTitle,
    String commitMessages,
    String diffText,
    String windowText,
    String slicesText,
    String extraSection,
    String reviewPolicySection,
    Set<String> scopedPaths) {

  public ReviewAgentInput {
    Objects.requireNonNull(prTitle, "prTitle");
    Objects.requireNonNull(commitMessages, "commitMessages");
    if (diffText == null || diffText.isBlank()) {
      throw new IllegalArgumentException("diffText must be non-blank");
    }
    windowText = windowText == null ? "" : windowText;
    slicesText = slicesText == null ? "" : slicesText;
    extraSection = extraSection == null ? "" : extraSection;
    reviewPolicySection = reviewPolicySection == null ? "" : reviewPolicySection;
    scopedPaths = Set.copyOf(scopedPaths == null ? Set.of() : scopedPaths);
  }
}
