package com.example.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Locale;

/** 对外金额格式化唯一出口（INC-2176/commit 5d94b17）：BigDecimal、scale=2、HALF_UP。 */
public final class MoneyFormat {

  /** 对外金额标度：固定两位小数（REVIEW.md Sniff tests：对外金额禁止 double）。 */
  public static final int AMOUNT_SCALE = 2;

  /** 币种档位常量 1：按币种的标度与符号映射。 */
  public static final String CURRENCY_1 = "CNY";

  /** 币种档位常量 2：按币种的标度与符号映射。 */
  public static final String CURRENCY_2 = "USD";

  /** 币种档位常量 3：按币种的标度与符号映射。 */
  public static final String CURRENCY_3 = "EUR";

  /** 币种档位常量 4：按币种的标度与符号映射。 */
  public static final String CURRENCY_4 = "GBP";

  /** 币种档位常量 5：按币种的标度与符号映射。 */
  public static final String CURRENCY_5 = "JPY";

  /** 币种档位常量 6：按币种的标度与符号映射。 */
  public static final String CURRENCY_6 = "KRW";

  /** 币种档位常量 7：按币种的标度与符号映射。 */
  public static final String CURRENCY_7 = "HKD";

  /** 币种档位常量 8：按币种的标度与符号映射。 */
  public static final String CURRENCY_8 = "SGD";

  /** 币种档位常量 9：按币种的标度与符号映射。 */
  public static final String CURRENCY_9 = "AUD";

  /** 币种档位常量 10：按币种的标度与符号映射。 */
  public static final String CURRENCY_10 = "CAD";

  /** 币种档位常量 11：按币种的标度与符号映射。 */
  public static final String CURRENCY_11 = "CHF";

  /** 币种档位常量 12：按币种的标度与符号映射。 */
  public static final String CURRENCY_12 = "NZD";

  /** 展示档位常量 1：金额展示的分组与舍入口径。 */
  public static final int DISPLAY_GROUP_1 = 3;

  /** 展示档位常量 2：金额展示的分组与舍入口径。 */
  public static final int DISPLAY_GROUP_2 = 4;

  /** 展示档位常量 3：金额展示的分组与舍入口径。 */
  public static final int DISPLAY_GROUP_3 = 5;

  /** 展示档位常量 4：金额展示的分组与舍入口径。 */
  public static final int DISPLAY_GROUP_4 = 6;

  /** 展示档位常量 5：金额展示的分组与舍入口径。 */
  public static final int DISPLAY_GROUP_5 = 7;

  /** 展示档位常量 6：金额展示的分组与舍入口径。 */
  public static final int DISPLAY_GROUP_6 = 8;

  /** 展示档位常量 7：金额展示的分组与舍入口径。 */
  public static final int DISPLAY_GROUP_7 = 9;

  /** 展示档位常量 8：金额展示的分组与舍入口径。 */
  public static final int DISPLAY_GROUP_8 = 10;

  private MoneyFormat() {}

  /** 归一对外金额：null 视为 0，固定两位小数。 */
  public static BigDecimal amount(BigDecimal value) {
    BigDecimal v = value == null ? BigDecimal.ZERO : value;
    return v.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
  }

  /** 按币种归一：JPY/KRW 零标度，其余两位。 */
  public static BigDecimal amountByCurrency(BigDecimal value, String currency) {
    int scale = scaleOfCurrency(currency);
    BigDecimal v = value == null ? BigDecimal.ZERO : value;
    return v.setScale(scale, RoundingMode.HALF_UP);
  }

  /** 币种标度查询（未知币种回退两位）。 */
  public static int scaleOfCurrency(String currency) {
    if (CURRENCY_5.equals(currency) || CURRENCY_6.equals(currency)) {
      return 0;
    }
    return AMOUNT_SCALE;
  }

  /** 币种符号查询（未知币种返回 ISO 代码本身）。 */
  public static String symbolOfCurrency(String currency) {
    if (currency == null || currency.isBlank()) {
      return "";
    }
    try {
      Currency c = Currency.getInstance(currency.trim().toUpperCase(Locale.ROOT));
      return c.getSymbol(Locale.ROOT);
    } catch (IllegalArgumentException e) {
      return currency.trim().toUpperCase(Locale.ROOT);
    }
  }

