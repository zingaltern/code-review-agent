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

  Outcome promote(
      String runId,
      List<FindingAggregator.Scored> candidates,
      VerificationWave.WaveResult waveResult,
      Map<String, FileContent> headFiles,
      ReviewPolicy policy,
      RunBudgetTracker budget,
      TraceWriter trace) {
    return promote(runId, candidates, waveResult, headFiles, policy, budget, trace, null);
  }

  /**
   * 验证结论 → 发布决策（M4f-full：context 类事件引用按 repo 解析）。
   *
   * @param repo 仓库（owner/name；null = 不解析事件引用）
   * @return 晋升结果
   */
  Outcome promote(
      String runId,
      List<FindingAggregator.Scored> candidates,
      VerificationWave.WaveResult waveResult,
      Map<String, FileContent> headFiles,
      ReviewPolicy policy,
      RunBudgetTracker budget,
      TraceWriter trace,
      String repo) {
    return promote(runId, candidates, waveResult, headFiles, policy, budget, trace, repo, Map.of());
  }

  /**
   * 验证结论 → 发布决策（ADR-0015：bug 类 Judge 案件附带调用点索引）。
   *
   * @param callSiteIndexByFinding finding id → 调用点索引（验证波产出；空 = 不注入）
   * @return 晋升结果
   */
  Outcome promote(
      String runId,
      List<FindingAggregator.Scored> candidates,
      VerificationWave.WaveResult waveResult,
      Map<String, FileContent> headFiles,
      ReviewPolicy policy,
      RunBudgetTracker budget,
      TraceWriter trace,
      String repo,
      Map<String, CallSiteIndex> callSiteIndexByFinding) {
    boolean publishUnverified = "labeled".equals(options.publishUnverified());
    JudgeArbiter arbiter = new JudgeArbiter(judge, agentRunner, trace);
    List<FindingAggregator.Scored> confirmedCandidates = new ArrayList<>();
    List<FindingAggregator.Scored> unverifiedLabeled = new ArrayList<>();
    int confirmedCount = 0;
    int rejectedCount = 0;
    int unverifiedCount = 0;
    long tokensIn = 0;
    long tokensOut = 0;
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
      switch (promotion.decision()) {
        case PUBLISH -> {
          confirmedCount++;
          confirmedCandidates.add(s);
        }
        case REJECT -> rejectedCount++;
        case UNVERIFIED -> {
          if (publishUnverified) {
            // 05§5 review.publish-unverified=labeled：带 UNVERIFIED 标签发布（02§4.2 可配项）
            unverifiedLabeled.add(s);
          } else {
            unverifiedCount++;
          }
        }
        case JUDGE -> {
          if (budget.phase() == RunBudgetTracker.Phase.STOP) {
            // 02§4.4 100%：终止剩余代理（含仲裁）；候选按验证不可得处理，不静默当拒绝
            if (publishUnverified) {
              unverifiedLabeled.add(s);
            } else {
              unverifiedCount++;
            }
            continue;
          }
          boolean spotCheck = "refuted-spotcheck".equals(promotion.reason());
          JudgeArbiter.Judgement judgement =
              arbitrate(
                  arbiter,
                  finding,
                  verifierOutcome,
                  spotCheck,
                  headFiles,
                  policy,
                  runId,
                  repo,
                  callSiteIndexByFinding.get(finding.id()));
          budget.record(judgement.tokensIn(), judgement.tokensOut());
          tokensIn += judgement.tokensIn();
          tokensOut += judgement.tokensOut();
          if (judgement.available()
              && judgement.verdict() == JudgeJsonCodec.JudgeVerdict.CONFIRMED) {
            confirmedCount++;
            confirmedCandidates.add(s);
          } else if (judgement.available()) {
            rejectedCount++;
          } else if (spotCheck) {
            // 抽查复核的 judge 不可得：维持 REFUTED（保守不发布，02§4.2）
            rejectedCount++;
          } else {
            unverifiedCount++;
          }
        }
      }
    }
    return new Outcome(
        confirmedCandidates,
        unverifiedLabeled,
        confirmedCount,
        rejectedCount,
        unverifiedCount,
        tokensIn,
        tokensOut);
  }

  /** Judge 案件组装 + 调用（03§4.4：brief = finding + 验证者判定与证据 + ≤3 文件上下文；context 类追加被引证据与产品段）。 */
  private JudgeArbiter.Judgement arbitrate(
      JudgeArbiter arbiter,
      CandidateFinding finding,
      VerificationWave.PerFinding verifierOutcome,
      boolean spotCheck,
      Map<String, FileContent> headFiles,
      ReviewPolicy policy,
      String runId,
      String repo,
      CallSiteIndex callSiteIndex) {
    String verdictText =
        verifierOutcome != null && verifierOutcome.verdict() != null
            ? verifierOutcome.verdict().verdict().name()
                + "。理由："
                + verifierOutcome.verdict().rationale()
            : "（验证不可得：验证者超时/预算击穿/输出不合法）";
    JudgeCaseInput input =
        VerificationBatchAssembler.buildJudgeCase(
            finding,
            verdictText,
            JudgeArbiter.reasonText(spotCheck),
            headFiles,
            policy,
            citedResolver,
            repo,
            callSiteIndex);
    return arbiter.arbitrate(input, runId, finding.id());
  }

  record Outcome(
      List<FindingAggregator.Scored> confirmed,
      List<FindingAggregator.Scored> unverifiedLabeled,
      int confirmedCount,
      int rejectedCount,
      int unverifiedCount,
      long tokensIn,
      long tokensOut) {}
}
