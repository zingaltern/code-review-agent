package org.cra.core.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.cra.agents.api.BugHunterDefinition;
import org.cra.agents.api.CrossCheckerDefinition;
import org.cra.agents.api.JudgeAgentDefinition;
import org.cra.agents.api.ReviewCrew;
import org.cra.agents.api.VerifierAgentDefinition;
import org.cra.agents.internal.TemplateLoader;
import org.cra.common.context.ReviewPolicy;
import org.cra.common.context.TokenMath;
import org.cra.common.event.EventHistoryQuery;
import org.cra.common.event.EventQuery;
import org.cra.common.event.ProductEvent;
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
import org.cra.git.api.GitPlatformAdapter;
import org.cra.git.internal.JgitLocalRepoContext;
import org.cra.llm.api.LlmRequest;
import org.cra.store.api.ReviewLedger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

/**
 * M4f-lite 验收（07§5）：fixture = diff 本身无缺陷 + REVIEW.md Known incidents 含六周前定制条目 + 上下游路径含相关 commit（真实
 * jgit fixture）→ CrossChecker 产出 category=context 的 confirmed finding，评审→验证→晋升全链路成立， 且验证 brief
 * 能解析引用格式（commit sha + path / REVIEW.md 条目锚）。
 *
 * <p>LLM 全部脚本化（08§3.1），jgit 查询用真实 git 仓库（同一文件路径 + 固定提交时间）。
 */
class CrossCheckPipelineTest {

  private static final String FILE = "src/main/java/demo/SessionKeepAlive.java";
  private static final String BASE = "base00000000000000000000000000000000000";

  private static final String FILE_CONTENT =
      """
      package demo;

      import java.time.Duration;

      public class SessionKeepAlive {
        boolean shouldKeepAlive(Duration sessionAge) {
          // 假设：常规会话 30 分钟内跑完
          return sessionAge.compareTo(Duration.ofMinutes(30)) < 0;
        }

        void heartbeat() {
          // no-op
        }

        void touch() {
          // no-op
        }
      }
      """;

  private static final String OLD_CONTENT =
      """
      package demo;

      public class SessionKeepAlive {
        // enterprise-session-window: 8h
        boolean keepAlive() {
          return true;
        }
      }
      """;

  private static final String PATCH =
      """
      @@ -3,3 +5,6 @@
       public class SessionKeepAlive {
      +  boolean shouldKeepAlive(Duration sessionAge) {
      +    // 假设：常规会话 30 分钟内跑完
      +    return sessionAge.compareTo(Duration.ofMinutes(30)) < 0;
      +  }
      """;

  private static final String REVIEW_MD =
      """
      # Review Policy: demo

      ## Product context
      - 后台任务默认在常规会话内跑完；企业客户走长会话定制（2026-09 上线）

      ## Known incidents
      - 2026-08-14 会话超时事故：隐含假设被六周前企业定制破坏（INC-42）

      ## Sniff tests
      - 超时/重试类参数不得超过上游服务超时的 80%
      """;

  private static final String EMPTY_FINDINGS = "{\"findings\":[]}";

  @TempDir Path workspace;
  @TempDir Path runRoot;

  private GitPlatformAdapter adapter;
  private ReviewLedger ledger;
  private ScriptedGateway scripted;
  private EventHistoryQuery eventHistoryQuery;
  private String headSha;
  private String oldSha;

