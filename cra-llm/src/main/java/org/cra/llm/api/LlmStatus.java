package org.cra.llm.api;

/** 调用结果状态（02§4.2：降级返回结果对象，底层异常绝不抛穿编排层）。 */
public enum LlmStatus {
  /** 成功拿到内容 */
  OK,
  /** 不可得：重试耗尽 / 排队超时 / 客户端错误——content 为 null，errorMessage 说明原因 */
  UNAVAILABLE
}
