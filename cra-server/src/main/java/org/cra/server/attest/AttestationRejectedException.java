package org.cra.server.attest;

/** 签发请求被拒（status 直接映射 HTTP：400 非法报告 / 409 commit 未经服务端评审）。 */
public final class AttestationRejectedException extends RuntimeException {

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
