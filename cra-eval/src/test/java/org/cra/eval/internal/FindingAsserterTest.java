package org.cra.eval.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.cra.eval.api.ExpectedFinding;
import org.cra.eval.api.ExpectedFindings;
import org.cra.eval.api.NegativeBait;
import org.junit.jupiter.api.Test;

/** 断言器口径单测（06§3：位置 ±5 行 + 类别 + 断言关键词）。 */
class FindingAsserterTest {

  private static ObservedFinding inline(String file, int line, String category, String text) {
    return new ObservedFinding(ObservedFinding.INLINE, file, line, category, text);
  }

  @Test
  void matchesExactPositionCategoryAndAllKeywords() {
    ExpectedFinding exp =
        new ExpectedFinding("src/main/java/Foo.java", 87, "bug", List.of("size()", "get("), "");
    assertThat(
            matches(
                exp,
                inline("src/main/java/Foo.java", 87, "bug", "evidence: items.get(items.size()-1)")))
        .isTrue();
  }

  @Test
  void toleranceWindowIsFiveLines() {
    ExpectedFinding exp = new ExpectedFinding("Foo.java", 87, "bug", List.of(), "");
    assertThat(matches(exp, inline("Foo.java", 82, "bug", "x"))).isTrue(); // -5 边界内
    assertThat(matches(exp, inline("Foo.java", 92, "bug", "x"))).isTrue(); // +5 边界内
    assertThat(matches(exp, inline("Foo.java", 81, "bug", "x"))).isFalse(); // -6 越界
    assertThat(matches(exp, inline("Foo.java", 93, "bug", "x"))).isFalse(); // +6 越界
  }

  @Test
  void categoryAndFileMustMatchExactly() {
    ExpectedFinding exp = new ExpectedFinding("Foo.java", 10, "bug", List.of(), "");
    assertThat(matches(exp, inline("Foo.java", 10, "BUG", "x"))).isTrue(); // 大小写不敏感
    assertThat(matches(exp, inline("Foo.java", 10, "security", "x"))).isFalse();
    assertThat(matches(exp, inline("Bar.java", 10, "bug", "x"))).isFalse();
  }

  @Test
  void fallbackFindingWithoutCategoryNeverMatches() {
    ExpectedFinding exp = new ExpectedFinding("Foo.java", 10, "bug", List.of(), "");
    ObservedFinding fallback =
        new ObservedFinding(ObservedFinding.SUMMARY_FALLBACK, "Foo.java", 10, null, "标题");
    assertThat(matches(exp, fallback)).isFalse();
  }

  @Test
  void allKeywordsRequired_whenPresent() {
    ExpectedFinding exp = new ExpectedFinding("Foo.java", 10, "bug", List.of("alpha", "beta"), "");
    assertThat(matches(exp, inline("Foo.java", 10, "bug", "has alpha and beta"))).isTrue();
    assertThat(matches(exp, inline("Foo.java", 10, "bug", "has alpha only"))).isFalse();
  }

  @Test
  void matchAllConsumesEachObservationOnce() {
    ExpectedFindings expected =
        new ExpectedFindings(
            1,
            List.of(
                new ExpectedFinding("A.java", 10, "bug", List.of("aaa"), ""),
                new ExpectedFinding("B.java", 20, "bug", List.of("bbb"), "")),
            List.of());
    ObservedFinding one = inline("A.java", 10, "bug", "mentions aaa and bbb but only counts once");
    List<FindingAsserter.Match> matches = FindingAsserter.matchAll(expected, List.of(one));
    assertThat(matches).hasSize(1);
    assertThat(matches.get(0).expectedIndex()).isZero();

    List<FindingAsserter.Match> two =
        FindingAsserter.matchAll(
            expected,
            List.of(
                inline("A.java", 10, "bug", "aaa here"), inline("B.java", 20, "bug", "bbb here")));
    assertThat(two).hasSize(2);
  }

  @Test
  void baitHitAnyKeywordIsSensitive() {
    NegativeBait bait =
        new NegativeBait("Bait.java", 10, 20, List.of("safe constructs"), "hallucination-bait", "");
    assertThat(
            FindingAsserter.baitHit(
                bait, inline("Bait.java", 15, "bug", "claims safe constructs broken")))
        .isTrue();
    assertThat(FindingAsserter.baitHit(bait, inline("Bait.java", 15, "bug", "unrelated")))
        .isFalse();
    // 行区间 ±5 容差
    assertThat(FindingAsserter.baitHit(bait, inline("Bait.java", 25, "bug", "safe constructs")))
        .isTrue();
    assertThat(FindingAsserter.baitHit(bait, inline("Bait.java", 30, "bug", "safe constructs")))
        .isFalse();
    // file 不限定（空字符串 = 任意文件）
    NegativeBait anyFile = new NegativeBait("", null, null, List.of("totally-flagged"), "", "");
    assertThat(
            FindingAsserter.baitHit(anyFile, inline("Elsewhere.java", 1, "bug", "totally-flagged")))
        .isTrue();
  }

  private boolean matches(ExpectedFinding exp, ObservedFinding obs) {
    return FindingAsserter.matches(exp, obs);
  }
}
