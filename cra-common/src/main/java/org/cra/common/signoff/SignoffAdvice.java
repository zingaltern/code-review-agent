package org.cra.common.signoff;

import java.util.List;
import java.util.Objects;

/**
 * 签字建议（07§5 M4a；01§3.4 担责功能的落地形态）：建议的风险等级、签字人数与触发规则，绑定到被评审的 commit。
 *
 * <p><strong>本对象永远不是审批</strong>：{@link #DECISION_ADVISORY} 明确其为建议；{@link #AGENT_IS_APPROVER} 恒为
 * false—— agent 的赞成票不计为审批，approval 永远由人/平台策略决定（01§3.4 反模式 7）。
 *
 * <p>commit 绑定语义与账本 DONE/指纹同源（05§1.3）：approve 只对 {@link #commitSha()} 有效， 新 commit 出现后旧建议由账本比对标
 * stale（{@link SignoffBinding}）。
 *
 * @param applicable 是否生成了建议（配置关闭时为 false）
 * @param decision 决策类型：ADVISORY_ONLY（建议）或 DISABLED（配置关闭）
 * @param tier 风险等级（disabled 时为 null）
 * @param recommendedSigners 建议签字人数（0/1/2；disabled 时为 0）
 * @param commitSha 建议绑定的 commit（approve 只对该 commit 有效）
 * @param context 作者与测试状态（低风险 0 人签的前置条件）
 * @param rules 命中的分级规则（可解释性；HIGH 在前）
 * @param stale 是否已被新 commit 置为 stale
 * @param staleReason stale 原因（非 stale 时为空文本）
 */
public record SignoffAdvice(
    boolean applicable,
    String decision,
    RiskTier tier,
    int recommendedSigners,
    String commitSha,
    SignoffContext context,
    List<SignoffRuleHit> rules,
    boolean stale,
    String staleReason) {

  /** 建议型决策：不是审批，agent 不是 approver。 */
  public static final String DECISION_ADVISORY = "ADVISORY_ONLY";

  /** 配置关闭：不生成建议（Summary 不出签字段）。 */
  public static final String DECISION_DISABLED = "DISABLED";

  /** agent 永远不是 approver（01§3.4 三条签字规矩其一；报告字段显式声明）。 */
  public static final boolean AGENT_IS_APPROVER = false;

  public SignoffAdvice {
    Objects.requireNonNull(decision, "decision");
    Objects.requireNonNull(commitSha, "commitSha");
    if (commitSha.isBlank()) {
      throw new IllegalArgumentException("commitSha must be non-blank");
    }
    if (!DECISION_ADVISORY.equals(decision) && !DECISION_DISABLED.equals(decision)) {
      throw new IllegalArgumentException("decision must be ADVISORY_ONLY|DISABLED: " + decision);
    }
    context = context == null ? SignoffContext.unknown() : context;
    rules = List.copyOf(rules == null ? List.of() : rules);
    staleReason = staleReason == null ? "" : staleReason;
    if (recommendedSigners < 0 || recommendedSigners > 2) {
      throw new IllegalArgumentException("recommendedSigners must be 0..2: " + recommendedSigners);
    }
    if (applicable && tier == null) {
      throw new IllegalArgumentException("applicable advice requires a tier");
    }
  }

  /**
   * 配置关闭形态（不生成建议）。
   *
   * @param commitSha 当前评审 commit
   * @param context 作者与测试状态
   * @return disabled 建议
   */
  public static SignoffAdvice disabled(String commitSha, SignoffContext context) {
    return new SignoffAdvice(
        false, DECISION_DISABLED, null, 0, commitSha, context, List.of(), false, "");
  }

  /**
   * 生成建议（签字人数由风险等级决定，01§3.4 分级表）。
   *
   * @param tier 风险等级
   * @param commitSha 建议绑定的 commit
   * @param context 作者与测试状态
   * @param rules 命中规则
   * @return 建议型决策
   */
  public static SignoffAdvice advisory(
      RiskTier tier, String commitSha, SignoffContext context, List<SignoffRuleHit> rules) {
    Objects.requireNonNull(tier, "tier");
    return new SignoffAdvice(
        true,
        DECISION_ADVISORY,
        tier,
        tier.recommendedSigners(),
        commitSha,
        context,
        rules,
        false,
        "");
  }

  /**
   * 是否为建议型（非审批）决策。
   *
   * @return true 若 applicable 且 decision=ADVISORY_ONLY
   */
  public boolean advisoryOnly() {
    return applicable && DECISION_ADVISORY.equals(decision);
  }

  /**
   * 是否为审批（恒 false——报告字段显式声明 agent 不审批）。
   *
   * @return 恒 false
   */
  public boolean approval() {
    return AGENT_IS_APPROVER;
  }

  /**
   * 是否为 approver（恒 false——agent 的赞成票不计为审批）。
   *
   * @return 恒 false
   */
  public boolean agentApprover() {
    return AGENT_IS_APPROVER;
  }
}
