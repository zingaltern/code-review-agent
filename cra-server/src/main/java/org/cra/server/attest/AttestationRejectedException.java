package org.cra.server.attest;

import org.cra.common.exception.CraException;

/**
 * 签发请求被拒（HTTP 边界映射：400 非法报告 / 409 commit 未经服务端评审）。
 *
 * <p>继承项目异常基类（09§4）；它只在 web 边界表达"请求不可受理"，不进入 run 状态机。
 */
public final class AttestationRejectedException extends CraException {

  private final int status;

  /**
   * @param status HTTP 状态码
   * @param message 原因
   */
  public AttestationRejectedException(int status, String message) {
    super(message);
    this.status = status;
  }

  /**
   * @return HTTP 状态码
   */
  public int status() {
    return status;
  }
}
