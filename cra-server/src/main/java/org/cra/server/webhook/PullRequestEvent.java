package org.cra.server.webhook;

/**
 * pull_request webhook 子集（02§5 服务形态；仅评审需要的事件）。
 *
 * @param deliveryId GitHub delivery id（幂等键）
 * @param action 动作（opened|reopened|synchronize|ready_for_review）
 * @param repoOwner 仓库属主
 * @param repoName 仓库名
 * @param prNumber PR 编号
 * @param baseSha 基线 sha
 * @param headSha 目标 sha
 */
public record PullRequestEvent(
    String deliveryId,
    String action,
    String repoOwner,
    String repoName,
    int prNumber,
    String baseSha,
    String headSha) {

  /**
   * 仓库键（owner/name）。
   *
   * @return repo key
   */
  public String repoKey() {
    return repoOwner + "/" + repoName;
  }
}
