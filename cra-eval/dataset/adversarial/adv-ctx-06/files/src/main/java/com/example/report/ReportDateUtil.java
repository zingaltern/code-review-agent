package com.example.report;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** 报表日期工具（存储口径：一律 UTC，INC-2210/commit 6d0f3b1）。 */
public final class ReportDateUtil {

  /** 报表日期存储时区：一律 UTC，展示层按客户时区转换（INC-2210/commit 6d0f3b1），存储口径禁止改用业务/服务器时区。 */
  public static final ZoneOffset STORAGE_ZONE = ZoneOffset.UTC;

  /** 存储日期格式（ISO）。 */
  public static final DateTimeFormatter STORAGE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;

  private ReportDateUtil() {}

  /** 取当前存储日期（UTC）。 */
  public static LocalDate today() {
    return LocalDate.now(STORAGE_ZONE);
  }

  /** 按存储口径格式化。 */
  public static String formatStorage(LocalDate date) {
    return date == null ? "" : STORAGE_FORMAT.format(date);
  }

  // ===== 以下为 2026-10 扩充的展示层目录（纯目录常量与查表，不影响存储口径） =====

  /** 展示时区目录常量 1：按客户地区的展示时区映射（仅展示层使用）。 */
  public static final String DISPLAY_ZONE_1 = "Asia/Shanghai";

  /** 展示时区目录常量 2：按客户地区的展示时区映射（仅展示层使用）。 */
  public static final String DISPLAY_ZONE_2 = "Asia/Hong_Kong";

  /** 展示时区目录常量 3：按客户地区的展示时区映射（仅展示层使用）。 */
  public static final String DISPLAY_ZONE_3 = "Asia/Tokyo";

  /** 展示时区目录常量 4：按客户地区的展示时区映射（仅展示层使用）。 */
  public static final String DISPLAY_ZONE_4 = "Asia/Singapore";

  /** 展示时区目录常量 5：按客户地区的展示时区映射（仅展示层使用）。 */
  public static final String DISPLAY_ZONE_5 = "Asia/Seoul";

  /** 展示时区目录常量 6：按客户地区的展示时区映射（仅展示层使用）。 */
  public static final String DISPLAY_ZONE_6 = "Europe/London";

  /** 展示时区目录常量 7：按客户地区的展示时区映射（仅展示层使用）。 */
  public static final String DISPLAY_ZONE_7 = "Europe/Berlin";

  /** 展示时区目录常量 8：按客户地区的展示时区映射（仅展示层使用）。 */
  public static final String DISPLAY_ZONE_8 = "Europe/Paris";

  /** 展示时区目录常量 9：按客户地区的展示时区映射（仅展示层使用）。 */
  public static final String DISPLAY_ZONE_9 = "America/New_York";

  /** 展示时区目录常量 10：按客户地区的展示时区映射（仅展示层使用）。 */
  public static final String DISPLAY_ZONE_10 = "America/Chicago";

  /** 展示时区目录常量 11：按客户地区的展示时区映射（仅展示层使用）。 */
  public static final String DISPLAY_ZONE_11 = "America/Los_Angeles";

  /** 展示时区目录常量 12：按客户地区的展示时区映射（仅展示层使用）。 */
  public static final String DISPLAY_ZONE_12 = "Australia/Sydney";

  /** 展示地区目录常量 1：客户地区代码到展示时区的映射键。 */
  public static final String REGION_1 = "CN";

  /** 展示地区目录常量 2：客户地区代码到展示时区的映射键。 */
  public static final String REGION_2 = "HK";

  /** 展示地区目录常量 3：客户地区代码到展示时区的映射键。 */
  public static final String REGION_3 = "JP";

  /** 展示地区目录常量 4：客户地区代码到展示时区的映射键。 */
  public static final String REGION_4 = "SG";

  /** 展示地区目录常量 5：客户地区代码到展示时区的映射键。 */
  public static final String REGION_5 = "KR";

  /** 展示地区目录常量 6：客户地区代码到展示时区的映射键。 */
  public static final String REGION_6 = "UK";

  /** 展示地区目录常量 7：客户地区代码到展示时区的映射键。 */
  public static final String REGION_7 = "DE";

