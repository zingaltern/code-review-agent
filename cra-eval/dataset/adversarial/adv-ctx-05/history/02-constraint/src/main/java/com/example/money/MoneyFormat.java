package com.example.money;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** 对外金额格式化工具。 */
public final class MoneyFormat {

  /** 对外金额标度：固定两位小数。 */
  public static final int AMOUNT_SCALE = 2;

  private MoneyFormat() {}

  /** 归一对外金额：null 视为 0，固定两位小数。 */
  public static BigDecimal amount(BigDecimal value) {
    BigDecimal v = value == null ? BigDecimal.ZERO : value;
    return v.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
  }
}
