package org.cra.runner.cli;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.cra.common.finding.Side;
import org.cra.common.git.DiffResult;
import org.cra.common.git.InlineComment;
import org.cra.common.git.LedgerRunMarker;
import org.cra.common.git.PostReviewCommand;
import org.cra.common.git.PrReviewResult;
import org.cra.common.git.RepoRef;
import org.cra.common.git.ReviewKey;
import org.cra.store.api.ReviewLedger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * CLI GitLab 平台接线（M4e 第二半，ADR-0013）：`--platform gitlab` → AppConfig 装配 GitLab adapter +
 * CommentSource → 评论账本；发布后同 commit 重放命中 DONE 闸、零重复。
 *
 * <p>WireMock 回放 GitLab REST v4（Git/LLM 调用不进单测，07§6.3）；装配根与真实运行相同，避免"适配器单测绿、组合根仍接 GitHub"的假接线。
 */
class GitLabPlatformWiringTest {

  private static final RepoRef REPO = new RepoRef("group", "proj");
  private static final String PROJECT = "/api/v4/projects/group%2Fproj";
  private static final String BASE = "basesha1";
  private static final String HEAD = "abc1234";
  private static final String INLINE_BODY =
      "**[Important] 演示 finding** — `src/main/java/Foo.java:2`\n\n"
          + "演示接线测试的 finding 正文。\n\n"
          + "<!-- cra:v=2.1 | fp=9f2a1b | file=src/main/java/Foo.java | cat=bug | id=f_wiring"
          + " | run=run_wiring | commit="
          + HEAD
          + " | line=2 -->";
  private static final String SUMMARY_BODY =
      "## 评审摘要\n\n- 1 条重要问题\n\n"
          + "<!-- cra:v=2.1 | run=run_wiring | commit="
          + HEAD
          + " | status=DONE"
          + " | findings=1 | suppressed=0 -->";

