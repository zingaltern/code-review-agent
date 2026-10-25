package org.cra.core.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.cra.agents.api.GeneralReviewerDefinition;
import org.cra.agents.internal.TemplateLoader;
import org.cra.common.finding.AnchorStatus;
import org.cra.common.git.DiffFile;
import org.cra.common.git.DiffHunk;
import org.cra.common.git.DiffResult;
import org.cra.common.git.FileContent;
import org.cra.common.git.LedgerFindingMarker;
import org.cra.common.git.LedgerRunMarker;
import org.cra.common.git.PostReviewCommand;
import org.cra.common.git.PrReviewResult;
import org.cra.common.git.RepoRef;
import org.cra.common.run.Depth;
import org.cra.common.run.ReviewRequest;
import org.cra.common.run.ReviewResult;
import org.cra.common.run.RunStatus;
import org.cra.git.api.GitPlatformAdapter;
import org.cra.llm.api.LlmGateway;
import org.cra.llm.api.LlmResponse;
import org.cra.store.api.ReviewLedger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

/**
 * M0 流水线行为测试（07§1 验收的 mock 侧：08§5 场景 1/2/12 + 锚定/转义/schema 重试/trace）。 SPI
 * 边界（adapter/ledger/gateway）全部 Mockito mock（08§3.1），reviewer 用真实模板定义。
 */
class ReviewPipelineTest {

  private static final RepoRef REPO = new RepoRef("o", "r");
  private static final String HEAD = "abc1234000000000000000000000000000000000";
  private static final String BASE = "def5678000000000000000000000000000000000";
  private static final String FILE = "src/Foo.java";
  private static final String FILE_CONTENT =
      """
      package demo;
      public class Foo {
        int get(List<Integer> items) {
          return items.get(items.size() - 1);
        }
      }
      """;
  private static final String PATCH = "@@ -1,5 +1,6 @@\n package demo;\n+import java.util.*;\n";

  @TempDir Path runRoot;

  private GitPlatformAdapter adapter;
  private ReviewLedger ledger;
  private LlmGateway gateway;
  private ReviewPipeline pipeline;

  @BeforeEach
  void setUp() {
    adapter = mock(GitPlatformAdapter.class);
    ledger = mock(ReviewLedger.class);
    gateway = mock(LlmGateway.class);
    when(ledger.findCompletion(anyInt(), anyString())).thenReturn(Optional.empty());
    when(ledger.publishedFindings(anyInt())).thenReturn(List.of());
    when(adapter.fetchDiff(any(), anyString(), anyString())).thenReturn(diff());
    when(adapter.readFile(any(), anyString(), anyString()))
        .thenReturn(FileContent.ofText(FILE, FILE_CONTENT));
    when(adapter.postReview(any()))
        .thenAnswer(
            inv -> {
              PostReviewCommand cmd = inv.getArgument(0);
              List<org.cra.common.git.ReviewItemResult> items =
                  cmd.inlineComments().stream()
                      .map(
                          c ->
                              new org.cra.common.git.ReviewItemResult(
                                  c.findingId(),
                                  c.path(),
                                  c.line(),
                                  org.cra.common.git.PublishStatus.PUBLISHED,
                                  AnchorStatus.INLINE,
                                  1L,
                                  ""))
                      .toList();
              return new PrReviewResult(
                  cmd.prNumber(), Optional.of("9001"), Optional.of(9500L), items);
            });
    pipeline =
        new ReviewPipeline(
            adapter, ledger, gateway, new GeneralReviewerDefinition(new TemplateLoader()), runRoot);
  }

  private static DiffResult diff() {
    return new DiffResult(
        BASE,
        HEAD,
        List.of(new DiffFile(FILE, null, "modified", PATCH, List.of(new DiffHunk(1, 5, 1, 6)))),
        List.of(new org.cra.common.git.CommitSummary("c1", "fix: something")));
  }

  private static ReviewRequest request(boolean dryRun) {
    return new ReviewRequest("o", "r", 7, BASE, HEAD, Depth.STANDARD, dryRun, false);
  }

  private static String findingsJson(String title, int line, String category) {
    return "{\"findings\":[" + findingJson(title, line, category) + "]}";
  }

  private static String findingJson(String title, int line, String category) {
    return """
        {"category":"%s","title":"%s",
          "description":"d<!-- injected -->描述","failure_mode":"触发条件：x；错误结果：y",
          "file":"%s","line":%d,"side":"RIGHT",
          "evidence":"L%d: return items.get(items.size() - 1);","severity_proposed":"important",
          "pre_existing_suspect":false,"confidence":0.8}"""
        .formatted(category, title, FILE, line, line);
  }

  private void gatewayReturns(String json) {
    when(gateway.complete(any())).thenReturn(LlmResponse.ok(json, 100, 20, 5, "glm-x"));
  }

