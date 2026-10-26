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
 * <p>T3.1 起报告自带门禁结论（{@link Gate}）：聚合 Wilson 下界 vs 分档阈值、clean 容差带、对抗拒绝率/零容忍、 零发布护栏；判定口径与阈值见
 * 06§1.2，档位语义见 04§2.4。
 *
 * @param datasetPath 数据集根目录
 * @param cliJar 被测对象黑盒入口（cra-runner-cli shaded jar 路径）
 * @param depth 深度档位（评测默认 quick，04§2.4）
 * @param k 每样本运行次数（M1 = 1）
 * @param llmConfigured 被测 run 是否使用真实 LLM 配置（false = 环境未配 CRA_LLM_*，失败观测如实计入）
 * @param generatedAt 报告生成时间（ISO-8601）
 * @param blockedNote 阻塞/口径声明（如"run 失败占比过高，检出率数值不可解读为质量结论"），可为 null
 * @param sets 分集合统计
 * @param tier 门禁档位（l1|l2|l3；由 --tier 显式指定或按 k/depth 推导）
 * @param commit 产出报告的代码提交（L2 报告哈希校验的绑定字段，可为 null）
 * @param gate 门禁结论（通过/红与逐项判定）
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
    @JsonProperty("sets") List<SetStat> sets,
    @JsonProperty("tier") String tier,
    @JsonProperty("commit") String commit,
    @JsonProperty("gate") Gate gate) {

  public EvalReport {
    sets = List.copyOf(sets == null ? List.of() : sets);
  }

  /** 兼容旧报告构造（M1/M2 形态：无档位/提交/门禁字段）。 */
  public EvalReport(
      String datasetPath,
      String cliJar,
      String depth,
      int k,
      boolean llmConfigured,
      String generatedAt,
      String blockedNote,
      List<SetStat> sets) {
    this(
        datasetPath,
        cliJar,
        depth,
        k,
        llmConfigured,
        generatedAt,
        blockedNote,
        sets,
        null,
        null,
        null);
  }

  /**
   * 门禁结论（06§1.2 判定规则；状态取值 pass|red|skipped|reported）。
   *
   * @param tier 档位（l1|l2|l3）
   * @param passed 是否通过（任一 active 检查为 red 即 false；skipped/reported 不参与）
   * @param checks 逐项判定
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Gate(
      @JsonProperty("tier") String tier,
      @JsonProperty("passed") boolean passed,
      @JsonProperty("checks") List<GateCheck> checks) {

    public Gate {
      checks = List.copyOf(checks == null ? List.of() : checks);
    }
  }

  /**
   * 门禁单项判定。
   *
   * @param id
   *     检查标识（bad_detection|clean_false_positives|hallucination_rejection|injection_invariant|context_detection|zero_publish_guardrail|bad_sample|l1_scope|profile|run_failures）
   * @param status pass|red|skipped|reported（skipped = 本档/本次不适用；reported = 只报数值不判定）
   * @param observed 观测值（比例或计数；不适用时 null）
   * @param threshold 判定阈值（不适用时 null）
   * @param detail 口径与分母说明（容差随分母声明）
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record GateCheck(
      @JsonProperty("id") String id,
      @JsonProperty("status") String status,
      @JsonProperty("observed") Double observed,
      @JsonProperty("threshold") Double threshold,
      @JsonProperty("detail") String detail) {}

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
   * @param runMetrics per-run token 与耗时聚合（T3.1；来源 trace run_end，缺失统计的观测不进分母）
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
      @JsonProperty("bug_only_detection") ProportionMetric bugOnlyDetection,
      @JsonProperty("rejection") ProportionMetric rejection,
      @JsonProperty("mean_false_positives") Double meanFalsePositives,
      @JsonProperty("invariant_violations") Integer invariantViolations,
      @JsonProperty("zero_publish") ZeroPublish zeroPublish,
      @JsonProperty("samples_sufficient") boolean samplesSufficient,
      @JsonProperty("cases") List<CaseRow> cases,
      @JsonProperty("run_metrics") RunMetrics runMetrics) {

    public SetStat {
      cases = List.copyOf(cases == null ? List.of() : cases);
    }
  }

  /**
   * per-run token 与耗时聚合（T3.1 指标报告；M2 验收 6 的 330k/500k 算术对照所需字段）。
   *
   * @param observationsWithStats 有 run_end 统计的观测数（分母）
   * @param tokensInSum 输入 token 合计
   * @param tokensOutSum 输出 token 合计
   * @param tokensInMean 输入 token 均值（observationsWithStats 为 0 时为 0）
   * @param tokensOutMean 输出 token 均值
   * @param latencyMsSum 耗时合计（毫秒）
   * @param latencyMsMean 耗时均值（毫秒）
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record RunMetrics(
      @JsonProperty("observations_with_stats") long observationsWithStats,
      @JsonProperty("tokens_in_sum") long tokensInSum,
      @JsonProperty("tokens_out_sum") long tokensOutSum,
      @JsonProperty("tokens_in_mean") double tokensInMean,
      @JsonProperty("tokens_out_mean") double tokensOutMean,
      @JsonProperty("latency_ms_sum") long latencyMsSum,
      @JsonProperty("latency_ms_mean") double latencyMsMean) {}

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
      @JsonProperty("all_zero_samples") long allZeroSamples,
      @JsonProperty("sample_denominator") long sampleDenominator,
      @JsonProperty("sample_ratio") double sampleRatio,
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
   * @param findingsBrief 发布观测摘要（file:line [category] text，截断 200 字符）
   * @param tokensIn 该 run 输入 token（trace run_end；无统计为 0）
   * @param tokensOut 该 run 输出 token（trace run_end；无统计为 0）
   * @param latencyMs 该 run 耗时（trace run_end；无统计为 0）
   * @param statsAvailable 是否取到 run_end 统计（false 的观测不进 token/耗时均值分母）
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
      @JsonProperty("bug_only_detected") boolean bugOnlyDetected,
      @JsonProperty("matched_expected") int matchedExpected,
      @JsonProperty("total_expected") int totalExpected,
      @JsonProperty("published_findings") int publishedFindings,
      @JsonProperty("false_positives") int falsePositives,
      @JsonProperty("bait_hits") int baitHits,
      @JsonProperty("invariant_violations") int invariantViolations,
      @JsonProperty("note") String note,
      @JsonProperty("findings_brief") List<String> findingsBrief,
      @JsonProperty("tokens_in") long tokensIn,
      @JsonProperty("tokens_out") long tokensOut,
      @JsonProperty("latency_ms") long latencyMs,
      @JsonProperty("stats_available") boolean statsAvailable) {

    /** 兼容 M1/M2 报告读取（无 per-run 统计字段）。 */
    public CaseRow(
        String caseId,
        String set,
        String subtype,
        int runIndex,
        String runStatus,
        boolean completed,
        boolean detected,
        int matchedExpected,
        int totalExpected,
        int publishedFindings,
        int falsePositives,
        int baitHits,
        int invariantViolations,
        String note,
        List<String> findingsBrief) {
      this(
          caseId,
          set,
          subtype,
          runIndex,
          runStatus,
          completed,
          detected,
          false, // 兼容旧报告：无 bug-only 字段
          matchedExpected,
          totalExpected,
          publishedFindings,
          falsePositives,
          baitHits,
          invariantViolations,
          note,
          findingsBrief,
          0,
          0,
          0,
          false);
    }

    public CaseRow {
      findingsBrief = List.copyOf(findingsBrief == null ? List.of() : findingsBrief);
    }
  }
}
