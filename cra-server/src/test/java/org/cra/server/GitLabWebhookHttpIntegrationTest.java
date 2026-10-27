package org.cra.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.cra.common.git.LedgerRunMarker;
import org.cra.common.run.Coverage;
import org.cra.common.run.ReviewResult;
import org.cra.common.run.ReviewStats;
import org.cra.common.run.RunStatus;
import org.cra.server.pipeline.ReviewExecutor;
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
 * GitLab webhook 服务端端到端（M4e 第二半，ADR-0012；需要 PG：CI service 容器经 CRA_TEST_PG_URL）。
 *
 * <p>覆盖：`/webhooks/gitlab` 验签 → MR 事件映射 → 入队 → 异步执行（假执行器写 PG 完成标记）→ 同 event UUID 幂等； 错 token
 * 401、缺头/非法 payload 400、非 MR 事件 200 显式 ignored、GitHub 端点未注册 404、`/healthz` 声明平台。
 */
@EnabledIfEnvironmentVariable(named = "CRA_TEST_PG_URL", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(GitLabWebhookHttpIntegrationTest.FakeConfig.class)
class GitLabWebhookHttpIntegrationTest {

  private static final String WEBHOOK_SECRET = "test-gitlab-token";
  private static final String API_TOKEN = "test-api-token";
  private static final String SIGNING_KEY = "test-signing-key";
  private static final String HEAD = "gl-head-abc123";
  private static final String BASE = "gl-base-abc123";
  private static final String EVENT = "Merge Request Hook";

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
    registry.add("CRA_SERVER_PLATFORM", () -> "gitlab");
    registry.add("CRA_SERVER_WEBHOOK_SECRET", () -> WEBHOOK_SECRET);
    registry.add("CRA_GITLAB_TOKEN", () -> "dummy-gitlab-token");
    registry.add("CRA_GITLAB_BASE_URL", () -> "http://127.0.0.1:1/api/v4");
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
  }

  @BeforeEach
  void cleanTables() {
    jdbc.execute(
        "TRUNCATE review_run, finding, verdict, published_comment, prompt_template_version,"
            + " webhook_delivery, report_attestation");
  }

  @Test
  void mergeRequestAcceptedThenDuplicateDeliveryAndLedgerRecorded() {
    String uuid = "gl-uuid-" + System.nanoTime();
    ResponseEntity<String> first = postWebhook(uuid, EVENT, WEBHOOK_SECRET, webhookBody());
    assertThat(first.getStatusCode().value()).isEqualTo(202);

    await(() -> "COMPLETED".equals(deliveryStatus(uuid)));
    assertThat(count("SELECT count(*) FROM review_run WHERE commit_sha = ?", HEAD)).isEqualTo(1);
    assertThat(deliveryStatus(uuid)).isEqualTo("COMPLETED");

    ResponseEntity<String> duplicate = postWebhook(uuid, EVENT, WEBHOOK_SECRET, webhookBody());
    assertThat(duplicate.getStatusCode().value()).isEqualTo(200);
    assertThat(duplicate.getBody()).contains("duplicate");
    assertThat(count("SELECT count(*) FROM review_run WHERE commit_sha = ?", HEAD)).isEqualTo(1);
  }

  @Test
  void updateWithNewCommitsIsAcceptedButMetadataUpdateIsIgnored() {
    String synchronize = "gl-sync-" + System.nanoTime();
    ResponseEntity<String> pushed =
        postWebhook(synchronize, EVENT, WEBHOOK_SECRET, webhookBody("update", "old-sha"));
    assertThat(pushed.getStatusCode().value()).isEqualTo(202);
    await(() -> "COMPLETED".equals(deliveryStatus(synchronize)));

    String metadata = "gl-meta-" + System.nanoTime();
    ResponseEntity<String> retitled =
        postWebhook(metadata, EVENT, WEBHOOK_SECRET, webhookBody("update", null));
    assertThat(retitled.getStatusCode().value()).isEqualTo(200);
    assertThat(retitled.getBody()).contains("ignored").contains("metadata_update");
    assertThat(deliveryStatus(metadata)).isEqualTo("MISSING");
  }

  @Test
  void wrongTokenReturns401AndInvalidPayloadReturns400() {
    String uuid = "gl-bad-" + System.nanoTime();
    ResponseEntity<String> wrongToken = postWebhook(uuid, EVENT, "wrong", webhookBody());
    assertThat(wrongToken.getStatusCode().value()).isEqualTo(401);

    String body = "not-json";
    HttpHeaders headers = jsonHeaders();
    headers.add("X-Gitlab-Event", EVENT);
    headers.add("X-Gitlab-Token", WEBHOOK_SECRET);
    headers.add("X-Gitlab-Event-UUID", uuid);
    ResponseEntity<String> invalid =
        rest.exchange(
            url("/webhooks/gitlab"),
            HttpMethod.POST,
            new HttpEntity<>(body, headers),
            String.class);
    assertThat(invalid.getStatusCode().value()).isEqualTo(400);
    assertThat(invalid.getBody()).contains("bad_payload");
  }

  @Test
  void nonMergeRequestEventIsIgnoredAndGithubEndpointIsAbsent() {
    ResponseEntity<String> ignored =
        postWebhook("gl-push-" + System.nanoTime(), "Push Hook", WEBHOOK_SECRET, "{}");
    assertThat(ignored.getStatusCode().value()).isEqualTo(200);
    assertThat(ignored.getBody()).contains("ignored").contains("unsupported_event");

    ResponseEntity<String> githubEndpoint =
        rest.exchange(
            url("/webhooks/github"),
            HttpMethod.POST,
            new HttpEntity<>("{}", jsonHeaders()),
            String.class);
    assertThat(githubEndpoint.getStatusCode().value()).isEqualTo(404);
  }

  @Test
  void healthDeclaresPlatformAndReadinessStaysGreen() {
    ResponseEntity<String> health = rest.getForEntity(url("/healthz"), String.class);
    assertThat(health.getStatusCode().value()).isEqualTo(200);
    assertThat(health.getBody()).contains("\"platform\":\"gitlab\"");
    assertThat(rest.getForEntity(url("/readyz"), String.class).getStatusCode().value())
        .isEqualTo(200);
  }

  private ResponseEntity<String> postWebhook(String uuid, String event, String token, String body) {
    HttpHeaders headers = jsonHeaders();
    headers.add("X-Gitlab-Event", event);
    headers.add("X-Gitlab-Token", token);
    headers.add("X-Gitlab-Event-UUID", uuid);
    return rest.exchange(
        url("/webhooks/gitlab"), HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
  }

  private static HttpHeaders jsonHeaders() {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    return headers;
  }

  private static String webhookBody() {
    return webhookBody("open", null);
  }

  private static String webhookBody(String action, String oldrev) {
    return """
        {
          "object_kind": "merge_request",
          "object_attributes": {
            "action": "%s",
            "oldrev": %s,
            "draft": false,
            "title": "feat: gitlab wiring",
            "state": "opened",
            "iid": 7,
            "diff_refs": {"base_sha": "%s", "head_sha": "%s", "start_sha": "start-sha"}
          },
          "project": {"path_with_namespace": "group/subgroup/proj"}
        }
        """
        .formatted(action, oldrev == null ? "null" : "\"" + oldrev + "\"", BASE, HEAD);
  }

  private String url(String path) {
    return "http://127.0.0.1:" + port + path;
  }

  private int count(String sql, Object... args) {
    Integer value = jdbc.queryForObject(sql, Integer.class, args);
    return value == null ? 0 : value;
  }

  private String deliveryStatus(String deliveryId) {
    List<String> rows =
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

  /** 假执行器：避免真实 LLM/GitLab；写 PG 完成标记驱动 delivery 完成。 */
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
