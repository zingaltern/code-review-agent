package org.cra.core.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.cra.agents.api.JudgeCaseInput;
import org.cra.agents.api.SubAgent;
import org.cra.common.context.ReviewPolicy;
import org.cra.common.finding.CandidateFinding;
import org.cra.common.git.FileContent;
import org.cra.core.api.PipelineOptions;
import org.cra.core.internal.pipeline.CallSiteIndex;
import org.cra.core.internal.pipeline.FindingAggregator;
import org.cra.core.internal.pipeline.PromotionEngine;
import org.cra.core.internal.pipeline.RunBudgetTracker;
import org.cra.core.internal.pipeline.SpotCheckFuse;

/**
 * 验证结论 → 发布决策（03§4.3/03§4.4）：晋升规则、秒检保险丝与 Judge 仲裁。
 *
 * <p>从 {@link ReviewPipeline} 拆出：本类只消费验证波结果并产出确认/未验证集合与 token 增量， 发布与账本由 {@link ReviewPublisher}
 * 负责。
 */
final class FindingPromoter {

  private final SubAgent<JudgeCaseInput> judge;
  private final AgentRunner agentRunner;
  private final PipelineOptions options;
  private final CitedEvidenceResolver citedResolver;

  FindingPromoter(
      SubAgent<JudgeCaseInput> judge,
      AgentRunner agentRunner,
      PipelineOptions options,
      CitedEvidenceResolver citedResolver) {
    this.judge = judge;
    this.agentRunner = agentRunner;
    this.options = options;
    this.citedResolver = citedResolver;
  }

  /**
   * 验证结论 → 发布决策。
   *
   * @param run run 上下文（runId/trace/预算/策略）
   * @param inputs 候选与验证波/head 文件
   * @param context 事件引用解析上下文（repo/调用点索引）
   * @return 晋升结果
   */
  Outcome promote(PromotionRun run, PromotionInputs inputs, ContextRefs context) {
    String runId = run.runId();
    List<FindingAggregator.Scored> candidates = inputs.candidates();
    VerificationWave.WaveResult waveResult = inputs.waveResult();
    Map<String, FileContent> headFiles = inputs.headFiles();
    ReviewPolicy policy = run.policy();
    RunBudgetTracker budget = run.budget();
    TraceWriter trace = run.trace();
    String repo = context.repo();
    Map<String, CallSiteIndex> callSiteIndexByFinding = context.callSiteIndexByFinding();
    boolean publishUnverified = "labeled".equals(options.publishUnverified());
    JudgeArbiter arbiter = new JudgeArbiter(judge, agentRunner, trace);
    PromotionTally tally = new PromotionTally();
    PromotionEnv env = new PromotionEnv(arbiter, budget, policy);
    PromotionContext promotionContext =
        new PromotionContext(runId, repo, headFiles, callSiteIndexByFinding, publishUnverified);
    for (FindingAggregator.Scored s : candidates) {
      CandidateFinding finding = s.finding();
      VerificationWave.PerFinding verifierOutcome = waveResult.outcomes().get(finding.id());
      VerdictJsonCodec.VerifierVerdict verdict =
          verifierOutcome == null || verifierOutcome.verdict() == null
              ? VerdictJsonCodec.VerifierVerdict.UNAVAILABLE
              : verifierOutcome.verdict().verdict();
      boolean fuseHit =
          SpotCheckFuse.hit(runId, finding.id(), options.importantRefutedSpotCheckPercent());
      PromotionEngine.Promotion promotion = PromotionEngine.promote(verdict, finding, fuseHit);
      trace.emit(
          "promotion",
          Map.of(
              "finding_id", finding.id(),
              "verifier_verdict", verdict.name(),
              "decision", promotion.decision().name().toLowerCase(Locale.ROOT),
              "reason", promotion.reason(),
              "spotcheck_fuse", fuseHit));
      applyDecision(promotion, s, finding, verifierOutcome, env, promotionContext, tally);
    }
    return new Outcome(
        tally.confirmed,
        tally.unverifiedLabeled,
        tally.confirmedCount,
        tally.rejectedCount,
        tally.unverifiedCount,
        tally.tokensIn,
        tally.tokensOut);
  }

