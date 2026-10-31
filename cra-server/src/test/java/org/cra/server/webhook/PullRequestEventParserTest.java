package org.cra.server.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** pull_request 事件子集解析（02§5：opened/reopened/synchronize/ready_for_review；draft 过滤）。 */
class PullRequestEventParserTest {

  private final PullRequestEventParser parser = new PullRequestEventParser();

  private static byte[] payload(String action, boolean draft) {
    return ("""
        {
          "action": "%s",
          "number": 7,
          "pull_request": {
            "number": 7,
            "draft": %s,
            "base": {"sha": "base111"},
            "head": {"sha": "head222"}
          },
          "repository": {
            "name": "repo",
            "owner": {"login": "owner"}
          }
        }
        """)
        .formatted(action, draft)
        .getBytes(StandardCharsets.UTF_8);
  }

  @Test
  void parsesOpenedPullRequest() {
    Optional<PullRequestEvent> event =
        parser.parse("pull_request", "delivery-1", payload("opened", false));
    assertThat(event)
        .hasValueSatisfying(
            e -> {
              assertThat(e.deliveryId()).isEqualTo("delivery-1");
              assertThat(e.action()).isEqualTo("opened");
              assertThat(e.repoKey()).isEqualTo("owner/repo");
              assertThat(e.prNumber()).isEqualTo(7);
              assertThat(e.baseSha()).isEqualTo("base111");
              assertThat(e.headSha()).isEqualTo("head222");
            });
  }

  @Test
  void parsesSynchronizeAndReadyForReview() {
    assertThat(parser.parse("pull_request", "d2", payload("synchronize", false))).isPresent();
    assertThat(parser.parse("pull_request", "d3", payload("ready_for_review", false))).isPresent();
  }

  @Test
  void ignoresDraftAndUnsupportedActions() {
    assertThat(parser.parse("pull_request", "d4", payload("opened", true))).isEmpty();
    assertThat(parser.parse("pull_request", "d5", payload("closed", false))).isEmpty();
    assertThat(parser.parse("pull_request", "d6", payload("labeled", false))).isEmpty();
  }

  @Test
  void ignoresNonPullRequestEvents() {
    assertThat(parser.parse("push", "d7", payload("opened", false))).isEmpty();
    assertThat(parser.parse("ping", "d8", "{}".getBytes(StandardCharsets.UTF_8))).isEmpty();
  }

  @Test
  void rejectsMalformedPayload() {
    byte[] notJson = "not-json".getBytes(StandardCharsets.UTF_8);
    assertThatThrownBy(() -> parser.parse("pull_request", "d9", notJson))
        .isInstanceOf(IllegalArgumentException.class);
    byte[] missingRefs = "{\"action\":\"opened\"}".getBytes(StandardCharsets.UTF_8);
    assertThatThrownBy(() -> parser.parse("pull_request", "d10", missingRefs))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
