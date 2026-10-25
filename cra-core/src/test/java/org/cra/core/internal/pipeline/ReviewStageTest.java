package org.cra.core.internal.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.cra.agents.api.AgentBrief;
import org.cra.agents.api.AgentBudget;
import org.cra.agents.api.AgentRole;
import org.cra.agents.api.BugHunterDefinition;
import org.cra.agents.api.ReviewAgentInput;
import org.cra.agents.api.ReviewCrew;
import org.cra.agents.api.SubAgent;
import org.cra.agents.api.ToolWhitelist;
import org.cra.common.context.AssembledContext;
import org.cra.common.context.ReviewPolicy;
import org.cra.common.finding.CandidateFinding;
import org.cra.common.finding.FindingCategory;
import org.cra.common.finding.Severity;
import org.cra.common.git.DiffResult;
import org.cra.common.run.Depth;
import org.cra.core.api.PipelineOptions;
import org.cra.core.internal.AgentRunner;
import org.cra.core.internal.TraceWriter;
import org.cra.llm.api.LlmGateway;
import org.cra.llm.api.LlmRequest;
import org.cra.llm.api.LlmResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T2.2 评审扇出编排测试（03§2.2/03§3）：视角路由、70% 降档砍视角、100% 终止、失败隔离、跨代理精确合并； LLM 用脚本化假网关（08§3.1），代理用桩定义（纯定义不触
 * LLM）。
 */
class ReviewStageTest {

  private static final String FINDINGS_JSON =
      """
      {"findings": [{"category": "bug", "title": "t", "description": "d", \
      "failure_mode": "f", "file": "src/Foo.java", "line": 3, \
      "evidence": "e", "severity_proposed": "nit"}]}
      """;

  @TempDir Path runRoot;

  private TraceWriter trace;

  @BeforeEach
  void setUp() {
    trace = new TraceWriter(runRoot, "run_stage");
  }

  /** 桩代理定义：固定 brief，不触模板。 */
  private static SubAgent<ReviewAgentInput> stubAgent(String roleId, AgentBudget budget) {
    AgentRole role = new AgentRole(roleId, roleId);
    return new SubAgent<>() {
      @Override
      public AgentRole role() {
        return role;
      }

      @Override
      public AgentBrief buildBrief(ReviewAgentInput input) {
        return new AgentBrief(role, "# System\ns", "u", "t@00000000", ToolWhitelist.NONE, budget);
      }

      @Override
      public ToolWhitelist tools() {
        return ToolWhitelist.NONE;
      }

      @Override
      public AgentBudget budget() {
        return budget;
      }
    };
  }

  private static ReviewCrew stubCrew() {
    AgentBudget bh = new AgentBudget(24_000, 2_000, 3, 240_000);
    return ReviewCrew.ofBugHunters(
        List.of(
            stubAgent("bug-hunter/dataflow", bh),
            stubAgent("bug-hunter/boundary", bh),
            stubAgent("bug-hunter/concurrency", bh),
            stubAgent("bug-hunter/resource", bh)));
  }

  /** 脚本化假网关：记录 caller，按序回放（耗尽后重复末条）。 */
  private static final class ScriptedGateway implements LlmGateway {

    // 并发派发下被多线程调用：线程安全集合 + 并发下顺序不保证（断言用计数）
    private final java.util.Queue<String> callers =
        new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final List<LlmResponse> script;
    private final AtomicInteger index = new AtomicInteger();

    ScriptedGateway(LlmResponse... responses) {
      this.script = List.of(responses);
    }

    java.util.Queue<String> callers() {
      return callers;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
      callers.add(request.caller());
      int i = Math.min(index.getAndIncrement(), script.size() - 1);
      return script.get(i);
    }
  }

  private ReviewStage.StageOutcome runStage(
      ReviewCrew crew, LlmGateway gateway, Depth depth, PipelineOptions options, int chunkCount) {
    ReviewStage stage =
        new ReviewStage(
            crew,
            new AgentRunner(gateway),
            input ->
                new AssembledContext(
                    "diff-text", "window-text", 1, 1, List.of(), List.of(), 10, List.of()));
    List<ChunkPlanner.Chunk> chunks = new ArrayList<>();
    for (int i = 0; i < chunkCount; i++) {
      chunks.add(new ChunkPlanner.Chunk("mod" + i, List.of()));
    }
    ChunkPlanner.Plan plan = new ChunkPlanner.Plan(chunks, List.of(), List.of());
    DepthPlan depthPlan = new DepthPlan(depth, 30, true, 200, false, List.of());
    return stage.execute(
        "run_stage",
        trace,
        null, // diff 参数随 T2.3 专角色触发判定启用
        ReviewPolicy.empty(),
        depthPlan,
        plan,
        Map.of(),
        new RunBudgetTracker(options),
        "PR #1",
        "abc fix",
        "");
  }