  /**
   * 应用单条晋升决策（PUBLISH/REJECT/UNVERIFIED/JUDGE）。
   *
   * @param promotion 晋升结果
   * @param scored 候选
   * @param finding 候选内容
   * @param verifierOutcome 验证结论
   * @param env run 级环境
   * @param context 发布上下文
   * @param tally 计数累计
   */
  private void applyDecision(
      PromotionEngine.Promotion promotion,
      FindingAggregator.Scored scored,
      CandidateFinding finding,
      VerificationWave.PerFinding verifierOutcome,
      PromotionEnv env,
      PromotionContext context,
      PromotionTally tally) {
    switch (promotion.decision()) {
      case PUBLISH -> {
        tally.confirmedCount++;
        tally.confirmed.add(scored);
      }
      case REJECT -> tally.rejectedCount++;
      case UNVERIFIED -> tally.markUnverified(scored, context.publishUnverified());
      case JUDGE -> applyJudge(scored, finding, verifierOutcome, promotion, env, context, tally);
    }
  }

  /**
   * JUDGE 决策：预算 STOP 时按验证不可得；否则仲裁并按判定累加。
   *
   * @param scored 候选
   * @param finding 候选内容
   * @param verifierOutcome 验证结论
   * @param promotion 晋升结果
   * @param env run 级环境
   * @param context 发布上下文
   * @param tally 计数累计
   */
  private void applyJudge(
      FindingAggregator.Scored scored,
      CandidateFinding finding,
      VerificationWave.PerFinding verifierOutcome,
      PromotionEngine.Promotion promotion,
      PromotionEnv env,
      PromotionContext context,
      PromotionTally tally) {
    if (env.budget().phase() == RunBudgetTracker.Phase.STOP) {
      // 02§4.4 100%：终止剩余代理（含仲裁）；候选按验证不可得处理，不静默当拒绝
      tally.markUnverified(scored, context.publishUnverified());
      return;
    }
    boolean spotCheck = "refuted-spotcheck".equals(promotion.reason());
    JudgeArbiter.Judgement judgement =
        arbitrate(
            env.arbiter(),
            new ArbitrationCase(
                finding,
                verifierOutcome,
                spotCheck,
                context.callSiteIndexByFinding().get(finding.id())),
            new ArbitrationEnv(env.policy(), context.runId(), context.repo(), context.headFiles()));
    env.budget().record(judgement.tokensIn(), judgement.tokensOut());
    tally.tokensIn += judgement.tokensIn();
    tally.tokensOut += judgement.tokensOut();
    if (judgement.available() && judgement.verdict() == JudgeJsonCodec.JudgeVerdict.CONFIRMED) {
      tally.confirmedCount++;
      tally.confirmed.add(scored);
    } else if (judgement.available() || spotCheck) {
      // 抽查复核的 judge 不可得：维持 REFUTED（保守不发布，02§4.2）
      tally.rejectedCount++;
    } else {
      tally.unverifiedCount++;
    }
  }

  /** 晋升计数累计（单 run 内可变）。 */
  private static final class PromotionTally {
    private final List<FindingAggregator.Scored> confirmed = new ArrayList<>();
    private final List<FindingAggregator.Scored> unverifiedLabeled = new ArrayList<>();
    private int confirmedCount;
    private int rejectedCount;
    private int unverifiedCount;
    private long tokensIn;
    private long tokensOut;

    /**
     * 未验证候选：按发布口径进 labeled 或计数。
     *
     * @param scored 候选
     * @param publishUnverified 是否按 labeled 发布
     */
    private void markUnverified(FindingAggregator.Scored scored, boolean publishUnverified) {
      if (publishUnverified) {
        // 05§5 review.publish-unverified=labeled：带 UNVERIFIED 标签发布（02§4.2 可配项）
        unverifiedLabeled.add(scored);
      } else {
        unverifiedCount++;
      }
    }
  }

