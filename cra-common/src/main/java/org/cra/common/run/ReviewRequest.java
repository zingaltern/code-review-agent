package org.cra.common.run;

import java.util.Objects;
import org.cra.common.git.ReviewKey;

/**
 * 一次评审请求（02§1 L4 编排层输入；core 全部逻辑与形态无关——CLI/server 只是两种壳，02§5）。
 *
 * @param repoOwner 仓库属主
 * @param repoName 仓库名
 * @param prNumber PR 编号（0 = 本地 pre-PR / push 直推模式无 PR 编号；PULL_REQUEST / ISSUE 形态为正数，05§3）
 * @param baseSha 基线 sha
 * @param headSha 目标 sha
 * @param depth 深度档位
 * @param dryRun dry-run 模式：全程零写操作（单测断言零写调用，05§6）
 * @param securityBlock --security-block 模式：存在 confirmed 安全 finding 时退出码 3（05§3）
 * @param mode 发布形态（M4c/M4d：PR / commit comment / issue，05§2.2）
 */
public record ReviewRequest(
    String repoOwner,
    String repoName,
    int prNumber,
    String baseSha,
    String headSha,
    Depth depth,
    boolean dryRun,
    boolean securityBlock,
    ReviewMode mode) {

  /**
   * M3 形态兼容构造（发布形态 = PR，05§2.2）。
   *
   * @param repoOwner 仓库属主
   * @param repoName 仓库名
   * @param prNumber PR 编号
   * @param baseSha 基线 sha
   * @param headSha 目标 sha
   * @param depth 深度档位
   * @param dryRun dry-run
   * @param securityBlock --security-block
   */
  public ReviewRequest(
      String repoOwner,
      String repoName,
      int prNumber,
      String baseSha,
      String headSha,
      Depth depth,
      boolean dryRun,
      boolean securityBlock) {
    this(
        repoOwner,
        repoName,
        prNumber,
        baseSha,
        headSha,
        depth,
        dryRun,
        securityBlock,
        ReviewMode.PULL_REQUEST);
  }

  public ReviewRequest {
    Objects.requireNonNull(repoOwner, "repoOwner");
    Objects.requireNonNull(repoName, "repoName");
    if (repoOwner.isBlank() || repoName.isBlank()) {
      throw new IllegalArgumentException("owner/repo must be non-blank");
    }
    if (prNumber < 0) {
      throw new IllegalArgumentException("prNumber must be non-negative: " + prNumber);
    }
    Objects.requireNonNull(baseSha, "baseSha");
    Objects.requireNonNull(headSha, "headSha");
    if (baseSha.isBlank() || headSha.isBlank()) {
      throw new IllegalArgumentException("base/head sha must be non-blank");
    }
    depth = depth == null ? Depth.STANDARD : depth;
    mode = mode == null ? ReviewMode.PULL_REQUEST : mode;
    if (mode == ReviewMode.COMMIT && prNumber != 0) {
      throw new IllegalArgumentException("push 直推形态无 PR 编号: " + prNumber);
    }
    if (mode == ReviewMode.ISSUE && prNumber <= 0) {
      throw new IllegalArgumentException("夜间存量形态需要正数 PR 编号: " + prNumber);
    }
  }

  /**
   * 账本键（05§1.3 keying extension）：PR 形态按 (repo, pr, commit)，无 PR 形态按 (repo, commit)。
   *
   * @return 本次评审的账本键
   */
  public ReviewKey key() {
    return switch (mode) {
      case COMMIT -> ReviewKey.commit(repoOwner, repoName, headSha);
      case ISSUE -> ReviewKey.issue(repoOwner, repoName, prNumber, headSha);
      case PULL_REQUEST -> ReviewKey.pullRequest(repoOwner, repoName, prNumber, headSha);
    };
  }
}
