package org.cra.common.run;

/** run 终态（05§1.2）。 */
public enum RunStatus {
  /** 正常完成 */
  SUCCESS,
  /** 降级完成（部分角色超时但 run 正常收尾并发布了全部应发内容——也是完成标记语义，02§4.1） */
  DEGRADED,
  /** 未预期异常（run 落盘 + CI 非零退出但不阻塞 PR，02§4.2） */
  FAILED,
  /** 全量跳过（同 commit 完成标记存在，02§4.1 第一道闸） */
  SKIPPED
}
