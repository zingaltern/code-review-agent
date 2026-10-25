package org.cra.eval.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * L1 在线噪声周报（06§1.1 {@code cra-eval report}；T3.4/T3.6 数据面）。
 *
 * <p>数据来源分离并如实声明：处置分类与 run 满意度来自 GitHub 账本（{@code CommentSource} 端口，ADR-0005）， 拒真池与候选→确认漏斗来自 run
 * trace（本地 {@code .cra/runs} 或 CI artifact 解包目录），零发布率来自账本 run 完成标记。任一部分不可得时只在数值处留空并在 {@link
 * #notes()} 声明，不以 0 假装"没有问题"。
 *
 * <p>最小样本量门槛（06§1.1）：处置样本 ≥30 才判定发布精确率/误报率，拒真评估池 ≥20 才判定拒真率；不足时只报数值并标注 "样本不足，不下结论"。
 *
 * @param repo 仓库（owner/repo）
 * @param since 窗口长度（如 7d）
 * @param windowStart 窗口起点（ISO-8601）
 * @param windowEnd 窗口终点/报告生成时间（ISO-8601）
 * @param generatedAt 报告生成时间（ISO-8601）
 * @param dataSource 账本数据源：github | ledger-fixture（离线回放）
 * @param prs 窗口内 PR 数（按 updated_at 过滤）
 * @param mergedPrs 其中已合并的 PR 数（合并回填前提）
 * @param runCompletions 窗口内账本 run 完成标记数（DONE/DEGRADED）
 * @param disposition 发布精确率/误报率（四类处置）
 * @param refusal 拒真率（REFUTED 被 Judge/事后 badcase 推翻）
 * @param funnel 候选→确认→发布漏斗
 * @param zeroPublish 零发布率与趋势
 * @param satisfaction run 级满意度（Summary reaction）
 * @param dualVerifierGate T3.6 双异构验证者门槛评估
 * @param findings 逐 finding 处置明细（审计用）
 * @param notes 口径与不可得声明
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WeeklyReport(
    @JsonProperty("repo") String repo,
    @JsonProperty("since") String since,
    @JsonProperty("window_start") String windowStart,
    @JsonProperty("window_end") String windowEnd,
    @JsonProperty("generated_at") String generatedAt,
    @JsonProperty("data_source") String dataSource,
    @JsonProperty("prs") int prs,
    @JsonProperty("merged_prs") int mergedPrs,
    @JsonProperty("run_completions") int runCompletions,
    @JsonProperty("disposition") Disposition disposition,
    @JsonProperty("refusal") Refusal refusal,
    @JsonProperty("funnel") Funnel funnel,
    @JsonProperty("zero_publish") ZeroPublish zeroPublish,
    @JsonProperty("satisfaction") Satisfaction satisfaction,
    @JsonProperty("dual_verifier_gate") DualVerifierGate dualVerifierGate,
    @JsonProperty("findings") List<FindingRow> findings,
    @JsonProperty("notes") List<String> notes) {

  public WeeklyReport {
    findings = List.copyOf(findings == null ? List.of() : findings);
    notes = List.copyOf(notes == null ? List.of() : notes);
  }

  /**
   * 处置分类与发布精确率（06§1.1）。
   *
   * <p>两套分母同时给出：<b>主口径</b>分母含 ignored（06§1.1 "分母含此噪声，报表注明口径"），
   * <b>附口径</b>只含明确处置（fixed/wrong/intentional）。门槛与 T3.6 判定用主口径（保守）。
   *
   * @param fixed 后续 commit 修改了锚定行（自动初判）
   * @param wrong 回复 :wrong: / not-a-bug
   * @param intentional 回复 :intentional:
   * @param ignored 合并 7 天后无任何信号（自动，噪声）
   * @param pending 未合并或合并未满 7 天（不参与分母）
   * @param explicitSamples fixed+wrong+intentional（附口径分母）
   * @param samplesIncludingIgnored 主口径分母（含 ignored）
   * @param precision 主口径发布精确率 (fixed+intentional)/samplesIncludingIgnored
   * @param falsePositive 主口径误报率 wrong/samplesIncludingIgnored
   * @param precisionExplicitOnly 附口径发布精确率
   * @param falsePositiveExplicitOnly 附口径误报率
   * @param samplesSufficient 主口径分母是否达到 30（06§1.1 门槛）
   * @param sampleGate 门槛值（30）
   * @param precisionTarget 目标下界（0.80）
   * @param falsePositiveLimit 误报率上限（0.15）
   * @param meetsPrecisionTarget 达到门槛时才判定；不足为 null
   * @param meetsFalsePositiveLimit 达到门槛时才判定；不足为 null
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Disposition(
      @JsonProperty("fixed") int fixed,
      @JsonProperty("wrong") int wrong,
      @JsonProperty("intentional") int intentional,
      @JsonProperty("ignored") int ignored,
      @JsonProperty("pending") int pending,
      @JsonProperty("explicit_samples") int explicitSamples,
      @JsonProperty("samples_including_ignored") int samplesIncludingIgnored,
      @JsonProperty("precision") EvalReport.ProportionMetric precision,
      @JsonProperty("false_positive_rate") EvalReport.ProportionMetric falsePositive,
      @JsonProperty("precision_explicit_only") EvalReport.ProportionMetric precisionExplicitOnly,
      @JsonProperty("false_positive_rate_explicit_only")
          EvalReport.ProportionMetric falsePositiveExplicitOnly,
      @JsonProperty("samples_sufficient") boolean samplesSufficient,
      @JsonProperty("sample_gate") int sampleGate,
      @JsonProperty("precision_target") double precisionTarget,
      @JsonProperty("false_positive_limit") double falsePositiveLimit,
      @JsonProperty("meets_precision_target") Boolean meetsPrecisionTarget,
      @JsonProperty("meets_false_positive_limit") Boolean meetsFalsePositiveLimit) {}

  /**
   * 拒真率（03§4.3 拒真评估池口径）。
   *
   * @param refutedTotal 窗口内 REFUTED 判定总数（trace promotion 事件）
   * @param judgeEvaluated Judge 复核过的 REFUTED 数（抽查命中，判定可读）
   * @param judgeOverturned Judge 翻案（CONFIRMED）数
   * @param judgeUnavailable Judge 不可得的抽查数（不进评估池）
   * @param postHocOverturned 事后 badcase（label=missed 且原判定 REFUTED）推翻数
   * @param pool 评估池 = Judge 可读样本 + 事后再评估样本（按 finding 去重）
   * @param overturnRate overturned/pool；pool=0 时为 null
   * @param samplesSufficient pool 是否达到 20（03§4.3 门槛）
   * @param sampleGate 门槛值（20）
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Refusal(
      @JsonProperty("refuted_total") int refutedTotal,
      @JsonProperty("judge_evaluated") int judgeEvaluated,
      @JsonProperty("judge_overturned") int judgeOverturned,
      @JsonProperty("judge_unavailable") int judgeUnavailable,
      @JsonProperty("post_hoc_overturned") int postHocOverturned,
      @JsonProperty("pool") int pool,
      @JsonProperty("overturn_rate") Double overturnRate,
      @JsonProperty("samples_sufficient") boolean samplesSufficient,
      @JsonProperty("sample_gate") int sampleGate) {}

  /**
   * 候选→确认→发布漏斗（06§1.1/§1.3；数据源 run trace，缺 trace 时仅发布/截断来自账本）。
   *
   * @param candidateDataAvailable trace 是否可用（false = candidates/confirmed 不可得，填 -1）
   * @param source 数据源描述
   * @param runs 计入的 run 数（非 dry-run）
   * @param candidates 候选数（promotion 事件去重）
   * @param confirmed 确认数（PUBLISH + Judge CONFIRMED）
   * @param published 实际发布数（run_end.findings_published 或账本标记）
   * @param suppressedByTruncation 预算截断丢弃的候选数
   * @param truncatedRuns suppressed>0 的 run 数（梯度沉默护栏）
   * @param confirmedRate confirmed/candidates
   * @param publishRate published/confirmed
   * @param truncatedRate suppressed/confirmed
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Funnel(
      @JsonProperty("candidate_data_available") boolean candidateDataAvailable,
      @JsonProperty("source") String source,
      @JsonProperty("runs") int runs,
      @JsonProperty("candidates") int candidates,
      @JsonProperty("confirmed") int confirmed,
      @JsonProperty("published") int published,
      @JsonProperty("suppressed_by_truncation") int suppressedByTruncation,
      @JsonProperty("truncated_runs") int truncatedRuns,
      @JsonProperty("confirmed_rate") Double confirmedRate,
      @JsonProperty("publish_rate") Double publishRate,
      @JsonProperty("truncated_rate") Double truncatedRate) {}

  /**
   * 零发布率与周趋势（06§1.1 趋势护栏：连续两周上行触发复盘）。
   *
   * @param runs 账本 run 完成标记数
   * @param zeroRuns findings=0 的 run 数
   * @param ratio zeroRuns/runs（runs=0 时为 null，不以 0 假装"无零发布"）
   * @param previousRatio 上一期报告的零发布率（无上期基线为 null）
   * @param series 历史 + 本期零发布率序列（旧→新）
   * @param consecutiveRise 最近两期是否连续上行（需序列长度 ≥3）
   * @param trend insufficient | rising | flat | falling
   * @param note 趋势判定说明
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ZeroPublish(
      @JsonProperty("runs") int runs,
      @JsonProperty("zero_runs") int zeroRuns,
      @JsonProperty("ratio") Double ratio,
      @JsonProperty("previous_ratio") Double previousRatio,
      @JsonProperty("series") List<Double> series,
      @JsonProperty("consecutive_rise") boolean consecutiveRise,
      @JsonProperty("trend") String trend,
      @JsonProperty("note") String note) {

    public ZeroPublish {
      series = List.copyOf(series == null ? List.of() : series);
    }
  }

  /**
   * run 级满意度（06§1.1：Summary issue comment 的 👍/👎，不参与单 finding 分类）。
   *
   * @param up 👍 数
   * @param down 👎 数
   * @param runsWithReaction 有 reaction 的 run 数
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Satisfaction(
      @JsonProperty("up") int up,
      @JsonProperty("down") int down,
      @JsonProperty("runs_with_reaction") int runsWithReaction) {}

  /**
   * 单个门槛条件。
   *
   * @param sampleSufficient 样本量是否达到门槛
   * @param samples 实际样本量
   * @param sampleGate 样本门槛
   * @param observed 观测值（率）；不可得为 null
   * @param threshold 阈值
   * @param operator 比较方向：gte（率越高越危险）或 lt（率越低越危险）
   * @param met 条件是否满足（样本不足恒 false）
   * @param verdict 文字判定（含"样本不足，不下结论"）
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record GateCondition(
      @JsonProperty("sample_sufficient") boolean sampleSufficient,
      @JsonProperty("samples") int samples,
      @JsonProperty("sample_gate") int sampleGate,
      @JsonProperty("observed") Double observed,
      @JsonProperty("threshold") double threshold,
      @JsonProperty("operator") String operator,
      @JsonProperty("met") boolean met,
      @JsonProperty("verdict") String verdict) {}

  /**
   * T3.6 双异构验证者门槛评估（03§4.3 / 07 T3.6）。
   *
   * <p>触发 = 拒真池 ≥20 且推翻率 ≥10%，<b>或</b> 处置样本 ≥30 且发布精确率 <80%。样本不足时不下结论、不开启—— 不得为"完成 T3.6"放松门槛。
   *
   * @param triggered 是否满足开启条件
   * @param decision 结论文字（开启需实施硬校验 + 语义去重；否则继续单验证者 + 抽查）
   * @param refusalCondition 拒真侧条件
   * @param dispositionCondition 处置侧条件
   * @param reasons 依据与声明
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record DualVerifierGate(
      @JsonProperty("triggered") boolean triggered,
      @JsonProperty("decision") String decision,
      @JsonProperty("refusal_condition") GateCondition refusalCondition,
      @JsonProperty("disposition_condition") GateCondition dispositionCondition,
      @JsonProperty("reasons") List<String> reasons) {

    public DualVerifierGate {
      reasons = List.copyOf(reasons == null ? List.of() : reasons);
    }
  }

  /**
   * 逐 finding 处置明细（审计用；文本报告不打印全量）。
   *
   * @param pr PR 编号
   * @param commentId 账本评论 id
   * @param runId run 标识
   * @param findingId finding 标识
   * @param file 文件
   * @param line 行号
   * @param category 类别
   * @param disposition fixed|wrong|intentional|ignored|pending
   * @param signal 判定信号（reply 标签 / 行变更 / 合并未改 / 未合并）
   * @param publishedAt 评论创建时间（ISO）
   * @param ageDays 距报告生成的天数
   * @param note 备注
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record FindingRow(
      @JsonProperty("pr") int pr,
      @JsonProperty("comment_id") long commentId,
      @JsonProperty("run_id") String runId,
      @JsonProperty("finding_id") String findingId,
      @JsonProperty("file") String file,
      @JsonProperty("line") int line,
      @JsonProperty("category") String category,
      @JsonProperty("disposition") String disposition,
      @JsonProperty("signal") String signal,
      @JsonProperty("published_at") String publishedAt,
      @JsonProperty("age_days") long ageDays,
      @JsonProperty("note") String note) {}
}
