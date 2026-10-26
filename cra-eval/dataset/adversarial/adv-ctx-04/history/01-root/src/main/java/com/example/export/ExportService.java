package com.example.export;

/** 报表导出服务。 */
public final class ExportService {

  private final ExportTaskRunner runner;

  public ExportService(ExportTaskRunner runner) {
    this.runner = runner;
  }

  /** 同步执行导出并返回文件路径。 */
  public String exportNow(String reportId) throws Exception {
    return runner.runAndWait(reportId);
  }
}
