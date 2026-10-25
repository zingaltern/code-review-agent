package org.cra.common.finding;

/** finding 行号所属侧（GitHub PR review comment 的 side 语义：新代码 RIGHT）。 */
public enum Side {
  /** 新代码侧（PR 引入的行） */
  RIGHT,
  /** 旧代码侧（被删除的行） */
  LEFT
}
