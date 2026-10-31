package org.cra.git.gitlab.internal;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.cra.common.exception.CraIntegrationException;
import org.cra.common.finding.Side;
import org.cra.common.git.CommentLedger;
import org.cra.common.git.DiffFile;
import org.cra.common.git.DiffResult;
import org.cra.common.git.DispositionSignal;
import org.cra.common.git.FileContent;
import org.cra.common.git.InlineComment;
import org.cra.common.git.LedgerComment;
import org.cra.common.git.PostCommitReviewCommand;
import org.cra.common.git.PostIssueCommand;
import org.cra.common.git.PostReviewCommand;
import org.cra.common.git.PrReviewResult;
import org.cra.common.git.PublishStatus;
import org.cra.common.git.PullRequestRef;
import org.cra.common.git.RepoRef;
import org.cra.common.git.ReviewItemResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * GitLab adapter 端点契约测试（WireMock stub 即端点契约文档，08§8.1；ADR-0011 端点映射）。
 *
 * <p>端点形状对照 GitLab REST v4 官方文档核查：compare / repository files(raw) / repository tree / merge request
 * discussions / merge request notes / versions / commit comments / issues / emoji reactions。 项目路径按
 * GitLab 要求整体编码为 group%2Fproj。
 */
class GitLabHttpAdapterWireMockTest {

  private static final RepoRef REPO = new RepoRef("group", "proj");
  private static final String PROJECT = "/projects/group%2Fproj";
  private static final String HEAD = "abc1234";
  private static final String MARKER_BODY =
      "正文 src/Foo.java:87\n<!-- cra:v=2.1 | fp=9f2a1b | file=src/Foo.java | cat=bug"
          + " | id=f_1 | run=run_1 | commit=abc1234 | line=87 -->";
  private static final String SUMMARY_BODY =
      "Summary\n<!-- cra:v=2.1 | run=run_1 | commit=abc1234 | status=DONE"
          + " | findings=1 | suppressed=0 -->";
  private static WireMockServer wm;
  private GitLabHttpAdapter adapter;
  private final List<Long> sleeps = new ArrayList<>();

  @BeforeAll
  static void startWireMock() {
    wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    wm.start();
  }

  @AfterAll
  static void stopWireMock() {
    wm.stop();
  }

  @BeforeEach
  void setUp() {
    wm.resetAll();
    sleeps.clear();
    adapter =
        new GitLabHttpAdapter(
            wm.baseUrl(), "test-token", HttpClient.newBuilder().build(), sleeps::add, "cra-bot");
  }

  private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder okJson(
      String body) {
    return aResponse()
        .withStatus(200)
        .withHeader("Content-Type", "application/json")
        .withBody(body);
  }

  private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder createdJson(
      String body) {
    return aResponse()
        .withStatus(201)
        .withHeader("Content-Type", "application/json")
        .withBody(body);
  }

  /** 把含真实换行的正文嵌入 JSON 字符串字面量（WireMock 响应必须是合法 JSON）。 */
  private static String jsonEscape(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
  }

  private static PostReviewCommand command(int inlineCount) {
    List<InlineComment> inline = new ArrayList<>();
    for (int i = 1; i <= inlineCount; i++) {
      inline.add(new InlineComment("src/Foo.java", 87 + i, Side.RIGHT, "body " + i, "f_" + i));
    }
    return new PostReviewCommand("group", "proj", 7, HEAD, "引导语", inline, SUMMARY_BODY);
  }

  private static String compareBody() {
    return """
        {"compare_timeout":false,
         "diffs":[
           {"old_path":"src/Foo.java","new_path":"src/Foo.java","new_file":false,
            "renamed_file":false,"deleted_file":false,
            "diff":"@@ -10,7 +10,9 @@ ctx\\n old\\n+new1\\n+new2\\n old2"},
           {"old_path":"old.txt","new_path":"new.txt","renamed_file":true,
            "diff":"@@ -1 +1 @@\\n-a\\n+b"},
           {"old_path":"bin.dat","new_path":"bin.dat","too_large":true}],
         "commits":[{"id":"c1","title":"fix: something"},{"id":"c2","message":"fallback"}]}""";
  }

  @Test
  void fetchDiffMapsDiffsHunksAndCommits() {
    wm.stubFor(
        get(urlEqualTo(PROJECT + "/repository/compare?from=base1&to=head2"))
            .willReturn(okJson(compareBody())));
    DiffResult diff = adapter.fetchDiff(REPO, "base1", "head2");
    assertThat(diff.files()).hasSize(3);
    DiffFile foo = diff.files().get(0);
    assertThat(foo.path()).isEqualTo("src/Foo.java");
    assertThat(foo.status()).isEqualTo("modified");
    assertThat(foo.hunks()).hasSize(1);
    assertThat(foo.containsLine(Side.RIGHT, 10)).isTrue();
    assertThat(foo.containsLine(Side.RIGHT, 12)).isTrue();
    assertThat(foo.containsLine(Side.RIGHT, 19)).isFalse();
    DiffFile renamed = diff.files().get(1);
    assertThat(renamed.status()).isEqualTo("renamed");
    assertThat(renamed.previousPath()).isEqualTo("old.txt");
    assertThat(renamed.path()).isEqualTo("new.txt");
    assertThat(diff.files().get(2).patch()).isNull(); // too_large → 无 patch（不伪造）
    assertThat(diff.commits()).extracting("sha").containsExactly("c1", "c2");
    assertThat(diff.commits().get(0).message()).isEqualTo("fix: something");
    assertThat(diff.commits().get(1).message()).isEqualTo("fallback");
  }