  /**
   * Judge 案件组装 + 调用（03§4.4：brief = finding + 验证者判定与证据 + ≤3 文件上下文；context 类追加被引证据与产品段）。
   *
   * @param arbiter 仲裁器
   * @param arbitrationCase 案件内容
   * @param env 案件环境（策略/run/repo/head 文件）
   * @return 仲裁结果
   */
  private JudgeArbiter.Judgement arbitrate(
      JudgeArbiter arbiter, ArbitrationCase arbitrationCase, ArbitrationEnv env) {
    VerificationWave.PerFinding verifierOutcome = arbitrationCase.verifierOutcome();
    String verdictText =
        verifierOutcome != null && verifierOutcome.verdict() != null
            ? verifierOutcome.verdict().verdict().name()
                + "。理由："
                + verifierOutcome.verdict().rationale()
            : "（验证不可得：验证者超时/预算击穿/输出不合法）";
    JudgeCaseInput input =
        VerificationBatchAssembler.buildJudgeCase(
            new VerificationBatchAssembler.JudgeCaseContent(
                arbitrationCase.finding(),
                verdictText,
                JudgeArbiter.reasonText(arbitrationCase.spotCheck())),
            new VerificationBatchAssembler.JudgeCaseSources(
                env.headFiles(), env.policy(), citedResolver),
            new VerificationBatchAssembler.JudgeCaseContext(
                env.repo(), arbitrationCase.callSiteIndex()));
    return arbiter.arbitrate(input, env.runId(), arbitrationCase.finding().id());
  }

  /**
   * run 级晋升参数。
   *
   * @param runId run 标识
   * @param trace 追踪写入
   * @param budget run 预算
   * @param policy 评审策略
   */
  record PromotionRun(
      String runId, TraceWriter trace, RunBudgetTracker budget, ReviewPolicy policy) {}

  /**
   * 晋升输入。
   *
   * @param candidates 候选
   * @param waveResult 验证波结果
   * @param headFiles head 文件
   */
  record PromotionInputs(
      List<FindingAggregator.Scored> candidates,
      VerificationWave.WaveResult waveResult,
      Map<String, FileContent> headFiles) {}

  /**
   * 事件引用解析上下文。
   *
   * @param repo 仓库（可空）
   * @param callSiteIndexByFinding finding id → 调用点索引
   */
  record ContextRefs(String repo, Map<String, CallSiteIndex> callSiteIndexByFinding) {}

  /**
   * run 级晋升环境。
   *
   * @param arbiter 仲裁器
   * @param budget run 预算
   * @param policy 评审策略
   */
  private record PromotionEnv(JudgeArbiter arbiter, RunBudgetTracker budget, ReviewPolicy policy) {}

  /**
   * 发布上下文。
   *
   * @param runId run 标识
   * @param repo 仓库（可空）
   * @param headFiles head 文件
   * @param callSiteIndexByFinding finding id → 调用点索引
   * @param publishUnverified 是否按 labeled 发布未验证
   */
  private record PromotionContext(
      String runId,
      String repo,
      Map<String, FileContent> headFiles,
      Map<String, CallSiteIndex> callSiteIndexByFinding,
      boolean publishUnverified) {}

  /**
   * 仲裁案件内容。
   *
   * @param finding 候选
   * @param verifierOutcome 验证结论
   * @param spotCheck 是否抽查复核
   * @param callSiteIndex 调用点索引（可空）
   */
  private record ArbitrationCase(
      CandidateFinding finding,
      VerificationWave.PerFinding verifierOutcome,
      boolean spotCheck,
      CallSiteIndex callSiteIndex) {}

  /**
   * 仲裁案件环境。
   *
   * @param policy 评审策略
   * @param runId run 标识
   * @param repo 仓库（可空）
   * @param headFiles head 文件
   */
  private record ArbitrationEnv(
      ReviewPolicy policy, String runId, String repo, Map<String, FileContent> headFiles) {}

  record Outcome(
      List<FindingAggregator.Scored> confirmed,
      List<FindingAggregator.Scored> unverifiedLabeled,
      int confirmedCount,
      int rejectedCount,
      int unverifiedCount,
      long tokensIn,
      long tokensOut) {}
}