  @BeforeEach
  void setUp() throws Exception {
    initFixtureRepo();
    adapter = mock(GitPlatformAdapter.class);
    ledger = mock(ReviewLedger.class);
    scripted = new ScriptedGateway();
    eventHistoryQuery = null;
    when(ledger.findCompletion(7, headSha)).thenReturn(Optional.empty());
    when(ledger.findCompletion(anyInt(), anyString())).thenReturn(Optional.empty());
    when(ledger.publishedFindings(anyInt())).thenReturn(List.of());
    when(ledger.completedRuns(anyInt())).thenReturn(List.of());
    bridgeKeyedLedgerToIntStubs();
    when(adapter.fetchDiff(any(), anyString(), anyString())).thenReturn(diff());
    when(adapter.readFile(any(), anyString(), eq("REVIEW.md")))
        .thenReturn(FileContent.ofText("REVIEW.md", REVIEW_MD));
    when(adapter.readFile(any(), anyString(), eq(FILE)))
        .thenReturn(FileContent.ofText(FILE, FILE_CONTENT));
    when(adapter.postReview(any()))
        .thenAnswer(
            inv -> {
              PostReviewCommand cmd = inv.getArgument(0);
              List<ReviewItemResult> items =
                  cmd.inlineComments().stream()
                      .map(
                          c ->
                              new ReviewItemResult(
                                  c.findingId(),
                                  c.path(),
                                  c.line(),
                                  PublishStatus.PUBLISHED,
                                  org.cra.common.finding.AnchorStatus.INLINE,
                                  1L,
                                  ""))
                      .toList();
              return new PrReviewResult(
                  cmd.prNumber(), Optional.of("9001"), Optional.of(9500L), items);
            });
  }

  // ---------- 验收：context finding 全链路 confirmed 并引用矛盾来源 ----------

  @Test
  void crossCheckerProducesConfirmedContextFindingWithCitedContradiction() throws Exception {
    scripted.review(EMPTY_FINDINGS);
    scripted.crossChecker(contextFinding("nit"));

    ReviewResult result = pipeline(enabled()).run(request(Depth.DEEP));

    // 评审→验证→晋升：context 类走既有管线，不享免检（03§3.1）
    assertThat(result.status()).isEqualTo(RunStatus.SUCCESS);
    assertThat(result.stats().confirmed()).isEqualTo(1);
    assertThat(result.stats().findingsPublished()).isEqualTo(1);

    ArgumentCaptor<PostReviewCommand> captor = ArgumentCaptor.forClass(PostReviewCommand.class);
    verify(adapter).postReview(captor.capture());
    String body = captor.getValue().inlineComments().get(0).body();
    assertThat(body)
        .contains("[IMPORTANT]") // 05§1.1：context 默认 important（脚本给 nit，schema 归一）
        .contains("cat=context")
        .contains("矛盾来源")
        .contains(oldSha);

    // CrossChecker 输入：真实 jgit 变更史（六周前企业定制）+ REVIEW.md 产品段 + diff 精简视图
    LlmRequest crossCall = scripted.crossCheckerCalls.get(0);
    String crossPrompt = crossCall.userPrompt();
    assertThat(crossPrompt)
        .contains("长会话定制")
        .contains(oldSha.substring(0, 7))
        .contains("INC-42")
        .contains("超时/重试类参数不得超过上游服务超时的 80%")
        .contains("+  boolean shouldKeepAlive")
        // 本 PR 自身 commit 被排除（不把变更本身当外部证据）：历史段只命中六周前那 1 条
        .contains("近 8 周内上下游路径集的 commit（共 1 条");

    // 验证分型组装：被引 commit patch（旧路径解析）+ REVIEW.md 条目注入 verifier brief
    String verifierBrief = scripted.verifierCalls.get(0).userPrompt();
    assertThat(verifierBrief)
        .contains("enterprise-session-window: 8h")
        .contains("INC-42")
        .contains("Known incidents");

    // trace：crosscheck 事件 + 模板版本
    String trace = traceOf(result);
    assertThat(trace)
        .contains("\"event\":\"crosscheck\"")
        .contains("\"triggered\":true")
        .contains("\"history_commits\":1")
        .contains("\"modes\":[\"assumption\",\"sniff\"]")
        .contains("\"cross-checker\":\"cross-checker@");

    // 增量实测：CrossChecker 单次输入估算（03§6 预算 ≤32k，2026-10-31 翻倍；04§2.2 文档算术 ≈14k in；
    // 断言沿用 ≤16k 作为更严的算术锚，不随默认预算放宽）
    long inputTokens = TokenMath.estimateTokens(crossCall.systemPrompt() + crossPrompt);
    System.out.println(
        "MEASURED CrossChecker input tokens (fixture) = "
            + inputTokens
            + ", output budget = "
            + crossCall.maxOutputTokens());
    assertThat(inputTokens).isLessThanOrEqualTo(16_000);

    // 验收证据落盘（target/，随构建清理；可直接作为 07§5 验收记录取证）
    Files.createDirectories(Path.of("target"));
    Files.writeString(
        Path.of("target", "crosscheck-acceptance-evidence.txt"),
        "summary: "
            + result.summaryLine()
            + "\n\n[inline finding body]\n"
            + body
            + "\n\n[crosschecker input tokens] "
            + inputTokens
            + "\n\n[trace excerpts]\n"
            + trace
                .lines()
                .filter(
                    l ->
                        l.contains("\"event\":\"crosscheck\"")
                            || l.contains("cross-checker")
                            || l.contains("\"event\":\"verify\"")
                            || l.contains("\"event\":\"promotion\""))
                .collect(java.util.stream.Collectors.joining("\n"))
            + "\n\n[verifier brief cited evidence excerpt]\n"
            + verifierBrief
                .lines()
                .filter(
                    l ->
                        l.contains("commit ")
                            || l.contains("Known incidents")
                            || l.contains("enterprise-session-window")
                            || l.contains("INC-42"))
                .collect(java.util.stream.Collectors.joining("\n"))
            + "\n");
  }

