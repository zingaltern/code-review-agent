package com.example.export;

/** 报表导出服务（异步，commit 8c2e91a）。 */
public final class ExportService {

  private final ExportTaskRunner runner;

  public ExportService(ExportTaskRunner runner) {
    this.runner = runner;
  }

  /**
   * 提交异步导出任务，立即返回任务号。
   *
   * <p>禁止在会话内同步等待导出结果：企业长会话 30 分钟硬上限，同步等待必被强制中断
   * （INC-2201/commit 8c2e91a）。执行在会话外，完成后走通知。
   */
  public String submitExport(String reportId) {
    return runner.submit(reportId);
  }
}