  @Test
  void doneGateSkipsRunCompletely() {
    // 07§1 验收 3（闸语义）：账本存在 DONE → 全量跳过
    when(ledger.findCompletion(7, HEAD))
        .thenReturn(Optional.of(new LedgerRunMarker("run_old", HEAD, "DONE", 3, 0, 11L)));
    ReviewResult result = pipeline.run(request(false));
    assertThat(result.status()).isEqualTo(RunStatus.SKIPPED);
    verify(adapter, never()).fetchDiff(any(), anyString(), anyString());
    verify(adapter, never()).postReview(any());
  }

  @Test
  void interruptedRunRepublishesMissingOnly() {
    // 07§1 验收 3：掐断的 run 无 DONE → 指纹级补发：已发布 1 条不重发，另 1 条补发
    String fp =
        FingerprintCalculator.fingerprint(
            FILE,
            "bug",
            FILE_CONTENT.split("\n")[3],
            List.of(
                FILE_CONTENT.split("\n")[1],
                FILE_CONTENT.split("\n")[2],
                FILE_CONTENT.split("\n")[3],
                FILE_CONTENT.split("\n")[4],
                FILE_CONTENT.split("\n")[5]));
    when(ledger.publishedFindings(7))
        .thenReturn(
            List.of(new LedgerFindingMarker(fp, FILE, 4, "bug", "f_old", "run_old", HEAD, 42L)));
    gatewayReturns(
        "{\"findings\":["
            + findingJson("published-before-crash", 4, "bug")
            + ","
            + findingJson("new-finding", 4, "security")
            + "]}");
    // 两条 finding 同文件同行 → 指纹取决于 category（bug 已发布 / security 新）
    ReviewResult result = pipeline.run(request(false));
    assertThat(result.status()).isEqualTo(RunStatus.SUCCESS);
    assertThat(result.stats().skippedDuplicate()).isEqualTo(1);
    ArgumentCaptor<PostReviewCommand> captor = ArgumentCaptor.forClass(PostReviewCommand.class);
    verify(adapter).postReview(captor.capture());
    assertThat(captor.getValue().inlineComments()).hasSize(1);
    assertThat(captor.getValue().inlineComments().get(0).findingId()).isNotEqualTo("f_old");
  }

  @Test
  void dryRunNeverWrites() {
    // 07§1 验收 2：dry-run 全程零写（08§5 场景 12）
    gatewayReturns(findingsJson("越界", 4, "bug"));
    ReviewResult result = pipeline.run(request(true));
    assertThat(result.status()).isEqualTo(RunStatus.SUCCESS);
    assertThat(result.stats().findingsPublished()).isEqualTo(1); // would-be
    verify(adapter, never()).postReview(any());
    verify(adapter, times(1)).fetchDiff(any(), anyString(), anyString()); // 读操作允许
  }

  @Test
  void invalidAnchorFallsBackToSummary() {
    // 07§1 验收 1：位置非法的 finding 进 Summary 而非 422（08§5 场景 5 的前置）
    gatewayReturns(findingsJson("不在 hunk 内", 500, "bug"));
    ReviewResult result = pipeline.run(request(false));
    ArgumentCaptor<PostReviewCommand> captor = ArgumentCaptor.forClass(PostReviewCommand.class);
    verify(adapter).postReview(captor.capture());
    assertThat(captor.getValue().inlineComments()).isEmpty(); // 不发 inline，避免 422
    assertThat(captor.getValue().summaryBody()).contains("位置非法");
    assertThat(captor.getValue().summaryBody()).contains("不在 hunk 内");
    assertThat(result.stats().anchorFallback()).isEqualTo(1);
    assertThat(result.status()).isEqualTo(RunStatus.SUCCESS);
  }

  @Test
  void inlineAndSummaryCarryMarkersAndEscaping() {
    // 05§1.3 规则 3：正文 <!-- 转义 + 标记行最后追加；Summary 文末 DONE 标记
    gatewayReturns(findingsJson("越界", 4, "bug"));
    pipeline.run(request(false));
    ArgumentCaptor<PostReviewCommand> captor = ArgumentCaptor.forClass(PostReviewCommand.class);
    verify(adapter).postReview(captor.capture());
    PostReviewCommand cmd = captor.getValue();
    String inline = cmd.inlineComments().get(0).body();
    assertThat(inline).contains("d&lt;!-- injected -->描述"); // 仅开标记转义（规则 3）
    assertThat(inline.indexOf("&lt;!-- injected")).isLessThan(inline.lastIndexOf("<!-- cra:v=2.1"));
    assertThat(inline)
        .containsPattern(
            Pattern.compile(
                "<!-- cra:v=2\\.1 \\| fp=[0-9a-f]{16} \\| file=src/Foo.java \\| cat=bug \\| id=f_[0-9a-f]+ "
                    + "\\| run=run_\\S+ \\| commit="
                    + HEAD
                    + " \\| line=4 -->"));
    assertThat(cmd.summaryBody())
        .containsPattern(
            Pattern.compile(
                "<!-- cra:v=2\\.1 \\| run=run_\\S+ \\| commit="
                    + HEAD
                    + " \\| status=DONE \\| findings=1 \\| suppressed=0 -->"));
  }