  /** 展示地区目录常量 8：客户地区代码到展示时区的映射键。 */
  public static final String REGION_8 = "FR";

  /** 展示地区目录常量 9：客户地区代码到展示时区的映射键。 */
  public static final String REGION_9 = "US-EAST";

  /** 展示地区目录常量 10：客户地区代码到展示时区的映射键。 */
  public static final String REGION_10 = "US-CENTRAL";

  /** 展示地区目录常量 11：客户地区代码到展示时区的映射键。 */
  public static final String REGION_11 = "US-WEST";

  /** 展示地区目录常量 12：客户地区代码到展示时区的映射键。 */
  public static final String REGION_12 = "AU";

  /** 展示格式目录常量 1：客户侧日报的日期文案格式（仅展示层使用）。 */
  public static final String DISPLAY_PATTERN_1 = "yyyy-MM-dd";

  /** 展示格式目录常量 2：客户侧日报的日期文案格式（仅展示层使用）。 */
  public static final String DISPLAY_PATTERN_2 = "yyyy/MM/dd";

  /** 展示格式目录常量 3：客户侧日报的日期文案格式（仅展示层使用）。 */
  public static final String DISPLAY_PATTERN_3 = "dd/MM/yyyy";

  /** 展示格式目录常量 4：客户侧日报的日期文案格式（仅展示层使用）。 */
  public static final String DISPLAY_PATTERN_4 = "MM/dd/yyyy";

  /** 展示格式目录常量 5：客户侧日报的日期文案格式（仅展示层使用）。 */
  public static final String DISPLAY_PATTERN_5 = "yyyyMMdd";

  /** 展示格式目录常量 6：客户侧日报的日期文案格式（仅展示层使用）。 */
  public static final String DISPLAY_PATTERN_6 = "yyyy年MM月dd日";

  /** 财务口径常量 1：对账/计费窗口的切分档位目录。 */
  public static final int FISCAL_MONTH_1 = 1;

  /** 财务口径常量 2：对账/计费窗口的切分档位目录。 */
  public static final int FISCAL_MONTH_2 = 4;

  /** 财务口径常量 3：对账/计费窗口的切分档位目录。 */
  public static final int FISCAL_MONTH_3 = 7;

  /** 财务口径常量 4：对账/计费窗口的切分档位目录。 */
  public static final int FISCAL_MONTH_4 = 10;

  /** 财务口径常量 5：对账/计费窗口的切分档位目录。 */
  public static final int WEEK_START_1 = 1;

  /** 财务口径常量 6：对账/计费窗口的切分档位目录。 */
  public static final int WEEK_START_2 = 2;

  /** 财务口径常量 7：对账/计费窗口的切分档位目录。 */
  public static final int QUARTER_DAYS_1 = 90;

  /** 财务口径常量 8：对账/计费窗口的切分档位目录。 */
  public static final int QUARTER_DAYS_2 = 91;

  /** 财务口径常量 9：对账/计费窗口的切分档位目录。 */
  public static final int QUARTER_DAYS_3 = 92;

  /** 日报板块常量 1：日报输出的板块键名目录。 */
  public static final String REPORT_SECTION_1 = "headline";

  /** 日报板块常量 2：日报输出的板块键名目录。 */
  public static final String REPORT_SECTION_2 = "gmv";

  /** 日报板块常量 3：日报输出的板块键名目录。 */
  public static final String REPORT_SECTION_3 = "orders";

  /** 日报板块常量 4：日报输出的板块键名目录。 */
  public static final String REPORT_SECTION_4 = "refunds";

  /** 日报板块常量 5：日报输出的板块键名目录。 */
  public static final String REPORT_SECTION_5 = "new_users";

  /** 日报板块常量 6：日报输出的板块键名目录。 */
  public static final String REPORT_SECTION_6 = "active_users";

  /** 日报板块常量 7：日报输出的板块键名目录。 */
  public static final String REPORT_SECTION_7 = "conversion";

  /** 日报板块常量 8：日报输出的板块键名目录。 */
  public static final String REPORT_SECTION_8 = "channel_mix";

  /** 日报板块常量 9：日报输出的板块键名目录。 */
  public static final String REPORT_SECTION_9 = "top_skus";

