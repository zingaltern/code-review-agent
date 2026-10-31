package org.cra.core.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.cra.agents.api.BugHunterDefinition;
import org.cra.agents.api.JudgeAgentDefinition;
import org.cra.agents.api.ReviewCrew;
import org.cra.agents.api.SecurityReviewerDefinition;
import org.cra.agents.api.VerifierAgentDefinition;
import org.cra.agents.internal.TemplateLoader;
import org.cra.common.exception.CraIntegrationException;
import org.cra.common.git.CommitSummary;
import org.cra.common.git.DiffFile;
import org.cra.common.git.DiffHunk;
import org.cra.common.git.DiffResult;
import org.cra.common.git.FileContent;
import org.cra.common.git.PostReviewCommand;
import org.cra.common.git.PrReviewResult;
import org.cra.common.git.PublishStatus;
import org.cra.common.git.ReviewItemResult;
import org.cra.common.run.Depth;
import org.cra.common.run.ReviewRequest;
import org.cra.common.run.ReviewResult;
import org.cra.common.run.RunStatus;
import org.cra.core.api.CrossCheckerOptions;
import org.cra.core.api.PipelineOptions;
import org.cra.core.api.SemanticContextOptions;
import org.cra.core.api.SignoffOptions;
import org.cra.git.api.GitPlatformAdapter;
import org.cra.git.internal.JgitLocalRepoContext;
import org.cra.git.internal.JgitSymbolIndex;
import org.cra.store.api.ReviewLedger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

/**
 * M4e 语义上下文验收（ADR-0014）：开启时 reviewer/verifier brief 含 L2 跨文件切片（真实 git fixture，调用方文件+行可见）； 关闭时 brief
 * 与开关引入前一致（模板占位 "（无切片）"、无 trace 事件、无覆盖声明）；quick 档不触发。
 *
 * <p>LLM 全部脚本化（08§3.1）；索引与 diff 数据面来自真实 git 仓库（同一批 fixture 文件）。
 */
class SemanticContextPipelineTest {

  private static final String KEEP_ALIVE = "src/main/java/demo/SessionKeepAlive.java";
  private static final String CALLER = "src/main/java/demo/Caller.java";
  private static final String BASE = "base00000000000000000000000000000000000";
  private static final String EMPTY_FINDINGS = "{\"findings\":[]}";

  private static final String KEEP_ALIVE_SRC =
      """
      package demo;

      import java.time.Duration;

      public class SessionKeepAlive {
        private static final int MINUTES = 30;

        boolean shouldKeepAlive(Duration sessionAge) {
          return sessionAge.compareTo(Duration.ofMinutes(MINUTES)) < 0;
        }
      }
      """;

  private static final String CALLER_SRC =
      """
      package demo;

      import java.time.Duration;

      public class Caller {
        private final SessionKeepAlive keepAlive = new SessionKeepAlive();

        boolean check(Duration age) {
          return keepAlive.shouldKeepAlive(age);
        }
      }
      """;

  private static final String PATCH =
      """
      @@ -7,3 +7,5 @@
       public class SessionKeepAlive {
      +  boolean shouldKeepAlive(Duration sessionAge) {
      +    return sessionAge.compareTo(Duration.ofMinutes(MINUTES)) < 0;
      +  }
      """;

  @TempDir Path workspace;
  @TempDir Path runRoot;

  private GitPlatformAdapter adapter;
  private ReviewLedger ledger;
  private ScriptedGateway scripted;
  private String headSha;

