package org.cra.server.queue;

/**
 * 一次异步评审任务（webhook 事件的队列载荷；02§5 服务形态）。
 *
 * @param deliveryId 平台 delivery 键（GitHub delivery id / GitLab event UUID；幂等键）
 * @param repoOwner 仓库属主
 * @param repoName 仓库名
 * @param prNumber PR 编号
 * @param baseSha 基线 sha
 * @param headSha 目标 sha
 */
public record ReviewJob(
    String deliveryId,
    String repoOwner,
    String repoName,
    int prNumber,
    String baseSha,
    String headSha) {

  public ReviewJob {
    if (deliveryId == null || deliveryId.isBlank()) {
      throw new IllegalArgumentException("deliveryId 不能为空");
    }
    if (repoOwner == null || repoOwner.isBlank() || repoName == null || repoName.isBlank()) {
      throw new IllegalArgumentException("owner/repo 不能为空");
    }
    if (prNumber <= 0) {
      throw new IllegalArgumentException("prNumber 必须为正: " + prNumber);
    }
    if (baseSha == null || baseSha.isBlank() || headSha == null || headSha.isBlank()) {
      throw new IllegalArgumentException("base/head sha 不能为空");
    }
  }

  /**
   * 仓库键（owner/name）。
   *
   * @return repo key
   */
  public String repoKey() {
    return repoOwner + "/" + repoName;
  }
}
