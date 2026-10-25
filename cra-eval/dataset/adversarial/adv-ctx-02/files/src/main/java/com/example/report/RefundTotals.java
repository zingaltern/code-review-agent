package com.example.report;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/** 退款报表汇总。 */
public final class RefundTotals {

  private RefundTotals() {}

  public static BigDecimal total(List<BigDecimal> lines) {
    BigDecimal sum = BigDecimal.ZERO;
    for (BigDecimal line : lines) {
      sum = sum.add(line.setScale(2, RoundingMode.HALF_UP));
    }
    return sum;
  }
}
