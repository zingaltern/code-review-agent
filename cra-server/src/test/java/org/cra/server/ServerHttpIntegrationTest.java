package org.cra.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.cra.common.attest.ReportSignature;
import org.cra.common.git.LedgerRunMarker;
import org.cra.common.run.Coverage;
import org.cra.common.run.ReviewResult;
import org.cra.common.run.ReviewStats;
import org.cra.common.run.RunStatus;
import org.cra.server.pipeline.ReviewExecutor;
import org.cra.server.webhook.WebhookSignatureVerifier;
import org.cra.store.api.StoreAssembly;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 服务端 HTTP 端到端（需要 PG：CI service 容器经 CRA_TEST_PG_URL；本地 docker-compose PG）。
 *
 * <p>覆盖：webhook 验签 → 入队 → 异步执行（假执行器写 PG 完成标记）→ 同 delivery 幂等；报告签发端点（token 保护 + 已知 commit 策略）→ HMAC
 * 验签；健康检查。
 */
@EnabledIfEnvironmentVariable(named = "CRA_TEST_PG_URL", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ServerHttpIntegrationTest.FakeConfig.class)
class ServerHttpIntegrationTest {

  private static final String WEBHOOK_SECRET = "test-webhook-secret";
  private static final String API_TOKEN = "test-api-token";
  private static final String SIGNING_KEY = "test-signing-key";
  private static final String HEAD = "head-abc123";

  @LocalServerPort private int port;

