package com.example.report;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/** 销售报表汇总（口径：先求和后舍入，commit 5b81e77）。 */
public final class ReportTotals {

  private ReportTotals() {}

  public static BigDecimal total(List<BigDecimal> lines) {
    BigDecimal sum = lines.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    return sum.setScale(2, RoundingMode.HALF_UP);
  }
}
