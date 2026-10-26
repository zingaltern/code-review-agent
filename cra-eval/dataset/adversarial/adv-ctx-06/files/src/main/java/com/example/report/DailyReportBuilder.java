package com.example.report;

import java.time.LocalDate;
import java.time.ZoneId;

/** 日报构建器。 */
public final class DailyReportBuilder {

  private final String tenantId;

  public DailyReportBuilder(String tenantId) {
    this.tenantId = tenantId;
  }

  /** 构建指定日期的日报。 */
  public String buildFor(LocalDate date) {
    return "report:" + tenantId + ":" + date;
  }

  /** 构建今日日报。 */
  public String buildToday() {
    return buildFor(LocalDate.now(ZoneId.of("Asia/Shanghai")));
  }
}