  @BeforeEach
  void setUp() throws Exception {
    initFixtureRepo();
    adapter = mock(GitPlatformAdapter.class);
    ledger = mock(ReviewLedger.class);
    scripted = new ScriptedGateway();
    when(ledger.findCompletion(anyInt(), anyString())).thenReturn(Optional.empty());
    when(ledger.publishedFindings(anyInt())).thenReturn(List.of());
    when(ledger.completedRuns(anyInt())).thenReturn(List.of());
    when(ledger.findCompletion(any(org.cra.common.git.ReviewKey.class)))
        .thenAnswer(
            inv -> {
              org.cra.common.git.ReviewKey key = inv.getArgument(0);
              return ledger.findCompletion(key.prNumber(), key.commitSha());
            });
    when(ledger.completedRuns(any(org.cra.common.git.ReviewKey.class))).thenReturn(List.of());
    when(ledger.publishedFindings(any(org.cra.common.git.ReviewKey.class))).thenReturn(List.of());
    when(adapter.fetchDiff(any(), anyString(), anyString())).thenReturn(diff());
    when(adapter.readFile(any(), anyString(), eq("REVIEW.md")))
        .thenThrow(new CraIntegrationException("404: REVIEW.md"));
    when(adapter.readFile(any(), anyString(), eq(KEEP_ALIVE)))
        .thenReturn(FileContent.ofText(KEEP_ALIVE, KEEP_ALIVE_SRC));
    when(adapter.readFile(any(), anyString(), eq(CALLER)))
        .thenReturn(FileContent.ofText(CALLER, CALLER_SRC));
    when(adapter.postReview(any()))
        .thenAnswer(
            inv -> {
              PostReviewCommand cmd = inv.getArgument(0);
              List<ReviewItemResult> items =
                  cmd.inlineComments().stream()
                      .map(
                          comment ->
                              new ReviewItemResult(
                                  comment.findingId(),
                                  comment.path(),
                                  comment.line(),
                                  PublishStatus.PUBLISHED,
                                  org.cra.common.finding.AnchorStatus.INLINE,
                                  1L,
                                  ""))
                      .toList();
              return new PrReviewResult(
                  cmd.prNumber(), Optional.of("9001"), Optional.of(9500L), items);
            });
  }

  @Test
  void enabledInjectsCrossFileSliceIntoDataflowAndSecurityBriefs() throws Exception {
    scripted.review(EMPTY_FINDINGS);

    ReviewResult result =
        pipeline(SemanticContextOptions.enabledWithDefaults()).run(request(Depth.DEEP));

    assertThat(result.status()).isEqualTo(RunStatus.SUCCESS);
    // 只有 dataflow / security 收到切片；boundary 不注入（04§1 按角色默认注入）
    assertThat(scripted.reviewCalls).isNotEmpty();
    List<String> slicePrompts =
        scripted.reviewCalls.stream()
            .filter(call -> call.userPrompt().contains("L2 跨文件上下文"))
            .map(call -> call.caller())
            .toList();
    assertThat(slicePrompts).contains("bug-hunter/dataflow");
    String dataflowPrompt =
        scripted.reviewCalls.stream()
            .filter(call -> call.caller().equals("bug-hunter/dataflow"))
            .findFirst()
            .orElseThrow()
            .userPrompt();
    assertThat(dataflowPrompt)
        .contains("L2 跨文件上下文")
        .contains("仅 Java")
        .contains("### 符号 `shouldKeepAlive`")
        .contains("定义 `" + KEEP_ALIVE + ":8`")
        .contains("`" + CALLER + ":9`")
        .contains("keepAlive.shouldKeepAlive(age)")
        // 分层核对契约（04§2.6）：仅索引可用时随 dataflow brief 注入
        .contains("分层核对契约")
        .contains("controller→service→DAO")
        .contains("视为不可见")
        .doesNotContain("（无切片）");

    // 语义索引事件进 trace（可观测性），覆盖声明含索引范围
    String trace = traceOf(result);
    assertThat(trace)
        .contains("\"event\":\"semantic_context\"")
        .contains("\"available\":true")
        .contains("\"files_scanned\":2");

    // 覆盖声明进 Summary（02§4：裁剪/不可得必须可见）
    ArgumentCaptor<PostReviewCommand> captor = ArgumentCaptor.forClass(PostReviewCommand.class);
    verify(adapter).postReview(captor.capture());
    assertThat(captor.getValue().summaryBody()).contains("L2 跨文件切片启用").contains("仅支持 Java 源文件");

    // 切片计入单代理输入预算：brief 估算不越该角色硬限（04§2.2 / ADR-0014 决策 7）
    long promptTokens =
        org.cra.common.context.TokenMath.estimateTokens(
            scripted.reviewCalls.get(0).systemPrompt() + scripted.reviewCalls.get(0).userPrompt());
    assertThat(promptTokens)
        .isLessThanOrEqualTo(
            new BugHunterDefinition(BugHunterDefinition.Perspective.DATAFLOW, new TemplateLoader())
                .budget()
                .maxInputTokens());
  }