  @Test
  void fetchDiffRefusesPartialResultWhenCompareTimesOut() {
    // GitLab 文档：超时的 comparison 可能只返回部分 diffs——静默评审会产生假的覆盖声明
    wm.stubFor(
        get(urlEqualTo(PROJECT + "/repository/compare?from=base1&to=head2"))
            .willReturn(okJson("{\"compare_timeout\":true,\"diffs\":[],\"commits\":[]}")));
    assertThatThrownBy(() -> adapter.fetchDiff(REPO, "base1", "head2"))
        .isInstanceOf(CraIntegrationException.class)
        .hasMessageContaining("compare timed out");
  }

  @Test
  void readFileUsesRawEndpointWithFullyEncodedPath() {
    wm.stubFor(
        get(urlEqualTo(PROJECT + "/repository/files/src%2FFoo.java/raw?ref=head2"))
            .withHeader("PRIVATE-TOKEN", equalTo("test-token"))
            .willReturn(aResponse().withStatus(200).withBody("line1\nline2\n")));
    FileContent content = adapter.readFile(REPO, "head2", "src/Foo.java");
    assertThat(content.text()).isEqualTo("line1\nline2\n");
    assertThat(content.binary()).isFalse();
  }

  @Test
  void readFileDetectsBinary() {
    wm.stubFor(
        get(urlEqualTo(PROJECT + "/repository/files/blob.bin/raw?ref=head2"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withBody(new byte[] {(byte) 0xff, (byte) 0xfe, (byte) 0x00})));
    FileContent content = adapter.readFile(REPO, "head2", "blob.bin");
    assertThat(content.binary()).isTrue();
    assertThat(content.text()).isNull();
  }

  @Test
  void listFilesMatchesGlobAndFollowsNextPageHeader() {
    wm.stubFor(
        get(urlEqualTo(PROJECT + "/repository/tree?ref=head2&recursive=true&per_page=100&page=1"))
            .willReturn(
                okJson(
                        """
                [{"path":"src/main/java/A.java","type":"blob"},
                 {"path":"src/main","type":"tree"}]""")
                    .withHeader("X-Next-Page", "2")));
    wm.stubFor(
        get(urlEqualTo(PROJECT + "/repository/tree?ref=head2&recursive=true&per_page=100&page=2"))
            .willReturn(
                okJson(
                    """
                [{"path":"src/test/java/B.java","type":"blob"},
                 {"path":"docs/readme.md","type":"blob"}]""")));
    assertThat(adapter.listFiles(REPO, "head2", "src/**/*.java"))
        .containsExactly("src/main/java/A.java", "src/test/java/B.java");
    assertThat(adapter.listFiles(REPO, "head2", "docs/*")).containsExactly("docs/readme.md");
  }

  @Test
  void postReviewCreatesDiscussionsThenSummaryWithPositionTriple() {
    wm.stubFor(
        get(urlEqualTo(PROJECT + "/merge_requests/7"))
            .willReturn(
                okJson(
                    """
                {"diff_refs":{"base_sha":"base1","head_sha":"abc1234","start_sha":"start1"}}""")));
    wm.stubFor(
        post(urlEqualTo(PROJECT + "/merge_requests/7/discussions"))
            .inScenario("notes")
            .whenScenarioStateIs("Started")
            .willReturn(createdJson("{\"id\":\"d1\",\"notes\":[{\"id\":8001}]}"))
            .willSetStateTo("second"));
    wm.stubFor(
        post(urlEqualTo(PROJECT + "/merge_requests/7/discussions"))
            .inScenario("notes")
            .whenScenarioStateIs("second")
            .willReturn(createdJson("{\"id\":\"d2\",\"notes\":[{\"id\":8002}]}")));
    wm.stubFor(
        post(urlEqualTo(PROJECT + "/merge_requests/7/notes"))
            .willReturn(createdJson("{\"id\":9500}")));

    PrReviewResult result = adapter.postReview(command(2));
    assertThat(result.reviewId()).isEmpty(); // GitLab 无 review 聚合对象（ADR-0011 决策 5）
    assertThat(result.summaryCommentId()).contains(9500L);
    assertThat(result.items())
        .extracting(ReviewItemResult::publishStatus)
        .containsExactly(PublishStatus.PUBLISHED, PublishStatus.PUBLISHED);
    assertThat(result.items())
        .extracting(ReviewItemResult::commentId)
        .containsExactly(8001L, 8002L);

    List<ServeEvent> events = wm.getAllServeEvents();
    java.util.Collections.reverse(events);
    List<String> paths = events.stream().map(e -> e.getRequest().getUrl()).toList();
    assertThat(paths.indexOf(PROJECT + "/merge_requests/7/discussions"))
        .isLessThan(paths.indexOf(PROJECT + "/merge_requests/7/notes"));

    wm.verify(
        postRequestedFor(urlEqualTo(PROJECT + "/merge_requests/7/discussions"))
            .withRequestBody(matchingJsonPath("$.position.base_sha", equalTo("base1")))
            .withRequestBody(matchingJsonPath("$.position.head_sha", equalTo(HEAD)))
            .withRequestBody(matchingJsonPath("$.position.start_sha", equalTo("start1")))
            .withRequestBody(matchingJsonPath("$.position.position_type", equalTo("text")))
            .withRequestBody(matchingJsonPath("$.position.new_path", equalTo("src/Foo.java")))
            .withRequestBody(matchingJsonPath("$.position.new_line", equalTo("88"))));
  }

  @Test
  void postReviewUsesHistoricVersionWhenReviewedHeadIsNotCurrentMrHead() {
    wm.stubFor(
        get(urlEqualTo(PROJECT + "/merge_requests/7"))
            .willReturn(
                okJson(
                    """
                {"diff_refs":{"base_sha":"curbase","head_sha":"currenth","start_sha":"curstart"}}""")));
    wm.stubFor(
        get(urlEqualTo(PROJECT + "/merge_requests/7/versions"))
            .willReturn(
                okJson(
                    """
                [{"base_commit_sha":"oldbase","head_commit_sha":"abc1234","start_commit_sha":"oldstart"},
                 {"base_commit_sha":"curbase","head_commit_sha":"currenth","start_commit_sha":"curstart"}]""")));
    wm.stubFor(
        post(urlEqualTo(PROJECT + "/merge_requests/7/discussions"))
            .willReturn(createdJson("{\"notes\":[{\"id\":8001}]}")));
    wm.stubFor(
        post(urlEqualTo(PROJECT + "/merge_requests/7/notes"))
            .willReturn(createdJson("{\"id\":9500}")));
    adapter.postReview(command(1));
    wm.verify(
        postRequestedFor(urlEqualTo(PROJECT + "/merge_requests/7/discussions"))
            .withRequestBody(matchingJsonPath("$.position.base_sha", equalTo("oldbase")))
            .withRequestBody(matchingJsonPath("$.position.head_sha", equalTo(HEAD)))
            .withRequestBody(matchingJsonPath("$.position.start_sha", equalTo("oldstart"))));
  }

  @Test
  void invalidPositionFallsBackToSummaryAndStillPostsSummary() {
    wm.stubFor(
        get(urlEqualTo(PROJECT + "/merge_requests/7"))
            .willReturn(
                okJson(
                    """
                {"diff_refs":{"base_sha":"base1","head_sha":"abc1234","start_sha":"start1"}}""")));
    wm.stubFor(
        post(urlEqualTo(PROJECT + "/merge_requests/7/discussions"))
            .willReturn(
                aResponse()
                    .withStatus(400)
                    .withBody("{\"message\":\"Note position is invalid\"}")));
    wm.stubFor(
        post(urlEqualTo(PROJECT + "/merge_requests/7/notes"))
            .willReturn(createdJson("{\"id\":9500}")));
    PrReviewResult result = adapter.postReview(command(2));
    assertThat(result.items())
        .extracting(ReviewItemResult::publishStatus)
        .containsExactly(PublishStatus.SUMMARY_FALLBACK, PublishStatus.SUMMARY_FALLBACK);
    assertThat(result.items().get(0).anchorStatus().name()).isEqualTo("SUMMARY_FALLBACK");
    assertThat(result.summaryCommentId()).contains(9500L);
  }

  @Test
  void nonAnchorFailurePropagates() {
    wm.stubFor(
        get(urlEqualTo(PROJECT + "/merge_requests/7"))
            .willReturn(
                okJson(
                    """
                {"diff_refs":{"base_sha":"base1","head_sha":"abc1234","start_sha":"start1"}}""")));
    wm.stubFor(
        post(urlEqualTo(PROJECT + "/merge_requests/7/discussions"))
            .willReturn(aResponse().withStatus(500).withBody("boom")));
    PostReviewCommand cmd = command(1);
    assertThatThrownBy(() -> adapter.postReview(cmd))
        .isInstanceOf(CraIntegrationException.class)
        .hasMessageContaining("GitLab API 500");
  }

  @Test
  void updateSummaryCommentPutsMergeRequestNote() {
    wm.stubFor(
        put(urlEqualTo(PROJECT + "/merge_requests/7/notes/9500"))
            .willReturn(okJson("{\"id\":9500}")));
    adapter.updateSummaryComment(REPO, 7, 9500L, "[已被 run_x 取代]\n\n旧 Summary");
    wm.verify(
        putRequestedFor(urlEqualTo(PROJECT + "/merge_requests/7/notes/9500"))
            .withRequestBody(matchingJsonPath("$.body", containing("旧 Summary"))));
  }

  @Test
  void updateSummaryCommentSurfacesIntegrationFailure() {
    wm.stubFor(
        put(urlEqualTo(PROJECT + "/merge_requests/7/notes/4040"))
            .willReturn(aResponse().withStatus(404).withBody("{\"message\":\"Not Found\"}")));
    assertThatThrownBy(() -> adapter.updateSummaryComment(REPO, 7, 4040L, "any"))
        .isInstanceOf(CraIntegrationException.class)
        .hasMessageContaining("404");
  }

  @Test
  void fetchBotCommentsMergesDiscussionNotesFiltersBotAndParsesMarkers() {
    wm.stubFor(get(urlEqualTo("/user")).willReturn(okJson("{\"username\":\"cra-bot\"}")));
    String discussionsBody =
        """
        [{"id":"d1","individual_note":false,"notes":[
           {"id":1,"system":false,"author":{"username":"cra-bot"},
            "position":{"head_sha":"abc1234","new_path":"src/Foo.java","new_line":87},
            "body":"%s","created_at":"2026-10-25T00:00:00Z"},
           {"id":2,"system":false,"author":{"username":"human"},"body":"human reply"}]},
         {"id":"d2","individual_note":true,"notes":[
           {"id":3,"system":false,"author":{"username":"cra-bot"},
            "body":"%s","created_at":"2026-10-25T01:00:00Z"}]},
         {"id":"d3","individual_note":true,"notes":[
           {"id":4,"system":true,"author":{"username":"cra-bot"},"body":"system event"}]},
         {"id":"d4","individual_note":true,"notes":[
           {"id":5,"system":false,"author":{"username":"cra-bot"},
            "body":"被污染 <!-- cra:v=2.1 -->"}]}]"""
            .formatted(jsonEscape(MARKER_BODY), jsonEscape(SUMMARY_BODY));
    wm.stubFor(
        get(urlEqualTo(PROJECT + "/merge_requests/7/discussions?per_page=100&page=1"))
            .willReturn(okJson(discussionsBody)));

    CommentLedger ledger = adapter.fetchBotComments(REPO, 7);
    assertThat(ledger.findingMarkers()).hasSize(1);
    assertThat(ledger.findingMarkers().get(0).fingerprint()).isEqualTo("9f2a1b");
    assertThat(ledger.runMarkers()).hasSize(1);
    assertThat(ledger.runMarkers().get(0).status()).isEqualTo("DONE");
    assertThat(ledger.suspectCount()).isEqualTo(1); // 正文中间的伪造标记（05§1.3 规则 1）
    assertThat(ledger.comments()).hasSize(3); // 人类与 system note 被过滤
    assertThat(
            ledger.comments().stream()
                .filter(c -> LedgerComment.KIND_INLINE.equals(c.kind()))
                .findFirst()
                .orElseThrow()
                .commitId())
        .isEqualTo(HEAD);
  }

  @Test
  void botIdentityUsesConfiguredFallbackWhenUserIsUnauthorized() {
    wm.stubFor(
        get(urlEqualTo("/user"))
            .willReturn(
                aResponse().withStatus(401).withBody("{\"message\":\"401 Unauthorized\"}")));
    wm.stubFor(
        get(urlEqualTo(PROJECT + "/merge_requests/7/discussions?per_page=100&page=1"))
            .willReturn(
                okJson(
                    """
                [{"id":"d1","notes":[{"id":3,"system":false,
                  "author":{"username":"cra-bot"},"body":"%s"}]}]"""
                        .formatted(jsonEscape(SUMMARY_BODY)))));
    assertThat(adapter.fetchBotComments(REPO, 7).runMarkers()).hasSize(1);
  }

  @Test
  void botIdentityWithoutFallbackFailsLoudly() {
    GitLabHttpAdapter noFallback =
        new GitLabHttpAdapter(
            wm.baseUrl(), "test-token", HttpClient.newBuilder().build(), sleeps::add, null);
    wm.stubFor(
        get(urlEqualTo("/user"))
            .willReturn(
                aResponse().withStatus(401).withBody("{\"message\":\"401 Unauthorized\"}")));
    assertThatThrownBy(() -> noFallback.fetchBotComments(REPO, 7))
        .isInstanceOf(CraIntegrationException.class)
        .hasMessageContaining("bot identity unavailable");
  }

  @Test
  void listPullRequestsFiltersWindowMapsStateAndFillsBaseFromSingleMr() {
    wm.stubFor(
        get(urlEqualTo(
                PROJECT
                    + "/merge_requests?state=all&order_by=updated_at&sort=desc"
                    + "&per_page=100&page=1"))
            .willReturn(
                okJson(
                    """
                [{"iid":1,"state":"opened","sha":"h1","updated_at":"2026-10-27T00:00:00Z"},
                 {"iid":2,"state":"merged","sha":"h2","updated_at":"2026-10-26T00:00:00Z",
                  "merge_commit_sha":"m2","merged_at":"2026-10-26T01:00:00Z",
                  "diff_refs":{"base_sha":"b2","head_sha":"h2","start_sha":"s2"}},
                 {"iid":3,"state":"closed","sha":"h3","updated_at":"2026-10-01T00:00:00Z"}]""")));
    wm.stubFor(
        get(urlEqualTo(PROJECT + "/merge_requests/1"))
            .willReturn(
                okJson(
                    """
                {"iid":1,"state":"opened","sha":"h1",
                 "diff_refs":{"base_sha":"b1","head_sha":"h1","start_sha":"s1"}}""")));
    List<PullRequestRef> pulls =
        adapter.listPullRequests(REPO, Instant.parse("2026-10-20T00:00:00Z"));
    assertThat(pulls).hasSize(2);
    assertThat(pulls.get(0).number()).isEqualTo(1);
    assertThat(pulls.get(0).state()).isEqualTo(PullRequestRef.STATE_OPEN);
    assertThat(pulls.get(0).baseSha()).isEqualTo("b1");
    assertThat(pulls.get(1).number()).isEqualTo(2);
    assertThat(pulls.get(1).merged()).isTrue();
    assertThat(pulls.get(1).mergeCommitSha()).isEqualTo("m2");
    assertThat(pulls.get(1).baseSha()).isEqualTo("b2");
  }

  @Test
  void fetchDispositionSignalsMapsRepliesAndSummaryReactions() {
    wm.stubFor(get(urlEqualTo("/user")).willReturn(okJson("{\"username\":\"cra-bot\"}")));
    wm.stubFor(
        get(urlEqualTo(PROJECT + "/merge_requests/7/discussions?per_page=100&page=1"))
            .willReturn(
                okJson(
                    """
                [{"id":"d1","notes":[
                   {"id":10,"system":false,"author":{"username":"cra-bot"},
                    "position":{"head_sha":"abc1234"},"body":"finding","created_at":"2026-10-25T00:00:00Z"},
                   {"id":11,"system":false,"author":{"username":"bob"},
                    "body":"this is :wrong:","created_at":"2026-10-25T01:00:00Z"},
                   {"id":12,"system":false,"author":{"username":"bob"},
                    "body":"no tag here","created_at":"2026-10-25T02:00:00Z"}]},
                 {"id":"d2","notes":[
                   {"id":20,"system":false,"author":{"username":"alice"},"body":"human-led"}]},
                 {"id":"d3","notes":[
                   {"id":30,"system":false,"author":{"username":"cra-bot"},
                    "body":"summary","created_at":"2026-10-25T03:00:00Z"}]}]""")));
    wm.stubFor(
        get(urlEqualTo(PROJECT + "/merge_requests/7/notes/30/award_emoji?per_page=100&page=1"))
            .willReturn(
                okJson(
                    """
                [{"name":"thumbsup","user":{"username":"alice"},"created_at":"2026-10-25T04:00:00Z"},
                 {"name":"thumbsdown","user":{"username":"bob"},"created_at":"2026-10-25T05:00:00Z"},
                 {"name":"rocket","user":{"username":"carol"},"created_at":"2026-10-25T06:00:00Z"}]""")));
    DispositionSignal[] signals = adapter.fetchDispositionSignals(REPO, 7);
    assertThat(signals).hasSize(3);
    assertThat(signals[0].kind()).isEqualTo(DispositionSignal.KIND_REPLY_TAG);
    assertThat(signals[0].commentId()).isEqualTo(10);
    assertThat(signals[0].detail()).isEqualTo(DispositionSignal.TAG_WRONG);
    assertThat(signals[0].actor()).isEqualTo("bob");
    assertThat(signals[1].kind()).isEqualTo(DispositionSignal.KIND_REACTION);
    assertThat(signals[1].commentId()).isEqualTo(30);
    assertThat(signals[1].detail()).isEqualTo(DispositionSignal.REACTION_UP);
    assertThat(signals[2].detail()).isEqualTo(DispositionSignal.REACTION_DOWN);
  }

  @Test
  void postCommitReviewReturnsStableSyntheticIdAndLedgerMatches() {
    wm.stubFor(get(urlEqualTo("/user")).willReturn(okJson("{\"username\":\"cra-bot\"}")));
    wm.stubFor(
        post(urlEqualTo(PROJECT + "/repository/commits/" + HEAD + "/comments"))
            .willReturn(createdJson("{\"note\":\"stored\"}")));
    long id =
        adapter
            .postCommitReview(new PostCommitReviewCommand("group", "proj", HEAD, SUMMARY_BODY))
            .orElseThrow();
    assertThat(id).isPositive();
    wm.stubFor(
        get(urlEqualTo(PROJECT + "/repository/commits/" + HEAD + "/comments?per_page=100&page=1"))
            .willReturn(
                okJson(
                    """
                [{"note":"%s","author":{"username":"cra-bot"},
                  "created_at":"2026-10-25T00:00:00Z"}]"""
                        .formatted(jsonEscape(SUMMARY_BODY)))));
    CommentLedger ledger = adapter.fetchCommitBotComments(REPO, HEAD);
    assertThat(ledger.runMarkers()).hasSize(1);
    assertThat(ledger.runMarkers().get(0).commentId()).isEqualTo(id);
    assertThat(ledger.runMarkers().get(0).isCompletion()).isTrue();
  }

  @Test
  void postIssueReviewUsesDescriptionAndCommaLabelsAndLedgerFiltersByCommit() {
    wm.stubFor(get(urlEqualTo("/user")).willReturn(okJson("{\"username\":\"cra-bot\"}")));
    wm.stubFor(
        post(urlEqualTo(PROJECT + "/issues"))
            .withRequestBody(matchingJsonPath("$.title", containing("[cra-nightly]")))
            .withRequestBody(matchingJsonPath("$.description", containing("Summary")))
            .withRequestBody(matchingJsonPath("$.labels", equalTo("cra-nightly,extra")))
            .willReturn(createdJson("{\"iid\":42,\"id\":4242}")));
    assertThat(
            adapter.postIssueReview(
                new PostIssueCommand(
                    "group",
                    "proj",
                    "[cra-nightly] PR #7 abc1234",
                    SUMMARY_BODY,
                    List.of("cra-nightly", "extra"))))
        .contains(42L);
    String issuesBody =
        """
        [{"iid":42,"state":"opened","author":{"username":"cra-bot"},
          "description":"%s","created_at":"2026-10-27T00:00:00Z",
          "updated_at":"2026-10-27T00:00:00Z"},
         {"iid":43,"state":"opened","author":{"username":"cra-bot"},
          "description":"<!-- cra:v=2.1 | run=run_2 | commit=def5678 | status=DONE | findings=0 | suppressed=0 -->","created_at":"2026-10-27T00:00:00Z",
          "updated_at":"2026-10-27T00:00:00Z"},
         {"iid":44,"state":"opened","author":{"username":"human"},
          "description":"human issue","created_at":"2026-10-27T00:00:00Z",
          "updated_at":"2026-10-27T00:00:00Z"}]"""
            .formatted(jsonEscape(SUMMARY_BODY));
    wm.stubFor(
        get(urlEqualTo(
                PROJECT
                    + "/issues?state=all&order_by=updated_at&sort=desc&author_username=cra-bot"
                    + "&per_page=100&page=1"))
            .willReturn(okJson(issuesBody)));
    CommentLedger ledger = adapter.fetchIssueBotComments(REPO, HEAD);
    assertThat(ledger.runMarkers()).hasSize(1);
    assertThat(ledger.runMarkers().get(0).commentId()).isEqualTo(42L);
    assertThat(adapter.listBotIssues(REPO, null)).hasSize(2);
  }

  @Test
  void listBotIssuesUsesUpdatedAfterWindow() {
    wm.stubFor(get(urlEqualTo("/user")).willReturn(okJson("{\"username\":\"cra-bot\"}")));
    wm.stubFor(
        get(urlEqualTo(
                PROJECT
                    + "/issues?state=all&order_by=updated_at&sort=desc&author_username=cra-bot"
                    + "&updated_after=2026-10-20T00%3A00%3A00Z&per_page=100&page=1"))
            .willReturn(
                okJson(
                    """
                [{"iid":42,"author":{"username":"cra-bot"},"description":"d",
                  "created_at":"2026-10-27T00:00:00Z","updated_at":"2026-10-27T00:00:00Z"},
                 {"iid":41,"author":{"username":"cra-bot"},"description":"d",
                  "created_at":"2026-10-01T00:00:00Z","updated_at":"2026-10-01T00:00:00Z"}]""")));
    List<LedgerComment> issues = adapter.listBotIssues(REPO, Instant.parse("2026-10-20T00:00:00Z"));
    assertThat(issues).hasSize(1);
    assertThat(issues.get(0).id()).isEqualTo(42L);
  }

  @Test
  void pageIncrementTreatsNonNumericPageAsNoNextPage() {
    // 异常分页头（非数字/超 int 范围）按无下一页处理，不抛 NumberFormatException
    assertThat(GitLabCommentReader.incrementPageOrNull("2")).isEqualTo("3");
    assertThat(GitLabCommentReader.incrementPageOrNull("abc")).isNull();
    assertThat(GitLabCommentReader.incrementPageOrNull("99999999999")).isNull();
  }

  @Test
  void rateLimit429BacksOffThenSucceeds() {
    wm.stubFor(
        get(urlEqualTo(PROJECT + "/repository/compare?from=base1&to=head2"))
            .inScenario("limit")
            .whenScenarioStateIs("Started")
            .willReturn(
                aResponse().withStatus(429).withBody("Retry later").withHeader("Retry-After", "1"))
            .willSetStateTo("recovered"));
    wm.stubFor(
        get(urlEqualTo(PROJECT + "/repository/compare?from=base1&to=head2"))
            .inScenario("limit")
            .whenScenarioStateIs("recovered")
            .willReturn(okJson(compareBody())));
    assertThat(adapter.fetchDiff(REPO, "base1", "head2").files()).hasSize(3);
    assertThat(sleeps).containsExactly(1000L);
  }

  @Test
  void rateLimitExhaustedThrows() {
    wm.stubFor(
        get(urlEqualTo(PROJECT + "/repository/compare?from=base1&to=head2"))
            .willReturn(
                aResponse()
                    .withStatus(429)
                    .withBody("Retry later")
                    .withHeader("Retry-After", "1")));
    assertThatThrownBy(() -> adapter.fetchDiff(REPO, "base1", "head2"))
        .isInstanceOf(CraIntegrationException.class);
    assertThat(sleeps).hasSize(3);
  }

  @Test
  void readPathsAreWriteFreeForDryRun() {
    // 05§6 dry-run 全程零写：adapter 的读路径只允许 GET（发布动作由 core 的 Publisher 在非 dry-run 才调用）
    wm.stubFor(get(urlEqualTo("/user")).willReturn(okJson("{\"username\":\"cra-bot\"}")));
    wm.stubFor(
        get(urlEqualTo(PROJECT + "/repository/compare?from=base1&to=head2"))
            .willReturn(okJson(compareBody())));
    wm.stubFor(
        get(urlEqualTo(PROJECT + "/repository/files/blob.bin/raw?ref=head2"))
            .willReturn(aResponse().withStatus(200).withBody("text")));
    wm.stubFor(
        get(urlEqualTo(PROJECT + "/repository/tree?ref=head2&recursive=true&per_page=100&page=1"))
            .willReturn(okJson("[]")));
    wm.stubFor(
        get(urlEqualTo(PROJECT + "/merge_requests/7/discussions?per_page=100&page=1"))
            .willReturn(okJson("[]")));
    wm.stubFor(
        get(urlEqualTo(PROJECT + "/merge_requests/7/notes/30/award_emoji?per_page=100&page=1"))
            .willReturn(okJson("[]")));
    wm.stubFor(
        get(urlEqualTo(
                PROJECT
                    + "/merge_requests?state=all&order_by=updated_at&sort=desc"
                    + "&per_page=100&page=1"))
            .willReturn(okJson("[]")));
    wm.stubFor(
        get(urlEqualTo(
                PROJECT
                    + "/issues?state=all&order_by=updated_at&sort=desc&author_username=cra-bot"
                    + "&per_page=100&page=1"))
            .willReturn(okJson("[]")));
    wm.stubFor(
        get(urlEqualTo(PROJECT + "/repository/commits/" + HEAD + "/comments?per_page=100&page=1"))
            .willReturn(okJson("[]")));

    adapter.fetchDiff(REPO, "base1", "head2");
    adapter.readFile(REPO, "head2", "blob.bin");
    adapter.listFiles(REPO, "head2", "**/*.java");
    adapter.fetchBotComments(REPO, 7);
    adapter.fetchDispositionSignals(REPO, 7);
    adapter.listPullRequests(REPO, Instant.parse("2026-10-20T00:00:00Z"));
    adapter.listBotIssues(REPO, null);
    adapter.fetchCommitBotComments(REPO, HEAD);

    wm.verify(0, postRequestedFor(anyUrl()));
    wm.verify(0, putRequestedFor(anyUrl()));
    wm.verify(0, patchRequestedFor(anyUrl()));
  }

  @Test
  void listFilesAllowsExactlyPaginationLimitAndRejectsOverflow() {
    // 边界：前 100 页各 100 条（10k），第 101 页为空 → 正常返回；
    // 第 101 页仍有数据 → 显式失败（不静默截断）
    for (int page = 1; page <= 100; page++) {
      wm.stubFor(
          get(urlEqualTo(
                  PROJECT + "/repository/tree?ref=head9&recursive=true&per_page=100&page=" + page))
              .willReturn(
                  okJson(treePage(page)).withHeader("X-Next-Page", String.valueOf(page + 1))));
    }
    wm.stubFor(
        get(urlEqualTo(PROJECT + "/repository/tree?ref=head9&recursive=true&per_page=100&page=101"))
            .willReturn(okJson("[]")));
    assertThat(adapter.listFiles(REPO, "head9", "**")).hasSize(10_000);

    wm.resetAll();
    for (int page = 1; page <= 100; page++) {
      wm.stubFor(
          get(urlEqualTo(
                  PROJECT + "/repository/tree?ref=head10&recursive=true&per_page=100&page=" + page))
              .willReturn(
                  okJson(treePage(page)).withHeader("X-Next-Page", String.valueOf(page + 1))));
    }
    // 第 101 页只有 1 条且无 next 头：仍属超过上限，必须失败（不得把越限数据当完整结果）
    wm.stubFor(
        get(urlEqualTo(
                PROJECT + "/repository/tree?ref=head10&recursive=true&per_page=100&page=101"))
            .willReturn(okJson("[{\"path\":\"p101_0.java\",\"type\":\"blob\"}]")));
    assertThatThrownBy(() -> adapter.listFiles(REPO, "head10", "**"))
        .isInstanceOf(CraIntegrationException.class)
        .hasMessageContaining("分页超过上限");
  }

  @Test
  void listPullRequestsOverflowFailsLoudly() {
    wm.stubFor(get(urlEqualTo("/user")).willReturn(okJson("{\"username\":\"cra-bot\"}")));
    for (int page = 1; page <= 100; page++) {
      wm.stubFor(
          get(urlEqualTo(
                  PROJECT
                      + "/merge_requests?state=all&order_by=updated_at&sort=desc"
                      + "&per_page=100&page="
                      + page))
              .willReturn(
                  okJson(mrPage(page)).withHeader("X-Next-Page", String.valueOf(page + 1))));
    }
    wm.stubFor(
        get(urlEqualTo(
                PROJECT
                    + "/merge_requests?state=all&order_by=updated_at&sort=desc"
                    + "&per_page=100&page=101"))
            .willReturn(okJson(mrPage(101))));

    assertThatThrownBy(() -> adapter.listPullRequests(REPO, null))
        .isInstanceOf(CraIntegrationException.class)
        .hasMessageContaining("分页超过上限");
  }

  @Test
  void listBotIssuesOverflowFailsLoudly() {
    wm.stubFor(get(urlEqualTo("/user")).willReturn(okJson("{\"username\":\"cra-bot\"}")));
    for (int page = 1; page <= 100; page++) {
      wm.stubFor(
          get(urlEqualTo(
                  PROJECT
                      + "/issues?state=all&order_by=updated_at&sort=desc&author_username=cra-bot"
                      + "&per_page=100&page="
                      + page))
              .willReturn(
                  okJson(issuePage(page)).withHeader("X-Next-Page", String.valueOf(page + 1))));
    }
    wm.stubFor(
        get(urlEqualTo(
                PROJECT
                    + "/issues?state=all&order_by=updated_at&sort=desc&author_username=cra-bot"
                    + "&per_page=100&page=101"))
            .willReturn(okJson(issuePage(101))));

    assertThatThrownBy(() -> adapter.listBotIssues(REPO, null))
        .isInstanceOf(CraIntegrationException.class)
        .hasMessageContaining("分页超过上限");
  }

  @Test
  void retryAfterIsCapped() {
    wm.stubFor(
        get(urlEqualTo(PROJECT + "/repository/compare?from=base1&to=head2"))
            .inScenario("huge-retry-after")
            .whenScenarioStateIs("Started")
            .willReturn(
                aResponse()
                    .withStatus(429)
                    .withBody("Retry later")
                    .withHeader("Retry-After", "999999999"))
            .willSetStateTo("recovered"));
    wm.stubFor(
        get(urlEqualTo(PROJECT + "/repository/compare?from=base1&to=head2"))
            .inScenario("huge-retry-after")
            .whenScenarioStateIs("recovered")
            .willReturn(okJson(compareBody())));

    adapter.fetchDiff(REPO, "base1", "head2");

    assertThat(sleeps).containsExactly(60_000L);
  }

  @Test
  void listPullRequestsAllowsExactlyTenThousandAndProbesNextPage() {
    wm.stubFor(get(urlEqualTo("/user")).willReturn(okJson("{\"username\":\"cra-bot\"}")));
    for (int page = 1; page <= 100; page++) {
      wm.stubFor(
          get(urlEqualTo(
                  PROJECT
                      + "/merge_requests?state=all&order_by=updated_at&sort=desc"
                      + "&per_page=100&page="
                      + page))
              .willReturn(
                  okJson(mrPage(page)).withHeader("X-Next-Page", String.valueOf(page + 1))));
    }
    wm.stubFor(
        get(urlEqualTo(
                PROJECT
                    + "/merge_requests?state=all&order_by=updated_at&sort=desc"
                    + "&per_page=100&page=101"))
            .willReturn(okJson("[]")));

    assertThat(adapter.listPullRequests(REPO, null)).hasSize(10_000);
  }

  private static String mrPage(int page) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < 100; i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append("{\"iid\":")
          .append(page * 1_000L + i)
          .append(",\"state\":\"opened\",\"sha\":\"h\",")
          .append("\"diff_refs\":{\"base_sha\":\"b\",\"head_sha\":\"h\",")
          .append("\"start_sha\":\"s\"},\"updated_at\":\"2026-10-27T00:00:00Z\"}");
    }
    return sb.append(']').toString();
  }

  private static String issuePage(int page) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < 100; i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append("{\"iid\":")
          .append(page * 1_000L + i)
          .append(",\"author\":{\"username\":\"cra-bot\"},\"description\":\"d\",")
          .append("\"created_at\":\"2026-10-27T00:00:00Z\",")
          .append("\"updated_at\":\"2026-10-27T00:00:00Z\"}");
    }
    return sb.append(']').toString();
  }

  private static String treePage(int page) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < 100; i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append("{\"path\":\"p")
          .append(page)
          .append("_")
          .append(i)
          .append(".java\",\"type\":\"blob\"}");
    }
    return sb.append(']').toString();
  }
}
