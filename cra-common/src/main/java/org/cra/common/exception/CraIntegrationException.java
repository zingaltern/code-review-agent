package org.cra.common.exception;

/** 外部系统（Git API / 存储）边界故障，在边界内转换后上抛（09§4 边界转换）。 */
public class CraIntegrationException extends CraException {

  public CraIntegrationException(String message) {
    super(message);
  }

  public CraIntegrationException(String message, Throwable cause) {
    super(message, cause);
  }
}
