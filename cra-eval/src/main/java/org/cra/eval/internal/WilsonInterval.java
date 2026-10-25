package org.cra.eval.internal;

import org.cra.common.exception.CraConfigException;

/**
 * Wilson 95% 置信区间（06§1.2 v1.2.1 修正后的正确公式——v1.2 曾误用 Wald）。
 *
 * <p>公式（z 为双侧 95% 分位 1.959963984540054）：
 *
 * <pre>center = (p̂ + z²/2n) / (1 + z²/n)
 * half   = z·√( p̂(1−p̂)/n + z²/4n² ) / (1 + z²/n)
 * [center − half, center + half]</pre>
 *
 * <p>锚点自校验（与文档独立推演一致）：n=36、x=31 时下界 ≈0.713；n=12×k=36、p̂≈0.85 时下界 ≈0.70（06§1.2 功效核算）。
 */
public final class WilsonInterval {

  /** 双侧 95% 正态分位数。 */
  static final double Z_95 = 1.959963984540054;

  private WilsonInterval() {}

  /**
   * 计算 Wilson 95% 区间。
   *
   * @param successes 成功观测数
   * @param total 观测总数
   * @return {lower, upper}
   * @throws CraConfigException total 非正或 successes 越界
   */
  public static double[] wilson95(long successes, long total) {
    if (total <= 0) {
      throw new CraConfigException("Wilson 区间要求 total > 0: " + total);
    }
    if (successes < 0 || successes > total) {
      throw new CraConfigException("successes 越界: " + successes + "/" + total);
    }
    double p = (double) successes / total;
    double z2 = Z_95 * Z_95;
    double denom = 1 + z2 / total;
    double center = (p + z2 / (2 * total)) / denom;
    double half = Z_95 * Math.sqrt(p * (1 - p) / total + z2 / (4.0 * total * total)) / denom;
    double low = Math.max(0.0, center - half);
    double high = Math.min(1.0, center + half);
    return new double[] {low, high};
  }
}
