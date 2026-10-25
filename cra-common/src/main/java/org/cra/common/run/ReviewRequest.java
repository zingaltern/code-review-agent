package org.cra.common.run;

import java.util.Objects;

/**
 * 一次评审请求（02§1 L4 编排层输入；core 全部逻辑与形态无关——CLI/server 只是两种壳，02§5）。
 *
 * @param repoOwner 仓库属主
 * @param repoName 仓库名
 * @param prNumber PR 编号（0 = 本地 pre-PR 模式无 PR 编号，账本按 (repo, 0, commit) 键控，02§5 本地形态； CI 形态由 CLI
 *     层强制正数，05§3）
 * @param baseSha 基线 sha
 * @param headSha 目标 sha
 * @param depth 深度档位
 * @param dryRun dry-run 模式：全程零写操作（单测断言零写调用，05§6）
 * @param securityBlock --security-block 模式：存在 confirmed 安全 finding 时退出码 3（05§3）
 */
public record ReviewRequest(
    String repoOwner,
    String repoName,
    int prNumber,
    String baseSha,
    String headSha,
    Depth depth,
    boolean dryRun,
    boolean securityBlock) {

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
  }
}
