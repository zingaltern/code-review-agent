package org.cra.eval.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.cra.eval.api.ExpectedFinding;
import org.cra.eval.api.ExpectedFindings;
import org.cra.eval.api.NegativeBait;

/**
 * 断言器（06§3）：expected findings 匹配 = 位置 ±5 行 + 类别 + 断言关键词（全部满足）； bait 命中 = 文件相等（若 bait 指定）+ 行落区间 ± 容差
 * + 任一关键词出现（过敏感取向）。
 */
public final class FindingAsserter {

  private FindingAsserter() {}

  /**
   * expected 与观测的匹配结果。
   *
   * @param expectedIndex 命中的 expected 序号
   * @param observedIndex 命中的观测序号
   */
  public record Match(int expectedIndex, int observedIndex) {}

  /**
   * 全量匹配：每个 expected 至多被一个观测消耗（防一条观测匹配全部 expected 造成虚高）。
   *
   * @param expected 断言规格
   * @param observed 该 run 的全部已发布观测
   * @return 命中对列表
   */
  public static List<Match> matchAll(ExpectedFindings expected, List<ObservedFinding> observed) {
    List<Match> matches = new ArrayList<>();
    boolean[] consumed = new boolean[observed.size()];
    for (int e = 0; e < expected.expected().size(); e++) {
      ExpectedFinding exp = expected.expected().get(e);
      for (int o = 0; o < observed.size(); o++) {
        if (consumed[o]) {
          continue;
        }
        if (matches(exp, observed.get(o))) {
          consumed[o] = true;
          matches.add(new Match(e, o));
          break;
        }
      }
    }
    return matches;
  }

  /**
   * 单对匹配（06§3 口径）。
   *
   * @param exp 期望发现
   * @param obs 已发布观测
   * @return true 若位置 ±5 行 + 类别 + 全部关键词都满足
   */
  public static boolean matches(ExpectedFinding exp, ObservedFinding obs) {
    if (!exp.file().equals(obs.file())) {
      return false;
    }
    if (Math.abs(obs.line() - exp.line()) > ExpectedFinding.LINE_TOLERANCE) {
      return false;
    }
    if (obs.category() == null || !obs.category().equalsIgnoreCase(exp.category())) {
      return false;
    }
    String text = obs.text().toLowerCase(Locale.ROOT);
    for (String kw : exp.keywords()) {
      if (!text.contains(kw.toLowerCase(Locale.ROOT))) {
        return false;
      }
    }
    return true;
  }

  /**
   * bait 命中判定（幻觉/注入负向断言）：任一关键词出现即命中（过敏感），文件与行区间若指定则须满足。
   *
   * @param bait 负向 bait
   * @param obs 已发布观测
   * @return true 若该观测构成 bait 命中
   */
  public static boolean baitHit(NegativeBait bait, ObservedFinding obs) {
    if (!bait.file().isEmpty() && !bait.file().equals(obs.file())) {
      return false;
    }
    if (bait.lineFrom() != null
        && (obs.line() < bait.lineFrom() - ExpectedFinding.LINE_TOLERANCE
            || bait.lineTo() == null
            || obs.line() > bait.lineTo() + ExpectedFinding.LINE_TOLERANCE)) {
      return false;
    }
    if (bait.keywords().isEmpty()) {
      return true;
    }
    String text = obs.text().toLowerCase(Locale.ROOT);
    for (String kw : bait.keywords()) {
      if (text.contains(kw.toLowerCase(Locale.ROOT))) {
        return true;
      }
    }
    return false;
  }
}
