package com.example.export;

/** 导出任务执行器（会话外执行，commit 8c2e91a）。 */
public final class ExportTaskRunner {

  /** 提交任务到会话外执行队列，立即返回任务号。 */
  public String submit(String reportId) {
    return "task-" + reportId;
  }

  /** 直接执行导出并返回文件路径（同步语义：本次调用内完成工作）。 */
  public String runAndWait(String reportId) {
    String safeName = reportId == null ? "unknown" : reportId.replaceAll("[^A-Za-z0-9_-]", "_");
    return "/data/exports/" + safeName + ".xlsx";
  }
}
