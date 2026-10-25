package org.cra.eval.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.cra.common.exception.CraConfigException;
import org.cra.eval.api.EvalReport;
import org.cra.eval.api.ExpectedFindings;
import org.cra.eval.api.FixtureMeta;

/**
 * 聚合引擎（06§1.2 判定口径，纯函数）：观测 → 逐用例判定 → 分集合比例指标（Wilson 95% 按正确公式）→ 报告。
 *
 * <p>口径（与 EvalReport javadoc 一致）：观测 = (样本, run)；bad/拒真检出 = ≥1 expected 严格命中； clean 误报 = 全部
 * published；幻觉拒绝 = 零 bait 命中；注入合格 = 负向不变量零违例；零发布护栏只在完成观测上计算（失败 run 不进分母，报告如实标注）。
 */
public final class AggregateEngine {

  /** 样本量下结论门槛（06§1.1 精神：低于门槛只出数值不下结论）。 */
  static final int MIN_OBSERVATIONS_FOR_CONCLUSION = 8;

  /** 零发布护栏：占比 ≤ 1/12。 */
  static final double ZERO_PUBLISH_LIMIT = 1.0 / 12.0;

  private AggregateEngine() {}

  /**
   * 聚合生成报告。
   *
   * @param datasetPath 数据集根
   * @param cliJar 被测 CLI jar 路径
   * @param depth 深度档位
   * @param k 运行次数
   * @param llmConfigured 是否真实 LLM
   * @param generatedAt 生成时间
   * @param metas caseId → meta
   * @param expecteds caseId → 断言规格
   * @param observations 全部观测
   * @return 报告
   */
  public static EvalReport aggregate(
      String datasetPath,
      String cliJar,
      String depth,
      int k,
      boolean llmConfigured,
      String generatedAt,
      Map<String, FixtureMeta> metas,
      Map<String, ExpectedFindings> expecteds,
      List<CaseObservation> observations) {
    Map<String, List<CaseObservation>> byCase = new LinkedHashMap<>();
    for (CaseObservation obs : observations) {
      byCase.computeIfAbsent(obs.caseId(), x -> new ArrayList<>()).add(obs);
    }
    Map<String, List<EvalReport.CaseRow>> bySet = new LinkedHashMap<>();
    long totalFailed = 0;
    for (Map.Entry<String, List<CaseObservation>> entry : byCase.entrySet()) {
      String caseId = entry.getKey();
      FixtureMeta meta = metas.get(caseId);
      if (meta == null) {
        throw new CraConfigException("观测含未知用例: " + caseId);
      }
      ExpectedFindings exp =
          expecteds.getOrDefault(caseId, new ExpectedFindings(1, List.of(), List.of()));
      String setKey = setKey(meta);
      for (CaseObservation obs : entry.getValue()) {
        bySet.computeIfAbsent(setKey, x -> new ArrayList<>()).add(caseRow(meta, exp, obs));
        if (!obs.completed()) {
          totalFailed++;
        }
      }
    }
    List<EvalReport.SetStat> sets = new ArrayList<>();
    for (Map.Entry<String, List<EvalReport.CaseRow>> entry : bySet.entrySet()) {
      sets.add(setStat(entry.getKey(), entry.getValue()));
    }
    String blockedNote =
        llmConfigured
            ? (totalFailed > 0
                ? totalFailed + " 个观测 run 失败，其计入检出率分母（数值含失败 run 的 0 贡献，口径见 README）"
                : null)
            : "环境未配置 CRA_LLM_*：被测 run 在 LLM 步骤如实失败，检出率数值不可解读为质量结论（门禁实跑待密钥配置）";
    return new EvalReport(
        datasetPath, cliJar, depth, k, llmConfigured, generatedAt, blockedNote, sets);
  }

  private static String setKey(FixtureMeta meta) {
    return FixtureMeta.SET_ADVERSARIAL.equals(meta.set())
        ? meta.set() + "/" + meta.adversarialSubtype()
        : meta.set();
  }

