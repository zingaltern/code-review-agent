package org.cra.git.github.internal;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
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
 * GitHub adapter 端点契约测试（WireMock stub 即端点契约文档，08§8.1；08§5 场景 5/17 的 adapter 侧）。 端点形状对照 GitHub REST
 * v3 官方文档核查（07 头部第二条腿）： compare / contents(raw) / git trees / pulls reviews / issues comments。
 */
class GitHubHttpAdapterWireMockTest {

  private static final RepoRef REPO = new RepoRef("o", "r");
  private static WireMockServer wm;
  private GitHubHttpAdapter adapter;
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
        new GitHubHttpAdapter(
            wm.baseUrl(), "test-token", HttpClient.newBuilder().build(), sleeps::add);
  }

  private static String reviewRequestBody() {
    return """
        {"files":[{"filename":"src/Foo.java","status":"modified",
          "patch":"@@ -10,7 +10,9 @@ context\\n old\\n+new1\\n+new2\\n old2"},
                 {"filename":"removed.txt","status":"removed","patch":null}],
         "commits":[{"sha":"c1","commit":{"message":"fix: something"}},
                    {"sha":"c2","commit":{"message":"fix: more"}}]}""";
  }

  @Test
  void fetchDiffMapsFilesAndHunks() {
    wm.stubFor(
        get(urlEqualTo("/repos/o/r/compare/base1...head2"))
            .willReturn(okJson(reviewRequestBody())));
    DiffResult diff = adapter.fetchDiff(REPO, "base1", "head2");
    assertThat(diff.files()).hasSize(2);
    DiffFile foo = diff.files().get(0);
    assertThat(foo.path()).isEqualTo("src/Foo.java");
    assertThat(foo.hunks()).hasSize(1);
    assertThat(foo.containsLine(Side.RIGHT, 10)).isTrue();
    assertThat(foo.containsLine(Side.RIGHT, 12)).isTrue();
    assertThat(foo.containsLine(Side.RIGHT, 18)).isTrue();
    assertThat(foo.containsLine(Side.RIGHT, 19)).isFalse();
    assertThat(diff.files().get(1).patch()).isNull();
    assertThat(diff.commits()).extracting("sha").containsExactly("c1", "c2");
  }

  @Test
  void readFileUsesRawAcceptAndDecodesText() {
    wm.stubFor(
        get(urlEqualTo("/repos/o/r/contents/src/Foo.java?ref=head2"))
            .withHeader("Accept", containing("vnd.github.raw"))
            .willReturn(aResponse().withStatus(200).withBody("line1\nline2\n")));
    FileContent content = adapter.readFile(REPO, "head2", "src/Foo.java");
    assertThat(content.text()).isEqualTo("line1\nline2\n");
    assertThat(content.binary()).isFalse();
  }

  @Test
  void readFileDetectsBinary() {
    wm.stubFor(
        get(urlEqualTo("/repos/o/r/contents/blob.bin?ref=head2"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withBody(new byte[] {(byte) 0xff, (byte) 0xfe, (byte) 0x00})));
    FileContent content = adapter.readFile(REPO, "head2", "blob.bin");
    assertThat(content.binary()).isTrue();
    assertThat(content.text()).isNull();
  }

  @Test
  void listFilesMatchesGlob() {
    wm.stubFor(
        get(urlEqualTo("/repos/o/r/git/trees/head2?recursive=1"))
            .willReturn(
                okJson(
                    """
            {"tree":[{"path":"src/main/java/A.java","type":"blob"},
                     {"path":"src/test/B.java","type":"blob"},
                     {"path":"docs/readme.md","type":"blob"},
                     {"path":"src/main","type":"tree"}]}""")));
    assertThat(adapter.listFiles(REPO, "head2", "src/**/*.java"))
        .containsExactly("src/main/java/A.java", "src/test/B.java");
    assertThat(adapter.listFiles(REPO, "head2", "docs/*")).containsExactly("docs/readme.md");
  }

  @Test
  void updateSummaryCommentPatchesIssueCommentsEndpoint() {
    // 07§2 验收 3（过期收尾端点契约，05§2.3 端点清单"Summary 发布/收尾编辑"行）：
    // PATCH /repos/{o}/{r}/issues/comments/{id}，载荷为编辑后的完整 body
    wm.stubFor(
        patch(urlEqualTo("/repos/o/r/issues/comments/9500"))
            .withRequestBody(matchingJsonPath("$.body", containing("[已被 run_x 取代]")))
            .willReturn(okJson("{\"id\":9500}")));
    adapter.updateSummaryComment(REPO, 7, 9500L, "[已被 run_x 取代]\n\n旧 Summary 正文");
    wm.verify(
        patchRequestedFor(urlEqualTo("/repos/o/r/issues/comments/9500"))
            .withRequestBody(matchingJsonPath("$.body", containing("旧 Summary 正文"))));
  }

  @Test
  void updateSummaryCommentSurfacesIntegrationFailure() {
    // 404（评论已删/无权限）不吞：上抛 CraIntegrationException，由调用方降级（收尾失败不回滚完成标记）
    wm.stubFor(
        patch(urlEqualTo("/repos/o/r/issues/comments/4040"))
            .willReturn(aResponse().withStatus(404).withBody("{\"message\":\"Not Found\"}")));
    assertThatThrownBy(() -> adapter.updateSummaryComment(REPO, 7, 4040L, "any"))
        .isInstanceOf(CraIntegrationException.class)
        .hasMessageContaining("404");
  }

  @Test
  void postReviewPostsInlineThenSummaryInOrder() {
    wm.stubFor(
        post(urlEqualTo("/repos/o/r/pulls/7/reviews"))
            .willReturn(
                okJson("""
            {"id":9001,"comments":[{"id":8001},{"id":8002}]}""")));
    wm.stubFor(
        post(urlEqualTo("/repos/o/r/issues/7/comments")).willReturn(okJson("{\"id\":9500}")));
    PrReviewResult result = adapter.postReview(command(2));
    assertThat(result.reviewId()).contains("9001");
    assertThat(result.summaryCommentId()).contains(9500L);
    assertThat(result.items()).allMatch(i -> i.publishStatus() == PublishStatus.PUBLISHED);
    assertThat(result.items())
        .extracting(ReviewItemResult::commentId)
        .containsExactly(8001L, 8002L);
    // 发布顺序（05§2.2 规则 3）：inline review 先于 Summary issue comment
    List<ServeEvent> events = wm.getAllServeEvents();
    java.util.Collections.reverse(events); // journal 倒序，还原成时间正序
    List<String> paths = events.stream().map(e -> e.getRequest().getUrl()).toList();
    assertThat(paths.indexOf("/repos/o/r/pulls/7/reviews"))
        .isLessThan(paths.indexOf("/repos/o/r/issues/7/comments"));
    // payload 契约：commit_id 绑定 head sha + event=COMMENT + side 字段
    wm.verify(
        postRequestedFor(urlEqualTo("/repos/o/r/pulls/7/reviews"))
            .withRequestBody(matchingJsonPath("$[?(@.commit_id == 'head2')]"))
            .withRequestBody(matchingJsonPath("$[?(@.event == 'COMMENT')]")));
  }

  @Test
  void postReviewEmptyInlineSkipsReviewAndOnlyPostsSummary() {
    wm.stubFor(
        post(urlEqualTo("/repos/o/r/issues/7/comments")).willReturn(okJson("{\"id\":9500}")));
    PostReviewCommand cmd =
        new PostReviewCommand("o", "r", 7, "head2", "body", List.of(), "summary");
    PrReviewResult result = adapter.postReview(cmd);
    assertThat(result.reviewId()).isEmpty();
    assertThat(result.summaryCommentId()).contains(9500L);
    wm.verify(0, postRequestedFor(urlEqualTo("/repos/o/r/pulls/7/reviews")));
  }

  @Test
  void unprocessableBundleFallsBackToPerItem() {
    // 整包 422 → 逐条提交：第一条成功，第二条仍 422 → SUMMARY_FALLBACK（08§5 场景 5）
    // scenario 状态机：整包 → 422；逐条 1 → 201；逐条 2 → 仍 422（SUMMARY_FALLBACK，08§5 场景 5）
    wm.stubFor(
        post(urlEqualTo("/repos/o/r/pulls/7/reviews"))
            .inScenario("fallback")
            .whenScenarioStateIs("Started")
            .willReturn(aResponse().withStatus(422).withBody("{\"message\":\"Validation Failed\"}"))
            .willSetStateTo("one"));
    wm.stubFor(
        post(urlEqualTo("/repos/o/r/pulls/7/reviews"))
            .inScenario("fallback")
            .whenScenarioStateIs("one")
            .willReturn(okJson("{\"id\":9002,\"comments\":[{\"id\":8100}]}"))
            .willSetStateTo("two"));
    wm.stubFor(
        post(urlEqualTo("/repos/o/r/pulls/7/reviews"))
            .inScenario("fallback")
            .whenScenarioStateIs("two")
            .willReturn(
                aResponse().withStatus(422).withBody("{\"message\":\"Validation Failed\"}")));
    wm.stubFor(
        post(urlEqualTo("/repos/o/r/issues/7/comments")).willReturn(okJson("{\"id\":9500}")));
    PrReviewResult result = adapter.postReview(command(2));
    assertThat(result.items())
        .extracting(ReviewItemResult::publishStatus)
        .containsExactly(PublishStatus.PUBLISHED, PublishStatus.SUMMARY_FALLBACK);
    assertThat(result.items().get(1).anchorStatus().name()).isEqualTo("SUMMARY_FALLBACK");
  }

  @Test
  void non422FailurePropagates() {
    wm.stubFor(
        post(urlEqualTo("/repos/o/r/pulls/7/reviews"))
            .willReturn(aResponse().withStatus(500).withBody("boom")));
    PostReviewCommand cmd = command(1);
    assertThatThrownBy(() -> adapter.postReview(cmd))
        .isInstanceOf(CraIntegrationException.class)
        .hasMessageContaining("GitHub API 500");
  }

  @Test
  void rateLimit429BacksOffThenSucceeds() {
    // 08§5 场景 17（GitHub 侧）：429 + Retry-After → 退避后成功（05§2.3 ≤3 次）
    wm.stubFor(
        get(urlEqualTo("/repos/o/r/compare/base1...head2"))
            .inScenario("limit")
            .whenScenarioStateIs("Started")
            .willReturn(
                aResponse().withStatus(429).withBody("rate limited").withHeader("Retry-After", "1"))
            .willSetStateTo("recovered"));
    wm.stubFor(
        get(urlEqualTo("/repos/o/r/compare/base1...head2"))
            .inScenario("limit")
            .whenScenarioStateIs("recovered")
            .willReturn(okJson(reviewRequestBody())));
    DiffResult diff = adapter.fetchDiff(REPO, "base1", "head2");
    assertThat(diff.files()).hasSize(2);
    assertThat(sleeps).containsExactly(1000L);
  }

  @Test
  void rateLimitExhaustedThrows() {
    wm.stubFor(
        get(urlEqualTo("/repos/o/r/compare/base1...head2"))
            .inScenario("exhaust")
            .whenScenarioStateIs("Started")
            .willReturn(
                aResponse()
                    .withStatus(429)
                    .withBody("rate limited")
                    .withHeader("Retry-After", "1")));
    assertThatThrownBy(() -> adapter.fetchDiff(REPO, "base1", "head2"))
        .isInstanceOf(CraIntegrationException.class);
    assertThat(sleeps).hasSize(3); // 最多 3 次退避（05§2.3）
  }

  @Test
  void fetchBotCommentsMergesEndpointsFiltersBotAndParses() {
    wm.stubFor(get(urlEqualTo("/user")).willReturn(okJson("{\"login\":\"cra-bot\"}")));
    wm.stubFor(
        get(urlEqualTo("/repos/o/r/pulls/7/comments?per_page=100&page=1"))
            .willReturn(
                okJson(
                    "[{\"id\":1,\"user\":{\"login\":\"cra-bot\"},"
                        + "\"body\":\"正文 src/Foo.java\\n<!-- cra:v=2.1 | fp=9f2a1b | file=src/Foo.java "
                        + "| cat=bug | id=f_1 | run=run_1 | commit=abc1234 | line=87 -->\","
                        + "\"created_at\":\"2026-10-25T00:00:00Z\",\"commit_id\":\"abc1234\"},"
                        + "{\"id\":2,\"user\":{\"login\":\"human\"},\"body\":\"human comment\"}]")));
    wm.stubFor(
        get(urlEqualTo("/repos/o/r/issues/7/comments?per_page=100&page=1"))
            .willReturn(
                okJson(
                    "[{\"id\":3,\"user\":{\"login\":\"cra-bot\"},"
                        + "\"body\":\"Summary\\n<!-- cra:v=2.1 | run=run_1 | commit=abc1234 | status=DONE "
                        + "| findings=1 | suppressed=0 -->\","
                        + "\"created_at\":\"2026-10-25T01:00:00Z\"},"
                        + "{\"id\":4,\"user\":{\"login\":\"cra-bot\"},"
                        + "\"body\":\"被污染 <!-- cra:v=2.1 -->\"}]")));
    CommentLedger ledger = adapter.fetchBotComments(REPO, 7);
    assertThat(ledger.findingMarkers()).hasSize(1);
    assertThat(ledger.findingMarkers().get(0).fingerprint()).isEqualTo("9f2a1b");
    assertThat(ledger.runMarkers()).hasSize(1);
    assertThat(ledger.runMarkers().get(0).status()).isEqualTo("DONE");
    assertThat(ledger.suspectCount()).isEqualTo(1); // 正文中间的伪造标记（05§1.3 规则 1）
    assertThat(ledger.comments()).hasSize(3); // 人类评论被过滤
  }

  @Test
  void botIdentityFallsBackToActionsBotWhenUserForbidden() {
    // Actions 集成 token 对 /user 恒 403（App 语义）——回退到 05§2.3 钉死的 CI bot 身份
    wm.stubFor(
        get(urlEqualTo("/user"))
            .willReturn(
                aResponse()
                    .withStatus(403)
                    .withBody("{\"message\":\"Resource not accessible by integration\"}")));
    wm.stubFor(
        get(urlEqualTo("/repos/o/r/pulls/7/comments?per_page=100&page=1"))
            .willReturn(
                okJson(
                    "[{\"id\":1,\"user\":{\"login\":\"github-actions[bot]\"},"
                        + "\"body\":\"正文 src/Foo.java\\n<!-- cra:v=2.1 | fp=9f2a1b | file=src/Foo.java "
                        + "| cat=bug | id=f_1 | run=run_1 | commit=abc1234 | line=87 -->\","
                        + "\"created_at\":\"2026-10-25T00:00:00Z\",\"commit_id\":\"abc1234\"}]")));
    wm.stubFor(
        get(urlEqualTo("/repos/o/r/issues/7/comments?per_page=100&page=1"))
            .willReturn(okJson("[]")));
    CommentLedger ledger = adapter.fetchBotComments(REPO, 7);
    assertThat(ledger.findingMarkers()).hasSize(1);
    assertThat(ledger.comments()).hasSize(1);
  }

  @Test
  void listPullRequestsFiltersWindowAndMapsMergeState() {
    wm.stubFor(
        get(urlEqualTo(
                "/repos/o/r/pulls?state=all&sort=updated&direction=desc&per_page=100&page=1"))
            .willReturn(
                okJson(
                    "[{\"number\":7,\"state\":\"closed\",\"head\":{\"sha\":\"h1\"},"
                        + "\"base\":{\"sha\":\"b1\"},"
                        + "\"merge_commit_sha\":\"m1\",\"updated_at\":\"2026-10-24T00:00:00Z\","
                        + "\"merged_at\":\"2026-10-24T00:00:00Z\"},"
                        + "{\"number\":6,\"state\":\"closed\",\"head\":{\"sha\":\"h0\"},"
                        + "\"base\":{\"sha\":\"b0\"},"
                        + "\"merge_commit_sha\":null,\"updated_at\":\"2026-10-01T00:00:00Z\","
                        + "\"merged_at\":null}]")));
    List<PullRequestRef> pulls =
        adapter.listPullRequests(REPO, Instant.parse("2026-10-18T00:00:00Z"));
    assertThat(pulls).hasSize(1);
    assertThat(pulls.get(0).number()).isEqualTo(7);
    assertThat(pulls.get(0).headSha()).isEqualTo("h1");
    assertThat(pulls.get(0).baseSha()).isEqualTo("b1");
    assertThat(pulls.get(0).mergeCommitSha()).isEqualTo("m1");
    assertThat(pulls.get(0).merged()).isTrue();
  }

  @Test
  void fetchDispositionSignalsReadsBotRepliesAndSummaryReactions() {
    wm.stubFor(get(urlEqualTo("/user")).willReturn(okJson("{\"login\":\"cra-bot\"}")));
    wm.stubFor(
        get(urlEqualTo("/repos/o/r/pulls/7/comments?per_page=100&page=1"))
            .willReturn(
                okJson(
                    "[{\"id\":100,\"user\":{\"login\":\"cra-bot\"},\"body\":\"finding\","
                        + "\"created_at\":\"2026-10-20T00:00:00Z\",\"commit_id\":\"abc\"},"
                        + "{\"id\":101,\"user\":{\"login\":\"human\"},"
                        + "\"body\":\"this is :wrong: not-a-bug\","
                        + "\"created_at\":\"2026-10-21T00:00:00Z\",\"in_reply_to_id\":100},"
                        + "{\"id\":102,\"user\":{\"login\":\"human\"},\"body\":\"thanks, will fix\","
                        + "\"created_at\":\"2026-10-21T01:00:00Z\",\"in_reply_to_id\":100},"
                        + "{\"id\":103,\"user\":{\"login\":\"human\"},\"body\":\"human to human\","
                        + "\"created_at\":\"2026-10-21T02:00:00Z\",\"in_reply_to_id\":101}]")));
    wm.stubFor(
        get(urlEqualTo("/repos/o/r/issues/7/comments?per_page=100&page=1"))
            .willReturn(
                okJson(
                    "[{\"id\":200,\"user\":{\"login\":\"cra-bot\"},\"body\":\"summary\","
                        + "\"created_at\":\"2026-10-20T00:00:00Z\"},"
                        + "{\"id\":201,\"user\":{\"login\":\"human\"},\"body\":\"hi\","
                        + "\"created_at\":\"2026-10-20T01:00:00Z\"}]")));
    wm.stubFor(
        get(urlEqualTo("/repos/o/r/issues/comments/200/reactions?per_page=100&page=1"))
            .willReturn(
                okJson(
                    "[{\"content\":\"+1\",\"user\":{\"login\":\"bob\"},"
                        + "\"created_at\":\"2026-10-22T00:00:00Z\"},"
                        + "{\"content\":\"heart\",\"user\":{\"login\":\"eve\"},"
                        + "\"created_at\":\"2026-10-22T01:00:00Z\"}]")));
    DispositionSignal[] signals = adapter.fetchDispositionSignals(REPO, 7);
    assertThat(signals).hasSize(2);
    DispositionSignal reply =
        java.util.Arrays.stream(signals)
            .filter(s -> DispositionSignal.KIND_REPLY_TAG.equals(s.kind()))
            .findFirst()
            .orElseThrow();
    assertThat(reply.commentId()).isEqualTo(100);
    assertThat(reply.detail()).isEqualTo(DispositionSignal.TAG_WRONG);
    assertThat(reply.actor()).isEqualTo("human");
    assertThat(reply.excerpt()).contains(":wrong:");
    DispositionSignal reaction =
        java.util.Arrays.stream(signals)
            .filter(s -> DispositionSignal.KIND_REACTION.equals(s.kind()))
            .findFirst()
            .orElseThrow();
    assertThat(reaction.commentId()).isEqualTo(200);
    assertThat(reaction.detail()).isEqualTo(DispositionSignal.REACTION_UP);
  }

  // ---------- M4c/M4d 载体端点（05§2.4/§2.5） ----------

  @Test
  void postCommitReviewPostsToCommitCommentsEndpoint() {
    // push 直推载体：POST /repos/{o}/{r}/commits/{sha}/comments，正文含 run 完成标记
    wm.stubFor(
        post(urlEqualTo("/repos/o/r/commits/abc1234/comments"))
            .withRequestBody(matchingJsonPath("$.body", containing("status=DONE")))
            .willReturn(okJson("{\"id\":7777}")));
    java.util.Optional<Long> id =
        adapter.postCommitReview(
            new PostCommitReviewCommand(
                "o",
                "r",
                "abc1234",
                """
                ## cra 评审 Summary

                <!-- cra:v=2.1 | run=run_1 | commit=abc1234 | status=DONE | findings=0 | suppressed=0 -->"""));
    assertThat(id).contains(7777L);
  }

  @Test
  void postIssueReviewCreatesIssueCarrier() {
    // 夜间存量载体：POST /repos/{o}/{r}/issues，标题带 [cra-nightly] 前缀，返回 issue 编号
    wm.stubFor(
        post(urlEqualTo("/repos/o/r/issues"))
            .withRequestBody(matchingJsonPath("$.title", containing("[cra-nightly] PR #7")))
            .withRequestBody(matchingJsonPath("$.body", containing("status=DONE")))
            .withRequestBody(matchingJsonPath("$[?(@.labels[0] == 'cra-nightly')]"))
            .willReturn(okJson("{\"number\":15,\"id\":15888}")));
    java.util.Optional<Long> number =
        adapter.postIssueReview(
            new PostIssueCommand(
                "o",
                "r",
                "[cra-nightly] PR #7 abc1234 存量评审",
                """
                ## cra 评审 Summary

                <!-- cra:v=2.1 | run=run_1 | commit=abc1234 | status=DONE | findings=0 | suppressed=0 -->""",
                List.of("cra-nightly")));
    assertThat(number).contains(15L);
  }

  @Test
  void fetchCommitBotCommentsParsesCommitScopedLedger() {
    wm.stubFor(get(urlEqualTo("/user")).willReturn(okJson("{\"login\":\"cra-bot\"}")));
    wm.stubFor(
        get(urlEqualTo("/repos/o/r/commits/abc1234/comments?per_page=100&page=1"))
            .willReturn(
                okJson(
                    "[{\"id\":31,\"user\":{\"login\":\"cra-bot\"},"
                        + "\"body\":\"Summary\\n<!-- cra:v=2.1 | run=run_1 | commit=abc1234 "
                        + "| status=DONE | findings=2 | suppressed=0 -->\","
                        + "\"created_at\":\"2026-10-26T00:00:00Z\",\"commit_id\":\"abc1234\"},"
                        + "{\"id\":32,\"user\":{\"login\":\"human\"},\"body\":\"hi\"}]")));
    CommentLedger ledger = adapter.fetchCommitBotComments(REPO, "abc1234");
    assertThat(ledger.runMarkers()).hasSize(1);
    assertThat(ledger.runMarkers().get(0).commentId()).isEqualTo(31L);
    assertThat(ledger.comments()).hasSize(1);
    assertThat(ledger.comments().get(0).kind()).isEqualTo(LedgerComment.KIND_COMMIT);
  }

  @Test
  void fetchIssueBotCommentsFiltersByCommitAndSkipsPullRequests() {
    wm.stubFor(get(urlEqualTo("/user")).willReturn(okJson("{\"login\":\"github-actions[bot]\"}")));
    wm.stubFor(
        get(urlEqualTo(
                "/repos/o/r/issues?state=all&sort=updated&direction=desc&per_page=100&page=1"))
            .willReturn(
                okJson(
                    "[{\"number\":15,\"user\":{\"login\":\"github-actions[bot]\"},"
                        + "\"body\":\"## 夜间存量评审\\n<!-- cra:v=2.1 | run=run_1 | commit=abc1234 "
                        + "| status=DONE | findings=1 | suppressed=0 -->\","
                        + "\"created_at\":\"2026-10-26T00:00:00Z\"},"
                        + "{\"number\":16,\"user\":{\"login\":\"github-actions[bot]\"},"
                        + "\"body\":\"## 夜间存量评审\\n<!-- cra:v=2.1 | run=run_2 | commit=def5678 "
                        + "| status=DONE | findings=0 | suppressed=0 -->\","
                        + "\"created_at\":\"2026-10-26T01:00:00Z\"},"
                        + "{\"number\":7,\"user\":{\"login\":\"github-actions[bot]\"},"
                        + "\"pull_request\":{\"url\":\"x\"},"
                        + "\"body\":\"PR 条目不是 issue 载体\\n<!-- cra:v=2.1 | run=run_3 "
                        + "| commit=abc1234 | status=DONE | findings=0 | suppressed=0 -->\","
                        + "\"created_at\":\"2026-10-26T02:00:00Z\"},"
                        + "{\"number\":17,\"user\":{\"login\":\"human\"},"
                        + "\"body\":\"human issue\",\"created_at\":\"2026-10-26T03:00:00Z\"}]")));
    CommentLedger ledger = adapter.fetchIssueBotComments(REPO, "abc1234");
    assertThat(ledger.runMarkers()).hasSize(1);
    assertThat(ledger.runMarkers().get(0).runId()).isEqualTo("run_1");
    assertThat(ledger.comments()).hasSize(1);
    assertThat(ledger.comments().get(0).id()).isEqualTo(15L);
  }

  @Test
  void listBotIssuesExcludesPullRequestsAndHumans() {
    wm.stubFor(get(urlEqualTo("/user")).willReturn(okJson("{\"login\":\"cra-bot\"}")));
    wm.stubFor(
        get(urlEqualTo(
                "/repos/o/r/issues?state=all&sort=updated&direction=desc&per_page=100&page=1"))
            .willReturn(
                okJson(
                    "[{\"number\":15,\"user\":{\"login\":\"cra-bot\"},\"body\":\"issue-bot\","
                        + "\"created_at\":\"2026-10-26T00:00:00Z\"},"
                        + "{\"number\":7,\"user\":{\"login\":\"cra-bot\"},"
                        + "\"pull_request\":{\"url\":\"x\"},\"body\":\"pr-as-issue\","
                        + "\"created_at\":\"2026-10-26T01:00:00Z\"},"
                        + "{\"number\":17,\"user\":{\"login\":\"human\"},\"body\":\"human\","
                        + "\"created_at\":\"2026-10-26T02:00:00Z\"}]")));
    List<LedgerComment> issues = adapter.listBotIssues(REPO, null);
    assertThat(issues).hasSize(1);
    assertThat(issues.get(0).id()).isEqualTo(15L);
    assertThat(issues.get(0).body()).isEqualTo("issue-bot");
  }

  @Test
  void fetchBotCommentsAllowsExactlyTenThousandAndProbesNextPage() {
    wm.stubFor(get(urlEqualTo("/user")).willReturn(okJson("{\"login\":\"cra-bot\"}")));
    for (int page = 1; page <= 100; page++) {
      wm.stubFor(
          get(urlEqualTo("/repos/o/r/pulls/7/comments?per_page=100&page=" + page))
              .willReturn(okJson(commentPage(page))));
    }
    wm.stubFor(
        get(urlEqualTo("/repos/o/r/pulls/7/comments?per_page=100&page=101"))
            .willReturn(okJson("[]")));
    wm.stubFor(
        get(urlEqualTo("/repos/o/r/issues/7/comments?per_page=100&page=1"))
            .willReturn(okJson("[]")));

    CommentLedger ledger = adapter.fetchBotComments(REPO, 7);
    // 恰好 100 页满页（10k）→ 第 101 页为空必须正常返回，不得触顶误报
    assertThat(ledger.comments()).hasSize(10_000);
  }

  @Test
  void fetchBotCommentsRejectsOverflowPastProbePage() {
    wm.stubFor(get(urlEqualTo("/user")).willReturn(okJson("{\"login\":\"cra-bot\"}")));
    for (int page = 1; page <= 100; page++) {
      wm.stubFor(
          get(urlEqualTo("/repos/o/r/pulls/7/comments?per_page=100&page=" + page))
              .willReturn(okJson(commentPage(page))));
    }
    wm.stubFor(
        get(urlEqualTo("/repos/o/r/pulls/7/comments?per_page=100&page=101"))
            .willReturn(okJson(commentPage(101))));

    assertThatThrownBy(() -> adapter.fetchBotComments(REPO, 7))
        .isInstanceOf(CraIntegrationException.class)
        .hasMessageContaining("评论分页超过上限");
  }

  @Test
  void encodePathPercentEncodesSpacesNotPlus() {
    assertThat(GitHubPayloadSupport.encodePath("src/main/My File.java"))
        .isEqualTo("src/main/My%20File.java");
    assertThat(GitHubPayloadSupport.encodePath("a+b/c d.java")).isEqualTo("a%2Bb/c%20d.java");
  }

  @Test
  void readFileWithSpaceInPathUsesEncodedUrl() {
    wm.stubFor(
        get(urlEqualTo("/repos/o/r/contents/src/My%20File.java?ref=head2"))
            .willReturn(aResponse().withStatus(200).withBody("ok")));
    assertThat(adapter.readFile(REPO, "head2", "src/My File.java").text()).isEqualTo("ok");
  }

  @Test
  void dispositionPagingOverflowFailsLoudly() {
    wm.stubFor(get(urlEqualTo("/user")).willReturn(okJson("{\"login\":\"cra-bot\"}")));
    for (int page = 1; page <= 100; page++) {
      wm.stubFor(
          get(urlEqualTo("/repos/o/r/pulls/7/comments?per_page=100&page=" + page))
              .willReturn(okJson(commentPage(page))));
    }
    wm.stubFor(
        get(urlEqualTo("/repos/o/r/pulls/7/comments?per_page=100&page=101"))
            .willReturn(okJson(commentPage(101))));

    assertThatThrownBy(() -> adapter.fetchDispositionSignals(REPO, 7))
        .isInstanceOf(CraIntegrationException.class)
        .hasMessageContaining("分页超过上限");
  }

  @Test
  void malformedCreatedAtDoesNotCrashLedger() {
    wm.stubFor(get(urlEqualTo("/user")).willReturn(okJson("{\"login\":\"cra-bot\"}")));
    wm.stubFor(
        get(urlEqualTo("/repos/o/r/pulls/7/comments?per_page=100&page=1"))
            .willReturn(
                okJson(
                    "[{\"id\":1,\"user\":{\"login\":\"cra-bot\"},\"body\":\"c\","
                        + "\"created_at\":\"not-a-timestamp\"}]")));
    wm.stubFor(
        get(urlEqualTo("/repos/o/r/issues/7/comments?per_page=100&page=1"))
            .willReturn(okJson("[]")));

    CommentLedger ledger = adapter.fetchBotComments(REPO, 7);

    assertThat(ledger.comments()).hasSize(1);
    assertThat(ledger.comments().get(0).createdAt()).isEqualTo(Instant.EPOCH);
  }

  @Test
  void listPullRequestsAcceptsNullSince() {
    wm.stubFor(
        get(urlEqualTo(
                "/repos/o/r/pulls?state=all&sort=updated&direction=desc&per_page=100&page=1"))
            .willReturn(
                okJson(
                    "[{\"number\":7,\"state\":\"closed\",\"head\":{\"sha\":\"h1\"},"
                        + "\"base\":{\"sha\":\"b1\"},\"merge_commit_sha\":null,"
                        + "\"updated_at\":\"2026-10-24T00:00:00Z\"}]")));
    assertThat(adapter.listPullRequests(REPO, null)).hasSize(1);
  }

  @Test
  void perItemAuthFailurePropagatesInsteadOfSummaryFallback() {
    wm.stubFor(
        post(urlEqualTo("/repos/o/r/pulls/7/reviews"))
            .inScenario("auth")
            .whenScenarioStateIs("Started")
            .willReturn(aResponse().withStatus(422).withBody("{\"message\":\"Validation Failed\"}"))
            .willSetStateTo("item"));
    wm.stubFor(
        post(urlEqualTo("/repos/o/r/pulls/7/reviews"))
            .inScenario("auth")
            .whenScenarioStateIs("item")
            .willReturn(aResponse().withStatus(401).withBody("{\"message\":\"Bad credentials\"}")));

    PostReviewCommand secondAttempt = command(1);
    assertThatThrownBy(() -> adapter.postReview(secondAttempt))
        .isInstanceOf(CraIntegrationException.class)
        .hasMessageContaining("401");
  }

  @Test
  void postReviewWithoutReviewIdReturnsEmptyReviewId() {
    wm.stubFor(
        post(urlEqualTo("/repos/o/r/pulls/7/reviews"))
            .willReturn(okJson("{\"id\":null,\"comments\":[{\"id\":8001}]}")));
    wm.stubFor(
        post(urlEqualTo("/repos/o/r/issues/7/comments")).willReturn(okJson("{\"id\":9500}")));

    PrReviewResult result = adapter.postReview(command(1));

    assertThat(result.reviewId()).isEmpty();
    assertThat(result.items()).hasSize(1);
  }

  @Test
  void retryAfterIsCapped() {
    wm.stubFor(
        get(urlEqualTo("/repos/o/r/compare/base1...head2"))
            .inScenario("huge-retry-after")
            .whenScenarioStateIs("Started")
            .willReturn(
                aResponse()
                    .withStatus(429)
                    .withBody("rate limited")
                    .withHeader("Retry-After", "999999999"))
            .willSetStateTo("recovered"));
    wm.stubFor(
        get(urlEqualTo("/repos/o/r/compare/base1...head2"))
            .inScenario("huge-retry-after")
            .whenScenarioStateIs("recovered")
            .willReturn(okJson(reviewRequestBody())));

    adapter.fetchDiff(REPO, "base1", "head2");

    assertThat(sleeps).containsExactly(60_000L);
  }

  @Test
  void listPullRequestsFallsBackToEpochForMissingUpdatedAt() {
    wm.stubFor(
        get(urlEqualTo(
                "/repos/o/r/pulls?state=all&sort=updated&direction=desc&per_page=100&page=1"))
            .willReturn(
                okJson(
                    "[{\"number\":7,\"state\":\"closed\",\"head\":{\"sha\":\"h1\"},"
                        + "\"base\":{\"sha\":\"b1\"},\"merge_commit_sha\":null}]")));

    assertThat(adapter.listPullRequests(REPO, null))
        .singleElement()
        .satisfies(pr -> assertThat(pr.updatedAt()).isEqualTo(Instant.EPOCH));
  }

  @Test
  void listPullRequestsAllowsExactlyTenThousandAndProbesNextPage() {
    for (int page = 1; page <= 100; page++) {
      wm.stubFor(
          get(urlEqualTo(
                  "/repos/o/r/pulls?state=all&sort=updated&direction=desc&per_page=100&page="
                      + page))
              .willReturn(okJson(pullPage(page))));
    }
    wm.stubFor(
        get(urlEqualTo(
                "/repos/o/r/pulls?state=all&sort=updated&direction=desc&per_page=100&page=101"))
            .willReturn(okJson("[]")));

    assertThat(adapter.listPullRequests(REPO, null)).hasSize(10_000);
  }

  private static String pullPage(int page) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < 100; i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append("{\"number\":")
          .append(page * 1_000 + i)
          .append(",\"state\":\"closed\",\"head\":{\"sha\":\"h\"},")
          .append("\"base\":{\"sha\":\"b\"},\"merge_commit_sha\":null,")
          .append("\"updated_at\":\"2026-10-24T00:00:00Z\"}");
    }
    return sb.append(']').toString();
  }

  private static String commentPage(int page) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < 100; i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append("{\"id\":")
          .append(page * 1_000L + i)
          .append(",\"user\":{\"login\":\"cra-bot\"},\"body\":\"c\",")
          .append("\"created_at\":\"2026-10-25T00:00:00Z\"}");
    }
    return sb.append(']').toString();
  }

  private static PostReviewCommand command(int count) {
    List<InlineComment> inline = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      inline.add(
          new InlineComment(
              "src/Foo.java",
              10 + i,
              Side.RIGHT,
              "finding "
                  + i
                  + " src/Foo.java\n<!-- cra:v=2.1 | fp=aa0"
                  + i
                  + " | file=src/Foo.java | cat=bug | id=f_0"
                  + i
                  + " | run=run_1 | commit=abc1234 "
                  + "| line="
                  + (10 + i)
                  + " -->",
              "f_0" + i));
    }
    return new PostReviewCommand("o", "r", 7, "head2", "cra: 引导语", inline, "summary");
  }

  private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder okJson(
      String body) {
    return aResponse()
        .withStatus(200)
        .withHeader("Content-Type", "application/json")
        .withBody(body);
  }
}