  @Test
  void judgeBriefAlsoCarriesCitedContradictionForContextFindings() {
    // 03§4.2：Judge brief 同规则分型——否则仲裁者对代码外证据失明
    scripted.review(EMPTY_FINDINGS);
    scripted.crossChecker(contextFinding("important"));
    scripted.verifierScript(
        brief ->
            "{\"verdicts\":[{\"finding_id\":\"%s\",\"verdict\":\"INSUFFICIENT_EVIDENCE\","
                    .formatted(firstIdIn(brief))
                + "\"rationale\":\"代码本身无法判断外部会话窗口\"}]}");
    scripted.judge(
        "{\"judgement\":{\"verdict\":\"CONFIRMED\",\"rationale\":\"L8 与 INC-42 矛盾成立\"}}");

    ReviewResult result = pipeline(enabled()).run(request(Depth.DEEP));

    assertThat(result.stats().confirmed()).isEqualTo(1);
    String judgeBrief = scripted.judgeCalls.get(0).userPrompt();
    assertThat(judgeBrief)
        .contains("enterprise-session-window: 8h")
        .contains("INC-42")
        .contains("矛盾来源");
  }

  @Test
  void crossCheckerFindsContradictionInEventHistoryAndCitesEventId() throws Exception {
    ProductEvent event =
        new ProductEvent(
            "EVT-42",
            "o/r",
            "billing",
            null,
            Instant.now().minus(10, ChronoUnit.DAYS),
            "incident",
            "2026-08-14 会话超时事故：企业长会话窗口被改为 8h（SessionKeepAlive）",
            "https://tracker/EVT-42",
            List.of(oldSha),
            List.of(FILE),
            "{}",
            Instant.now());
    eventHistoryQuery = new SingleEventQuery("o/r", event);
    scripted.review(EMPTY_FINDINGS);
    scripted.crossChecker(contextFindingWithEvent("important"));

    ReviewResult result = pipeline(enabled(), eventHistoryQuery).run(request(Depth.DEEP));

    // 事件史进入 CrossChecker brief（路径相关 + event id 供引用）
    String crossPrompt = scripted.crossCheckerCalls.get(0).userPrompt();
    assertThat(crossPrompt)
        .contains("相关产品事件史")
        .contains("- event EVT-42")
        .contains("type=incident")
        .contains("2026-08-14 会话超时事故")
        .contains("paths=" + FILE);

    // 验证层分型组装：按 event id 预取事件条目（摘要/类型/来源），context 类不再失明
    String verifierBrief = scripted.verifierCalls.get(0).userPrompt();
    assertThat(verifierBrief)
        .contains("被引事件史条目")
        .contains("event EVT-42")
        .contains("type=incident")
        .contains("source=https://tracker/EVT-42")
        .contains("企业长会话窗口被改为 8h");

    // 评审→验证→晋升：context 类 confirmed 并发布，引用 event id 随 finding 正文可见
    assertThat(result.status()).isEqualTo(RunStatus.SUCCESS);
    assertThat(result.stats().confirmed()).isEqualTo(1);
    ArgumentCaptor<PostReviewCommand> captor = ArgumentCaptor.forClass(PostReviewCommand.class);
    verify(adapter).postReview(captor.capture());
    String body = captor.getValue().inlineComments().get(0).body();
    assertThat(body).contains("cat=context").contains("event EVT-42");

    String trace = traceOf(result);
    assertThat(trace)
        .contains("\"event\":\"crosscheck\"")
        .contains("\"events_available\":true")
        .contains("\"events_count\":1");
  }

