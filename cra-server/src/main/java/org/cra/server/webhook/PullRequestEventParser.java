package org.cra.server.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * GitHub pull_request 事件解析（02§5 触发边界：只评审 opened/reopened/synchronize/ready_for_review；draft
 * 过滤；其他事件忽略）。
 *
 * <p>解析失败抛 {@link IllegalArgumentException}（缺字段/非法 JSON），由 HTTP 层回 400；不评审的事件返回 empty（200 忽略，GitHub
 * 不会重投）。
 */
@Component
public final class PullRequestEventParser {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String PULL_REQUEST = "pull_request";
  private static final Set<String> REVIEW_ACTIONS =
      Set.of("opened", "reopened", "synchronize", "ready_for_review");

  /**
   * 解析事件。
   *
   * @param eventName X-GitHub-Event
   * @param deliveryId X-GitHub-Delivery
   * @param body 原始请求体
   * @return 需评审的事件；不评审/不支持时为 empty
   * @throws IllegalArgumentException payload 非法
   */
  public Optional<PullRequestEvent> parse(String eventName, String deliveryId, byte[] body) {
    if (!PULL_REQUEST.equals(eventName)) {
      return Optional.empty();
    }
    if (deliveryId == null || deliveryId.isBlank()) {
      throw new IllegalArgumentException("缺少 X-GitHub-Delivery");
    }
    JsonNode root;
    try {
      root = MAPPER.readTree(body);
    } catch (IOException e) {
      throw new IllegalArgumentException("webhook body 不是合法 JSON: " + e.getMessage());
    }
    String action = text(root, "action");
    if (!REVIEW_ACTIONS.contains(action)) {
      return Optional.empty();
    }
    JsonNode pullRequest = root.path("pull_request");
    boolean draft = pullRequest.path("draft").asBoolean(false);
    if (draft && !"ready_for_review".equals(action)) {
      return Optional.empty();
    }
    JsonNode repository = root.path("repository");
    String owner = text(repository.path("owner"), "login");
    String repo = text(repository, "name");
    int prNumber =
        pullRequest.hasNonNull("number")
            ? pullRequest.path("number").asInt()
            : root.path("number").asInt();
    if (prNumber <= 0) {
      throw new IllegalArgumentException("pull_request payload 缺合法 number: " + prNumber);
    }
    String baseSha = text(pullRequest.path("base"), "sha");
    String headSha = text(pullRequest.path("head"), "sha");
    return Optional.of(
        new PullRequestEvent(deliveryId, action, owner, repo, prNumber, baseSha, headSha));
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull() || value.asText().isBlank()) {
      throw new IllegalArgumentException("pull_request payload 缺字段: " + field);
    }
    return value.asText();
  }
}
