package org.cra.git.gitlab.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.function.LongConsumer;
import org.cra.common.exception.CraIntegrationException;
import org.cra.common.git.RepoRef;
import org.cra.common.text.Texts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * GitLab REST v4 HTTP 基础设施（ADR-0011）：`PRIVATE-TOKEN` 认证、项目路径编码、GET/POST/PUT 发送、 429/带 Retry-After 的
 * 403 退避（≤3 次）与 `RateLimit-Remaining` 告警。
 *
 * <p>本类不认识 diff/评论/处置语义，只负责把一次请求可靠送达；分页头 `X-Next-Page` 原样交给调用方。
 */
final class GitLabHttpTransport {

  private static final Logger LOG = LoggerFactory.getLogger(GitLabHttpTransport.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final int MAX_BACKOFF_RETRIES = 3;

  /** 外部 Retry-After 的单次等待上限：响应头可控，不设上限可让 run 挂起数天（ADR-0011 只要求退避，不要求盲从）。 */
  private static final long MAX_RETRY_AFTER_MS = 60_000L;

  /** 内部指数退避基数（1s/2s/4s/8s/16s）：与外部头分离，不受攻击者可控延迟影响。 */
  private static final long BASE_BACKOFF_MS = 1_000L;

  private final String apiBase;
  private final String token;
  private final HttpClient http;
  private final LongConsumer sleeper;

  GitLabHttpTransport(String apiBase, String token, HttpClient http, LongConsumer sleeper) {
    this.apiBase = apiBase.replaceAll("/+$", "");
    this.token = token;
    this.http = http;
    this.sleeper = sleeper;
  }

  /** 一页 JSON 响应：body + `X-Next-Page`（末页为空串）。 */
  record Page(JsonNode body, String nextPage) {}

  JsonNode getJson(RepoRef repo, String pathAndQuery) {
    return getPage(repo, pathAndQuery).body();
  }

  Page getPage(RepoRef repo, String pathAndQuery) {
    return getPage(projectPath(repo) + pathAndQuery, pathAndQuery);
  }

  byte[] getBytes(RepoRef repo, String pathAndQuery) {
    HttpRequest request = request(projectPath(repo) + pathAndQuery).GET().build();
    return executeBytes(request, pathAndQuery);
  }

  /** 非项目作用域端点（`GET /user`）。 */
  JsonNode getAbsoluteJson(String pathAndQuery) {
    HttpRequest request = request(pathAndQuery).GET().build();
    HttpResponse<String> response = execute(request, pathAndQuery);
    return parseJson(response.body(), pathAndQuery);
  }

  JsonNode postJson(String owner, String repo, String pathAndQuery, ObjectNode payload) {
    HttpRequest request =
        request(projectPath(owner, repo) + pathAndQuery)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
            .build();
    HttpResponse<String> response = execute(request, pathAndQuery);
    return parseJson(response.body(), pathAndQuery);
  }

  JsonNode putJson(String owner, String repo, String pathAndQuery, ObjectNode payload) {
    HttpRequest request =
        request(projectPath(owner, repo) + pathAndQuery)
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(payload.toString()))
            .build();
    HttpResponse<String> response = execute(request, pathAndQuery);
    return parseJson(response.body(), pathAndQuery);
  }

  HttpRequest.Builder request(String pathAndQuery) {
    return HttpRequest.newBuilder()
        .uri(URI.create(apiBase + pathAndQuery))
        .header("PRIVATE-TOKEN", token)
        .header("Accept", "application/json");
  }

  HttpResponse<String> execute(HttpRequest request, String path) {
    for (int attempt = 0; ; attempt++) {
      HttpResponse<String> response = send(request, path);
      int status = response.statusCode();
      if (status >= 200 && status < 300) {
        throttleIfCloseToLimit(response);
        return response;
      }
      if (isRetryable(response) && attempt < MAX_BACKOFF_RETRIES) {
        sleeper.accept(retryAfterMs(response, attempt));
        continue;
      }
      throw failure(status, path, response.body());
    }
  }

  byte[] executeBytes(HttpRequest request, String path) {
    for (int attempt = 0; ; attempt++) {
      HttpResponse<byte[]> response = sendBytes(request, path);
      int status = response.statusCode();
      if (status >= 200 && status < 300) {
        throttleIfCloseToLimit(response);
        return response.body();
      }
      if (isRetryable(response) && attempt < MAX_BACKOFF_RETRIES) {
        sleeper.accept(retryAfterMs(response, attempt));
        continue;
      }
      throw failure(
          status, path, Texts.truncate(new String(response.body(), StandardCharsets.UTF_8), 200));
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
    return e instanceof GitLabApiException api && (api.status() == 400 || api.status() == 422);
  }

  static boolean isUserUnauthorized(CraIntegrationException e) {
    return e instanceof GitLabApiException api
        && (api.status() == 401 || api.status() == 403)
        && "/user".equals(api.path());
  }

  private Page getPage(String path, String logPath) {
    HttpRequest request = request(path).GET().build();
    HttpResponse<String> response = execute(request, logPath);
    String next = response.headers().firstValue("X-Next-Page").orElse("");
    return new Page(parseJson(response.body(), logPath), next);
  }

  private static String projectPath(RepoRef repo) {
    return projectPath(repo.owner(), repo.repo());
  }

  private static String projectPath(String owner, String repo) {
    return "/projects/" + GitLabPayloadSupport.encodeProjectPath(owner, repo);
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
    if (retryAfter != null && retryAfter.matches("\\d{1,9}")) {
      return Math.min(Long.parseLong(retryAfter) * 1000L, MAX_RETRY_AFTER_MS);
    }
    return BASE_BACKOFF_MS * (1L << Math.min(attempt, 4));
  }

  private void throttleIfCloseToLimit(HttpResponse<?> response) {
    String remaining = response.headers().firstValue("RateLimit-Remaining").orElse(null);
    if (remaining != null && remaining.matches("\\d{1,9}") && Integer.parseInt(remaining) < 20) {
      LOG.warn("gitlab rate limit low: remaining={}", remaining);
    }
  }

  private static CraIntegrationException failure(int status, String path, String body) {
    return new GitLabApiException(
        status, path, "GitLab API " + status + " on " + path + ": " + Texts.truncate(body, 200));
  }
}
