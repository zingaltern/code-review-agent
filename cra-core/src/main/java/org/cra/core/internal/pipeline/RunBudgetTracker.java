package org.cra.core.internal.pipeline;

import org.cra.core.api.PipelineOptions;

/**
 * run 级预算账目与降档阶梯（02§4.4：&lt;70% 正常；≥70% 砍 light 角色与 1 视角；≥85% 候选按 severity 只取 top-10；100% 终止剩余代理
 * DEGRADED 收尾）。M1 单角色形态的映射：70% 档 → 剩余 chunk 用简化上下文（L1 收窄）；85% 档 → 发布集合截断 top-10 并计
 * suppressed_by_truncation（M1 无验证层，截断发生在发布前）。09§6：本类是编排器 run 级预算账目的持有点，一次 run 一个实例。
 */
public final class RunBudgetTracker {

  /** 阶梯相位。 */
  public enum Phase {
    /** 正常 */
    NORMAL,
    /** ≥70%：剩余 chunk 简化上下文（M1 映射） */
    DEGRADE,
    /** ≥85%：候选截断 top-10 */
    TRUNCATE,
    /** ≥100%：终止剩余代理 */
    STOP
  }

  private final PipelineOptions options;
  private volatile long usedTokens;

  public RunBudgetTracker(PipelineOptions options) {
    this.options = options;
  }

  /**
   * 记录一次代理调用的 token 消耗。
   *
   * @param tokensIn 输入 token
   * @param tokensOut 输出 token
   */
  public void record(long tokensIn, long tokensOut) {
    usedTokens += tokensIn + tokensOut;
  }

  /**
   * 当前消耗占 run 总预算比例。
   *
   * @return 比例 [0, ∞)
   */
  public double ratio() {
    return (double) usedTokens / options.runTotalTokens();
  }

  /**
   * 当前相位（按已消耗比例判定）。
   *
   * @return 阶梯相位
   */
  public Phase phase() {
    double ratio = ratio();
    if (ratio >= 1.0) {
      return Phase.STOP;
    }
    if (ratio >= options.degradeAtSecond()) {
      return Phase.TRUNCATE;
    }
    if (ratio >= options.degradeAtFirst()) {
      return Phase.DEGRADE;
    }
    return Phase.NORMAL;
  }

  /**
   * 已消耗 token。
   *
   * @return 输入+输出合计
   */
  public long usedTokens() {
    return usedTokens;
  }
}
