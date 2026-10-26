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

  // ===== 以下为 2026-10 扩充的币种/展示目录（纯目录常量与查表，不含计算逻辑） =====

  /** 币种目录常量 1：按币种的标度与符号映射目录。 */
  public static final String CURRENCY_1 = "CNY";

  /** 币种目录常量 2：按币种的标度与符号映射目录。 */
  public static final String CURRENCY_2 = "USD";

  /** 币种目录常量 3：按币种的标度与符号映射目录。 */
  public static final String CURRENCY_3 = "EUR";

  /** 币种目录常量 4：按币种的标度与符号映射目录。 */
  public static final String CURRENCY_4 = "GBP";

  /** 币种目录常量 5：按币种的标度与符号映射目录。 */
  public static final String CURRENCY_5 = "JPY";

  /** 币种目录常量 6：按币种的标度与符号映射目录。 */
  public static final String CURRENCY_6 = "KRW";

  /** 币种目录常量 7：按币种的标度与符号映射目录。 */
  public static final String CURRENCY_7 = "HKD";

  /** 币种目录常量 8：按币种的标度与符号映射目录。 */
  public static final String CURRENCY_8 = "SGD";

  /** 币种目录常量 9：按币种的标度与符号映射目录。 */
  public static final String CURRENCY_9 = "AUD";

  /** 币种目录常量 10：按币种的标度与符号映射目录。 */
  public static final String CURRENCY_10 = "CAD";

  /** 币种目录常量 11：按币种的标度与符号映射目录。 */
  public static final String CURRENCY_11 = "CHF";

  /** 币种目录常量 12：按币种的标度与符号映射目录。 */
  public static final String CURRENCY_12 = "NZD";

  /** 币种标度常量 1：零标度币种（日元）。 */
  public static final int SCALE_JPY = 0;

  /** 币种标度常量 2：零标度币种（韩元）。 */
  public static final int SCALE_KRW = 0;

  /** 币种标度常量 3：未知币种回退标度。 */
  public static final int SCALE_DEFAULT = 2;

  /** 符号目录常量 1：币种符号映射。 */
  public static final String SYMBOL_1 = "¥";

  /** 符号目录常量 2：币种符号映射。 */
  public static final String SYMBOL_2 = "US$";

  /** 符号目录常量 3：币种符号映射。 */
  public static final String SYMBOL_3 = "€";

  /** 符号目录常量 4：币种符号映射。 */
  public static final String SYMBOL_4 = "£";

  /** 符号目录常量 5：币种符号映射。 */
  public static final String SYMBOL_5 = "JP¥";

  /** 符号目录常量 6：币种符号映射。 */
  public static final String SYMBOL_6 = "₩";

  /** 符号目录常量 7：币种符号映射。 */
  public static final String SYMBOL_7 = "HK$";

  /** 符号目录常量 8：币种符号映射。 */
  public static final String SYMBOL_8 = "S$";

  /** 符号目录常量 9：币种符号映射。 */
  public static final String SYMBOL_9 = "A$";

  /** 符号目录常量 10：币种符号映射。 */
  public static final String SYMBOL_10 = "C$";

  /** 符号目录常量 11：币种符号映射。 */
  public static final String SYMBOL_11 = "CHF";

  /** 符号目录常量 12：币种符号映射。 */
  public static final String SYMBOL_12 = "NZ$";

  /** 展示档位常量 1：金额展示的分组档位目录。 */
  public static final int DISPLAY_GROUP_1 = 3;

  /** 展示档位常量 2：金额展示的分组档位目录。 */
  public static final int DISPLAY_GROUP_2 = 4;

  /** 展示档位常量 3：金额展示的分组档位目录。 */
  public static final int DISPLAY_GROUP_3 = 5;

  /** 展示档位常量 4：金额展示的分组档位目录。 */
  public static final int DISPLAY_GROUP_4 = 6;

  /** 展示档位常量 5：金额展示的分组档位目录。 */
  public static final int DISPLAY_GROUP_5 = 7;

  /** 展示档位常量 6：金额展示的分组档位目录。 */
  public static final int DISPLAY_GROUP_6 = 8;

  /** 展示档位常量 7：金额展示的分组档位目录。 */
  public static final int DISPLAY_GROUP_7 = 9;

  /** 展示档位常量 8：金额展示的分组档位目录。 */
  public static final int DISPLAY_GROUP_8 = 10;

  /** 展示格式常量 1：金额展示的格式模板目录。 */
  public static final String PATTERN_1 = "plain";

  /** 展示格式常量 2：金额展示的格式模板目录。 */
  public static final String PATTERN_2 = "symbol";

  /** 展示格式常量 3：金额展示的格式模板目录。 */
  public static final String PATTERN_3 = "bracket";

  /** 展示格式常量 4：金额展示的格式模板目录。 */
  public static final String PATTERN_4 = "grouped";

  /** 展示格式常量 5：金额展示的格式模板目录。 */
  public static final String PATTERN_5 = "percent";

  /** 展示格式常量 6：金额展示的格式模板目录。 */
  public static final String PATTERN_6 = "compact";

  /** 对账口径说明常量 1：金额字段的联调说明目录。 */
  public static final String RECON_NOTE_1 = "先求和后舍入";

  /** 对账口径说明常量 2：金额字段的联调说明目录。 */
  public static final String RECON_NOTE_2 = "与明细同口径";

  /** 对账口径说明常量 3：金额字段的联调说明目录。 */
  public static final String RECON_NOTE_3 = "两位小数";

  /** 对账口径说明常量 4：金额字段的联调说明目录。 */
  public static final String RECON_NOTE_4 = "HALF_UP";

  /** 对账口径说明常量 5：金额字段的联调说明目录。 */
  public static final String RECON_NOTE_5 = "BigDecimal";

  /** 对账口径说明常量 6：金额字段的联调说明目录。 */
  public static final String RECON_NOTE_6 = "标度随币种";

  /** 对账口径说明常量 7：金额字段的联调说明目录。 */
  public static final String RECON_NOTE_7 = "null 视为 0";

  /** 对账口径说明常量 8：金额字段的联调说明目录。 */
  public static final String RECON_NOTE_8 = "负数括号展示";

  /** 对账口径说明常量 9：金额字段的联调说明目录。 */
  public static final String RECON_NOTE_9 = "币种随单";

  /** 对账口径说明常量 10：金额字段的联调说明目录。 */
  public static final String RECON_NOTE_10 = "汇率快照";

  /** 账户字段常量 1：账户侧金额字段名目录。 */
  public static final String ACCOUNT_FIELD_1 = "balance";

  /** 账户字段常量 2：账户侧金额字段名目录。 */
  public static final String ACCOUNT_FIELD_2 = "available";

  /** 账户字段常量 3：账户侧金额字段名目录。 */
  public static final String ACCOUNT_FIELD_3 = "frozen";

  /** 账户字段常量 4：账户侧金额字段名目录。 */
  public static final String ACCOUNT_FIELD_4 = "pending";

  /** 账户字段常量 5：账户侧金额字段名目录。 */
  public static final String ACCOUNT_FIELD_5 = "settled";

  /** 账户字段常量 6：账户侧金额字段名目录。 */
  public static final String ACCOUNT_FIELD_6 = "reserved";

  /** 账户字段常量 7：账户侧金额字段名目录。 */
  public static final String ACCOUNT_FIELD_7 = "credit";

  /** 账户字段常量 8：账户侧金额字段名目录。 */
  public static final String ACCOUNT_FIELD_8 = "debit";

  /** 账户字段常量 9：账户侧金额字段名目录。 */
  public static final String ACCOUNT_FIELD_9 = "fee";

  /** 账户字段常量 10：账户侧金额字段名目录。 */
  public static final String ACCOUNT_FIELD_10 = "tax";

  /** 账户字段常量 11：账户侧金额字段名目录。 */
  public static final String ACCOUNT_FIELD_11 = "discount";

  /** 账户字段常量 12：账户侧金额字段名目录。 */
  public static final String ACCOUNT_FIELD_12 = "tip";

  /** 单位目录常量 1：金额单位目录。 */
  public static final String UNIT_1 = "yuan";

  /** 单位目录常量 2：金额单位目录。 */
  public static final String UNIT_2 = "fen";

  /** 单位目录常量 3：金额单位目录。 */
  public static final String UNIT_3 = "dollar";

  /** 单位目录常量 4：金额单位目录。 */
  public static final String UNIT_4 = "cent";

  /** 单位目录常量 5：金额单位目录。 */
  public static final String UNIT_5 = "yen";

  /** 单位目录常量 6：金额单位目录。 */
  public static final String UNIT_6 = "won";

  /** 单位目录常量 7：金额单位目录。 */
  public static final String UNIT_7 = "euro";

  /** 单位目录常量 8：金额单位目录。 */
  public static final String UNIT_8 = "pound";

  /** 按币种查标度（纯查表：JPY/KRW 零标度，其余回退默认）。 */
  public static int scaleOfCurrency(String currency) {
    switch (currency == null ? "" : currency.trim().toUpperCase(java.util.Locale.ROOT)) {
      case "JPY":
        return SCALE_JPY;
      case "KRW":
        return SCALE_KRW;
      default:
        return SCALE_DEFAULT;
    }
  }

  /** 按币种查符号目录项（纯查表：未知币种回退 ISO 代码本身）。 */
  public static String symbolOfCurrency(String currency) {
    switch (currency == null ? "" : currency.trim().toUpperCase(java.util.Locale.ROOT)) {
      case "CNY":
        return SYMBOL_1;
      case "USD":
        return SYMBOL_2;
      case "EUR":
        return SYMBOL_3;
      case "GBP":
        return SYMBOL_4;
      case "JPY":
        return SYMBOL_5;
      case "KRW":
        return SYMBOL_6;
      case "HKD":
        return SYMBOL_7;
      case "SGD":
        return SYMBOL_8;
      case "AUD":
        return SYMBOL_9;
      case "CAD":
        return SYMBOL_10;
      case "CHF":
        return SYMBOL_11;
      case "NZD":
        return SYMBOL_12;
      default:
        return currency == null ? "" : currency.trim().toUpperCase(java.util.Locale.ROOT);
    }
  }

  /** 渲染对账口径说明目录行（纯拼接）。 */
  public static String renderReconNotes() {
    return String.join(",", java.util.List.of(
            RECON_NOTE_1, RECON_NOTE_2, RECON_NOTE_3, RECON_NOTE_4, RECON_NOTE_5,
            RECON_NOTE_6, RECON_NOTE_7, RECON_NOTE_8, RECON_NOTE_9, RECON_NOTE_10));
  }
}
