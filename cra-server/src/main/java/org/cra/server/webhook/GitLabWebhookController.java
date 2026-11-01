package org.cra.server.webhook;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * GitLab webhook 端点（M4e 第二半，ADR-0013；02§5 服务形态的 GitLab 版）。
 *
 * <p>流程：`X-Gitlab-Token` 验签 → Merge Request Hook 解析（只评审 open/reopen/带新提交的 update）→ 幂等登记 → 有界队列。 非
 * MR 事件与合并/关闭/审批/元数据更新/draft 一律 **200 + 显式 reason**（GitLab 对非 2xx 会重投）；验签失败 401、缺头/非法 payload
 * 400、背压 503（带 Retry-After）。
 *
 * <p>本端点仅在 `CRA_SERVER_PLATFORM=gitlab` 时注册；GitHub 模式走 {@link WebhookController}。
 */
@RestController
@ConditionalOnProperty(name = "CRA_SERVER_PLATFORM", havingValue = "gitlab")
public final class GitLabWebhookController {

  private static final Logger LOG = LoggerFactory.getLogger(GitLabWebhookController.class);
  private static final String RETRY_AFTER_SECONDS = "30";

  private final GitLabWebhookTokenVerifier verifier;
  private final GitLabMergeRequestEventParser parser;
  private final WebhookIngestService ingestService;

  /**
   * @param verifier X-Gitlab-Token 校验
   * @param parser Merge Request Hook 解析
   * @param ingestService 入站编排
   */
  public GitLabWebhookController(
      GitLabWebhookTokenVerifier verifier,
      GitLabMergeRequestEventParser parser,
      WebhookIngestService ingestService) {
    this.verifier = verifier;
    this.parser = parser;
    this.ingestService = ingestService;
  }

  /**
   * 接收 GitLab webhook。
   *
   * @param eventName X-Gitlab-Event
   * @param token X-Gitlab-Token
   * @param eventUuid X-Gitlab-Event-UUID
   * @param body 原始请求体
   * @return 状态码 + 简短 JSON
   */
  @PostMapping(
      path = "/webhooks/gitlab",
      consumes = MediaType.ALL_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Map<String, Object>> receive(
      @RequestHeader(value = "X-Gitlab-Event", required = false) String eventName,
      @RequestHeader(value = "X-Gitlab-Token", required = false) String token,
      @RequestHeader(value = "X-Gitlab-Event-UUID", required = false) String eventUuid,
      @RequestBody byte[] body) {
    if (eventName == null || eventName.isBlank()) {
      return ResponseEntity.badRequest().body(Map.of("status", "bad_request"));
    }
    if (!verifier.verify(token)) {
      LOG.warn("gitlab webhook 验签失败: event={}", eventName);
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("status", "unauthorized"));
    }
    GitLabMergeRequestEventParser.Result parsed;
    try {
      parsed = parser.parse(eventName, eventUuid, body);
    } catch (IllegalArgumentException e) {
      LOG.warn("gitlab webhook payload 非法: event={} err={}", eventName, e.getMessage(), e);
      return ResponseEntity.badRequest().body(Map.of("status", "bad_payload"));
    }
    if (!parsed.accepted()) {
      LOG.info("gitlab webhook 忽略: event={} reason={}", eventName, parsed.reason());
      return ResponseEntity.ok(
          Map.of("status", "ignored", "event", eventName, "reason", parsed.reason()));
    }
    PullRequestEvent event = parsed.event();
    WebhookIngestService.Outcome outcome = ingestService.ingest(event);
    return switch (outcome) {
      case ACCEPTED ->
          ResponseEntity.status(HttpStatus.ACCEPTED)
              .body(Map.of("status", "accepted", "delivery", event.deliveryId()));
      case DUPLICATE ->
          ResponseEntity.ok(Map.of("status", "duplicate", "delivery", event.deliveryId()));
      case BACKPRESSURE ->
          ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
              .header("Retry-After", RETRY_AFTER_SECONDS)
              .body(Map.of("status", "backpressure", "delivery", event.deliveryId()));
    };
  }
}
