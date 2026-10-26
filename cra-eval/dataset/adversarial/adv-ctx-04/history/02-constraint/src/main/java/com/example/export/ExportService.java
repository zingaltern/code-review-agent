package com.example.export;

/** 报表导出服务。 */
public final class ExportService {

  private final ExportTaskRunner runner;

  public ExportService(ExportTaskRunner runner) {
    this.runner = runner;
  }

  /** 提交异步导出任务，立即返回任务号。 */
  public String submitExport(String reportId) {
    return runner.submit(reportId);
  }
}
