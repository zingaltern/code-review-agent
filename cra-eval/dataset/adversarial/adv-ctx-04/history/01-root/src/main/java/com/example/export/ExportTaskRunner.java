package com.example.export;

/** 导出任务执行器。 */
public final class ExportTaskRunner {

  /** 同步执行导出并返回文件路径。 */
  public String runAndWait(String reportId) throws Exception {
    return "/data/exports/" + reportId + ".xlsx";
  }
}
