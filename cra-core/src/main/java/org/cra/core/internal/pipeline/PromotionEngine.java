package org.cra.core.internal.pipeline;

import org.cra.common.finding.CandidateFinding;
import org.cra.core.internal.VerdictJsonCodec;

/**
 * 判定与晋升规则（07 T2.5；03§4.3 M2 形态：单验证者 + 仲裁）：
 *
 * <pre>
 * CONFIRMED                        → 发布
 * REFUTED                          → 丢弃（trace 留档，进入拒真统计）
 * security/important 级 REFUTED    → 确定性抽查保险丝命中则进 Judge 复核（召回侧保险丝，防单个验证者错杀）
 * INSUFFICIENT（补查后仍不能判）    → Judge 仲裁
 * 验证不可得（超时/预算击穿，缩上下文重试 1 次仍失败）→ UNVERIFIED，默认不发布（02§4.2）
 * </pre>
 *
 * <p>纯函数：spotCheckHit 由 {@link SpotCheckFuse} 在调用方计算（确定性可重放）。
 */
public final class PromotionEngine {

  /** 晋升决议。 */
  public enum Decision {
    /** 发布 */
    PUBLISH,
    /** 进 Judge（仲裁或抽查复核） */
    JUDGE,
    /** 丢弃（拒真统计口径） */
    REJECT,
    /** 验证不可得：默认不发布（publish-unverified=labeled 时由发布段带标签发布） */
    UNVERIFIED
  }

  /** 晋升决定（reason 进 trace）。 */
  public record Promotion(Decision decision, String reason) {}

  private PromotionEngine() {}

  /**
   * M2 晋升规则（03§4.3）。
   *
   * @param verdict 验证者三值判定（UNAVAILABLE = 验证不可得，编排器语义值）
   * @param finding 被判定的候选 finding
   * @param spotCheckHit 确定性抽查保险丝是否命中（仅 important/security 级 REFUTED 时被消费）
   * @return 晋升决定
   */
  public static Promotion promote(
      VerdictJsonCodec.VerifierVerdict verdict, CandidateFinding finding, boolean spotCheckHit) {
    return switch (verdict) {
      case CONFIRMED -> new Promotion(Decision.PUBLISH, "verifier-confirmed");
      case REFUTED ->
          ConvergenceGuard.securityOrImportant(finding)
              ? (spotCheckHit
                  ? new Promotion(Decision.JUDGE, "refuted-spotcheck")
                  : new Promotion(Decision.REJECT, "refuted"))
              : new Promotion(Decision.REJECT, "refuted-nit");
      case INSUFFICIENT_EVIDENCE -> new Promotion(Decision.JUDGE, "insufficient-arbitration");
      case UNAVAILABLE -> new Promotion(Decision.UNVERIFIED, "verification-unavailable");
    };
  }
}