  @TempDir Path runRoot;

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
  void setUp() {
    wm.resetAll();
    wm.stubFor(get(urlEqualTo("/api/v4/user")).willReturn(okJson("{\"username\":\"cra-bot\"}")));
    wm.stubFor(
        get(urlEqualTo(PROJECT + "/repository/compare?from=" + BASE + "&to=" + HEAD))
            .willReturn(okJson(compareJson())));
    wm.stubFor(get(urlEqualTo(PROJECT + "/merge_requests/7")).willReturn(okJson(mrJson())));
    wm.stubFor(
        post(urlEqualTo(PROJECT + "/merge_requests/7/discussions"))
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"id\":\"d-inline-1\",\"notes\":[{\"id\":8001}]}")));
    wm.stubFor(
        get(urlEqualTo(PROJECT + "/merge_requests/7/discussions?per_page=100&page=1"))
            .inScenario("publish")
            .whenScenarioStateIs("Started")
            .willReturn(okJson("[]")));
    wm.stubFor(
        get(urlEqualTo(PROJECT + "/merge_requests/7/discussions?per_page=100&page=1"))
            .inScenario("publish")
            .whenScenarioStateIs("published")
            .willReturn(okJson(discussionsJson())));
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
  void cliPlatformGitlabWiresGitlabAdapterAndCommentLedger() {
    CraConfig config = cliConfig();
    assertThat(config.isGitlab()).isTrue();
    assertThat(config.repoOwner()).isEqualTo("group");
    assertThat(config.repoName()).isEqualTo("proj");
    assertThat(config.gitlab().token()).isEqualTo("glpat-wiring");
    assertThat(config.gitlab().baseUrl()).isEqualTo(wm.baseUrl() + "/api/v4");

    try (AnnotationConfigApplicationContext context = contextFor(config)) {
      PlatformComponents components = context.getBean(PlatformComponents.class);
      ReviewLedger ledger = context.getBean(ReviewLedger.class);

      assertThat(components.adapter().getClass().getName()).contains("GitLabHttpAdapter");
      assertThat(components.commentSource()).isSameAs(components.adapter());

      ReviewKey key = ReviewKey.pullRequest("group", "proj", 7, HEAD);
      assertThat(reviewOnce(components, ledger, key)).isTrue();
      OptimisticResult first = ledgerState(ledger, key);
      assertThat(first.completion()).isTrue();
      assertThat(first.findings()).isEqualTo(1);

      assertThat(reviewOnce(components, ledger, key)).isFalse();
      wm.verify(
          1,
          getRequestedFor(
              urlEqualTo(PROJECT + "/repository/compare?from=" + BASE + "&to=" + HEAD)));
      wm.verify(1, postRequestedFor(urlEqualTo(PROJECT + "/merge_requests/7/discussions")));
      wm.verify(1, postRequestedFor(urlEqualTo(PROJECT + "/merge_requests/7/notes")));
    }
  }

  private CraConfig cliConfig() {
    Map<String, String> env = new HashMap<>();
    env.put("CRA_LLM_API_KEY", "sk-test");
    env.put("CRA_LLM_BASE_URL", "http://127.0.0.1:1/v1");
    env.put("CRA_LLM_MODEL", "model-test");
    env.put("CRA_GITLAB_TOKEN", "glpat-wiring");
    env.put("CRA_GITLAB_BOT_USERNAME", "cra-bot");
    return CliArgs.parse(
        new String[] {
          "--platform",
          "gitlab",
          "--gitlab-project",
          "group/proj",
          "--pr",
          "7",
          "--base",
          BASE,
          "--head",
          HEAD,
          "--gitlab-base-url",
          wm.baseUrl(),
          "--run-root",
          runRoot.toString(),
          "--dry-run"
        },
        env);
  }

  private static AnnotationConfigApplicationContext contextFor(CraConfig config) {
    AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
    context.registerBean(CraConfig.class, () -> config);
    context.register(AppConfig.class);
    context.refresh();
    return context;
  }

  private static boolean reviewOnce(
      PlatformComponents components, ReviewLedger ledger, ReviewKey key) {
    if (ledger.findCompletion(key).isPresent()) {
      return false;
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
                    "src/main/java/Foo.java", 2, Side.RIGHT, INLINE_BODY, "f_wiring")),
            SUMMARY_BODY);
    PrReviewResult result = components.adapter().postReview(cmd);
    assertThat(result.items()).hasSize(1);
    assertThat(result.summaryCommentId()).isPresent();
    return true;
  }

  private static OptimisticResult ledgerState(ReviewLedger ledger, ReviewKey key) {
    return new OptimisticResult(
        ledger
            .findCompletion(key)
            .map(m -> m.status().equals(LedgerRunMarker.STATUS_DONE))
            .orElse(false),
        ledger.publishedFindings(key).size());
  }

  private record OptimisticResult(boolean completion, int findings) {}

  private static String compareJson() {
    return """
        {
          "compare_timeout": false,
          "diffs": [
            {
              "old_path": "src/main/java/Foo.java",
              "new_path": "src/main/java/Foo.java",
              "new_file": false,
              "renamed_file": false,
              "deleted_file": false,
              "too_large": false,
              "diff": "@@ -1,3 +1,4 @@\\n class Foo {\\n+  int x = 1;\\n }\\n"
            }
          ],
          "commits": [{"id": "%s", "title": "feat: wiring"}]
        }
        """
        .formatted(HEAD);
  }

  private static String mrJson() {
    return """
        {
          "iid": 7,
          "state": "opened",
          "sha": "%s",
          "target_branch": "main",
          "source_branch": "feature",
          "diff_refs": {"base_sha": "%s", "head_sha": "%s", "start_sha": "startsha1"}
        }
        """
        .formatted(HEAD, BASE, HEAD);
  }

  private static String discussionsJson() {
    return """
        [
          {
            "id": "d-inline-1",
            "individual_note": false,
            "notes": [{
              "id": 8001,
              "system": false,
              "author": {"username": "cra-bot"},
              "position": {
                "base_sha": "%s", "head_sha": "%s", "start_sha": "startsha1",
                "position_type": "text",
                "old_path": "src/main/java/Foo.java",
                "new_path": "src/main/java/Foo.java",
                "new_line": 2
              },
              "body": %s,
              "created_at": "2026-10-27T00:00:00Z"
            }]
          },
          {
            "id": "d-summary-1",
            "individual_note": true,
            "notes": [{
              "id": 9500,
              "system": false,
              "author": {"username": "cra-bot"},
              "body": %s,
              "created_at": "2026-10-27T00:01:00Z"
            }]
          }
        ]
        """
        .formatted(BASE, HEAD, jsonString(INLINE_BODY), jsonString(SUMMARY_BODY));
  }

  private static String jsonString(String value) {
    return "\""
        + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "")
        + "\"";
  }

  private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder okJson(
      String body) {
    return aResponse()
        .withStatus(200)
        .withHeader("Content-Type", "application/json")
        .withBody(body);
  }
}
