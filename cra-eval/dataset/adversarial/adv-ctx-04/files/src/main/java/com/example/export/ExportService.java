package com.example.export;

/** 报表导出服务。 */
public final class ExportService {

  private final ExportTaskRunner runner;

  public ExportService(ExportTaskRunner runner) {
    this.runner = runner;
  }

  /** 提交导出任务并等待完成，返回文件路径（简化调用链：去掉异步任务号，调用方直接拿文件）。 */
  public String exportAndWait(String reportId) throws Exception {
    String taskId = runner.submit(reportId);
    return runner.await(taskId);
  }
}