  @Test
  void routePerspectivesFollowsDepthTable() {
    // 02§4.3：quick 1 / standard 2 / deep 3–4
    assertThat(ReviewStage.routePerspectives(Depth.QUICK))
        .containsExactly(BugHunterDefinition.Perspective.DATAFLOW);
    assertThat(ReviewStage.routePerspectives(Depth.STANDARD))
        .containsExactly(
            BugHunterDefinition.Perspective.DATAFLOW, BugHunterDefinition.Perspective.BOUNDARY);
    assertThat(ReviewStage.routePerspectives(Depth.DEEP))
        .containsExactly(
            BugHunterDefinition.Perspective.DATAFLOW,
            BugHunterDefinition.Perspective.BOUNDARY,
            BugHunterDefinition.Perspective.CONCURRENCY,
            BugHunterDefinition.Perspective.RESOURCE);
  }

  @Test
  void degradePerspectivesDropsOneKeepsOne() {
    assertThat(ReviewStage.degradePerspectives(ReviewStage.routePerspectives(Depth.DEEP)))
        .hasSize(3);
    assertThat(ReviewStage.degradePerspectives(ReviewStage.routePerspectives(Depth.STANDARD)))
        .containsExactly(BugHunterDefinition.Perspective.DATAFLOW);
    assertThat(ReviewStage.degradePerspectives(ReviewStage.routePerspectives(Depth.QUICK)))
        .containsExactly(BugHunterDefinition.Perspective.DATAFLOW);
  }

  @Test
  void quickDepthDispatchesOneAgentPerChunk() {
    ScriptedGateway gateway = new ScriptedGateway(LlmResponse.ok(FINDINGS_JSON, 10, 10, 1, "m"));
    ReviewStage.StageOutcome outcome =
        runStage(stubCrew(), gateway, Depth.QUICK, PipelineOptions.defaults(), 2);

    assertThat(gateway.callers()).hasSize(2).allMatch(c -> c.equals("bug-hunter/dataflow"));
    // 两 chunk 同位置候选在汇聚时被精确合并（03§4.5，FindingMerger）
    assertThat(outcome.findings()).hasSize(1);
    assertThat(outcome.contextNotes()).anyMatch(n -> n.contains("跨代理精确去重合并 1 条"));
    assertThat(outcome.llmUnavailable()).isFalse();
  }

  @Test
  void standardDepthDispatchesTwoPerspectivesPerChunk() {
    ScriptedGateway gateway = new ScriptedGateway(LlmResponse.ok(FINDINGS_JSON, 10, 10, 1, "m"));
    ReviewStage.StageOutcome outcome =
        runStage(stubCrew(), gateway, Depth.STANDARD, PipelineOptions.defaults(), 1);

    assertThat(gateway.callers())
        .containsExactlyInAnyOrder("bug-hunter/dataflow", "bug-hunter/boundary");
  }

  @Test
  void budgetDegradeCutsOnePerspectiveForRemainingChunks() {
    // 5 chunk × 2 视角 = 10 派发 > 并发 8：首 8 个即时派发；每代理 60 token、runTotal=150
    // → 第 2 次收集后比例 0.8 → DEGRADE，chunk5 只派 1 视角（dataflow），100% 未触发
    ScriptedGateway gateway = new ScriptedGateway(LlmResponse.ok(FINDINGS_JSON, 60, 0, 1, "m"));
    ReviewStage.StageOutcome outcome =
        runStage(
            stubCrew(),
            gateway,
            Depth.STANDARD,
            new PipelineOptions(150, 0.70, 0.85, 2000, 8000, 3, "never", 20),
            5);

    // c1–c4 各 2 视角 + DEGRADE 后 chunk5 仅 dataflow = 9 派发（dataflow 5 / boundary 4）
    assertThat(gateway.callers()).hasSize(9);
    assertThat(java.util.Collections.frequency(gateway.callers(), "bug-hunter/dataflow"))
        .isEqualTo(5);
    assertThat(java.util.Collections.frequency(gateway.callers(), "bug-hunter/boundary"))
        .isEqualTo(4);
    assertThat(outcome.degradations()).contains("budget-degrade-70");
    assertThat(outcome.stoppedByBudget()).isFalse();
  }

