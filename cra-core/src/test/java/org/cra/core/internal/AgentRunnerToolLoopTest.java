package org.cra.core.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.cra.agents.api.AgentBrief;
import org.cra.agents.api.AgentBudget;
import org.cra.agents.api.AgentRole;
import org.cra.agents.api.ToolWhitelist;
import org.cra.common.finding.CandidateFinding;
import org.cra.common.git.FileContent;
import org.cra.llm.api.LlmGateway;
import org.cra.llm.api.LlmRequest;
import org.cra.llm.api.LlmResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T2.1 隔离运行时行为测试（03§1/03§2：工具白名单硬边界、三重预算硬限、失败隔离；LLM 用脚本化假网关—— 08§3.1 真实调用不进单测）。trace 事件与工具协议断言一并覆盖。
 */
class AgentRunnerToolLoopTest {

  private static final String FINDINGS_JSON =
      """
      {"findings": [{"category": "bug", "title": "越界", "description": "空列表越界", \
      "failure_mode": "触发条件：items 为空；错误结果：IndexOutOfBounds", \
      "file": "src/Foo.java", "line": 3, "evidence": "L3", "severity_proposed": "important"}]}
      """;
  private static final String TOOL_CALL_JSON =
      "{\"tool\": \"read_file\", \"path\": \"src/Foo.java\", \"start_line\": 1, \"end_line\": 2}";

  @TempDir Path runRoot;

  private TraceWriter trace;

  @BeforeEach
  void setUp() {
    trace = new TraceWriter(runRoot, "run_t2");
  }

  private static AgentBrief brief(ToolWhitelist tools, AgentBudget budget) {
    return new AgentBrief(
        new AgentRole("bug-hunter/test", "测试视角"),
        "# System\n测试系统提示词",
        "用户内容",
        "t@00000000",
        tools,
        budget);
  }

  private static AgentBrief defaultBrief(ToolWhitelist tools) {
    return brief(tools, new AgentBudget(24_000, 2_000, 3, 240_000));
  }

  private static AgentRunner.ToolContext toolContext(FileContent... files) {
    Map<String, FileContent> scoped = new java.util.LinkedHashMap<>();
    for (FileContent file : files) {
      scoped.put(file.path(), file);
    }
    return new AgentRunner.ToolContext(ToolWhitelist.READ_FILE, scoped);
  }

  /** 脚本化假网关：按次序回放响应（耗尽后重复最后一条），记录全部请求。 */
  private static final class ScriptedGateway implements LlmGateway {

    private final List<LlmResponse> script = new ArrayList<>();
    private final List<LlmRequest> requests = new ArrayList<>();
    private final AtomicInteger index = new AtomicInteger();

    ScriptedGateway(LlmResponse... responses) {
      script.addAll(List.of(responses));
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
      requests.add(request);
      int i = Math.min(index.getAndIncrement(), script.size() - 1);
      return script.get(i);
    }

    List<LlmRequest> allRequests() {
      return requests;
    }
  }

  @Test
  void singleTurnFinalOutputParsesFindings() {
    ScriptedGateway gateway =
        new ScriptedGateway(LlmResponse.ok(FINDINGS_JSON, 100, 20, 5, "glm-x"));
    AgentRunner.AgentRun<List<CandidateFinding>> run =
        new AgentRunner(gateway)
            .execute(
                defaultBrief(ToolWhitelist.NONE),
                AgentRunner.ToolContext.none(),
                AgentRunner.findingsParser("run_t2", "bug-hunter/test"),
                "run_t2",
                trace);

    assertThat(run.termination()).isEqualTo(AgentRunner.Termination.COMPLETED);
    assertThat(run.output()).hasSize(1);
    assertThat(run.output().get(0).originAgentRole()).isEqualTo("bug-hunter/test");
    assertThat(run.turns()).isEqualTo(1);
  }

