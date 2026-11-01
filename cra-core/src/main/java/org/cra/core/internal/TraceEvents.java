package org.cra.core.internal;

/**
 * trace 事件名唯一集中地（05§1.4 NDJSON 格式 {@code event} 字段）：全部 {@link TraceWriter#emit} 调用第一参的字面量来源。
 *
 * <p><b>事件名 = 对外契约</b>：NDJSON trace 的消费方（cra-eval 评测采集、本地排查脚本）按字符串精确匹配 {@code event}
 * 值，事件名清单与取值逐字节不可漂移——本类是唯一的字面量定义点，发射端只允许引用这里的常量，禁止内联事件名字面量。
 *
 * <p><b>与 docs/05§1.4 清单的对应关系</b>：§1.4 登记了完整 run 生命周期的事件集（本类第一组，按管线顺序排列）； 另有实现先行、§1.4
 * 尚未登记的事件名（本类第二组，标注产出方）——新增事件名时先在 docs/05§1.4 补登再加常量， 本类 Javadoc 不替代文档登记。
 */
public final class TraceEvents {

  // ---------- docs/05§1.4 已登记事件（按管线顺序） ----------

  /** run 开始（05§1.4；产出方 ReviewPipeline）。 */
  public static final String RUN_START = "run_start";

  /** commit gate 命中检查（05§1.4；产出方 ReviewPipeline）。 */
  public static final String GATE_CHECK = "gate_check";

  /** 代理启动（05§1.4；产出方 VerificationWave、ReviewAgentDispatcher）。 */
  public static final String AGENT_START = "agent_start";

  /** 代理产出（05§1.4；产出方 JudgeArbiter、ReviewAgentDispatcher）。 */
  public static final String AGENT_OUTPUT = "agent_output";

  /** 工具调用（05§1.4，07§6.5 补登；产出方 AgentRunner）。 */
  public static final String TOOL_CALL = "tool_call";

  /** 工具结果（05§1.4，07§6.5 补登；产出方 AgentRunner）。 */
  public static final String TOOL_RESULT = "tool_result";

  /** 代理预算击穿（05§1.4，07§6.5 补登；产出方 AgentRunner）。 */
  public static final String AGENT_BUDGET_EXCEEDED = "agent_budget_exceeded";

  /** 收敛轮次与 diff-only 基点（05§1.4，03§5；产出方 ReviewPipeline、PipelineDataLoader）。 */
  public static final String CONVERGENCE = "convergence";

  /** run 内精确去重合并（05§1.4，03§4.5；产出方 ReviewPipeline）。 */
  public static final String MERGE = "merge";

  /** 合并汇总（05§1.4，03§4.5；产出方 ReviewPipeline）。 */
  public static final String MERGE_SUMMARY = "merge_summary";

  /** 锚定检查（05§1.4；产出方 FindingGatekeeper）。 */
  public static final String ANCHOR_CHECK = "anchor_check";

  /** 去重匹配（05§1.4；产出方 FindingGatekeeper、FindingMerger）。 */
  public static final String DEDUP = "dedup";

  /** 账本解析（05§1.4；产出方 ReviewPipeline）。 */
  public static final String LEDGER_PARSE = "ledger_parse";

  /** 评审计划（05§1.4；产出方 ReviewPipeline）。 */
  public static final String PLAN = "plan";

  /** 签字建议（05§1.4，M4a 补登；产出方 ReviewPipeline）。 */
  public static final String SIGNOFF_ADVICE = "signoff_advice";

  /** 模板版本（05§1.4，v1.3.2 补登；产出方 ReviewPipeline）。 */
  public static final String TEMPLATE_VERSIONS = "template_versions";

  /** blame 质控（05§1.4；产出方 FindingGatekeeper）。 */
  public static final String BLAME_CHECK = "blame_check";

  /** 验证批三值判定计数（05§1.4，03§4.2；产出方 VerificationWave）。 */
  public static final String VERIFY = "verify";

  /** 单 finding 验证判定（05§1.4，03§4.2；产出方 VerificationWave）。 */
  public static final String VERIFICATION = "verification";

  /** 测试索引（05§1.4；产出方 PipelineDataLoader）。 */
  public static final String TEST_INDEX = "test_index";

  /** 验证不可得缩上下文重试（05§1.4，02§4.2；产出方 VerificationWave）。 */
  public static final String VERIFY_RETRY = "verify_retry";

  /** INSUFFICIENT 受控补查（05§1.4，03§4.2；产出方 VerificationWave）。 */
  public static final String VERIFY_FOLLOWUP = "verify_followup";

  /** 反锚定质控信号（05§1.4，不阻断晋升；产出方 VerificationWave）。 */
  public static final String ANTI_ANCHOR_CHECK = "anti_anchor_check";

  /** 仲裁启动（05§1.4，03§4.4；产出方 JudgeArbiter）。 */
  public static final String JUDGE_START = "judge_start";

  /** 仲裁判定（05§1.4，03§4.4；产出方 JudgeArbiter）。 */
  public static final String JUDGE = "judge";

  /** 晋升决定（05§1.4，03§4.3；产出方 FindingPromoter）。 */
  public static final String PROMOTION = "promotion";

  /** 汇聚（05§1.4；产出方 ReviewPipeline）。 */
  public static final String AGGREGATE = "aggregate";

  /** 旧 Summary 过期收尾（05§1.4，02§5；产出方 ReviewPublisher）。 */
  public static final String SUMMARY_SUPERSEDE = "summary_supersede";

  /** 发布（05§1.4；产出方 ReviewPublisher）。 */
  public static final String PUBLISH = "publish";

  // ---------- 实现先行、docs/05§1.4 尚未登记的事件（新增时先补文档） ----------

  /** run 结束（产出方 ReviewRunFinisher；docs/05§1.4 未登记）。 */
  public static final String RUN_END = "run_end";

  /** run 预算相位（degrade/truncate/truncate-final；产出方 ReviewStage，02§4.4；docs/05§1.4 未登记）。 */
  public static final String BUDGET_CHECK = "budget_check";

  /** 交叉复核触发与跳过（产出方 ReviewStage；docs/05§1.4 未登记）。 */
  public static final String CROSSCHECK = "crosscheck";

  /** 评审计划为空（产出方 ReviewStage；docs/05§1.4 未登记）。 */
  public static final String PLAN_EMPTY = "plan_empty";

  /** 语义上下文组装（产出方 PipelineDataLoader，ADR-0014；docs/05§1.4 未登记）。 */
  public static final String SEMANTIC_CONTEXT = "semantic_context";

  /** 评审政策加载（含读取失败；产出方 PipelineDataLoader；docs/05§1.4 未登记）。 */
  public static final String POLICY_LOAD = "policy_load";

  /** Java 文件索引（产出方 PipelineDataLoader；docs/05§1.4 未登记）。 */
  public static final String JAVA_FILE_INDEX = "java_file_index";

  /** 调用点索引（产出方 VerificationWave，ADR-0015；docs/05§1.4 未登记）。 */
  public static final String CALL_SITE_INDEX = "call_site_index";

  /** schema 校验截断丢弃计数（产出方 ReviewPipeline；docs/05§1.4 未登记）。 */
  public static final String VALIDATION_TRUNCATION = "validation_truncation";

  /** 反驳证据检查（产出方 VerificationWave，ADR-0015；docs/05§1.4 未登记）。 */
  public static final String REFUTE_EVIDENCE_CHECK = "refute_evidence_check";

  private TraceEvents() {}
}
