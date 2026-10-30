package org.cra.core.internal.pipeline;

/**
 * 降级码契约（02§4.1/02§4.2）：全部降级码的唯一定义点——字符串常量、参数化工厂与"是否构成 run 降级"的分类谓词。
 *
 * <p><b>降级码契约 = 对外可见</b>：码值原样进 {@code ReviewStats.degradations}、Summary 正文与 trace 事件，
 * 字符串值即对外接口（评测采集与账本按前缀/全等匹配），逐字节不可漂移。
 *
 * <p><b>新增码必须同步分类谓词</b>：run 降级判定（{@link #isAgentFailure}/{@link #isVerifierUnavailable}/ {@link
 * #isSummaryPublishFailed}）只认已登记的前缀/全等集合，漏改 = 该码不触发 DEGRADED → 静默 SUCCESS（02§4.1 禁止）。
 *
 * <p>参数化码（role/计数/原因后缀）一律经工厂产出，禁止散落拼接；谓词集合以既有 if 链为准，不扩大不收窄。
 */
public final class Degradations {

  // ---------- 预算阶梯（ReviewStage 产出，02§4.4） ----------

  /** run 预算 100%：终止剩余派发（02§4.4）。 */
  public static final String BUDGET_EXHAUSTED_STOP = "budget-exhausted-stop";

  /** run 预算 70%：剩余 chunk 砍 1 视角 + 简化上下文（02§4.4）。 */
  public static final String BUDGET_DEGRADE_70 = "budget-degrade-70";

  /** run 预算 85%：验证/发布集合截断 top-10（02§4.4）。 */
  public static final String BUDGET_TRUNCATE_85 = "budget-truncate-85";

  // ---------- 评审代理失败隔离（ReviewStage/ReviewAgentDispatcher 产出，02§4.2/03§2.3） ----------

  /** 前缀：代理执行失败（brief 组装/上下文异常），后接角色 id（02§4.2）。 */
  public static final String AGENT_FAILED = "agent-failed:";

  /** 前缀：代理预算硬限击穿，后接角色 id（03§1）。 */
  public static final String AGENT_BUDGET_EXCEEDED = "agent-budget-exceeded:";

  /** 前缀：代理触发工具隔离边界被终止，后接角色 id（03§2.3）。 */
  public static final String AGENT_TOOL_VIOLATION = "agent-tool-violation:";

  /** 单代理等待超时（已开始执行）。 */
  public static final String AGENT_TIMEOUT = "agent-timeout";

  /** 单代理排队超时（未开始执行，共享池被占满）。 */
  public static final String AGENT_QUEUE_TIMEOUT = "agent-queue-timeout";

  /** 全部评审代理执行失败（非 LLM 不可得）= 无可信评审产出。 */
  public static final String ALL_REVIEW_AGENTS_FAILED = "all-review-agents-failed";

  /** 前缀：LLM 不可得而缺失的评审代理计数（部分缺失 = DEGRADED，02§4.2）。 */
  public static final String LLM_UNAVAILABLE_AGENTS = "llm-unavailable-agents:";

  // ---------- 验证波（VerificationWave 产出，02§4.2/03§4.2） ----------

  /** 验证者不可得：LLM 重试 + 缩上下文重试仍失败（02§4.2）。 */
  public static final String VERIFIER_UNAVAILABLE = "verifier-unavailable";

  /** 验证者 LLM 不可得后缩上下文重试 1 次（02§4.2）。 */
  public static final String VERIFIER_RETRY_ONCE = "verifier-retry-once";

  /** 验证者输出 schema 校验重试耗尽，本批无可信判定。 */
  public static final String VERIFIER_SCHEMA_VIOLATION = "verifier-schema-violation";

  /** 验证批等待超时（已开始执行）。 */
  public static final String VERIFIER_TIMEOUT = "verifier-timeout";

  /** 验证批排队超时（未开始执行）。 */
  public static final String VERIFIER_QUEUE_TIMEOUT = "verifier-queue-timeout";

  /** 前缀：验证批执行异常，后接原因简称（注意冒号后有空格——既有对外格式）。 */
  public static final String VERIFIER_BATCH_FAILED = "verifier-batch-failed: ";

  /** 验证批因 run 预算 100% 未执行（02§4.2/02§4.4）。 */
  public static final String VERIFIER_BUDGET_EXHAUSTED = "verifier-budget-exhausted";

  // ---------- 发布与账本（ReviewPublisher 产出，05§2.2/02§4.1/02§5） ----------

  /** inline 路径会破坏账本标记格式，降级为 Summary（05§1.3 规则 3）。 */
  public static final String PATH_MARKER_UNSAFE = "path-marker-unsafe";

  /** 整包 422 逐条降级产生额外 SUMMARY_FALLBACK（05§2.2）。 */
  public static final String PER_ITEM_FALLBACK_422 = "422-per-item-fallback";

  /** Summary 未发出 = 无完成标记 = 不完整发布（02§4.1）。 */
  public static final String SUMMARY_PUBLISH_FAILED = "summary-publish-failed";

  /** 前缀：旧 Summary 过期收尾 PATCH 失败计数，形如 {@code summary-supersede-failed:x2}（02§5）。 */
  public static final String SUMMARY_SUPERSEDE_FAILED = "summary-supersede-failed:x";

