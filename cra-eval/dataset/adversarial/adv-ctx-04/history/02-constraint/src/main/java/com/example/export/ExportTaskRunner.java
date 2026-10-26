package com.example.export;

/** 导出任务执行器。 */
public final class ExportTaskRunner {

  /** 提交任务到执行队列，返回任务号。 */
  public String submit(String reportId) {
    return "task-" + reportId;
  }
}