  @Test
  void toolLoopAppendsUntrustedResultThenCompletes() {
    ScriptedGateway gateway =
        new ScriptedGateway(
            LlmResponse.ok(TOOL_CALL_JSON, 100, 20, 5, "glm-x"),
            LlmResponse.ok(FINDINGS_JSON, 150, 20, 5, "glm-x"));
    AgentRunner.AgentRun<List<CandidateFinding>> run =
        new AgentRunner(gateway)
            .execute(
                defaultBrief(ToolWhitelist.READ_FILE),
                toolContext(
                    FileContent.ofText("src/Foo.java", "package demo;\npublic class Foo {\n")),
                AgentRunner.findingsParser("run_t2", "bug-hunter/test"),
                "run_t2",
                trace);

    assertThat(run.termination()).isEqualTo(AgentRunner.Termination.COMPLETED);
    assertThat(run.output()).hasSize(1);
    assertThat(run.turns()).isEqualTo(2);
    assertThat(gateway.allRequests()).hasSize(2);
    // 第二轮请求带回了 UNTRUSTED 定界的工具结果（带行号）
    String secondPrompt = gateway.allRequests().get(1).userPrompt();
    assertThat(secondPrompt)
        .contains("UNTRUSTED_CONTENT")
        .contains("L1: package demo;")
        .contains("L2: public class Foo {");
  }

  @Test
  void toolWithoutWhitelistIsKilledImmediately() {
    ScriptedGateway gateway = new ScriptedGateway(LlmResponse.ok(TOOL_CALL_JSON, 100, 20, 5, "m"));
    AgentRunner.AgentRun<List<CandidateFinding>> run =
        new AgentRunner(gateway)
            .execute(
                defaultBrief(ToolWhitelist.NONE),
                AgentRunner.ToolContext.none(),
                AgentRunner.findingsParser("run_t2", "bug-hunter/test"),
                "run_t2",
                trace);

    // 白名单硬边界：立即终止，不再给模型第二次机会（04§5 反模式 8）
    assertThat(run.termination()).isEqualTo(AgentRunner.Termination.TOOL_VIOLATION);
    assertThat(run.output()).isNull();
    assertThat(gateway.allRequests()).hasSize(1);
  }

  @Test
  void pathOutsideScopedSetIsKilled() {
    ScriptedGateway gateway =
        new ScriptedGateway(
            LlmResponse.ok("{\"tool\": \"read_file\", \"path\": \"etc/passwd\"}", 100, 20, 5, "m"));
    AgentRunner.AgentRun<List<CandidateFinding>> run =
        new AgentRunner(gateway)
            .execute(
                defaultBrief(ToolWhitelist.READ_FILE),
                toolContext(FileContent.ofText("src/Foo.java", "package demo;\n")),
                AgentRunner.findingsParser("run_t2", "bug-hunter/test"),
                "run_t2",
                trace);

    assertThat(run.termination()).isEqualTo(AgentRunner.Termination.TOOL_VIOLATION);
    assertThat(run.errorMessage()).contains("越界");
  }

  @Test
  void undeclaredToolIsKilled() {
    ScriptedGateway gateway =
        new ScriptedGateway(
            LlmResponse.ok("{\"tool\": \"shell\", \"cmd\": \"rm -rf /\"}", 1, 1, 1, "m"));
    AgentRunner.AgentRun<List<CandidateFinding>> run =
        new AgentRunner(gateway)
            .execute(
                defaultBrief(ToolWhitelist.READ_FILE_AND_GREP),
                toolContext(FileContent.ofText("src/Foo.java", "x\n")),
                AgentRunner.findingsParser("run_t2", "bug-hunter/test"),
                "run_t2",
                trace);

    assertThat(run.termination()).isEqualTo(AgentRunner.Termination.TOOL_VIOLATION);
  }