  // ---------- 触发条件（05§5 默认关闭 / 02§4.3 档位联动 / T1.4 段落缺失优雅跳过） ----------

  @Test
  void disabledByDefaultDoesNotDispatchOrChangeBaseline() throws Exception {
    scripted.review(EMPTY_FINDINGS);

    ReviewResult result = pipeline(CrossCheckerOptions.disabled()).run(request(Depth.DEEP));

    assertThat(result.status()).isEqualTo(RunStatus.SUCCESS);
    assertThat(scripted.crossCheckerCalls).isEmpty();
    assertThat(traceOf(result)).doesNotContain("\"event\":\"crosscheck\"");
  }

  @Test
  void quickDepthDoesNotTriggerAndDeclaresSkip() throws Exception {
    scripted.review(EMPTY_FINDINGS);
    // <300 行自动降为 quick 质量（02§4.3），CrossChecker 不派发，跳过原因进覆盖声明
    ReviewResult result = pipeline(enabled()).run(request(Depth.STANDARD));

    assertThat(scripted.crossCheckerCalls).isEmpty();
    assertThat(traceOf(result)).contains("quick 档不触发");
  }

  @Test
  void standardScaleInputStaysWithinBudgetAndMatchesDocArithmetic() {
    // 04§2.2 增量算术复核：10 文件 × 200 行 diff + 8 周变更史 + 产品段，按 03§6 上限裁剪后
    // CrossChecker 单次输入应落在 ≈14k（文档算术）且 ≤16k（更严锚；预算行 32k，2026-10-31 翻倍）
    java.util.List<org.cra.common.git.DiffFile> files = new java.util.ArrayList<>();
    for (int i = 0; i < 10; i++) {
      StringBuilder patch = new StringBuilder("@@ -1,1 +1,200 @@\n");
      for (int line = 0; line < 200; line++) {
        patch.append("+  int value").append(line).append(" = compute();\n");
      }
      files.add(
          new org.cra.common.git.DiffFile(
              "src/main/java/demo/File" + i + ".java",
              null,
              "modified",
              patch.toString(),
              List.of()));
    }
    java.util.List<org.cra.common.git.CommitHistoryEntry> history = new java.util.ArrayList<>();
    for (int i = 0; i < 120; i++) {
      history.add(
          new org.cra.common.git.CommitHistoryEntry(
              String.format("%040x", i + 1),
              "feat: 企业长会话定制与超时参数调整（第 " + i + " 次提交，携带较长的说明文本用于预算测量）",
              java.time.Instant.parse("2026-09-08T10:00:00Z")));
    }
    java.util.List<String> productContext = new java.util.ArrayList<>();
    for (int i = 0; i < 80; i++) {
      productContext.add("产品背景条目 " + i + "：后台任务默认在常规会话内跑完，企业客户走长会话定制，数据导出时长上限 30 分钟");
      // 第二条同主题背景（把产品段推到 03§6 的 ≤2k 预算附近，便于测量）
      productContext.add("补充背景 " + i + "：会话续期由网关统一处理，客户端不得自行假设固定会话窗口");
    }
    java.util.List<String> incidents = new java.util.ArrayList<>();
    for (int i = 0; i < 10; i++) {
      incidents.add("2026-08-14 会话超时事故：隐含假设被六周前企业定制破坏（INC-" + i + "）");
    }
    ReviewPolicy policy =
        new ReviewPolicy(
            true,
            List.of(),
            null,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            productContext,
            incidents,
            java.util.List.of("超时/重试类参数不得超过上游服务超时的 80%，导出报表 totals 口径必须与明细表一致"));
    var materials =
        org.cra.core.internal.pipeline.CrossCheckMaterials.build(
            files, history, 8, policy, true, true);
    var brief =
        new CrossCheckerDefinition(new TemplateLoader())
            .buildBrief(
                new org.cra.agents.api.ReviewAgentInput(
                    "PR #7",
                    "c1 chore",
                    materials.diffDigest(),
                    "",
                    "",
                    materials.historyText(),
                    materials.trustedSections(),
                    Set.of("src/main/java/demo/File0.java")));
    long inputTokens = TokenMath.estimateTokens(brief.systemPrompt() + brief.userPrompt());
    System.out.println(
        "MEASURED CrossChecker input tokens (standard 10-file scale) = "
            + inputTokens
            + " (diff="
            + TokenMath.estimateTokens(materials.diffDigest())
            + ", history="
            + TokenMath.estimateTokens(materials.historyText())
            + ", context="
            + TokenMath.estimateTokens(materials.trustedSections())
            + ", output budget="
            + brief.budget().maxOutputTokens()
            + ")");
    assertThat(inputTokens).isLessThanOrEqualTo(16_000);
    assertThat(TokenMath.estimateTokens(materials.diffDigest()))
        .isLessThanOrEqualTo(
            org.cra.core.internal.pipeline.CrossCheckMaterials.DIFF_TOKEN_CAP + 10);
    assertThat(TokenMath.estimateTokens(materials.historyText()))
        .isLessThanOrEqualTo(
            org.cra.core.internal.pipeline.CrossCheckMaterials.HISTORY_TOKEN_CAP + 10);
  }

