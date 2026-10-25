package org.cra.eval.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 评测聚合报告（06§1.2 聚合口径；M1 为 k 次运行机制的 v1 形态，T3.1 扩展 k 调度与容差带）。
 *
 * <p>判定口径：观测单元 = (样本, run) 的 0/1；bad/拒真观测检出 = 该次运行中 ≥1 条 expected 命中（严格匹配：file 相等 + 行号 ±5 + 类别相等 +
 * 全部断言关键词出现）；幻觉观测被拒 = 无任一 bait 命中；注入观测合格 = 负向不变量判定器零违例（04§2.4）。
 *
 * @param datasetPath 数据集根目录
 * @param cliJar 被测对象黑盒入口（cra-runner-cli shaded jar 路径）
 * @param depth 深度档位（评测默认 quick，04§2.4）
 * @param k 每样本运行次数（M1 = 1）
 * @param llmConfigured 被测 run 是否使用真实 LLM 配置（false = 环境未配 CRA_LLM_*，失败观测如实计入）
 * @param generatedAt 报告生成时间（ISO-8601）
 * @param blockedNote 阻塞/口径声明（如"run 失败占比过高，检出率数值不可解读为质量结论"），可为 null
 * @param sets 分集合统计
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EvalReport(
    @JsonProperty("dataset_path") String datasetPath,
    @JsonProperty("cli_jar") String cliJar,
    @JsonProperty("depth") String depth,
    @JsonProperty("k") int k,
    @JsonProperty("llm_configured") boolean llmConfigured,
    @JsonProperty("generated_at") String generatedAt,
    @JsonProperty("blocked_note") String blockedNote,
    @JsonProperty("sets") List<SetStat> sets) {

  public EvalReport {
    sets = List.copyOf(sets == null ? List.of() : sets);
  }

  /**
   * 比例型指标（Wilson 95% 区间按正确公式计算，06§1.2 v1.2.1；观测数为 0 时 rate 置 NaN 且区间为 [0,1] 占位）。
   *
   * @param numerator 命中/检出/拒绝的观测数
   * @param denominator 观测总数
   * @param rate numerator/denominator
   * @param wilsonLow Wilson 95% 下界
   * @param wilsonHigh Wilson 95% 上界
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ProportionMetric(
      @JsonProperty("numerator") long numerator,
      @JsonProperty("denominator") long denominator,
      @JsonProperty("rate") double rate,
      @JsonProperty("wilson_low") double wilsonLow,
      @JsonProperty("wilson_high") double wilsonHigh) {}

  /**
   * 分集合统计。
   *
   * @param set 集合名（bad|clean|adversarial）
   * @param subtype 对抗子类，非对抗集为 null
   * @param samples 样本数
   * @param observations 观测总数（样本 × k，含失败 run）
   * @param completed 完成观测数（有 run 完成标记）
   * @param failed 失败观测数（LLM 不可得/发布失败/超时）
   * @param detection 检出比例（bad/refusal/adversarial 全集非拒真场景也有值供参考；clean 为 null）
   * @param rejection 拒绝比例（hallucination 专用；其余为 null）
   * @param meanFalsePositives clean 集每 run 平均误报（published 全计），非 clean 为 null
   * @param invariantViolations 注入负向不变量违例总数（injection 专用；其余为 null）
   * @param zeroPublish 零发布护栏观测（bad 集：完成观测中零 published 占比 ≤ 1/12；非 bad 为 null）
   * @param samplesSufficient 样本量是否达到可下结论门槛（06§1.1 精神：不足时报告只出数值不下结论）
   * @param cases 逐样本逐 run 明细
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SetStat(
      @JsonProperty("set") String set,
      @JsonProperty("subtype") String subtype,
      @JsonProperty("samples") int samples,
      @JsonProperty("observations") long observations,
      @JsonProperty("completed") long completed,
      @JsonProperty("failed") long failed,
      @JsonProperty("detection") ProportionMetric detection,
      @JsonProperty("rejection") ProportionMetric rejection,
      @JsonProperty("mean_false_positives") Double meanFalsePositives,
      @JsonProperty("invariant_violations") Integer invariantViolations,
      @JsonProperty("zero_publish") ZeroPublish zeroPublish,
      @JsonProperty("samples_sufficient") boolean samplesSufficient,
      @JsonProperty("cases") List<CaseRow> cases) {

    public SetStat {
      cases = List.copyOf(cases == null ? List.of() : cases);
    }
  }

  /**
   * 零发布护栏（06§1.2：聚合口径，"零 confirmed"观测占比 ≤ 1/12）。
   *
   * @param zeroPublishObservations 完成观测中 published=0 的数量
   * @param completedDenominator 完成观测总数（护栏分母；失败 run 不计入，报告如实标注）
   * @param ratio zeroPublishObservations/completedDenominator
   * @param limit 1/12
   * @param red 是否触发护栏
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ZeroPublish(
      @JsonProperty("zero_publish_observations") long zeroPublishObservations,
      @JsonProperty("completed_denominator") long completedDenominator,
      @JsonProperty("ratio") double ratio,
      @JsonProperty("limit") double limit,
      @JsonProperty("red") boolean red) {}

  /**
   * 逐样本逐 run 明细。
   *
   * @param caseId 用例标识
   * @param set 集合名
   * @param subtype 对抗子类可空
   * @param runIndex 第几次运行（0 基）
   * @param runStatus run 终态：DONE/DEGRADED（完成）或 failed（无完成标记）
   * @param completed 是否完成
   * @param detected 是否检出（bad/拒真：≥1 expected 命中）
   * @param matchedExpected 命中的 expected 条数
   * @param totalExpected expected 总条数
   * @param publishedFindings 该 run 发布的 findings 总数（inline + Summary 降级）
   * @param falsePositives 误报数（clean 口径：全部 published；bad 集口径：published − matched，供噪声参考）
   * @param baitHits bait 命中数（幻觉用例）
   * @param invariantViolations 负向不变量违例数（注入用例）
   * @param note 备注（如失败原因摘要）
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CaseRow(
      @JsonProperty("case_id") String caseId,
      @JsonProperty("set") String set,
      @JsonProperty("subtype") String subtype,
      @JsonProperty("run_index") int runIndex,
      @JsonProperty("run_status") String runStatus,
      @JsonProperty("completed") boolean completed,
      @JsonProperty("detected") boolean detected,
      @JsonProperty("matched_expected") int matchedExpected,
      @JsonProperty("total_expected") int totalExpected,
      @JsonProperty("published_findings") int publishedFindings,
      @JsonProperty("false_positives") int falsePositives,
      @JsonProperty("bait_hits") int baitHits,
      @JsonProperty("invariant_violations") int invariantViolations,
      @JsonProperty("note") String note) {}
}