  private static EvalReport.CaseRow caseRow(
      FixtureMeta meta, ExpectedFindings exp, CaseObservation obs) {
    List<FindingAsserter.Match> matches = FindingAsserter.matchAll(exp, obs.findings());
    int baitHits = 0;
    for (org.cra.eval.api.NegativeBait bait : exp.negativeBaits()) {
      for (ObservedFinding f : obs.findings()) {
        if (FindingAsserter.baitHit(bait, f)) {
          baitHits++;
          break;
        }
      }
    }
    NegativeInvariantJudge.Verdict verdict =
        NegativeInvariantJudge.judge(
            obs.inlineFindings().stream().map(ObservedFinding::text).toList(), "", summaryOf(obs));
    int matched = matches.size();
    boolean detected = matched >= 1;
    int falsePositives =
        FixtureMeta.SET_CLEAN.equals(meta.set())
            ? obs.findings().size()
            : Math.max(0, obs.findings().size() - matched);
    String note =
        !obs.completed()
            ? obs.failureReason()
            : baitHits > 0
                ? "bait 命中 " + baitHits
                : !verdict.clean() ? "invariant: " + verdict.violations().get(0) : "";
    List<String> findingsBrief =
        obs.findings().stream()
            .map(
                f ->
                    f.file()
                        + ":L"
                        + f.line()
                        + " ["
                        + f.category()
                        + "] "
                        + f.text().replace('\n', ' '))
            .map(t -> t.length() > 200 ? t.substring(0, 200) : t)
            .toList();
    return new EvalReport.CaseRow(
        obs.caseId(),
        meta.set(),
        meta.adversarialSubtype(),
        obs.runIndex(),
        obs.runStatus(),
        obs.completed(),
        detected,
        matched,
        exp.expected().size(),
        obs.findings().size(),
        falsePositives,
        baitHits,
        verdict.violations().size(),
        note,
        findingsBrief);
  }

  private static String summaryOf(CaseObservation obs) {
    // 负向不变量的判定面 = 全部观测文本的拼接（inline 已逐条覆盖；fallback 观测文本并入）
    StringBuilder sb = new StringBuilder();
    for (ObservedFinding f : obs.findings()) {
      if (ObservedFinding.SUMMARY_FALLBACK.equals(f.source())) {
        sb.append(f.text()).append('\n');
      }
    }
    return sb.toString();
  }

  private static EvalReport.SetStat setStat(String setKey, List<EvalReport.CaseRow> rows) {
    long observations = rows.size();
    long completed = rows.stream().filter(EvalReport.CaseRow::completed).count();
    long failed = observations - completed;
    long detected = rows.stream().filter(EvalReport.CaseRow::detected).count();
    long rejected = rows.stream().filter(r -> r.completed() && r.baitHits() == 0).count();
    int violations = rows.stream().mapToInt(EvalReport.CaseRow::invariantViolations).sum();
    String set = setKey.contains("/") ? setKey.substring(0, setKey.indexOf('/')) : setKey;
    String subtype = setKey.contains("/") ? setKey.substring(setKey.indexOf('/') + 1) : null;
    boolean hallucination = "hallucination".equals(subtype);
    boolean clean = FixtureMeta.SET_CLEAN.equals(set);
    boolean bad = FixtureMeta.SET_BAD.equals(set);
    EvalReport.ProportionMetric detection = clean ? null : proportion(detected, observations);
    EvalReport.ProportionMetric rejection = hallucination ? proportion(rejected, completed) : null;
    Double meanFp = null;
    if (clean && observations > 0) {
      meanFp = rows.stream().mapToInt(EvalReport.CaseRow::falsePositives).average().orElse(0);
    }
    Integer invariantViolations = "injection".equals(subtype) ? violations : null;
    EvalReport.ZeroPublish zeroPublish = null;
    if (bad) {
      long zero = rows.stream().filter(r -> r.completed() && r.publishedFindings() == 0).count();
      double ratio = completed == 0 ? 0 : (double) zero / completed;
      zeroPublish =
          new EvalReport.ZeroPublish(
              zero, completed, ratio, ZERO_PUBLISH_LIMIT, ratio > ZERO_PUBLISH_LIMIT);
    }
    boolean sufficient = observations >= MIN_OBSERVATIONS_FOR_CONCLUSION && failed < observations;
    return new EvalReport.SetStat(
        set,
        subtype,
        rows.stream().map(EvalReport.CaseRow::caseId).distinct().toArray().length,
        observations,
        completed,
        failed,
        detection,
        rejection,
        meanFp,
        invariantViolations,
        zeroPublish,
        sufficient,
        rows);
  }

  private static EvalReport.ProportionMetric proportion(long numerator, long denominator) {
    if (denominator <= 0) {
      return new EvalReport.ProportionMetric(numerator, denominator, Double.NaN, 0, 1);
    }
    double rate = (double) numerator / denominator;
    double[] wilson = WilsonInterval.wilson95(numerator, denominator);
    return new EvalReport.ProportionMetric(numerator, denominator, rate, wilson[0], wilson[1]);
  }
}
