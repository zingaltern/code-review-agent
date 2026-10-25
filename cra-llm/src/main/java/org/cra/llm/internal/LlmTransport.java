package org.cra.llm.internal;

import java.time.Duration;

/**
 * HTTP 传输 SPI（网关唯一触网点，09§4 边界转换：HTTP 异常在本层之上有界处理）。 可注入 fake/cassette 实现做非网络单测与录制回放（08§2.2/08§3.2）。
 */
public interface LlmTransport {

  /**
   * POST 一次 chat/completions。
   *
   * @param url 端点完整 URL
   * @param apiKey Bearer token
   * @param jsonBody 请求体（OpenAI 兼容 JSON）
   * @param timeout 单次请求超时
   * @return 传输结果（HTTP 状态码 + 原始 body）
   * @throws java.io.IOException 网络/超时故障（可重试类故障由上层按 02§4.5 退避）
   * @throws InterruptedException 调用线程被中断
   */
  TransportResponse post(String url, String apiKey, String jsonBody, Duration timeout)
      throws java.io.IOException, InterruptedException;

  /**
   * 传输结果。
   *
   * @param statusCode HTTP 状态码
   * @param body 响应体原文
   */
  record TransportResponse(int statusCode, String body) {}
}
