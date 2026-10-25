package org.cra.agents.api;

import java.util.Objects;

/**
 * GeneralReviewer 的输入（04§4.1 骨架的 User 段；M1 起 L0 diff + L1 变更上下文 + REVIEW.md 段）。
 * 不可信内容（prTitle/commitMessages/diff/windowContext）在模板渲染时 UNTRUSTED 定界（04§3.3）；reviewPolicySection
 * 来自 base 分支 REVIEW.md，属信任模型内的可信内容（04§3.2）。
 *
 * @param prTitle PR 标题（不可信，04§3.3）
 * @param commitMessages commit message 全文拼接（不可信，04§3.3）
 * @param diffText L0 unified diff 全文（不可信，04§3.3）
 * @param windowContext L1 变更上下文（hunk 窗口/条件全文组装文本，04§1；不可信，04§3.3）
 * @param reviewPolicySection 仓库评审策略段（base 分支 REVIEW.md 适用段渲染文本）
 */
public record GeneralReviewInput(
    String prTitle,
    String commitMessages,
    String diffText,
    String windowContext,
    String reviewPolicySection) {

  public GeneralReviewInput {
    Objects.requireNonNull(prTitle, "prTitle");
    Objects.requireNonNull(commitMessages, "commitMessages");
    if (diffText == null || diffText.isBlank()) {
      throw new IllegalArgumentException("diffText must be non-blank");
    }
    windowContext = windowContext == null ? "" : windowContext;
    reviewPolicySection = reviewPolicySection == null ? "" : reviewPolicySection;
  }
}
