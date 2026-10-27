package org.cra.server.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/** GitLab webhook HTTP 语义（ADR-0013）：401/400/200 ignored/202/duplicate/503。 */
class GitLabWebhookControllerTest {

  private static final String SECRET = "gl-secret";

  private WebhookIngestService ingest;
  private GitLabWebhookController controller;

  @BeforeEach
  void setUp() {
    ingest = mock(WebhookIngestService.class);
    controller =
        new GitLabWebhookController(
            new GitLabWebhookTokenVerifier(SECRET), new GitLabMergeRequestEventParser(), ingest);
  }

  @Test
  void acceptedMergeRequestIsQueued() {
    when(ingest.ingest(any())).thenReturn(WebhookIngestService.Outcome.ACCEPTED);
    ResponseEntity<java.util.Map<String, Object>> response =
        controller.receive(EVENT, SECRET, "uuid-1", body("open", null, false));

    assertThat(response.getStatusCode().value()).isEqualTo(202);
    assertThat(response.getBody()).containsEntry("status", "accepted");
    assertThat(response.getBody()).containsEntry("delivery", "uuid-1");
    verify(ingest)
        .ingest(
            org.mockito.ArgumentMatchers.argThat(
                e ->
                    e.prNumber() == 7
                        && "opened".equals(e.action())
                        && "base-sha".equals(e.baseSha())
                        && "head-sha".equals(e.headSha())));
  }

  @Test
  void duplicateDeliveryReturns200() {
    when(ingest.ingest(any())).thenReturn(WebhookIngestService.Outcome.DUPLICATE);
    ResponseEntity<java.util.Map<String, Object>> response =
        controller.receive(EVENT, SECRET, "uuid-1", body("open", null, false));
    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(response.getBody()).containsEntry("status", "duplicate");
  }

  @Test
  void backpressureReturns503WithRetryAfter() {
    when(ingest.ingest(any())).thenReturn(WebhookIngestService.Outcome.BACKPRESSURE);
    ResponseEntity<java.util.Map<String, Object>> response =
        controller.receive(EVENT, SECRET, "uuid-1", body("open", null, false));
    assertThat(response.getStatusCode().value()).isEqualTo(503);
    assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("30");
  }

  @Test
  void wrongOrMissingTokenReturns401() {
    assertThat(
            controller
                .receive(EVENT, "wrong", "u", body("open", null, false))
                .getStatusCode()
                .value())
        .isEqualTo(401);
    assertThat(
            controller.receive(EVENT, null, "u", body("open", null, false)).getStatusCode().value())
        .isEqualTo(401);
    verifyNoInteractions(ingest);
  }

  @Test
  void missingEventHeaderReturns400() {
    assertThat(
            controller
                .receive(null, SECRET, "u", body("open", null, false))
                .getStatusCode()
                .value())
        .isEqualTo(400);
    verifyNoInteractions(ingest);
  }

  @Test
  void invalidPayloadReturns400() {
    ResponseEntity<java.util.Map<String, Object>> response =
        controller.receive(EVENT, SECRET, "u", "not-json".getBytes(StandardCharsets.UTF_8));
    assertThat(response.getStatusCode().value()).isEqualTo(400);
    assertThat(response.getBody()).containsEntry("status", "bad_payload");
    verifyNoInteractions(ingest);
  }

  @Test
  void nonMergeRequestEventIsIgnoredExplicitly() {
    ResponseEntity<java.util.Map<String, Object>> response =
        controller.receive("Push Hook", SECRET, "u", "{}".getBytes(StandardCharsets.UTF_8));
    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(response.getBody())
        .containsEntry("status", "ignored")
        .containsEntry("reason", "unsupported_event");
    verifyNoInteractions(ingest);
  }

  @Test
  void draftMergeRequestIsIgnoredExplicitly() {
    ResponseEntity<java.util.Map<String, Object>> response =
        controller.receive(EVENT, SECRET, "u", body("open", null, true));
    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(response.getBody()).containsEntry("reason", "draft");
    verifyNoInteractions(ingest);
  }

  private static final String EVENT = GitLabMergeRequestEventParser.EVENT_NAME;

  private static byte[] body(String action, String oldrev, boolean draft) {
    return """
        {
          "object_kind": "merge_request",
          "object_attributes": {
            "action": "%s",
            "oldrev": %s,
            "draft": %s,
            "title": "feat: x",
            "state": "opened",
            "iid": 7,
            "diff_refs": {"base_sha": "base-sha", "head_sha": "head-sha", "start_sha": "s"}
          },
          "project": {"path_with_namespace": "group/proj"}
        }
        """
        .formatted(action, oldrev == null ? "null" : "\"" + oldrev + "\"", draft)
        .getBytes(StandardCharsets.UTF_8);
  }
}
