package org.cra.server.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** GitLab Merge Request Hook 映射（ADR-0012；02§5 触发边界）。 */
class GitLabMergeRequestEventParserTest {

  private final GitLabMergeRequestEventParser parser = new GitLabMergeRequestEventParser();

  @Test
  void openMergeRequestIsAccepted() {
    GitLabMergeRequestEventParser.Result result =
        parser.parse(
            GitLabMergeRequestEventParser.EVENT_NAME,
            "uuid-1",
            body(
                "open",
                null,
                false,
                "feat: x",
                "opened",
                "group/sub/proj",
                "base-sha",
                "head-sha"));

    assertThat(result.accepted()).isTrue();
    assertThat(result.event().deliveryId()).isEqualTo("uuid-1");
    assertThat(result.event().action()).isEqualTo("opened");
    assertThat(result.event().repoOwner()).isEqualTo("group/sub");
    assertThat(result.event().repoName()).isEqualTo("proj");
    assertThat(result.event().prNumber()).isEqualTo(7);
    assertThat(result.event().baseSha()).isEqualTo("base-sha");
    assertThat(result.event().headSha()).isEqualTo("head-sha");
  }

  @Test
  void updateWithOldrevMapsToSynchronize() {
    GitLabMergeRequestEventParser.Result result =
        parser.parse(
            GitLabMergeRequestEventParser.EVENT_NAME,
            "uuid-2",
            body("update", "old-sha", false, "feat: x", "opened", "g/p", "base", "head"));
    assertThat(result.accepted()).isTrue();
    assertThat(result.event().action()).isEqualTo("synchronize");
  }

  @Test
  void updateWithoutOldrevIsMetadataUpdate() {
    GitLabMergeRequestEventParser.Result result =
        parser.parse(
            GitLabMergeRequestEventParser.EVENT_NAME,
            "uuid-3",
            body("update", null, false, "retitle", "opened", "g/p", "base", "head"));
    assertThat(result.accepted()).isFalse();
    assertThat(result.reason()).isEqualTo("metadata_update");
  }

  @Test
  void mergeAndCloseAreExplicitlyIgnored() {
    assertThat(
            parser
                .parse(
                    GitLabMergeRequestEventParser.EVENT_NAME,
                    "u",
                    body("merge", null, false, "x", "merged", "g/p", "b", "h"))
                .reason())
        .isEqualTo("merged");
    assertThat(
            parser
                .parse(
                    GitLabMergeRequestEventParser.EVENT_NAME,
                    "u",
                    body("close", null, false, "x", "closed", "g/p", "b", "h"))
                .reason())
        .isEqualTo("closed");
  }

  @Test
  void approvalEventsAreExplicitlyIgnored() {
    assertThat(
            parser
                .parse(
                    GitLabMergeRequestEventParser.EVENT_NAME,
                    "u",
                    body("approved", null, false, "x", "opened", "g/p", "b", "h"))
                .reason())
        .isEqualTo("approval_event");
    assertThat(
            parser
                .parse(
                    GitLabMergeRequestEventParser.EVENT_NAME,
                    "u",
                    body("unapproved", null, false, "x", "opened", "g/p", "b", "h"))
                .reason())
        .isEqualTo("approval_event");
  }

  @Test
  void draftByFlagOrTitleIsIgnored() {
    assertThat(
            parser
                .parse(
                    GitLabMergeRequestEventParser.EVENT_NAME,
                    "u",
                    body("open", null, true, "feat: x", "opened", "g/p", "b", "h"))
                .reason())
        .isEqualTo("draft");
    assertThat(
            parser
                .parse(
                    GitLabMergeRequestEventParser.EVENT_NAME,
                    "u",
                    body("open", null, false, "Draft: feat", "opened", "g/p", "b", "h"))
                .reason())
        .isEqualTo("draft");
    assertThat(
            parser
                .parse(
                    GitLabMergeRequestEventParser.EVENT_NAME,
                    "u",
                    body("open", null, false, "[WIP] feat", "opened", "g/p", "b", "h"))
                .reason())
        .isEqualTo("draft");
  }