  @Test
  void budgetStopTerminatesRemainingDispatches() {
    // 5 chunk × 2 视角 = 10 派发 > 并发 8；runTotal=10：首个代理收集后即跨 100%
    // → 剩余未派发 chunk（chunk5）终止，DEGRADED 收尾（02§4.4）
    ScriptedGateway gateway = new ScriptedGateway(LlmResponse.ok(FINDINGS_JSON, 10, 0, 1, "m"));
    ReviewStage.StageOutcome outcome =
        runStage(
            stubCrew(),
            gateway,
            Depth.STANDARD,
            new PipelineOptions(10, 0.70, 0.85, 2000, 8000, 3, "never", 20),
            5);

    assertThat(outcome.degradations()).contains("budget-exhausted-stop");
    assertThat(outcome.stoppedByBudget()).isTrue();
    assertThat(gateway.callers()).hasSize(8);
  }

  @Test
  void singleAgentFailureDoesNotInfectOthers() {
    // dataflow 收到 UNAVAILABLE、boundary 正常 → 部分缺失 = DEGRADED 语义（02§4.2 失败隔离）
    LlmGateway flaky =
        request ->
            "bug-hunter/dataflow".equals(request.caller())
                ? LlmResponse.unavailable("retries exhausted", 0, 5)
                : LlmResponse.ok(FINDINGS_JSON, 10, 10, 1, "m");
    ReviewStage.StageOutcome outcome =
        runStage(stubCrew(), flaky, Depth.STANDARD, PipelineOptions.defaults(), 1);

    assertThat(outcome.llmUnavailable()).isFalse();
    assertThat(outcome.findings()).hasSize(1);
    assertThat(outcome.degradations()).anyMatch(d -> d.startsWith("llm-unavailable-agents:"));
  }

  @Test
  void allAgentsUnavailableMarksRunLevelFailure() {
    LlmGateway down = request -> LlmResponse.unavailable("retries exhausted", 0, 5);
    ReviewStage.StageOutcome outcome =
        runStage(stubCrew(), down, Depth.STANDARD, PipelineOptions.defaults(), 1);

    assertThat(outcome.llmUnavailable()).isTrue();
    assertThat(outcome.llmError()).isEqualTo("retries exhausted");
  }

  @Test
  void agentBriefFailureIsIsolated() {
    // buildBrief 抛异常的代理不传染其他代理（03§2.3）：dataflow 桩坏，boundary 正常产出
    AgentRole bad = new AgentRole("bug-hunter/dataflow", "broken");
    SubAgent<ReviewAgentInput> broken =
        new SubAgent<>() {
          @Override
          public AgentRole role() {
            return bad;
          }

          @Override
          public AgentBrief buildBrief(ReviewAgentInput input) {
            throw new IllegalStateException("template boom");
          }

          @Override
          public ToolWhitelist tools() {
            return ToolWhitelist.NONE;
          }

          @Override
          public AgentBudget budget() {
            return new AgentBudget(24_000, 2_000, 3, 240_000);
          }
        };
    ReviewCrew crew =
        ReviewCrew.ofBugHunters(
            List.of(
                broken,
                stubAgent("bug-hunter/boundary", new AgentBudget(24_000, 2_000, 3, 240_000)),
                stubAgent("bug-hunter/concurrency", new AgentBudget(24_000, 2_000, 3, 240_000)),
                stubAgent("bug-hunter/resource", new AgentBudget(24_000, 2_000, 3, 240_000))));
    ScriptedGateway gateway = new ScriptedGateway(LlmResponse.ok(FINDINGS_JSON, 10, 10, 1, "m"));
    ReviewStage.StageOutcome outcome =
        runStage(crew, gateway, Depth.STANDARD, PipelineOptions.defaults(), 1);

    assertThat(outcome.findings()).hasSize(1);
    assertThat(outcome.degradations()).contains("agent-failed:bug-hunter/dataflow");
  }