  // ---------- fixture 构造 ----------

  private ReviewPipeline pipeline(CrossCheckerOptions options) {
    return pipeline(options, null);
  }

  private ReviewPipeline pipeline(CrossCheckerOptions options, EventHistoryQuery eventQuery) {
    ReviewCrew crew =
        new ReviewCrew(
            List.of(
                new BugHunterDefinition(
                    BugHunterDefinition.Perspective.DATAFLOW, new TemplateLoader()),
                new BugHunterDefinition(
                    BugHunterDefinition.Perspective.BOUNDARY, new TemplateLoader()),
                new BugHunterDefinition(
                    BugHunterDefinition.Perspective.CONCURRENCY, new TemplateLoader()),
                new BugHunterDefinition(
                    BugHunterDefinition.Perspective.RESOURCE, new TemplateLoader())),
            null,
            null,
            null,
            null,
            new CrossCheckerDefinition(new TemplateLoader()));
    return new ReviewPipeline(
        adapter,
        ledger,
        scripted,
        crew,
        new VerifierAgentDefinition(new TemplateLoader()),
        new JudgeAgentDefinition(new TemplateLoader()),
        runRoot,
        PipelineOptions.defaults(),
        new JgitLocalRepoContext(workspace),
        options,
        org.cra.core.api.SignoffOptions.disabled(),
        eventQuery);
  }

  private static CrossCheckerOptions enabled() {
    return new CrossCheckerOptions(
        true, Set.of(CrossCheckerOptions.Mode.ASSUMPTION, CrossCheckerOptions.Mode.SNIFF), 8);
  }

  private ReviewRequest request(Depth depth) {
    return new ReviewRequest("o", "r", 7, BASE, headSha, depth, false, false);
  }