  @Test
  void turnBudgetKillsLoopingToolCaller() {
    ScriptedGateway gateway = new ScriptedGateway(LlmResponse.ok(TOOL_CALL_JSON, 1, 1, 1, "m"));
    // maxTurns=2：两次工具调用后第三次调用被轮次硬限拦截
    AgentRunner.AgentRun<List<CandidateFinding>> run =
        new AgentRunner(gateway)
            .execute(
                brief(ToolWhitelist.READ_FILE, new AgentBudget(24_000, 2_000, 2, 240_000)),
                toolContext(FileContent.ofText("src/Foo.java", "package demo;\n")),
                AgentRunner.findingsParser("run_t2", "bug-hunter/test"),
                "run_t2",
                trace);

    assertThat(run.termination()).isEqualTo(AgentRunner.Termination.BUDGET_EXCEEDED);
    assertThat(run.errorMessage()).contains("轮次硬限");
    assertThat(gateway.allRequests()).hasSize(2);
  }

  @Test
  void inputBudgetKillsOversizedBriefBeforeCall() {
    ScriptedGateway gateway = new ScriptedGateway(LlmResponse.ok(FINDINGS_JSON, 1, 1, 1, "m"));
    AgentRunner.AgentRun<List<CandidateFinding>> run =
        new AgentRunner(gateway)
            .execute(
                brief(ToolWhitelist.NONE, new AgentBudget(5, 2_000, 3, 240_000)),
                AgentRunner.ToolContext.none(),
                AgentRunner.findingsParser("run_t2", "bug-hunter/test"),
                "run_t2",
                trace);

    assertThat(run.termination()).isEqualTo(AgentRunner.Termination.BUDGET_EXCEEDED);
    assertThat(run.errorMessage()).contains("输入超预算");
    assertThat(gateway.allRequests()).isEmpty();
  }

  @Test
  void cumulativeOutputBudgetKillsRunawayAgent() {
    ScriptedGateway gateway =
        new ScriptedGateway(
            LlmResponse.ok(TOOL_CALL_JSON, 1, 1_500, 1, "m"),
            LlmResponse.ok(TOOL_CALL_JSON, 1, 1_500, 1, "m"));
    AgentRunner.AgentRun<List<CandidateFinding>> run =
        new AgentRunner(gateway)
            .execute(
                brief(ToolWhitelist.READ_FILE, new AgentBudget(24_000, 2_000, 3, 240_000)),
                toolContext(FileContent.ofText("src/Foo.java", "x\n")),
                AgentRunner.findingsParser("run_t2", "bug-hunter/test"),
                "run_t2",
                trace);

    assertThat(run.termination()).isEqualTo(AgentRunner.Termination.BUDGET_EXCEEDED);
    assertThat(run.errorMessage()).contains("输出超预算");
  }

  @Test
  void timeBudgetKillsSlowAgent() {
    LlmGateway slow =
        request -> {
          try {
            Thread.sleep(50);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
          }
          return LlmResponse.ok(TOOL_CALL_JSON, 1, 1, 1, "m");
        };
    AgentRunner runnerWithSlow = new AgentRunner(slow);
    AgentRunner.AgentRun<List<CandidateFinding>> run =
        runnerWithSlow.execute(
            brief(ToolWhitelist.READ_FILE, new AgentBudget(24_000, 2_000, 5, 10)),
            toolContext(FileContent.ofText("src/Foo.java", "x\n")),
            AgentRunner.findingsParser("run_t2", "bug-hunter/test"),
            "run_t2",
            trace);

    assertThat(run.termination()).isEqualTo(AgentRunner.Termination.BUDGET_EXCEEDED);
    assertThat(run.errorMessage()).contains("时间硬限");
  }

  @Test
  void llmUnavailableConvergesToResultObject() {
    ScriptedGateway gateway =
        new ScriptedGateway(LlmResponse.unavailable("retries exhausted", 10, 20));
    AgentRunner.AgentRun<List<CandidateFinding>> run =
        new AgentRunner(gateway)
            .execute(
                defaultBrief(ToolWhitelist.NONE),
                AgentRunner.ToolContext.none(),
                AgentRunner.findingsParser("run_t2", "bug-hunter/test"),
                "run_t2",
                trace);

    assertThat(run.termination()).isEqualTo(AgentRunner.Termination.LLM_UNAVAILABLE);
    assertThat(run.llmUnavailable()).isTrue();
    assertThat(run.errorMessage()).isEqualTo("retries exhausted");
  }

