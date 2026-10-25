package org.cra.llm.api;

/** LLM 调用响应模式（05§1.1/04§6：chat + JSON 双模式的最小实现）。 */
public enum ResponseMode {
  /** 纯文本回复 */
  TEXT,
  /** JSON 模式：请求带 response_format={"type":"json_object"}，内容为 JSON 字符串 */
  JSON
}
