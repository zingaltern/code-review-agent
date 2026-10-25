package org.cra.common.finding;

/** 锚定校验结果（05§1.1 anchor_status）：非法位置的 finding 降级进 Summary 而非导致 422（05§2.2）。 */
public enum AnchorStatus {
  /** (file, line) 落在 diff hunk 内，可发 inline 评论 */
  INLINE,
  /** 位置非法（或逐条降级仍失败），正文进 Summary 列表 */
  SUMMARY_FALLBACK
}