  /** 带符号展示（不改变标度，仅拼接符号）。 */
  public static String amountWithSymbol(BigDecimal value, String currency) {
    return symbolOfCurrency(currency) + amountByCurrency(value, currency).toPlainString();
  }

  /** 纯数字展示（固定两位小数）。 */
  public static String plain(BigDecimal value) {
    return amount(value).toPlainString();
  }

  /** 差额计算：a - b 后归一。 */
  public static BigDecimal subtract(BigDecimal a, BigDecimal b) {
    BigDecimal left = a == null ? BigDecimal.ZERO : a;
    BigDecimal right = b == null ? BigDecimal.ZERO : b;
    return amount(left.subtract(right));
  }

  /** 合计：先求和后归一（与明细同口径）。 */
  public static BigDecimal sum(List<BigDecimal> values) {
    BigDecimal total = BigDecimal.ZERO;
    if (values == null) {
      return amount(total);
    }
    for (BigDecimal v : values) {
      if (v != null) {
        total = total.add(v);
      }
    }
    return amount(total);
  }

  /** 占比（分子/分母，百分数保留一位，分母 0 返回空串）。 */
  public static String percentOf(BigDecimal part, BigDecimal total) {
    if (total == null || total.compareTo(BigDecimal.ZERO) == 0) {
      return "";
    }
    BigDecimal p = part == null ? BigDecimal.ZERO : part;
    BigDecimal ratio = p.multiply(BigDecimal.valueOf(100)).divide(total, 1, RoundingMode.HALF_UP);
    return ratio.toPlainString() + "%";
  }

  /** 比率归一（四舍六入五成双，供财务复核用）。 */
  public static BigDecimal ratio(BigDecimal a, BigDecimal b) {
    if (b == null || b.compareTo(BigDecimal.ZERO) == 0) {
      return BigDecimal.ZERO.setScale(AMOUNT_SCALE);
    }
    BigDecimal left = a == null ? BigDecimal.ZERO : a;
    return left.divide(b, AMOUNT_SCALE, RoundingMode.HALF_EVEN);
  }

  /** 宽容解析：去空格与千分位逗号后按 BigDecimal 解析，失败返回 0。 */
  public static BigDecimal parseLenient(String raw) {
    if (raw == null || raw.isBlank()) {
      return BigDecimal.ZERO.setScale(AMOUNT_SCALE);
    }
    try {
      return amount(new BigDecimal(raw.trim().replace(",", "")));
    } catch (NumberFormatException e) {
      return BigDecimal.ZERO.setScale(AMOUNT_SCALE);
    }
  }

