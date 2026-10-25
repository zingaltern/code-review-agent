package org.cra.eval.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.cra.common.git.CommentLedger;
import org.cra.common.git.DiffResult;
import org.cra.common.git.FileContent;
import org.cra.common.git.PostReviewCommand;
import org.cra.common.git.PrReviewResult;
import org.cra.common.git.RepoRef;
import org.cra.git.api.GitPlatformAdapter;
import org.cra.git.github.api.GitHubAssembly;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** stub 服务器协议面单测：用**真实** GitHubHttpAdapter（cra-git-github 装配工厂）打 stub—— 协议不匹配即红（端点契约文档化在测试里）。 */
class EvalStubServerTest {

  @TempDir Path tmp;

  private static final String DIFF =
      """
      diff --git a/src/Demo.java b/src/Demo.java
      index aaa..bbb 100644
      --- a/src/Demo.java
      +++ b/src/Demo.java
      @@ -1,2 +1,3 @@
       class Demo {
      +  int bad() { return 1 / 0; }
       }
      """;

  private EvalStubServer server;
  private GitPlatformAdapter adapter;

  @BeforeEach
  void setUp() throws Exception {
    Path snapshot = tmp.resolve("snap");
    Files.createDirectories(snapshot.resolve("src"));
    Files.writeString(
        snapshot.resolve("src/Demo.java"),
        "class Demo {\n  int bad() { return 1 / 0; }\n}\n",
        StandardCharsets.UTF_8);
    server = EvalStubServer.start(DiffSplitter.split(DIFF), "base000", "head000", snapshot);
    adapter = GitHubAssembly.createComponents(server.baseUrl(), "stub-token").adapter();
  }

  @AfterEach
  void tearDown() {
    server.close();
  }

  @Test
  void fetchDiffServesPatchedFiles() {
    DiffResult diff =
        adapter.fetchDiff(new RepoRef("cra-eval-fixture", "bad-Demo"), "base000", "head000");
    assertThat(diff.files()).hasSize(1);
    assertThat(diff.files().get(0).path()).isEqualTo("src/Demo.java");
    assertThat(diff.files().get(0).patch()).startsWith("@@ -1,2 +1,3 @@");
    assertThat(diff.files().get(0).containsLine(org.cra.common.finding.Side.RIGHT, 2)).isTrue();
    assertThat(diff.commits()).hasSize(1);
  }

  @Test
  void readFileServesSnapshotBytes() {
    // owner/repo 用真实形态（多字符）——硬编码 "/repos/a/b/contents/" 前缀的 substring 长度
    // 会截断路径导致 404（M1 起潜伏：评测形态 REVIEW.md 与文件全文从未读到）
    FileContent content =
        adapter.readFile(new RepoRef("cra-eval-fixture", "bad-Demo"), "head000", "src/Demo.java");
    assertThat(content.binary()).isFalse();
    assertThat(content.text()).contains("1 / 0");
  }

  @Test
  void ledgerReadsAreEmptyAndBotLoginStable() {
    var components = GitHubAssembly.createComponents(server.baseUrl(), "stub-token");
    CommentLedger ledger = components.commentSource().fetchBotComments(new RepoRef("o", "r"), 9001);
    assertThat(ledger.comments()).isEmpty();
    assertThat(ledger.runMarkers()).isEmpty();
  }

  @Test
  void postReviewCapturesInlineAndSummary() {
    PostReviewCommand cmd =
        new PostReviewCommand(
            "cra-eval-fixture",
            "bad-Demo",
            9001,
            "head000",
            "cra 评审：发现 1 条问题",
            List.of(
                new org.cra.common.git.InlineComment(
                    "src/Demo.java",
                    2,
                    org.cra.common.finding.Side.RIGHT,
                    "**[IMPORTANT] 除零** — `src/Demo.java:2`\n\n<!-- cra:v=2.1 | fp=aa | file=src/Demo.java | cat=bug | id=f_1 | run=run_1 | commit=head000 | line=2 -->",
                    "f_1")),
            "## cra 评审 Summary（run_1）\n\n<!-- cra:v=2.1 | run=run_1 | commit=head000 | status=DONE | findings=1 | suppressed=0 -->");
    PrReviewResult result = adapter.postReview(cmd);
    assertThat(result.summaryCommentId()).isPresent();

    assertThat(server.reviews()).hasSize(1);
    assertThat(server.reviews().get(0).comments()).hasSize(1);
    assertThat(server.reviews().get(0).comments().get(0).path()).isEqualTo("src/Demo.java");
    assertThat(server.summaries()).hasSize(1);
    assertThat(server.summaries().get(0).body()).contains("status=DONE");
  }
}