  /** 无 PR 载体发布未返回 id = 无法确认完整发布（05§2.4/§2.5）。 */
  public static final String CARRIER_PUBLISH_NO_ID = "carrier-publish-no-id";

  // ---------- 汇聚与 Schema（ReviewPipeline 产出） ----------

  /** 候选全部被 schema 校验丢弃：run 降级收尾（02§4.1 降级完成语义）。 */
  public static final String SCHEMA_VIOLATION_ALL_DROPPED = "schema-violation-all-dropped";

  private Degradations() {}

  // ---------- 参数化工厂（拼接规则集中于此，拼接格式 = 对外契约，逐字节不可漂移） ----------

  /**
   * 代理执行失败码（02§4.2）。
   *
   * @param roleId 代理角色 id
   * @return 形如 {@code agent-failed:bug-hunter/dataflow}
   */
  public static String agentFailed(String roleId) {
    return AGENT_FAILED + roleId;
  }

  /**
   * 代理预算硬限击穿码（03§1）。
   *
   * @param roleId 代理角色 id
   * @return 形如 {@code agent-budget-exceeded:verifier}
   */
  public static String agentBudgetExceeded(String roleId) {
    return AGENT_BUDGET_EXCEEDED + roleId;
  }

  /**
   * 代理工具隔离终止码（03§2.3）。
   *
   * @param roleId 代理角色 id
   * @return 形如 {@code agent-tool-violation:bug-hunter/boundary}
   */
  public static String agentToolViolation(String roleId) {
    return AGENT_TOOL_VIOLATION + roleId;
  }

  /**
   * LLM 不可得缺失代理计数码（02§4.2）。
   *
   * @param count 缺失代理数
   * @return 形如 {@code llm-unavailable-agents:2}
   */
  public static String llmUnavailableAgents(long count) {
    return LLM_UNAVAILABLE_AGENTS + count;
  }

  /**
   * 验证批执行异常码。
   *
   * @param cause 异常简称（异常类 SimpleName）
   * @return 形如 {@code verifier-batch-failed: IllegalStateException}
   */
  public static String verifierBatchFailed(String cause) {
    return VERIFIER_BATCH_FAILED + cause;
  }

  /**
   * 旧 Summary 过期收尾失败码（02§5）。
   *
   * @param failures PATCH 失败次数
   * @return 形如 {@code summary-supersede-failed:x2}
   */
  public static String summarySupersedeFailed(int failures) {
    return SUMMARY_SUPERSEDE_FAILED + failures;
  }

  // ---------- 分类谓词（run 降级判定的唯一权威；集合以既有 if 链为准） ----------

  /**
   * 代理侧降级（超时/预算击穿/LLM 部分缺失/执行失败）→ run DEGRADED（02§4.1/§4.2）。
   *
   * <p>集合 = {@code agent-failed:* | agent-budget-exceeded:* | llm-unavailable-agents:* |
   * agent-timeout | agent-queue-timeout}（原 ReviewPipeline 判定链）。新增代理侧码必须登记进本谓词。
   *
   * @param code 降级码
   * @return true = 该码构成 run 降级
   */
  public static boolean isAgentFailure(String code) {
    return code.startsWith(AGENT_FAILED)
        || code.startsWith(AGENT_BUDGET_EXCEEDED)
        || code.startsWith(LLM_UNAVAILABLE_AGENTS)
        || code.equals(AGENT_TIMEOUT)
        || code.equals(AGENT_QUEUE_TIMEOUT);
  }

  /**
   * 单代理执行失败（异常/brief 落盘等，非 LLM 不可得）→ 计入失败代理数（02§4.2 失败隔离： 全部代理执行失败 = 无可信评审产出）。
   *
   * <p>与 {@link #isAgentFailure} 的区别：预算击穿/LLM 缺失/超时代理本就无产出但另有专属码，
   * "全部代理执行失败"的口径只认执行异常，不扩大（保持既有失败隔离语义）。
   *
   * @param code 降级码
   * @return true = 该代理为执行失败
   */
  public static boolean isAgentExecutionFailed(String code) {
    return code.startsWith(AGENT_FAILED);
  }

  /**
   * 验证侧不可得（超时/排队/预算/批异常/LLM 不可得）→ run DEGRADED（02§4.2）。
   *
   * <p>集合 = {@code verifier-budget-exhausted* | verifier-timeout* | verifier-queue-timeout* |
   * verifier-batch-failed* | verifier-unavailable}（原 ReviewPipeline 判定链）。新增验证侧码必须登记进本谓词。
   *
   * @param code 降级码
   * @return true = 该码构成 run 降级
   */
  public static boolean isVerifierUnavailable(String code) {
    return code.startsWith(VERIFIER_BUDGET_EXHAUSTED)
        || code.startsWith(VERIFIER_TIMEOUT)
        || code.startsWith(VERIFIER_QUEUE_TIMEOUT)
        || code.startsWith(VERIFIER_BATCH_FAILED)
        || code.equals(VERIFIER_UNAVAILABLE);
  }

  /**
   * Summary 发布失败 → 不完整发布，不得报 SUCCESS（02§4.1）。
   *
   * @param code 降级码
   * @return true = 该码构成 run 降级
   */
  public static boolean isSummaryPublishFailed(String code) {
    return code.equals(SUMMARY_PUBLISH_FAILED);
  }
}