  @Test
  void mergePreciseCollapsesSamePositionKeepingImportant() throws Exception {
    CandidateFinding nitA = finding("f_a", "src/Foo.java", 3, FindingCategory.BUG, Severity.NIT);
    CandidateFinding importantB =
        finding("f_b", "src/Foo.java", 4, FindingCategory.BUG, Severity.IMPORTANT);
    CandidateFinding distant =
        finding("f_c", "src/Foo.java", 400, FindingCategory.BUG, Severity.NIT);
    CandidateFinding otherCategory =
        finding("f_d", "src/Foo.java", 3, FindingCategory.PERFORMANCE, Severity.NIT);

    List<CandidateFinding> merged =
        FindingMerger.mergePrecise(List.of(nitA, importantB, distant, otherCategory), trace);

    // f_b（important）保留、f_a 丢弃（in-run-exact）、远行与异类保留
    assertThat(merged).extracting(CandidateFinding::id).containsExactly("f_b", "f_c", "f_d");
    Path traceFile = runRoot.resolve("run_stage").resolve("trace.ndjson");
    assertThat(Files.readString(traceFile, StandardCharsets.UTF_8)).contains("in-run-exact");
  }

  @Test
  void mergePreciseKeepsFirstOnSeverityTie() {
    CandidateFinding first = finding("f_1", "src/A.java", 10, FindingCategory.BUG, Severity.NIT);
    CandidateFinding second = finding("f_2", "src/A.java", 12, FindingCategory.BUG, Severity.NIT);
    List<CandidateFinding> merged = FindingMerger.mergePrecise(List.of(first, second), trace);
    assertThat(merged).extracting(CandidateFinding::id).containsExactly("f_1");
  }

  @Test
  void securityDispatchedPerChunkWithGrepTools() {
    // 03§6 v1.2：Security 每 chunk 1 实例；standard + 2 chunk → 2 BH ×2 + Security ×2 = 6 派发
    ReviewCrew crew =
        new ReviewCrew(
            stubCrew().bugHunters(),
            stubAgent("security-reviewer", new AgentBudget(24_000, 2_000, 3, 240_000)),
            null,
            null,
            null);
    ScriptedGateway gateway = new ScriptedGateway(LlmResponse.ok(FINDINGS_JSON, 10, 10, 1, "m"));
    ReviewStage.StageOutcome outcome =
        runStage(crew, gateway, Depth.STANDARD, PipelineOptions.defaults(), 2);

    assertThat(java.util.Collections.frequency(gateway.callers(), "security-reviewer"))
        .isEqualTo(2);
    assertThat(gateway.callers()).hasSize(6);
    assertThat(outcome.findings()).isNotEmpty();
  }

  @Test
  void testReviewerTriggeredOnlyWhenTestRatioLow() {
    // 03§3：逻辑文件变更且测试变更比例低；≥0.5 不触发
    DiffResult logicOnly =
        new DiffResult(
            "b",
            "h",
            List.of(
                new org.cra.common.git.DiffFile(
                    "src/A.java", null, "modified", "@@ -1 +1 @@", List.of())),
            List.of());
    DiffResult balanced =
        new DiffResult(
            "b",
            "h",
            List.of(
                new org.cra.common.git.DiffFile(
                    "src/A.java", null, "modified", "@@ -1 +1 @@", List.of()),
                new org.cra.common.git.DiffFile(
                    "src/test/A.java", null, "modified", "@@ -1 +1 @@", List.of())),
            List.of());
    assertThat(ReviewStage.testReviewerTriggered(logicOnly)).isTrue();
    assertThat(ReviewStage.testReviewerTriggered(balanced)).isFalse();
  }

  @Test
  void conventionTriggeredOnlyWithConventionsConfigured() {
    assertThat(ReviewStage.conventionTriggered(ReviewPolicy.empty())).isFalse();
    ReviewPolicy withConventions =
        new ReviewPolicy(
            true,
            List.of(),
            null,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of("禁止使用 Fastjson"),
            List.of(),
            List.of(),
            List.of());
    assertThat(ReviewStage.conventionTriggered(withConventions)).isTrue();
  }

  @Test
  void perfTriggeredOnlyInDeep() {
    assertThat(
            ReviewStage.perfTriggered(
                new DepthPlan(Depth.STANDARD, 30, true, 200, false, List.of())))
        .isFalse();
    assertThat(
            ReviewStage.perfTriggered(new DepthPlan(Depth.DEEP, 60, true, 400, false, List.of())))
        .isTrue();
  }

