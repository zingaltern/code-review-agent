package org.cra.agents.api;

import java.util.Objects;

/**
 * GeneralReviewer 的输入（04§4.1 M0 简化版骨架的 User 段；L0 diff + 变更说明，REVIEW.md 段 M1 接入）。
 *
 * @param prTitle PR 标题（不可信，04§3.3）
 * @param commitMessages commit message 全文拼接（不可信，04§3.3）
 * @param diffText unified diff 全文（不可信，04§3.3）
 * @param reviewPolicySection 仓库评审策略段（base 分支 REVIEW.md 适用段；M0 为空占位）
 */
public record GeneralReviewInput(
    String prTitle, String commitMessages, String diffText, String reviewPolicySection) {

  public GeneralReviewInput {
    Objects.requireNonNull(prTitle, "prTitle");
    Objects.requireNonNull(commitMessages, "commitMessages");
    if (diffText == null || diffText.isBlank()) {
      throw new IllegalArgumentException("diffText must be non-blank");
    }
    reviewPolicySection = reviewPolicySection == null ? "" : reviewPolicySection;
  }
}
