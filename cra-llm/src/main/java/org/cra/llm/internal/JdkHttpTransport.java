package org.cra.llm.internal;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** JDK HttpClient 实现（无额外依赖；openai-code-review 的 HttpUtil 语义收敛到唯一传输层）。 */
public final class JdkHttpTransport implements LlmTransport {

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
    return post(url, apiKey, jsonBody, timeout, java.util.Map.of());
  }

  @Override
  public TransportResponse post(
      String url,
      String apiKey,
      String jsonBody,
      Duration timeout,
      java.util.Map<String, String> extraHeaders)
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
    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    return new TransportResponse(response.statusCode(), response.body());
  }
}
