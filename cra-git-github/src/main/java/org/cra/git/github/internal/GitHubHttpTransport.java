package org.cra.git.github.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.cra.common.exception.CraIntegrationException;
import org.cra.common.git.RepoRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * GitHub REST HTTP 基础设施（05§2.3）：认证/版本头、GET/POST/PATCH 发送、429/403 退避（≤3 次）与限流告警。
 *
 * <p>从 {@link GitHubHttpAdapter} 拆出：本类不认识 diff/评论/处置语义，只负责把一次请求可靠地送达并返回 JSON/字节。
 */
final class GitHubHttpTransport {

  private static final Logger LOG = LoggerFactory.getLogger(GitHubHttpTransport.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final int MAX_BACKOFF_RETRIES = 3;

  private final String apiBase;
  private final String token;
  private final HttpClient http;
  private final java.util.function.LongConsumer sleeper;

  GitHubHttpTransport(
      String apiBase, String token, HttpClient http, java.util.function.LongConsumer sleeper) {
    this.apiBase = apiBase.replaceAll("/+$", "");
    this.token = token;
    this.http = http;
    this.sleeper = sleeper;
  }

  JsonNode getJson(RepoRef repo, String pathAndQuery) {
    HttpRequest request =
        request("/repos/" + repo.owner() + "/" + repo.repo() + pathAndQuery)
            .header("Accept", "application/vnd.github+json")
            .GET()
            .build();
    HttpResponse<String> response = execute(request, pathAndQuery);
    return parseJson(response.body(), pathAndQuery);
  }

  byte[] getBytes(RepoRef repo, String pathAndQuery, String accept) {
    HttpRequest request =
        request("/repos/" + repo.owner() + "/" + repo.repo() + pathAndQuery)
            .header("Accept", accept)
            .GET()
            .build();
    return executeBytes(request, pathAndQuery);
  }

  JsonNode postJson(String owner, String repo, String pathAndQuery, ObjectNode payload) {
    HttpRequest request =
        request("/repos/" + owner + "/" + repo + pathAndQuery)
            .header("Accept", "application/vnd.github+json")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
            .build();
    HttpResponse<String> response = execute(request, pathAndQuery);
    return parseJson(response.body(), pathAndQuery);
  }

  HttpRequest.Builder request(String pathAndQuery) {
    return HttpRequest.newBuilder()
        .uri(URI.create(apiBase + pathAndQuery))
        .header("Authorization", "Bearer " + token)
        .header("X-GitHub-Api-Version", "2022-11-28");
  }

  HttpResponse<String> execute(HttpRequest request, String path) {
    for (int attempt = 0; ; attempt++) {
      HttpResponse<String> response = send(request, path);
      int status = response.statusCode();
      if (status == 200 || status == 201) {
        throttleIfCloseToLimit(response);
        return response;
      }
      if (isRetryable(response) && attempt < MAX_BACKOFF_RETRIES) {
        sleeper.accept(retryAfterMs(response, attempt)); // 指数退避（05§2.3 ≤3 次）
        continue;
      }
      throw new CraIntegrationException(
          "GitHub API "
              + status
              + " on "
              + path
              + ": "
              + GitHubPayloadSupport.abbreviate(response.body()));
    }
  }

  byte[] executeBytes(HttpRequest request, String path) {
    for (int attempt = 0; ; attempt++) {
      HttpResponse<byte[]> response = sendBytes(request, path);
      int status = response.statusCode();
      if (status == 200 || status == 201) {
        return response.body();
      }
      if (isRetryable(response) && attempt < MAX_BACKOFF_RETRIES) {
        sleeper.accept(retryAfterMs(response, attempt));
        continue;
      }
      throw new CraIntegrationException(
          "GitHub API "
              + status
              + " on "
              + path
              + ": "
              + GitHubPayloadSupport.abbreviate(
                  new String(response.body(), StandardCharsets.UTF_8)));
    }
  }

  JsonNode parseJson(String body, String path) {
    try {
      return MAPPER.readTree(body);
    } catch (IOException e) {
      throw new CraIntegrationException("non-JSON response from " + path, e);
    }
  }

  static boolean isUnprocessable(CraIntegrationException e) {
    return e.getMessage() != null && e.getMessage().contains("GitHub API 422");
  }

  private HttpResponse<String> send(HttpRequest request, String path) {
    try {
      return http.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new CraIntegrationException("transport failure on " + path, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new CraIntegrationException("interrupted on " + path, e);
    }
  }

  private HttpResponse<byte[]> sendBytes(HttpRequest request, String path) {
    try {
      return http.send(request, HttpResponse.BodyHandlers.ofByteArray());
    } catch (IOException e) {
      throw new CraIntegrationException("transport failure on " + path, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new CraIntegrationException("interrupted on " + path, e);
    }
  }

  private static boolean isRetryable(HttpResponse<?> response) {
    return response.statusCode() == 429
        || (response.statusCode() == 403
            && response.headers().firstValue("Retry-After").isPresent());
  }

  private long retryAfterMs(HttpResponse<?> response, int attempt) {
    String retryAfter = response.headers().firstValue("Retry-After").orElse(null);
    // 正则前置校验替代 try/parse：无效头不进 catch（09§4 禁吞异常），直接走指数退避
    if (retryAfter != null && retryAfter.matches("\\d{1,9}")) {
      return Long.parseLong(retryAfter) * 1000L;
    }
    return 1_000L * (1L << Math.min(attempt, 4));
  }

  private void throttleIfCloseToLimit(HttpResponse<?> response) {
    String remaining = response.headers().firstValue("X-RateLimit-Remaining").orElse(null);
    if (remaining != null && remaining.matches("\\d{1,9}") && Integer.parseInt(remaining) < 20) {
      // 主动节流告警不硬睡（CI 单 run 调用量小，硬睡拖尾；05§2.3）
      LOG.warn("github rate limit low: remaining={}", remaining);
    }
  }
}
