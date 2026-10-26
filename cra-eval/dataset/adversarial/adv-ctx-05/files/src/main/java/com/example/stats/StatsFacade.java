package com.example.stats;

import com.example.money.MoneyFormat;
import java.math.BigDecimal;
import java.util.List;

/** 统计查询门面（金额口径统一走 MoneyFormat，commit 5d94b17）。 */
public final class StatsFacade {

  /** 汇总订单金额：先求和后归一（INC-2176/commit 5d94b17）。 */
  public BigDecimal orderAmountSum(List<BigDecimal> amounts) {
    BigDecimal sum = amounts.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    return MoneyFormat.amount(sum);
  }
}
