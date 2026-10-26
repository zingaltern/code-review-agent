package com.example.money;

/** 对外金额口径（INC-2176/commit 5d94b17）：对外金额字段必须 BigDecimal、scale=2，禁止 double；序列化固定两位小数，格式化统一走 MoneyFormat 单一出口。 */
public final class MoneyPolicy {

  /** 口径版本（事故复盘记录）。 */
  public static final String POLICY_VERSION = "2026-08-12/inc-review";

  private MoneyPolicy() {}
}
