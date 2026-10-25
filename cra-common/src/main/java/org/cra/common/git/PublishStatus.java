package org.cra.common.git;

/** 单条 finding 的发布结果（05§2.2 锚定校验与逐条降级）。 */
public enum PublishStatus {
  /** inline 评论发布成功 */
  PUBLISHED,
  /** 位置非法或整包 422 后逐条仍失败——正文降级进 Summary 列表 */
  SUMMARY_FALLBACK,
  /** 发布失败（网络/权限等），待重试或人工 */
  FAILED
}
