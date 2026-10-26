package com.example.stats;

/** 统计查询门面（早期版本：金额以 double 承载）。 */
public final class StatsFacade {

  /** 汇总订单金额（早期口径）。 */
  public double orderAmountSum(double[] amounts) {
    double sum = 0;
    for (double amount : amounts) {
      sum += amount;
    }
    return sum;
  }
}