  @Test
  void unsupportedEventAndObjectKindAreIgnored() {
    assertThat(parser.parse("Push Hook", "u", "{}".getBytes(StandardCharsets.UTF_8)).reason())
        .isEqualTo("unsupported_event");
    assertThat(
            parser
                .parse(
                    GitLabMergeRequestEventParser.EVENT_NAME,
                    "u",
                    """
                    {"object_kind":"push","object_attributes":{"action":"open"}}
                    """
                        .getBytes(StandardCharsets.UTF_8))
                .reason())
        .isEqualTo("unsupported_object_kind");
  }

  @Test
  void illegalJsonAndMissingFieldsAreBadPayload() {
    assertThatThrownBy(
            () ->
                parser.parse(
                    GitLabMergeRequestEventParser.EVENT_NAME,
                    "u",
                    "not-json".getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                parser.parse(
                    GitLabMergeRequestEventParser.EVENT_NAME,
                    "u",
                    """
                    {"object_kind":"merge_request","object_attributes":{"action":"open","iid":7},
                     "project":{"path_with_namespace":"g/p"}}
                    """
                        .getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("diff_refs");
    assertThatThrownBy(
            () ->
                parser.parse(
                    GitLabMergeRequestEventParser.EVENT_NAME,
                    "u",
                    """
                    {"object_kind":"merge_request",
                     "object_attributes":{"action":"open","iid":0,
                       "diff_refs":{"base_sha":"b","head_sha":"h"}},
                     "project":{"path_with_namespace":"g/p"}}
                    """
                        .getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("iid");
  }

  @Test
  void headShaFallsBackToLastCommitId() {
    GitLabMergeRequestEventParser.Result result =
        parser.parse(
            GitLabMergeRequestEventParser.EVENT_NAME,
            "u",
            """
            {"object_kind":"merge_request",
             "object_attributes":{"action":"open","iid":7,"title":"x","state":"opened",
               "diff_refs":{"base_sha":"base"},
               "last_commit":{"id":"last-sha"}},
             "project":{"path_with_namespace":"g/p"}}
            """
                .getBytes(StandardCharsets.UTF_8));
    assertThat(result.event().headSha()).isEqualTo("last-sha");
  }

  @Test
  void eventUuidIsIdempotencyKeyAndFallbackIsDeterministic() {
    byte[] body = body("open", null, false, "x", "opened", "g/p", "b", "h");
    assertThat(
            parser.parse(GitLabMergeRequestEventParser.EVENT_NAME, null, body).event().deliveryId())
        .isEqualTo(
            parser.parse(GitLabMergeRequestEventParser.EVENT_NAME, "", body).event().deliveryId())
        .startsWith("gitlab-")
        .hasSize(47);
    assertThat(
            parser
                .parse(GitLabMergeRequestEventParser.EVENT_NAME, "uuid-explicit", body)
                .event()
                .deliveryId())
        .isEqualTo("uuid-explicit");
  }

  private static byte[] body(
      String action,
      String oldrev,
      boolean draft,
      String title,
      String state,
      String project,
      String baseSha,
      String headSha) {
    return """
        {
          "object_kind": "merge_request",
          "object_attributes": {
            "action": "%s",
            "oldrev": %s,
            "draft": %s,
            "title": "%s",
            "state": "%s",
            "iid": 7,
            "diff_refs": {"base_sha": "%s", "head_sha": "%s", "start_sha": "start"}
          },
          "project": {"path_with_namespace": "%s"}
        }
        """
        .formatted(
            action,
            oldrev == null ? "null" : "\"" + oldrev + "\"",
            draft,
            title,
            state,
            baseSha,
            headSha,
            project)
        .getBytes(StandardCharsets.UTF_8);
  }
}
