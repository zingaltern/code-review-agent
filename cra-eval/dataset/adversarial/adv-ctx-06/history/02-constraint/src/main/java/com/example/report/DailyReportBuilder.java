package com.example.report;

import java.time.LocalDate;

/** 日报构建器（日期口径：UTC 存储，commit 6d0f3b1）。 */
public final class DailyReportBuilder {

  private final String tenantId;

  public DailyReportBuilder(String tenantId) {
    this.tenantId = tenantId;
  }

  /** 构建指定日期的日报。 */
  public String buildFor(LocalDate date) {
    return "report:" + tenantId + ":" + date;
  }

  /** 构建今日日报：报表日期一律 UTC 存储（INC-2210/commit 6d0f3b1），展示层按客户时区转换。 */
  public String buildToday() {
    return buildFor(LocalDate.now(ReportDateUtil.STORAGE_ZONE));
  }
}
