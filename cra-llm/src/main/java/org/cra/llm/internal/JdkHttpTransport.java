package org.cra.llm.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/** JDK HttpClient 实现（无额外依赖；openai-code-review 的 HttpUtil 语义收敛到唯一传输层）。 */
public final class JdkHttpTransport implements LlmTransport {

  /** 响应体上限：正常 chat/completions 远小于此值；超限视为协议级异常，避免外部可控的堆耗尽。 */
  static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;

  private final HttpClient client;

  public JdkHttpTransport() {
    this.client =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(20))
            .build();
  }

  @Override
  public TransportResponse post(String url, String apiKey, String jsonBody, Duration timeout)
      throws IOException, InterruptedException {
    return post(url, apiKey, jsonBody, timeout, Map.of());
  }

  @Override
  public TransportResponse post(
      String url,
      String apiKey,
      String jsonBody,
      Duration timeout,
      Map<String, String> extraHeaders)
      throws IOException, InterruptedException {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + apiKey);
    if (extraHeaders != null) {
      // 附加头（v1.3.2）：opencode-go 路由要求的 x-opencode-session 等
      extraHeaders.forEach(builder::header);
    }
    HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString(jsonBody)).build();
    HttpResponse<InputStream> response =
        client.send(request, HttpResponse.BodyHandlers.ofInputStream());
    try (InputStream in = response.body()) {
      return new TransportResponse(response.statusCode(), readBounded(in));
    }
  }

  /** 包私有以便单测直测上限逻辑（08§2.2：HTTP 行为在集成层，纯读取逻辑本地覆盖）。 */
  static String readBounded(InputStream in) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int n;
    while ((n = in.read(buffer)) > 0) {
      if (out.size() + n > MAX_RESPONSE_BYTES) {
        throw new IOException("llm 响应体超过上限 " + MAX_RESPONSE_BYTES + " 字节");
      }
      out.write(buffer, 0, n);
    }
    return out.toString(StandardCharsets.UTF_8);
  }
}
