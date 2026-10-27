package org.cra.git.gitlab.internal;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.cra.common.finding.Side;
import org.cra.common.git.DiffResult;
import org.cra.common.git.InlineComment;
import org.cra.common.git.LedgerRunMarker;
import org.cra.common.git.PostReviewCommand;
import org.cra.common.git.PrReviewResult;
import org.cra.common.git.RepoRef;
import org.cra.common.git.ReviewKey;
import org.cra.git.gitlab.api.GitLabAssembly;
import org.cra.git.gitlab.api.GitLabComponents;
import org.cra.store.api.ReviewLedger;
import org.cra.store.api.StoreAssembly;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * GitLab fixture 端到端（11§6 DoD）：录制回放一次 MR 评审的发布与同 commit 幂等重放。
 *
 * <p>fixture = `src/test/resources/fixtures/gitlab/` 下按 GitLab REST v4 响应形状录制的数据；网络层用 WireMock
 * 回放（LLM/Git 调用不进单测，07§6.3）。账本走与 core 相同的 {@link ReviewLedger} 读侧（StoreAssembly 的 CommentSource
 * 账本实现），因此"账本命中、零重复"用的是真实闸语义而不是测试专用复刻。
 */
class GitLabFixtureEndToEndTest {

  private static final RepoRef REPO = new RepoRef("group", "proj");
  private static final String PROJECT = "/projects/group%2Fproj";
  private static final String BASE = "basesha1";
  private static final String HEAD = "abc1234";
  private static final String INLINE_BODY =
      "**[Important] 演示 finding** — `src/main/java/Foo.java:2`\n\n"
          + "演示 fixture 的 finding 正文。\n\n"
          + "<!-- cra:v=2.1 | fp=9f2a1b | file=src/main/java/Foo.java | cat=bug | id=f_fixture"
          + " | run=run_fixture | commit="
          + HEAD
          + " | line=2 -->";
  private static final String SUMMARY_BODY =
      "## 评审摘要\n\n- 1 条重要问题\n\n"
          + "<!-- cra:v=2.1 | run=run_fixture | commit="
          + HEAD
          + " | status=DONE"
          + " | findings=1 | suppressed=0 -->";

  private static WireMockServer wm;

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
  void setUp() throws IOException {
    wm.resetAll();
    wm.stubFor(get(urlEqualTo("/user")).willReturn(okJson("{\"username\":\"cra-bot\"}")));
    wm.stubFor(
        get(urlEqualTo(PROJECT + "/repository/compare?from=" + BASE + "&to=" + HEAD))
            .willReturn(okJson(resource("fixtures/gitlab/compare.json"))));
    wm.stubFor(
        get(urlEqualTo(PROJECT + "/merge_requests/7"))
            .willReturn(okJson(resource("fixtures/gitlab/mr.json"))));
    wm.stubFor(
        post(urlEqualTo(PROJECT + "/merge_requests/7/discussions"))
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"id\":\"d-inline-1\",\"notes\":[{\"id\":8001}]}")));
    // 场景回放真实时序：发布前账本为空；Summary note 发布成功后，账本才出现 DONE 标记（05§1.3）
    wm.stubFor(
        get(urlEqualTo(PROJECT + "/merge_requests/7/discussions?per_page=100&page=1"))
            .inScenario("publish")
            .whenScenarioStateIs("Started")
            .willReturn(okJson("[]")));
    wm.stubFor(
        get(urlEqualTo(PROJECT + "/merge_requests/7/discussions?per_page=100&page=1"))
            .inScenario("publish")
            .whenScenarioStateIs("published")
            .willReturn(
                okJson(
                    resource("fixtures/gitlab/discussions-after-publish.json")
                        .replace("__INLINE_BODY__", jsonEscape(INLINE_BODY))
                        .replace("__SUMMARY_BODY__", jsonEscape(SUMMARY_BODY)))));
    wm.stubFor(
        post(urlEqualTo(PROJECT + "/merge_requests/7/notes"))
            .inScenario("publish")
            .whenScenarioStateIs("Started")
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"id\":9500}"))
            .willSetStateTo("published"));
  }

  @Test
  void publishThenReplaySameCommitHitsLedgerAndDoesNotRepublish() {
    GitLabComponents components = GitLabAssembly.createComponents(wm.baseUrl(), "token", "cra-bot");
    ReviewLedger ledger = StoreAssembly.createGitHubLedger(components.commentSource(), REPO);
    ReviewKey key = ReviewKey.pullRequest("group", "proj", 7, HEAD);

    assertThat(reviewOnce(components, ledger, key)).isTrue();

    Optional<LedgerRunMarker> completion = ledger.findCompletion(key);
    assertThat(completion).isPresent();
    assertThat(completion.orElseThrow().status()).isEqualTo(LedgerRunMarker.STATUS_DONE);
    assertThat(completion.orElseThrow().findings()).isEqualTo(1);
    assertThat(ledger.publishedFindings(key)).hasSize(1);
    assertThat(ledger.publishedFindings(key).get(0).fingerprint()).isEqualTo("9f2a1b");
    assertThat(ledger.suspectMarkerCount(key)).isZero();

    int discussionsAfterFirst =
        wm.findAll(postRequestedFor(urlEqualTo(PROJECT + "/merge_requests/7/discussions"))).size();
    int summariesAfterFirst =
        wm.findAll(postRequestedFor(urlEqualTo(PROJECT + "/merge_requests/7/notes"))).size();
    assertThat(discussionsAfterFirst).isEqualTo(1);
    assertThat(summariesAfterFirst).isEqualTo(1);

    // 同 commit 重放：账本命中 → 不抓 diff、不重发任何评论（05§1.3 规则 5 的幂等闸语义）
    assertThat(reviewOnce(components, ledger, key)).isFalse();
    wm.verify(
        1,
        getRequestedFor(urlEqualTo(PROJECT + "/repository/compare?from=" + BASE + "&to=" + HEAD)));
    wm.verify(1, postRequestedFor(urlEqualTo(PROJECT + "/merge_requests/7/discussions")));
    wm.verify(1, postRequestedFor(urlEqualTo(PROJECT + "/merge_requests/7/notes")));
    assertThat(ledger.findCompletion(key)).isPresent();
  }

  private static boolean reviewOnce(
      GitLabComponents components, ReviewLedger ledger, ReviewKey key) {
    if (ledger.findCompletion(key).isPresent()) {
      return false; // 02§4.1 第一道闸：同 commit 已完整发布 → 全量跳过
    }
    DiffResult diff = components.adapter().fetchDiff(REPO, BASE, HEAD);
    assertThat(diff.files()).hasSize(1);
    PostReviewCommand cmd =
        new PostReviewCommand(
            "group",
            "proj",
            7,
            HEAD,
            "发现 1 条问题，详见本 MR 的评审 Summary 评论",
            List.of(
                new InlineComment(
                    "src/main/java/Foo.java", 2, Side.RIGHT, INLINE_BODY, "f_fixture")),
            SUMMARY_BODY);
    PrReviewResult result = components.adapter().postReview(cmd);
    assertThat(result.items()).hasSize(1);
    assertThat(result.summaryCommentId()).isPresent();
    return true;
  }

  private static String resource(String path) throws IOException {
    try (InputStream in = GitLabFixtureEndToEndTest.class.getResourceAsStream("/" + path)) {
      if (in == null) {
        throw new IOException("missing test resource: " + path);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static String jsonEscape(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
  }

  private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder okJson(
      String body) {
    return aResponse()
        .withStatus(200)
        .withHeader("Content-Type", "application/json")
        .withBody(body);
  }
}
