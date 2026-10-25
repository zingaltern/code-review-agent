package org.cra.common.git;

import java.util.Objects;

/**
 * 账本中的 run 完成标记（05§1.3：Summary issue comment 末尾；commit 级幂等闸只认它，02§4.1）。 status ∈ {DONE,
 * DEGRADED}，两者均为完成标记（降级完成也是完整发布）。
 *
 * @param runId run 标识
 * @param commitSha 该 run 评审的 head sha
 * @param status DONE 或 DEGRADED（其他值在解析层已拒收）
 * @param findings 该 run 发布的 finding 数
 * @param suppressed 因指纹/预算抑制而未发布的候选数
 * @param commentId 承载该标记的 Summary issue comment id
 */
public record LedgerRunMarker(
    String runId, String commitSha, String status, int findings, int suppressed, long commentId) {

  /** 完成标记的合法取值（05§1.3）。 */
  public static final String STATUS_DONE = "DONE";

  /** 降级完成同样算完整发布（02§4.1 v1 基线修正）。 */
  public static final String STATUS_DEGRADED = "DEGRADED";

  public LedgerRunMarker {
    Objects.requireNonNull(runId, "runId");
    if (runId.isBlank()) {
      throw new IllegalArgumentException("runId must be non-blank");
    }
    Objects.requireNonNull(commitSha, "commitSha");
    if (commitSha.isBlank()) {
      throw new IllegalArgumentException("commitSha must be non-blank");
    }
    Objects.requireNonNull(status, "status");
    if (!STATUS_DONE.equals(status) && !STATUS_DEGRADED.equals(status)) {
      throw new IllegalArgumentException("status must be DONE|DEGRADED: " + status);
    }
    if (findings < 0 || suppressed < 0) {
      throw new IllegalArgumentException("findings/suppressed must be non-negative");
    }
  }

  /**
   * 是否为完成标记（DONE 或 DEGRADED）。
   *
   * @return true 若该 run 已完整发布
   */
  public boolean isCompletion() {
    return STATUS_DONE.equals(status) || STATUS_DEGRADED.equals(status);
  }
}