  private DiffResult diff() {
    return new DiffResult(
        BASE,
        headSha,
        List.of(new DiffFile(FILE, null, "modified", PATCH, List.of(new DiffHunk(3, 3, 5, 6)))),
        List.of(new CommitSummary(headSha, "chore: update SessionKeepAlive")));
  }

  private String contextFinding(String severity) {
    return """
        {"findings":[{"category":"context","title":"会话超时假设与企业长会话定制矛盾",
          "description":"shouldKeepAlive 以 30 分钟为硬边界，但六周前企业定制把会话窗口延长到 8h",
          "failure_mode":"触发条件：企业长会话客户；错误结果：30 分钟后会话被提前回收",
          "file":"%s","line":8,"side":"RIGHT",
          "evidence":"L8: return sessionAge.compareTo(Duration.ofMinutes(30)) < 0;\\n矛盾来源：\\n- commit %s path %s — 企业长会话定制 8h\\n- REVIEW.md Known incidents: 2026-08-14 会话超时事故",
          "severity_proposed":"%s","pre_existing_suspect":false,"confidence":0.9}]}"""
        .formatted(FILE, oldSha, FILE, severity);
  }

  private String traceOf(ReviewResult result) throws Exception {
    return Files.readString(
        Path.of(result.traceRef().substring("file://".length())).resolve("trace.ndjson"));
  }

  private String contextFindingWithEvent(String severity) {
    return """
        {"findings":[{"category":"context","title":"会话超时假设与产品事件史矛盾",
          "description":"shouldKeepAlive 以 30 分钟为硬边界，但产品事件史记录企业长会话窗口 8h",
          "failure_mode":"触发条件：企业长会话客户；错误结果：30 分钟后会话被提前回收",
          "file":"%s","line":8,"side":"RIGHT",
          "evidence":"L8: return sessionAge.compareTo(Duration.ofMinutes(30)) < 0;\\n矛盾来源：\\n- event EVT-42 — 2026-08-14 会话超时事故",
          "severity_proposed":"%s","pre_existing_suspect":false,"confidence":0.9}]}"""
        .formatted(FILE, severity);
  }

  /** 单事件查询端口（只服务本 PR 的 repo；不触 PG）。 */
  private static final class SingleEventQuery implements EventHistoryQuery {

    private final String repo;
    private final ProductEvent event;

    private SingleEventQuery(String repo, ProductEvent event) {
      this.repo = repo;
      this.event = event;
    }

    @Override
    public List<ProductEvent> search(EventQuery query) {
      if (query == null || query.repo() == null || !query.repo().equals(repo)) {
        return List.of();
      }
      if (query.since() != null && event.occurredAt().isBefore(query.since())) {
        return List.of();
      }
      return List.of(event);
    }

    @Override
    public Optional<ProductEvent> find(String repo, String eventId) {
      return this.repo.equals(repo) && event.eventId().equals(eventId)
          ? Optional.of(event)
          : Optional.empty();
    }
  }

  private static String firstIdIn(String userPrompt) {
    java.util.regex.Matcher matcher =
        java.util.regex.Pattern.compile("f_[0-9a-f]+").matcher(userPrompt);
    return matcher.find() ? matcher.group() : "f_missing";
  }

  /** 真实 git fixture：root → 六周前企业定制（命中路径）→ head（本 PR commit，应被排除）。 */
  private void initFixtureRepo() throws Exception {
    git("init", "-q");
    Files.writeString(workspace.resolve("README.md"), "demo\n");
    git("add", ".");
    commitAt("2026-06-01T10:00:00+0000", "chore: init");
    Files.createDirectories(workspace.resolve(FILE).getParent());
    Files.writeString(workspace.resolve(FILE), OLD_CONTENT);
    git("add", ".");
    commitAt("2026-09-08T10:00:00+0000", "feat(enterprise): 长会话定制 8h（企业客户）");
    oldSha = git("rev-parse", "HEAD").strip();
    Files.writeString(workspace.resolve(FILE), FILE_CONTENT);
    git("add", ".");
    commitAt("2026-10-20T10:00:00+0000", "chore: update SessionKeepAlive");
    headSha = git("rev-parse", "HEAD").strip();
  }

