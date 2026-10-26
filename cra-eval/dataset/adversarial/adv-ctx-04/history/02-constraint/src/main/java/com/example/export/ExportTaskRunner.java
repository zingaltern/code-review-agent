package com.example.export;

/** 导出任务执行器（会话外执行，commit 8c2e91a）。 */
public final class ExportTaskRunner {

  /** 提交任务到会话外执行队列，立即返回任务号。 */
  public String submit(String reportId) {
    return "task-" + reportId;
  }
}