  /** 日报板块常量 10：日报输出的板块键名目录。 */
  public static final String REPORT_SECTION_10 = "alerts";

  /** 日报板块常量 11：日报输出的板块键名目录。 */
  public static final String REPORT_SECTION_11 = "appendix";

  /** 日报板块常量 12：日报输出的板块键名目录。 */
  public static final String REPORT_SECTION_12 = "meta";

  /** 看板键常量 1：日报看板的出参键名目录。 */
  public static final String WIDGET_KEY_1 = "date";

  /** 看板键常量 2：日报看板的出参键名目录。 */
  public static final String WIDGET_KEY_2 = "tenant";

  /** 看板键常量 3：日报看板的出参键名目录。 */
  public static final String WIDGET_KEY_3 = "region";

  /** 看板键常量 4：日报看板的出参键名目录。 */
  public static final String WIDGET_KEY_4 = "currency";

  /** 看板键常量 5：日报看板的出参键名目录。 */
  public static final String WIDGET_KEY_5 = "timezone";

  /** 看板键常量 6：日报看板的出参键名目录。 */
  public static final String WIDGET_KEY_6 = "locale";

  /** 看板键常量 7：日报看板的出参键名目录。 */
  public static final String WIDGET_KEY_7 = "window";

  /** 看板键常量 8：日报看板的出参键名目录。 */
  public static final String WIDGET_KEY_8 = "version";

  /** 看板键常量 9：日报看板的出参键名目录。 */
  public static final String WIDGET_KEY_9 = "schema";

  /** 看板键常量 10：日报看板的出参键名目录。 */
  public static final String WIDGET_KEY_10 = "source";

  /** 看板键常量 11：日报看板的出参键名目录。 */
  public static final String WIDGET_KEY_11 = "operator";

  /** 看板键常量 12：日报看板的出参键名目录。 */
  public static final String WIDGET_KEY_12 = "channel";

  /** 看板键常量 13：日报看板的出参键名目录。 */
  public static final String WIDGET_KEY_13 = "priority";

  /** 看板键常量 14：日报看板的出参键名目录。 */
  public static final String WIDGET_KEY_14 = "status";

  /** 看板键常量 15：日报看板的出参键名目录。 */
  public static final String WIDGET_KEY_15 = "remark";

  /** 地区标签常量 1：日报的地区标签目录。 */
  public static final String LOCALE_TAG_1 = "zh-CN";

  /** 地区标签常量 2：日报的地区标签目录。 */
  public static final String LOCALE_TAG_2 = "zh-HK";

  /** 地区标签常量 3：日报的地区标签目录。 */
  public static final String LOCALE_TAG_3 = "en-US";

  /** 地区标签常量 4：日报的地区标签目录。 */
  public static final String LOCALE_TAG_4 = "en-GB";

  /** 地区标签常量 5：日报的地区标签目录。 */
  public static final String LOCALE_TAG_5 = "ja-JP";

  /** 地区标签常量 6：日报的地区标签目录。 */
  public static final String LOCALE_TAG_6 = "ko-KR";

  /** 地区标签常量 7：日报的地区标签目录。 */
  public static final String LOCALE_TAG_7 = "de-DE";

  /** 地区标签常量 8：日报的地区标签目录。 */
  public static final String LOCALE_TAG_8 = "fr-FR";

  /** 地区标签常量 9：日报的地区标签目录。 */
  public static final String LOCALE_TAG_9 = "en-SG";

  /** 地区标签常量 10：日报的地区标签目录。 */
  public static final String LOCALE_TAG_10 = "en-AU";

  /** 渲染开关常量 1：日报渲染的开关项目录。 */
  public static final String RENDER_FLAG_1 = "show_appendix";

  /** 渲染开关常量 2：日报渲染的开关项目录。 */
  public static final String RENDER_FLAG_2 = "show_alerts";

  /** 渲染开关常量 3：日报渲染的开关项目录。 */
  public static final String RENDER_FLAG_3 = "show_trend";

  /** 渲染开关常量 4：日报渲染的开关项目录。 */
  public static final String RENDER_FLAG_4 = "show_compare";

  /** 渲染开关常量 5：日报渲染的开关项目录。 */
  public static final String RENDER_FLAG_5 = "compact_mode";