  @Test
  void schemaRetryThenSuccess() {
    when(gateway.complete(any()))
        .thenReturn(LlmResponse.ok("这不是 JSON", 50, 5, 1, "glm-x"))
        .thenReturn(LlmResponse.ok(findingsJson("越界", 4, "bug"), 50, 5, 1, "glm-x"));
    ReviewResult result = pipeline.run(request(false));
    assertThat(result.status()).isEqualTo(RunStatus.SUCCESS);
    assertThat(result.stats().findingsPublished()).isEqualTo(1);
    verify(gateway, times(2)).complete(any());
  }

  @Test
  void schemaExhaustedDegradesWithDoneMarker() {
    // 三次全非法：候选全丢 → DEGRADED 收尾且发布 DONE 标记（降级完成也是完整发布，02§4.1）
    when(gateway.complete(any()))
        .thenReturn(LlmResponse.ok("a", 50, 5, 1, "glm-x"))
        .thenReturn(LlmResponse.ok("b", 50, 5, 1, "glm-x"))
        .thenReturn(LlmResponse.ok("c", 50, 5, 1, "glm-x"));
    ReviewResult result = pipeline.run(request(false));
    assertThat(result.status()).isEqualTo(RunStatus.DEGRADED);
    assertThat(result.stats().findingsPublished()).isZero();
    ArgumentCaptor<PostReviewCommand> captor = ArgumentCaptor.forClass(PostReviewCommand.class);
    verify(adapter).postReview(captor.capture());
    assertThat(captor.getValue().summaryBody())
        .contains("DEGRADED")
        .contains("schema-violation-all-dropped");
  }

  @Test
  void llmUnavailableFailsRunWithoutPublish() {
    when(gateway.complete(any())).thenReturn(LlmResponse.unavailable("retries exhausted", 0, 10));
    ReviewResult result = pipeline.run(request(false));
    assertThat(result.status()).isEqualTo(RunStatus.FAILED);
    verify(adapter, never()).postReview(any());
  }

  @Test
  void briefAndTracePersistedWithFullText() {
    // 07§1 验收 4（本地侧）：trace 可读且含 brief 全文
    gatewayReturns(findingsJson("越界", 4, "bug"));
    ReviewResult result = pipeline.run(request(false));
    Path runDir = Path.of(result.traceRef().substring("file://".length()));
    assertThat(Files.exists(runDir.resolve("briefs/general-reviewer.md"))).isTrue();
    assertThat(Files.exists(runDir.resolve("trace.ndjson"))).isTrue();
    try {
      String brief = Files.readString(runDir.resolve("briefs/general-reviewer.md"));
      assertThat(brief).contains("UNTRUSTED_CONTENT").contains("# user");
      String trace = Files.readString(runDir.resolve("trace.ndjson"));
      assertThat(trace)
          .contains("\"event\":\"run_start\"")
          .contains("\"event\":\"agent_start\"")
          .contains("\"event\":\"agent_output\"")
          .contains("\"event\":\"anchor_check\"")
          .contains("\"event\":\"publish\"")
          .contains("\"event\":\"run_end\"");
    } catch (java.io.IOException e) {
      throw new AssertionError(e);
    }
  }

  @Test
  void adapterFailureDegradesToFailedRun() {
    when(adapter.fetchDiff(any(), anyString(), anyString()))
        .thenThrow(new org.cra.common.exception.CraIntegrationException("github down"));
    ReviewResult result = pipeline.run(request(false));
    assertThat(result.status()).isEqualTo(RunStatus.FAILED);
    assertThat(result.summaryLine()).contains("run failed");
    verify(adapter, never()).postReview(any());
  }

  @Test
  void emptyFindingsStillPublishSummaryWithDoneMarker() {
    // 空结果合法且被鼓励（04§5 反模式 2）——仍发 Summary + DONE（验收 1 的空结果陈述）
    gatewayReturns("{\"findings\":[]}");
    ReviewResult result = pipeline.run(request(false));
    assertThat(result.status()).isEqualTo(RunStatus.SUCCESS);
    ArgumentCaptor<PostReviewCommand> captor = ArgumentCaptor.forClass(PostReviewCommand.class);
    verify(adapter).postReview(captor.capture());
    assertThat(captor.getValue().summaryBody()).contains("本次未发现问题");
    assertThat(captor.getValue().summaryBody()).contains("status=DONE");
    assertThat(captor.getValue().inlineComments()).isEmpty();
  }

  @Test
  void anchorStatusHelperUsesDiffHunksOnly() {
    // 非法侧 LEFT 一律降级（05§2.2 校验按 PR diff hunk）
    gatewayReturns(
        """
        {"findings":[{"category":"bug","title":"t","description":"d","failure_mode":"f",
          "file":"src/Foo.java","line":2,"side":"LEFT","evidence":"e",
          "severity_proposed":"nit","pre_existing_suspect":false}]}"""
            .formatted());
    ReviewResult result = pipeline.run(request(false));
    assertThat(result.stats().anchorFallback()).isEqualTo(1);
    assertThat(AnchorStatus.SUMMARY_FALLBACK.name()).isEqualTo("SUMMARY_FALLBACK");
  }
}