  @Test
  void wholePrSpecialistsDispatchAfterChunks() {
    // Test（测试比例低触发）+ Convention（约定已配置）在 chunk 扇出后派发
    ReviewCrew crew =
        new ReviewCrew(
            stubCrew().bugHunters(),
            null,
            stubAgent("test-reviewer", new AgentBudget(12_000, 1_000, 2, 120_000)),
            stubAgent("convention-reviewer", new AgentBudget(8_000, 1_000, 1, 60_000)),
            null);
    ScriptedGateway gateway = new ScriptedGateway(LlmResponse.ok(FINDINGS_JSON, 10, 10, 1, "m"));
    ReviewStage.StageOutcome outcome =
        runStageWithDiff(
            crew,
            gateway,
            Depth.STANDARD,
            PipelineOptions.defaults(),
            1,
            new DiffResult(
                "b",
                "h",
                List.of(
                    new org.cra.common.git.DiffFile(
                        "src/A.java", null, "modified", "@@ -1 +1 @@", List.of())),
                List.of()),
            withConventionsPolicy());

    assertThat(java.util.Collections.frequency(gateway.callers(), "test-reviewer")).isEqualTo(1);
    assertThat(java.util.Collections.frequency(gateway.callers(), "convention-reviewer"))
        .isEqualTo(1);
    // 1 chunk 标准档 2 个 BugHunter + Test + Convention = 4 次派发
    assertThat(gateway.callers()).hasSize(4);
    assertThat(outcome.findings()).isNotEmpty();
  }

  @Test
  void conventionDroppedAtDegradePhase() {
    // 70% 档砍 light 角色（02§4.4）：预算即时进入 DEGRADE → Convention 不派发，Test（标准档）照常
    ReviewCrew crew =
        new ReviewCrew(
            stubCrew().bugHunters(),
            null,
            stubAgent("test-reviewer", new AgentBudget(12_000, 1_000, 2, 120_000)),
            stubAgent("convention-reviewer", new AgentBudget(8_000, 1_000, 1, 60_000)),
            null);
    ScriptedGateway gateway = new ScriptedGateway(LlmResponse.ok(FINDINGS_JSON, 150, 0, 1, "m"));
    ReviewStage.StageOutcome outcome =
        runStageWithDiff(
            crew,
            gateway,
            Depth.QUICK,
            new PipelineOptions(250, 0.70, 0.85, 2000, 8000, 3, "never", 20),
            2,
            new DiffResult(
                "b",
                "h",
                List.of(
                    new org.cra.common.git.DiffFile(
                        "src/A.java", null, "modified", "@@ -1 +1 @@", List.of())),
                List.of()),
            withConventionsPolicy());

    // 两个 chunk 在 NORMAL 相位派发完；收集后 1.2 ≥100% → STOP，全 PR 级专角色（Test/Convention）被拦下
    assertThat(java.util.Collections.frequency(gateway.callers(), "convention-reviewer")).isZero();
    assertThat(java.util.Collections.frequency(gateway.callers(), "test-reviewer")).isZero();
    assertThat(outcome.degradations()).contains("budget-truncate-85");
    assertThat(outcome.contextNotes()).anyMatch(n -> n.contains("全 PR 级专角色未派发"));
  }

  private ReviewStage.StageOutcome runStageWithDiff(
      ReviewCrew crew,
      LlmGateway gateway,
      Depth depth,
      PipelineOptions options,
      int chunkCount,
      DiffResult diff,
      ReviewPolicy policy) {
    ReviewStage stage =
        new ReviewStage(
            crew,
            new AgentRunner(gateway),
            input ->
                new AssembledContext(
                    "diff-text", "window-text", 1, 1, List.of(), List.of(), 10, List.of()));
    List<ChunkPlanner.Chunk> chunks = new ArrayList<>();
    for (int i = 0; i < chunkCount; i++) {
      chunks.add(new ChunkPlanner.Chunk("mod" + i, List.of()));
    }
    ChunkPlanner.Plan plan = new ChunkPlanner.Plan(chunks, List.of(), List.of());
    DepthPlan depthPlan = new DepthPlan(depth, 30, true, 200, false, List.of());
    return stage.execute(
        "run_stage",
        trace,
        diff,
        policy,
        depthPlan,
        plan,
        Map.of(),
        new RunBudgetTracker(options),
        "PR #1",
        "abc fix",
        "");
  }

  private static ReviewPolicy withConventionsPolicy() {
    return new ReviewPolicy(
        true,
        List.of(),
        null,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of("禁止使用 Fastjson"),
        List.of(),
        List.of(),
        List.of());
  }

  private static CandidateFinding finding(
      String id, String file, int line, FindingCategory category, Severity severity) {
    return new CandidateFinding(
        id,
        "run_stage",
        category,
        "title " + id,
        "desc",
        "failure",
        file,
        line,
        null,
        "evidence",
        null,
        severity,
        false,
        null,
        "bug-hunter/test",
        "m");
  }
}
