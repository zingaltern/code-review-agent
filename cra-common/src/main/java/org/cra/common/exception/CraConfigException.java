package org.cra.common.exception;

/** 配置非法、拒绝启动（09§4；如 dual-vote 异构校验失败，05§5）→ CLI 退出码 2（05§3）。 */
public class CraConfigException extends CraException {

  public CraConfigException(String message) {
    super(message);
  }

  /**
   * 带底层原因的构造：运行期故障（文件/流 IO、解析等）借 Config 语义上报时保留 cause 链，避免"消息只含裸路径、NoSuchFile 与 AccessDenied
   * 等不可辨"的诊断丢失（09§4）。消息文本仍须自含"操作 + 定位 + 原因"，cause 只增类名与堆栈。
   *
   * @param message 完整错误消息（操作 + 定位 + 原因）
   * @param cause 底层异常（保留类名与堆栈）
   */
  public CraConfigException(String message, Throwable cause) {
    super(message, cause);
  }
}
