package org.cra.server.delivery;

import java.time.Instant;

/**
 * webhook delivery 记录（幂等键 = 平台事件 id；02§5 服务形态）。
 *
 * @param deliveryId 平台 delivery 键（GitHub delivery id / GitLab event UUID；主键）
 * @param repo 仓库（owner/name）
 * @param prNumber PR 编号
 * @param headSha 目标 sha
 * @param eventAction pull_request action
 * @param status RECEIVED|PROCESSING|COMPLETED|FAILED
 * @param runId 评审 run 标识（完成/失败后回填）
 * @param detail 失败原因或结果摘要
 * @param receivedAt 接收时间
 * @param updatedAt 最近状态变更时间
 */
public record DeliveryRecord(
    String deliveryId,
    String repo,
    int prNumber,
    String headSha,
    String eventAction,
    String status,
    String runId,
    String detail,
    Instant receivedAt,
    Instant updatedAt) {

  /** 已接收（尚未被 worker 取走）。 */
  public static final String RECEIVED = "RECEIVED";

  /** 执行中。 */
  public static final String PROCESSING = "PROCESSING";

  /** 完成（含 SKIPPED：commit 闸命中）。 */
  public static final String COMPLETED = "COMPLETED";

  /** 失败（run FAILED 或执行异常）。 */
  public static final String FAILED = "FAILED";
}
