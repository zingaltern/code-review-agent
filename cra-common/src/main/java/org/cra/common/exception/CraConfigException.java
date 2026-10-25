package org.cra.common.exception;

/** 配置非法、拒绝启动（09§4；如 dual-vote 异构校验失败，05§5）→ CLI 退出码 2（05§3）。 */
public class CraConfigException extends CraException {

  public CraConfigException(String message) {
    super(message);
  }
}