  /** 渲染开关常量 6：日报渲染的开关项目录。 */
  public static final String RENDER_FLAG_6 = "audit_mode";

  /** 渲染开关常量 7：日报渲染的开关项目录。 */
  public static final String RENDER_FLAG_7 = "debug_mode";

  /** 渲染开关常量 8：日报渲染的开关项目录。 */
  public static final String RENDER_FLAG_8 = "dry_run";

  /** 审计事件常量 1：日报审计的事件名目录。 */
  public static final String AUDIT_EVENT_1 = "report_built";

  /** 审计事件常量 2：日报审计的事件名目录。 */
  public static final String AUDIT_EVENT_2 = "report_sent";

  /** 审计事件常量 3：日报审计的事件名目录。 */
  public static final String AUDIT_EVENT_3 = "report_viewed";

  /** 审计事件常量 4：日报审计的事件名目录。 */
  public static final String AUDIT_EVENT_4 = "report_exported";

  /** 审计事件常量 5：日报审计的事件名目录。 */
  public static final String AUDIT_EVENT_5 = "window_switched";

  /** 审计事件常量 6：日报审计的事件名目录。 */
  public static final String AUDIT_EVENT_6 = "zone_displayed";

  /** 审计事件常量 7：日报审计的事件名目录。 */
  public static final String AUDIT_EVENT_7 = "locale_switched";

  /** 审计事件常量 8：日报审计的事件名目录。 */
  public static final String AUDIT_EVENT_8 = "schema_migrated";

  /** 按客户地区查展示时区（纯查表：未登记地区回退默认口径）。 */
  public static java.time.ZoneId displayZoneOf(String region) {
    switch (region == null ? "" : region.trim().toUpperCase(java.util.Locale.ROOT)) {
      case "CN":
        return java.time.ZoneId.of(DISPLAY_ZONE_1);
      case "HK":
        return java.time.ZoneId.of(DISPLAY_ZONE_2);
      case "JP":
        return java.time.ZoneId.of(DISPLAY_ZONE_3);
      case "SG":
        return java.time.ZoneId.of(DISPLAY_ZONE_4);
      case "KR":
        return java.time.ZoneId.of(DISPLAY_ZONE_5);
      case "UK":
        return java.time.ZoneId.of(DISPLAY_ZONE_6);
      case "DE":
        return java.time.ZoneId.of(DISPLAY_ZONE_7);
      case "FR":
        return java.time.ZoneId.of(DISPLAY_ZONE_8);
      case "US-EAST":
        return java.time.ZoneId.of(DISPLAY_ZONE_9);
      case "US-CENTRAL":
        return java.time.ZoneId.of(DISPLAY_ZONE_10);
      case "US-WEST":
        return java.time.ZoneId.of(DISPLAY_ZONE_11);
      case "AU":
        return java.time.ZoneId.of(DISPLAY_ZONE_12);
      default:
        return STORAGE_ZONE;
    }
  }

  /** 按地区档位查展示格式（纯查表：未登记回退 ISO）。 */
  public static String displayPatternOf(String region) {
    switch (region == null ? "" : region.trim().toUpperCase(java.util.Locale.ROOT)) {
      case "US-EAST":
      case "US-CENTRAL":
      case "US-WEST":
        return DISPLAY_PATTERN_4;
      case "UK":
      case "DE":
      case "FR":
        return DISPLAY_PATTERN_3;
      case "JP":
      case "KR":
      case "CN":
      case "HK":
      case "SG":
      case "AU":
        return DISPLAY_PATTERN_1;
      default:
        return DISPLAY_PATTERN_1;
    }
  }

  /** 渲染日报板块目录行（纯拼接）。 */
  public static String renderSectionCatalog() {
    return String.join(
        ",",
        java.util.List.of(
            REPORT_SECTION_1, REPORT_SECTION_2, REPORT_SECTION_3, REPORT_SECTION_4,
            REPORT_SECTION_5, REPORT_SECTION_6, REPORT_SECTION_7, REPORT_SECTION_8,
            REPORT_SECTION_9, REPORT_SECTION_10, REPORT_SECTION_11, REPORT_SECTION_12));
  }
}