  @Test
  void unavailableIndexDeclaresInCoverageInsteadOfFailing() throws Exception {
    scripted.review(EMPTY_FINDINGS);

    ReviewPipeline pipeline =
        new ReviewPipeline(
            adapter,
            ledger,
            scripted,
            crew(),
            new VerifierAgentDefinition(new TemplateLoader()),
            new JudgeAgentDefinition(new TemplateLoader()),
            runRoot,
            PipelineOptions.defaults(),
            new JgitLocalRepoContext(workspace),
            CrossCheckerOptions.disabled(),
            SignoffOptions.disabled(),
            null,
            SemanticContextOptions.enabledWithDefaults(),
            // 工作区不是 git checkout：索引不可得 → 切片为空 + 覆盖声明，不炸 run
            new JgitSymbolIndex(java.nio.file.Path.of("/tmp/cra-semantic-not-a-repo")));

    ReviewResult result = pipeline.run(request(Depth.DEEP));

    assertThat(result.status()).isEqualTo(RunStatus.SUCCESS);
    assertThat(scripted.reviewCalls)
        .allSatisfy(call -> assertThat(call.userPrompt()).doesNotContain("L2 跨文件上下文"));
    assertThat(traceOf(result))
        .contains("\"event\":\"semantic_context\"")
        .contains("\"available\":false");
    ArgumentCaptor<PostReviewCommand> captor = ArgumentCaptor.forClass(PostReviewCommand.class);
    verify(adapter).postReview(captor.capture());
    assertThat(captor.getValue().summaryBody()).contains("L2 跨文件切片不可得");
  }

  @Test
  void verifierBriefCarriesCrossFileSlice() throws Exception {
    scripted.review(findingJson());
    scripted.verifierScript(
        brief ->
            "{\"verdicts\":[{\"finding_id\":\"%s\",\"verdict\":\"CONFIRMED\","
                    .formatted(firstIdIn(brief))
                + "\"rationale\":\"L9 的调用点未校验 null，L9: return keepAlive.shouldKeepAlive(age); 印证\"}]}");

    ReviewResult result =
        pipeline(SemanticContextOptions.enabledWithDefaults()).run(request(Depth.DEEP));

    assertThat(result.stats().confirmed()).isEqualTo(1);
    assertThat(scripted.verifierCalls).isNotEmpty();
    assertThat(scripted.verifierCalls.get(0).userPrompt())
        .contains("L2 跨文件上下文")
        .contains("### 符号 `shouldKeepAlive`")
        .contains("`" + CALLER + ":9`");
  }

  @Test
  void disabledKeepsBriefIdenticalToPreSwitchBehaviour() throws Exception {
    scripted.review(EMPTY_FINDINGS);

    ReviewResult result = pipeline(SemanticContextOptions.disabled()).run(request(Depth.DEEP));

    assertThat(result.status()).isEqualTo(RunStatus.SUCCESS);
    assertThat(scripted.reviewCalls).isNotEmpty();
    // 关闭 = 模板占位文本、无切片段、无 trace 事件（与开关引入前逐字节一致）；
    // 仅带切片槽位的模板（dataflow/security）出现占位文本，其余视角模板本就没有该段
    List<String> sliceSlotPrompts =
        scripted.reviewCalls.stream()
            .filter(
                call ->
                    call.caller().equals("bug-hunter/dataflow")
                        || call.caller().equals("security-reviewer"))
            .map(call -> call.userPrompt())
            .toList();
    assertThat(sliceSlotPrompts)
        .isNotEmpty()
        .allSatisfy(prompt -> assertThat(prompt).contains("（无切片）"));
    assertThat(scripted.reviewCalls)
        .noneSatisfy(call -> assertThat(call.userPrompt()).contains("L2 跨文件上下文"))
        .noneSatisfy(call -> assertThat(call.userPrompt()).contains("分层核对契约"));
    assertThat(traceOf(result)).doesNotContain("\"event\":\"semantic_context\"");
  }

  @Test
  void quickDepthDoesNotBuildIndexAndDeclaresSkip() throws Exception {
    scripted.review(EMPTY_FINDINGS);

    // quick 档不触发（02§4.3 档位联动）：不构建索引、不进 brief，原因进 trace
    ReviewResult quick =
        pipeline(SemanticContextOptions.enabledWithDefaults()).run(request(Depth.QUICK));

    assertThat(quick.status()).isEqualTo(RunStatus.SUCCESS);
    assertThat(scripted.reviewCalls)
        .allSatisfy(call -> assertThat(call.userPrompt()).contains("（无切片）"));
    assertThat(traceOf(quick)).contains("\"event\":\"semantic_context\"").contains("quick 档不触发");
  }

