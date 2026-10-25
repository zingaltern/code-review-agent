package org.cra.core.internal.pipeline;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/**
 * 确定性抽查保险丝（07 T2.5；03§4.3 v1.2 补定义）：important/security 级 REFUTED 是否进 Judge 抽查， 由 {@code
 * sha256(run_id | finding_id) mod 100 < spotcheck_pct} 决定——同 run 重放结果一致、trace 与
 * 账本可审计；禁止随机数与时间因素（02§4.4 同源纪律）。
 */
public final class SpotCheckFuse {

  private SpotCheckFuse() {}

  /**
   * 判定一条 REFUTED finding 是否命中抽查。
   *
   * @param runId run 标识
   * @param findingId finding 标识
   * @param percentPercent 抽查百分比（0–100；03§4.3 默认 20，对应 important-refuted-spotcheck=0.20）
   * @return true 命中（进 Judge 复核）
   */
  public static boolean hit(String runId, String findingId, int percentPercent) {
    Objects.requireNonNull(runId, "runId");
    Objects.requireNonNull(findingId, "findingId");
    if (percentPercent <= 0) {
      return false;
    }
    if (percentPercent >= 100) {
      return true;
    }
    return new BigInteger(1, sha256(runId + "|" + findingId))
            .mod(BigInteger.valueOf(100))
            .intValue()
        < percentPercent;
  }

  private static byte[] sha256(String input) {
    try {
      return MessageDigest.getInstance("SHA-256")
          .digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
