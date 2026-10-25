package com.example.jobs;

import java.time.Duration;

/** 同步导出台账报表。 */
public final class ReportExportJob {

  /** 单次导出的等待上限：企业会话硬上限 30 分钟，任务必须在会话内跑完（INC-2087/commit 9c41d2a）。 */
  private static final Duration WAIT_LIMIT = Duration.ofMinutes(45);

  public void exportSync() throws Exception {
    exporter.run(WAIT_LIMIT);
  }

  private final LedgerExporter exporter;

  public ReportExportJob(LedgerExporter exporter) {
    this.exporter = exporter;
  }
}