  // ---------- fixture ----------

  /** 四视角班组 + Security（DEEP 档路由需要全部视角；quick 降档后只用 dataflow）。 */
  private ReviewCrew crew() {
    return new ReviewCrew(
        List.of(
            new BugHunterDefinition(BugHunterDefinition.Perspective.DATAFLOW, new TemplateLoader()),
            new BugHunterDefinition(BugHunterDefinition.Perspective.BOUNDARY, new TemplateLoader()),
            new BugHunterDefinition(
                BugHunterDefinition.Perspective.CONCURRENCY, new TemplateLoader()),
            new BugHunterDefinition(
                BugHunterDefinition.Perspective.RESOURCE, new TemplateLoader())),
        new SecurityReviewerDefinition(new TemplateLoader()),
        null,
        null,
        null,
        null);
  }

  private ReviewPipeline pipeline(SemanticContextOptions semanticContextOptions) {
    return new ReviewPipeline(
        adapter,
        ledger,
        scripted,
        crew(),
        new VerifierAgentDefinition(new TemplateLoader()),
        new JudgeAgentDefinition(new TemplateLoader()),
        runRoot,
        PipelineOptions.defaults(),
        new JgitLocalRepoContext(workspace),
        CrossCheckerOptions.disabled(),
        SignoffOptions.disabled(),
        null,
        semanticContextOptions,
        new JgitSymbolIndex(workspace));
  }

  private ReviewRequest request(Depth depth) {
    return new ReviewRequest("o", "r", 7, BASE, headSha, depth, false, false);
  }

  private DiffResult diff() {
    return new DiffResult(
        BASE,
        headSha,
        List.of(
            new DiffFile(KEEP_ALIVE, null, "modified", PATCH, List.of(new DiffHunk(7, 3, 7, 5)))),
        List.of(new CommitSummary(headSha, "chore: tighten session window")));
  }

  private String findingJson() {
    return """
        {"findings":[{"category":"bug","title":"会话窗口边界缺少 null 校验",
          "description":"shouldKeepAlive 对 sessionAge 未判空，调用方传 null 时 NPE",
          "failure_mode":"触发条件：sessionAge 为 null；错误结果：NullPointerException",
          "file":"%s","line":9,"side":"RIGHT",
          "evidence":"L9: return sessionAge.compareTo(Duration.ofMinutes(MINUTES)) < 0;",
          "severity_proposed":"important","pre_existing_suspect":false,"confidence":0.9}]}"""
        .formatted(KEEP_ALIVE);
  }

  private String traceOf(ReviewResult result) throws Exception {
    return Files.readString(
        Path.of(result.traceRef().substring("file://".length())).resolve("trace.ndjson"));
  }

  private static String firstIdIn(String userPrompt) {
    java.util.regex.Matcher matcher =
        java.util.regex.Pattern.compile("f_[0-9a-f]+").matcher(userPrompt);
    return matcher.find() ? matcher.group() : "f_missing";
  }

  /** 真实 git fixture：init（旧内容，无 shouldKeepAlive 调用方信息）→ head（含调用方文件）。 */
  private void initFixtureRepo() throws Exception {
    git("init", "-q");
    Files.writeString(workspace.resolve("README.md"), "demo\n");
    Files.createDirectories(workspace.resolve(KEEP_ALIVE).getParent());
    Files.writeString(
        workspace.resolve(KEEP_ALIVE),
        "package demo;\n\npublic class SessionKeepAlive {\n  boolean keepAlive() {\n    return true;\n  }\n}\n");
    git("add", ".");
    git("-c", "user.name=alice", "-c", "user.email=a@x", "commit", "-q", "-m", "init");
    Files.writeString(workspace.resolve(KEEP_ALIVE), KEEP_ALIVE_SRC);
    Files.writeString(workspace.resolve(CALLER), CALLER_SRC);
    git("add", ".");
    git("-c", "user.name=alice", "-c", "user.email=a@x", "commit", "-q", "-m", "change");
    headSha = git("rev-parse", "HEAD").strip();
  }

  private String git(String... args) throws Exception {
    List<String> cmd = new java.util.ArrayList<>();
    cmd.add("git");
    cmd.addAll(List.of(args));
    Process process = new ProcessBuilder(cmd).directory(workspace.toFile()).start();
    process.waitFor();
    return new String(process.getInputStream().readAllBytes());
  }
}
