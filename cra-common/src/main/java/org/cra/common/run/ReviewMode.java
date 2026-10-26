package org.cra.common.run;

/**
 * 评审发布形态（M4c/M4d，05§2.2 载体分离的扩展）。
 *
 * <ul>
 *   <li>{@link #PULL_REQUEST}：PR 形态——inline review + Summary issue comment（M0–M3 既有）；
 *   <li>{@link #COMMIT}：push 直推形态——commit comment 单载体，无行级锚点（M4c，05§2.4）；
 *   <li>{@link #ISSUE}：夜间存量评审形态——每条被评审 PR 一个 issue 载体（M4d，05§2.5）。
 * </ul>
 *
 * <p>diff 语义三种形态完全一致（base..head 的 compare）；差异只在发布载体与账本键控（05§1.3）。
 */
public enum ReviewMode {
  /** PR 形态：inline 评论 + Summary issue comment（05§2.2）。 */
  PULL_REQUEST,

  /** push 直推形态：单条 commit comment（05§2.4）。 */
  COMMIT,

  /** 夜间存量评审形态：单条 issue（05§2.5）。 */
  ISSUE
}
