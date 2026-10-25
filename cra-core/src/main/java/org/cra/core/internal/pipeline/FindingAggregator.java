package org.cra.core.internal.pipeline;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.cra.common.context.ReviewPolicy;
import org.cra.common.finding.AnchorStatus;
import org.cra.common.finding.CandidateFinding;
import org.cra.common.finding.Severity;
import org.cra.core.internal.pipeline.PreExistingBlameChecker.Verdict;

/**
 * 发布聚合（07 T1.7）：severity 排序（Important → Nit → Pre-existing 分组，组内 file/line 稳定序）+ nit 预算（REVIEW.md
 * Nit budget 优先，0 = 禁止）+ 降档阶梯截断 top-10（02§4.4，丢弃计数 suppressed_by_truncation）。
 */
public final class FindingAggregator {

  /** 发布集合截断上限（02§4.4 ≥85% 档）。 */
  static final int TRUNCATION_LIMIT = 10;

  private FindingAggregator() {}

  /** 聚合后的发布条目（finding + 指纹 + 锚定 + blame 结论）。 */
  public record Scored(
      CandidateFinding finding, String fingerprint, AnchorStatus anchor, Verdict verdict) {}

  /**
   * 聚合结果。
   *
   * @param ordered 最终发布顺序（截断与 nit 预算后）
   * @param nitDropped nit 超预算丢弃数
   * @param suppressedByTruncation 预算截断丢弃数（02§4.4 梯度沉默护栏）
   */
  public record Aggregation(List<Scored> ordered, int nitDropped, int suppressedByTruncation) {

    public Aggregation {
      ordered = List.copyOf(ordered == null ? List.of() : ordered);
    }
  }

  /**
   * 聚合发布集合。
   *
   * @param candidates 去重+锚定后的候选
   * @param policy base 分支策略（Nit budget）
   * @param nitBudgetDefault 策略未配置时的默认预算
   * @param truncate true 时按 severity 截断 top-10（阶梯 ≥85% 档）
   * @return 聚合结果
   */
  public static Aggregation aggregate(
      List<Scored> candidates, ReviewPolicy policy, int nitBudgetDefault, boolean truncate) {
    List<Scored> sorted = new ArrayList<>(candidates);
    sorted.sort(publishOrder());

    int nitBudget = policy.effectiveNitBudget(nitBudgetDefault);
    List<Scored> kept = new ArrayList<>();
    int nits = 0;
    int nitDropped = 0;
    for (Scored scored : sorted) {
      boolean preExisting = scored.verdict() == Verdict.CONFIRMED_PRE_EXISTING;
      if (!preExisting && scored.finding().severityProposed() == Severity.NIT) {
        if (nits >= nitBudget) {
          nitDropped++;
          continue;
        }
        nits++;
      }
      kept.add(scored);
    }

    int suppressed = 0;
    if (truncate && kept.size() > TRUNCATION_LIMIT) {
      suppressed = kept.size() - TRUNCATION_LIMIT;
      kept = new ArrayList<>(kept.subList(0, TRUNCATION_LIMIT));
    }
    return new Aggregation(kept, nitDropped, suppressed);
  }

  private static Comparator<Scored> publishOrder() {
    Comparator<Scored> byGroup =
        Comparator.comparingInt(
            scored -> {
              if (scored.verdict() == Verdict.CONFIRMED_PRE_EXISTING) {
                return 2;
              }
              return scored.finding().severityProposed() == Severity.IMPORTANT ? 0 : 1;
            });
    return byGroup
        .thenComparing(s -> s.finding().file())
        .thenComparingInt(s -> s.finding().line())
        .thenComparing(s -> s.finding().id());
  }
}