  private void commitAt(String date, String message) throws Exception {
    java.util.List<String> cmd =
        java.util.List.of(
            "git", "-c", "user.name=alice", "-c", "user.email=a@x", "commit", "-q", "-m", message);
    ProcessBuilder builder = new ProcessBuilder(cmd).directory(workspace.toFile());
    builder.environment().put("GIT_COMMITTER_DATE", date);
    builder.environment().put("GIT_AUTHOR_DATE", date);
    Process p = builder.start();
    p.waitFor();
  }

  private String git(String... args) throws Exception {
    java.util.List<String> cmd = new java.util.ArrayList<>();
    cmd.add("git");
    cmd.addAll(java.util.List.of(args));
    Process p = new ProcessBuilder(cmd).directory(workspace.toFile()).start();
    p.waitFor();
    return new String(p.getInputStream().readAllBytes());
  }

  /**
   * 键控扩展（05§1.3）兼容桩：pipeline 走 ReviewKey 方法，测试的既有 int 键 stub 通过委托继续生效 （Mockito 不调用 default
   * 方法，必须显式桥接）。
   */
  private void bridgeKeyedLedgerToIntStubs() {
    when(ledger.findCompletion(any(org.cra.common.git.ReviewKey.class)))
        .thenAnswer(
            inv -> {
              org.cra.common.git.ReviewKey k = inv.getArgument(0);
              return ledger.findCompletion(k.prNumber(), k.commitSha());
            });
    when(ledger.publishedFindings(any(org.cra.common.git.ReviewKey.class)))
        .thenAnswer(
            inv ->
                ledger.publishedFindings(
                    ((org.cra.common.git.ReviewKey) inv.getArgument(0)).prNumber()));
    when(ledger.completedRuns(any(org.cra.common.git.ReviewKey.class)))
        .thenAnswer(
            inv ->
                ledger.completedRuns(
                    ((org.cra.common.git.ReviewKey) inv.getArgument(0)).prNumber()));
    when(ledger.suspectMarkerCount(any(org.cra.common.git.ReviewKey.class)))
        .thenAnswer(
            inv ->
                ledger.suspectMarkerCount(
                    ((org.cra.common.git.ReviewKey) inv.getArgument(0)).prNumber()));
    when(ledger.comment(any(org.cra.common.git.ReviewKey.class), anyLong()))
        .thenAnswer(
            inv ->
                ledger.comment(
                    ((org.cra.common.git.ReviewKey) inv.getArgument(0)).prNumber(),
                    inv.getArgument(1)));
    doAnswer(
            inv -> {
              org.cra.common.git.ReviewKey k = inv.getArgument(0);
              ledger.recordRunCompletion(k.prNumber(), inv.getArgument(1));
              return null;
            })
        .when(ledger)
        .recordRunCompletion(
            any(org.cra.common.git.ReviewKey.class), any(org.cra.common.git.LedgerRunMarker.class));
    doAnswer(
            inv -> {
              org.cra.common.git.ReviewKey k = inv.getArgument(0);
              ledger.recordPublishedFinding(k.prNumber(), inv.getArgument(1));
              return null;
            })
        .when(ledger)
        .recordPublishedFinding(
            any(org.cra.common.git.ReviewKey.class),
            any(org.cra.common.git.LedgerFindingMarker.class));
    doAnswer(
            inv -> {
              org.cra.common.git.ReviewKey k = inv.getArgument(0);
              ledger.recordTemplateVersions(k.prNumber(), inv.getArgument(1), inv.getArgument(2));
              return null;
            })
        .when(ledger)
        .recordTemplateVersions(any(org.cra.common.git.ReviewKey.class), anyString(), anyMap());
  }
}