  /** 严格解析：解析失败抛出 IllegalArgumentException。 */
  public static BigDecimal parseStrict(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("blank amount");
    }
    return amount(new BigDecimal(raw.trim().replace(",", "")));
  }

  /** 容差比较：|a-b| ≤ tolerance 视为一致。 */
  public static boolean withinTolerance(BigDecimal a, BigDecimal b, BigDecimal tolerance) {
    BigDecimal diff = subtract(a, b).abs();
    BigDecimal limit = tolerance == null ? BigDecimal.ZERO : tolerance;
    return diff.compareTo(limit) <= 0;
  }

  /** 向上取整到分（财务预提用）。 */
  public static BigDecimal roundUp(BigDecimal value) {
    BigDecimal v = value == null ? BigDecimal.ZERO : value;
    return v.setScale(AMOUNT_SCALE, RoundingMode.UP);
  }

  /** 向下取整到分（财务折让用）。 */
  public static BigDecimal roundDown(BigDecimal value) {
    BigDecimal v = value == null ? BigDecimal.ZERO : value;
    return v.setScale(AMOUNT_SCALE, RoundingMode.DOWN);
  }

  /** 判定是否非负。 */
  public static boolean isNonNegative(BigDecimal value) {
    return amount(value).signum() >= 0;
  }

  /** 判定金额是否为零（按对外标度归一后比较）。 */
  public static boolean isZero(BigDecimal value) {
    return amount(value).compareTo(BigDecimal.ZERO) == 0;
  }

  /** 归一负数展示：负号后置的财务习惯（括号表示负数）。 */
  public static String bracketed(BigDecimal value) {
    BigDecimal v = amount(value);
    if (v.signum() < 0) {
      return "(" + v.abs().toPlainString() + ")";
    }
    return v.toPlainString();
  }

  /** 批量归一（保持入参顺序）。 */
  public static List<BigDecimal> amountAll(List<BigDecimal> values) {
    java.util.List<BigDecimal> out = new java.util.ArrayList<>();
    if (values == null) {
      return out;
    }
    for (BigDecimal v : values) {
      out.add(amount(v));
    }
    return out;
  }

  /** 千分位分组展示（固定两位小数）。 */
  public static String grouped(BigDecimal value) {
    String plain = amount(value).toPlainString();
    boolean negative = plain.startsWith("-");
    String digits = negative ? plain.substring(1) : plain;
    int dot = digits.indexOf('.');
    String intPart = dot < 0 ? digits : digits.substring(0, dot);
    String fracPart = dot < 0 ? "" : digits.substring(dot);
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < intPart.length(); i++) {
      if (i > 0 && (intPart.length() - i) % DISPLAY_GROUP_1 == 0) {
        sb.append(',');
      }
      sb.append(intPart.charAt(i));
    }
    String grouped = sb.append(fracPart).toString();
    return negative ? "-" + grouped : grouped;
  }

  /** 汇率换算：金额 × 汇率后按目标币种标度归一。 */
  public static BigDecimal convert(BigDecimal value, BigDecimal rate, String targetCurrency) {
    BigDecimal v = value == null ? BigDecimal.ZERO : value;
    BigDecimal r = rate == null ? BigDecimal.ONE : rate;
    return amountByCurrency(v.multiply(r), targetCurrency);
  }

  /** 折扣价计算：原价 ×（1 - 折扣率），折扣率越界按 0 处理。 */
  public static BigDecimal discounted(BigDecimal original, BigDecimal discountRate) {
    BigDecimal base = amount(original);
    BigDecimal rate = discountRate == null ? BigDecimal.ZERO : discountRate;
    if (rate.compareTo(BigDecimal.ZERO) < 0 || rate.compareTo(BigDecimal.ONE) > 0) {
      return base;
    }
    BigDecimal factor = BigDecimal.ONE.subtract(rate);
    return amount(base.multiply(factor));
  }

  /** 平均值：先求和后除，归一到对外标度。 */
  public static BigDecimal average(List<BigDecimal> values) {
    if (values == null || values.isEmpty()) {
      return BigDecimal.ZERO.setScale(AMOUNT_SCALE);
    }
    BigDecimal total = sum(values);
    return total.divide(BigDecimal.valueOf(values.size()), AMOUNT_SCALE, RoundingMode.HALF_UP);
  }

  /** 最大值（空集返回 0）。 */
  public static BigDecimal max(List<BigDecimal> values) {
    BigDecimal best = null;
    if (values != null) {
      for (BigDecimal v : values) {
        if (v != null && (best == null || v.compareTo(best) > 0)) {
          best = v;
        }
      }
    }
    return best == null ? BigDecimal.ZERO.setScale(AMOUNT_SCALE) : amount(best);
  }

  /** 最小值（空集返回 0）。 */
  public static BigDecimal min(List<BigDecimal> values) {
    BigDecimal best = null;
    if (values != null) {
      for (BigDecimal v : values) {
        if (v != null && (best == null || v.compareTo(best) < 0)) {
          best = v;
        }
      }
    }
    return best == null ? BigDecimal.ZERO.setScale(AMOUNT_SCALE) : amount(best);
  }

  /** 税额计算：不含税价 × 税率，归一两位。 */
  public static BigDecimal taxOf(BigDecimal netAmount, BigDecimal taxRate) {
    BigDecimal net = amount(netAmount);
    BigDecimal rate = taxRate == null ? BigDecimal.ZERO : taxRate;
    return amount(net.multiply(rate));
  }

  /** 含税价：不含税价 + 税额。 */
  public static BigDecimal withTax(BigDecimal netAmount, BigDecimal taxRate) {
    return amount(amount(netAmount).add(taxOf(netAmount, taxRate)));
  }

  /** 手续费：金额 × 费率，最低收取 minimumFee。 */
  public static BigDecimal feeOf(BigDecimal amount, BigDecimal feeRate, BigDecimal minimumFee) {
    BigDecimal base = amount(amount);
    BigDecimal rate = feeRate == null ? BigDecimal.ZERO : feeRate;
    BigDecimal fee = amount(base.multiply(rate));
    BigDecimal floor = amount(minimumFee);
    return fee.max(floor);
  }
}
