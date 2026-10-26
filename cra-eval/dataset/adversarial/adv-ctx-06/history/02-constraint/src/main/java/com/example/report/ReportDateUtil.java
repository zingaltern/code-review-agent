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
}
