package org.cra.common.exception;

/** 项目异常基类（09§4）：仅承载"终止性故障"，可预期业务分支不用异常；一律 unchecked， 方法签名不声明 checked 异常。 */
public abstract class CraException extends RuntimeException {

  protected CraException(String message) {
    super(message);
  }

  protected CraException(String message, Throwable cause) {
    super(message, cause);
  }
}