  @Test
  void gatewayExceptionIsIsolatedAsFailedResult() {
    LlmGateway throwing =
        request -> {
          throw new IllegalStateException("boom");
        };
    AgentRunner runnerWithThrowing = new AgentRunner(throwing);
    AgentRunner.AgentRun<List<CandidateFinding>> run =
        runnerWithThrowing.execute(
            defaultBrief(ToolWhitelist.NONE),
            AgentRunner.ToolContext.none(),
            AgentRunner.findingsParser("run_t2", "bug-hunter/test"),
            "run_t2",
            trace);

    assertThat(run.termination()).isEqualTo(AgentRunner.Termination.FAILED);
    assertThat(run.errorMessage()).contains("boom");
  }

  @Test
  void schemaViolationRetriesThenDrops() {
    ScriptedGateway gateway =
        new ScriptedGateway(
            LlmResponse.ok("{\"findings\": \"not-an-array\"}", 10, 10, 1, "m"),
            LlmResponse.ok("{\"findings\": \"still-bad\"}", 10, 10, 1, "m"),
            LlmResponse.ok("{\"findings\": \"third-bad\"}", 10, 10, 1, "m"));
    AgentRunner.AgentRun<List<CandidateFinding>> run =
        new AgentRunner(gateway)
            .execute(
                defaultBrief(ToolWhitelist.NONE),
                AgentRunner.ToolContext.none(),
                AgentRunner.findingsParser("run_t2", "bug-hunter/test"),
                "run_t2",
                trace);

    assertThat(run.termination()).isEqualTo(AgentRunner.Termination.SCHEMA_VIOLATION);
    assertThat(run.schemaErrors()).isNotEmpty();
    assertThat(run.degradations()).containsExactly("schema_retry_1", "schema_retry_2");
    assertThat(gateway.allRequests()).hasSize(3);
  }

  @Test
  void schemaRetryRecoversOnSecondAttempt() {
    ScriptedGateway gateway =
        new ScriptedGateway(
            LlmResponse.ok("这不是 JSON", 10, 10, 1, "m"),
            LlmResponse.ok(FINDINGS_JSON, 10, 10, 1, "m"));
    AgentRunner.AgentRun<List<CandidateFinding>> run =
        new AgentRunner(gateway)
            .execute(
                defaultBrief(ToolWhitelist.NONE),
                AgentRunner.ToolContext.none(),
                AgentRunner.findingsParser("run_t2", "bug-hunter/test"),
                "run_t2",
                trace);

    assertThat(run.termination()).isEqualTo(AgentRunner.Termination.COMPLETED);
    assertThat(run.output()).hasSize(1);
    assertThat(run.degradations()).containsExactly("schema_retry_1");
  }

  @Test
  void budgetExceededEventsAreTraced() throws Exception {
    ScriptedGateway gateway = new ScriptedGateway(LlmResponse.ok(TOOL_CALL_JSON, 1, 1, 1, "m"));
    new AgentRunner(gateway)
        .execute(
            brief(ToolWhitelist.READ_FILE, new AgentBudget(24_000, 2_000, 1, 240_000)),
            toolContext(FileContent.ofText("src/Foo.java", "x\n")),
            AgentRunner.findingsParser("run_t2", "bug-hunter/test"),
            "run_t2",
            trace);

    Path traceFile = runRoot.resolve("run_t2").resolve("trace.ndjson");
    assertThat(traceFile).exists();
    String content =
        java.nio.file.Files.readString(traceFile, java.nio.charset.StandardCharsets.UTF_8);
    assertThat(content)
        .contains("\"event\":\"agent_budget_exceeded\"")
        .contains("\"limit\":\"turns\"");
  }
}
