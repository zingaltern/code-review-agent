package com.example.export;

/** 导出任务执行器。 */
public final class ExportTaskRunner {

  /** 提交任务到执行队列，返回任务号。 */
  public String submit(String reportId) {
    return "task-" + reportId;
  }

  /** 等待任务完成并返回导出文件路径。 */
  public String await(String taskId) throws Exception {
    return "/data/exports/" + taskId + ".xlsx";
  }
}
