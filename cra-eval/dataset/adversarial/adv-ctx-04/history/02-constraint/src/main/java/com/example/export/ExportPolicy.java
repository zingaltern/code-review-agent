package com.example.export;

/** 导出执行口径（INC-2201/commit 8c2e91a）：报表导出必须异步——提交后立即返回任务号，执行在会话外，完成后经站内信与邮件通知返回；禁止在会话内同步等待导出结果（企业长会话 30 分钟硬上限，同步等待必被强制中断）。 */
public final class ExportPolicy {

  /** 口径版本（事故复盘记录）。 */
  public static final String POLICY_VERSION = "2026-09-20/inc-review";

  private ExportPolicy() {}
}
