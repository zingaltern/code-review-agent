package org.cra.server.webhook;

import java.util.Map;
import java.util.Optional;
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
 * GitHub webhook 端点（02§5 服务形态）：验签 → 事件解析（pull_request 子集）→ 幂等登记 → 有界队列。
 *
 * <p>状态码语义：401 验签失败；400 缺头/payload 非法；202 已入队；200 重复 delivery 或事件被忽略；503 背压（带 Retry-After，GitHub
 * 会重投同一 delivery）。
 *
 * <p>M4e 第二半（ADR-0012）：本端点仅在 `CRA_SERVER_PLATFORM=github`（或未设置）时注册；GitLab 模式走 {@link
 * GitLabWebhookController}，非当前平台的端点不存在（404）。
 */
@RestController
@ConditionalOnProperty(name = "CRA_SERVER_PLATFORM", havingValue = "github", matchIfMissing = true)
public final class WebhookController {

  private static final Logger LOG = LoggerFactory.getLogger(WebhookController.class);
  private static final String RETRY_AFTER_SECONDS = "30";

  private final WebhookSignatureVerifier verifier;
  private final PullRequestEventParser parser;
  private final WebhookIngestService ingestService;

  /**
   * @param verifier 签名校验
   * @param parser 事件解析
   * @param ingestService 入站编排
   */
  public WebhookController(
      WebhookSignatureVerifier verifier,
      PullRequestEventParser parser,
      WebhookIngestService ingestService) {
    this.verifier = verifier;
    this.parser = parser;
    this.ingestService = ingestService;
  }

  /**
   * 接收 GitHub webhook。
   *
   * @param deliveryId X-GitHub-Delivery
   * @param eventName X-GitHub-Event
   * @param signature X-Hub-Signature-256
   * @param body 原始请求体
   * @return 状态码 + 简短 JSON
   */
  @PostMapping(
      path = "/webhooks/github",
      consumes = MediaType.ALL_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Map<String, Object>> receive(
      @RequestHeader(value = "X-GitHub-Delivery", required = false) String deliveryId,
      @RequestHeader(value = "X-GitHub-Event", required = false) String eventName,
      @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
      @RequestBody byte[] body) {
    if (deliveryId == null || deliveryId.isBlank() || eventName == null || eventName.isBlank()) {
      return ResponseEntity.badRequest().body(Map.of("status", "bad_request"));
    }
    if (!verifier.verify(body, signature)) {
      LOG.warn("webhook 验签失败: delivery={} event={}", deliveryId, eventName);
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("status", "unauthorized"));
    }
    Optional<PullRequestEvent> parsed;
    try {
      parsed = parser.parse(eventName, deliveryId, body);
    } catch (IllegalArgumentException e) {
      LOG.warn(
          "webhook payload 非法: delivery={} event={} err={}", deliveryId, eventName, e.getMessage());
      return ResponseEntity.badRequest().body(Map.of("status", "bad_payload"));
    }
    if (parsed.isEmpty()) {
      return ResponseEntity.ok(Map.of("status", "ignored", "event", eventName));
    }
    WebhookIngestService.Outcome outcome = ingestService.ingest(parsed.get());
    return switch (outcome) {
      case ACCEPTED ->
          ResponseEntity.status(HttpStatus.ACCEPTED)
              .body(Map.of("status", "accepted", "delivery", deliveryId));
      case DUPLICATE -> ResponseEntity.ok(Map.of("status", "duplicate", "delivery", deliveryId));
      case BACKPRESSURE ->
          ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
              .header("Retry-After", RETRY_AFTER_SECONDS)
              .body(Map.of("status", "backpressure", "delivery", deliveryId));
    };
  }
}
