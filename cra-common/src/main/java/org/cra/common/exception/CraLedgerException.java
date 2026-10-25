package org.cra.common.exception;

/** 账本读写/解析失败 → 按 05§1.3 兜底分层降级，不允许炸穿 run（09§4）。 */
public class CraLedgerException extends CraException {

  public CraLedgerException(String message) {
    super(message);
  }

  public CraLedgerException(String message, Throwable cause) {
    super(message, cause);
  }
}
