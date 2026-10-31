package org.cra.core.internal;

import java.util.Map;
import org.cra.agents.api.AgentBrief;
import org.cra.agents.api.JudgeCaseInput;
import org.cra.agents.api.SubAgent;

/**
 * Judge 仲裁（07 T2.6；03§4.4 一次性终裁）：INSUFFICIENT 仲裁 + important/security 级 REFUTED 抽查复核。
 * 一次性——不做多轮辩论；上下文按案件界定（≤3 文件，组装在 {@link VerificationBatchAssembler}）。
 *
 * <p>不可得语义（02§4.2）：仲裁 judge 不可得 → 案件回到验证不可得（UNVERIFIED，不发布）；抽查复核的 judge 不可得 → 维持
 * REFUTED（保险丝无仲裁者可翻案，保守不发布，与 UNVERIFIED 同为不发布侧）。
 */
final class JudgeArbiter {

  private final SubAgent<JudgeCaseInput> judge;
  private final AgentRunner agentRunner;
  private final TraceWriter trace;

  /** 终裁结果（available = 拿到合法 CONFIRMED/REJECTED）。 */
  record Judgement(
      JudgeJsonCodec.JudgeVerdict verdict,
      String rationale,
      boolean available,
      long tokensIn,
      long tokensOut,
      String model) {}

  JudgeArbiter(SubAgent<JudgeCaseInput> judge, AgentRunner agentRunner, TraceWriter trace) {
    this.judge = judge;
    this.agentRunner = agentRunner;
    this.trace = trace;
  }

  /**
   * 终裁一个案件。
   *
   * @param input 案件输入（组装器产出）
   * @param runId run 标识
   * @param findingId trace 归属
   * @return 终裁结果
   */
  Judgement arbitrate(JudgeCaseInput input, String runId, String findingId) {
    AgentBrief brief = judge.buildBrief(input);
    // 位掩码取正（& 0x7fffffff）：Math.abs(Integer.MIN_VALUE) 仍为负，会生成 "case--…" 文件名
    var persisted =
        trace.persistBrief(brief, "case-" + (findingId.hashCode() & 0x7fffffff) % 100000);
    trace.emit(
        "judge_start",
        Map.of(
            "agent", judge.role().id(),
            "finding_id", findingId,
            "brief_path", persisted.toString(),
            "reason", input.caseReason()));
    AgentRunner.AgentRun<JudgeJsonCodec.Judgement> run =
        agentRunner.executeParsed(
            brief,
            runId,
            (json, model) -> {
              JudgeJsonCodec.ParseResult parsed = JudgeJsonCodec.parse(json);
              return parsed.errors().isEmpty()
                  ? AgentRunner.FinalOutputParser.Parsed.of(parsed.judgement())
                  : AgentRunner.FinalOutputParser.Parsed.invalid(parsed.errors());
            },
            trace);
    trace.emit(
        "agent_output",
        Map.of(
            "agent", judge.role().id(),
            "finding_id", findingId,
            "tokens", Map.of("in", run.tokensIn(), "out", run.tokensOut()),
            "latency_ms", run.latencyMs()));
    if (run.llmUnavailable() || run.output() == null) {
      trace.emit(
          "judge",
          Map.of(
              "finding_id",
              findingId,
              "verdict",
              "unavailable",
              "reason",
              run.llmUnavailable() ? "llm" : "schema"));
      return new Judgement(null, null, false, run.tokensIn(), run.tokensOut(), run.model());
    }
    trace.emit(
        "judge",
        Map.of(
            "finding_id", findingId,
            "verdict", run.output().verdict().name(),
            "rationale", run.output().rationale()));
    return new Judgement(
        run.output().verdict(),
        run.output().rationale(),
        true,
        run.tokensIn(),
        run.tokensOut(),
        run.model());
  }

  /** 情形说明文案（可信内容，进 judge brief）。 */
  static String reasonText(boolean spotCheck) {
    return spotCheck
        ? "情形：该 important/security 级结论被独立复核员推翻；本次为确定性抽查复核（防复核员错杀的召回保险丝）。"
        : "情形：独立复核员经补查后仍无法判定；请你以更大上下文配额仲裁。";
  }
}
