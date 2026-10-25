package org.cra.common.run;

/**
 * run 级覆盖声明（05§1.2 stats.coverage；验收 4：覆盖范围在 Summary 如实声明）。
 *
 * @param filesChanged 变更文件总数（diff 文件数）
 * @param filesReviewed 实际进入评审 brief 的文件数
 * @param note 覆盖备注（"全量"或降档/跳过/截断说明；多原因以"；"连接）
 */
public record Coverage(int filesChanged, int filesReviewed, String note) {

  public Coverage {
    note = note == null ? "" : note;
  }
}