  @Autowired private TestRestTemplate rest;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private DataSource dataSource;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> System.getenv("CRA_TEST_PG_URL"));
    registry.add(
        "spring.datasource.username",
        () -> System.getenv().getOrDefault("CRA_TEST_PG_USER", "cra"));
    registry.add(
        "spring.datasource.password",
        () -> System.getenv().getOrDefault("CRA_TEST_PG_PASSWORD", "cra"));
    registry.add("CRA_SERVER_WEBHOOK_SECRET", () -> WEBHOOK_SECRET);
    registry.add("CRA_SERVER_API_TOKEN", () -> API_TOKEN);
    registry.add("CRA_SERVER_SIGNING_KEY", () -> SIGNING_KEY);
    registry.add("CRA_SERVER_SIGNING_KEY_ID", () -> "test-key-v1");
    registry.add("CRA_SERVER_QUEUE_CAPACITY", () -> "10");
    registry.add("CRA_SERVER_WORKERS", () -> "1");
    registry.add("CRA_SERVER_RUN_ROOT", () -> "target/test-server-runs");
    registry.add("CRA_SERVER_EVENT_HISTORY_ENABLED", () -> "false");
    registry.add("CRA_LLM_API_KEY", () -> "dummy");
    registry.add("CRA_LLM_BASE_URL", () -> "http://127.0.0.1:1/v1");
    registry.add("CRA_LLM_MODEL", () -> "dummy");
    registry.add("CRA_GITHUB_API_BASE", () -> "http://127.0.0.1:1");
  }

  @BeforeEach
  void cleanTables() {
    jdbc.execute(
        "TRUNCATE review_run, finding, verdict, published_comment, prompt_template_version,"
            + " webhook_delivery, report_attestation");
  }

  @Test
  void webhookAcceptedThenDuplicateAndLedgerRecorded() {
    String delivery = "delivery-" + System.nanoTime();
    ResponseEntity<String> first = postWebhook(delivery, webhookBody());
    assertThat(first.getStatusCode().value()).isEqualTo(202);

    await(() -> "COMPLETED".equals(deliveryStatus(delivery)));
    assertThat(count("SELECT count(*) FROM review_run WHERE commit_sha = ?", HEAD)).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM webhook_delivery WHERE delivery_id = ?", delivery))
        .isEqualTo(1);
    assertThat(deliveryStatus(delivery)).isEqualTo("COMPLETED");

    ResponseEntity<String> duplicate = postWebhook(delivery, webhookBody());
    assertThat(duplicate.getStatusCode().value()).isEqualTo(200);
    assertThat(duplicate.getBody()).contains("duplicate");
    assertThat(count("SELECT count(*) FROM review_run WHERE commit_sha = ?", HEAD)).isEqualTo(1);

    ResponseEntity<String> badSignature =
        rest.exchange(
            url("/webhooks/github"),
            HttpMethod.POST,
            new HttpEntity<>(webhookBody(), webhookHeaders(delivery, "sha256=" + "0".repeat(64))),
            String.class);
    assertThat(badSignature.getStatusCode().value()).isEqualTo(401);
  }

  @Test
  void attestSignedReportAndVerifySignature() {
    String delivery = "delivery-sign-" + System.nanoTime();
    assertThat(postWebhook(delivery, webhookBody()).getStatusCode().value()).isEqualTo(202);
    await(() -> "COMPLETED".equals(deliveryStatus(delivery)));
    assertThat(count("SELECT count(*) FROM review_run WHERE commit_sha = ?", HEAD)).isEqualTo(1);

    String report =
        """
        {"commit":"%s","tier":"l2","k":2,"depth":"quick","generated_at":"2026-10-26T21:00:00Z",
         "gate":{"tier":"l2","passed":true,"checks":[]}}
        """
            .formatted(HEAD);
    ResponseEntity<String> signed = postAttest(report);
    assertThat(signed.getStatusCode().value()).isEqualTo(200);
    ReportSignature.Envelope envelope = ReportSignature.parse(signed.getBody());
    assertThat(
            ReportSignature.verify(
                SIGNING_KEY.getBytes(StandardCharsets.UTF_8),
                envelope,
                ReportSignature.sha256(report.getBytes(StandardCharsets.UTF_8))))
        .isTrue();
    assertThat(envelope.commit()).isEqualTo(HEAD);
    assertThat(count("SELECT count(*) FROM report_attestation WHERE commit_sha = ?", HEAD))
        .isEqualTo(1);

    ResponseEntity<String> noToken =
        rest.postForEntity(
            url("/api/v1/reports/attest"),
            new HttpEntity<>(report, jsonContentType()),
            String.class);
    assertThat(noToken.getStatusCode().value()).isEqualTo(401);

    ResponseEntity<String> unknownCommit = postAttest(report.replace(HEAD, "unknown-commit"));
    assertThat(unknownCommit.getStatusCode().value()).isEqualTo(409);
  }

  @Test
  void healthAndReadiness() {
    assertThat(rest.getForEntity(url("/healthz"), String.class).getStatusCode().value())
        .isEqualTo(200);
    assertThat(rest.getForEntity(url("/readyz"), String.class).getStatusCode().value())
        .isEqualTo(200);
  }

  private ResponseEntity<String> postWebhook(String delivery, String body) {
    return rest.exchange(
        url("/webhooks/github"),
        HttpMethod.POST,
        new HttpEntity<>(body, webhookHeaders(delivery, null)),
        String.class);
  }

  private ResponseEntity<String> postAttest(String report) {
    HttpHeaders headers = jsonContentType();
    headers.setBearerAuth(API_TOKEN);
    return rest.postForEntity(
        url("/api/v1/reports/attest"), new HttpEntity<>(report, headers), String.class);
  }

  private static HttpHeaders webhookHeaders(String delivery, String signature) {
    HttpHeaders headers = jsonContentType();
    headers.add("X-GitHub-Delivery", delivery);
    headers.add("X-GitHub-Event", "pull_request");
    String body = webhookBody();
    headers.add(
        "X-Hub-Signature-256",
        signature == null
            ? WebhookSignatureVerifier.signatureHeader(
                body.getBytes(StandardCharsets.UTF_8), WEBHOOK_SECRET)
            : signature);
    return headers;
  }

  private static HttpHeaders jsonContentType() {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    return headers;
  }

  private static String webhookBody() {
    return """
        {
          "action": "opened",
          "number": 7,
          "pull_request": {
            "number": 7,
            "draft": false,
            "base": {"sha": "base-abc123"},
            "head": {"sha": "%s"}
          },
          "repository": {"name": "repo", "owner": {"login": "owner"}}
        }
        """
        .formatted(HEAD);
  }

  private String url(String path) {
    return "http://127.0.0.1:" + port + path;
  }

  private int count(String sql, Object... args) {
    Integer value = jdbc.queryForObject(sql, Integer.class, args);
    return value == null ? 0 : value;
  }

  private String deliveryStatus(String deliveryId) {
    java.util.List<String> rows =
        jdbc.queryForList(
            "SELECT status FROM webhook_delivery WHERE delivery_id = ?", String.class, deliveryId);
    return rows.isEmpty() ? "MISSING" : rows.get(0);
  }

  private static void await(BooleanSupplier condition) {
    long deadline = System.currentTimeMillis() + 10_000;
    while (System.currentTimeMillis() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError("等待被中断", e);
      }
    }
    throw new AssertionError("等待条件超时（10s）");
  }

  /** 假执行器：避免真实 LLM/GitHub；写 PG 完成标记以驱动签发策略。 */
  @TestConfiguration
  static class FakeConfig {

    @Bean
    @Primary
    ReviewExecutor fakeReviewExecutor(DataSource dataSource) {
      return job -> {
        StoreAssembly.createPostgresLedger(job.repoOwner(), job.repoName(), dataSource)
            .recordRunCompletion(
                job.prNumber(),
                new LedgerRunMarker("run_" + job.deliveryId(), job.headSha(), "DONE", 0, 0, 0L));
        ReviewStats stats =
            new ReviewStats(
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1L, List.of(), 0, new Coverage(0, 0, ""));
        return new ReviewResult(
            "run_" + job.deliveryId(),
            job.repoOwner(),
            job.repoName(),
            job.prNumber(),
            job.headSha(),
            RunStatus.SUCCESS,
            stats,
            "file:///tmp",
            "fake executor");
      };
    }
  }
}
