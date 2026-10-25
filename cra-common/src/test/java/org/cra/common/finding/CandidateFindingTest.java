package org.cra.common.finding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

/** 05§1.1 schema 校验单测（08§2.2：解析拒收是行为，必须锁住）。 */
class CandidateFindingTest {

  private static CandidateFinding valid() {
    return new CandidateFinding(
        "f_01hxy",
        "run_01hxy",
        FindingCategory.BUG,
        "Offset 计算在空列表时越界",
        "items 为空时 size()-1 返回 -1，get(-1) 抛 IndexOutOfBoundsException",
        "触发条件：items 为空列表；错误结果：IndexOutOfBoundsException",
        "src/main/java/Foo.java",
        87,
        null,
        "L85-88: return items.get(items.size()-1)",
        null,
        Severity.IMPORTANT,
        false,
        0.8,
        "general-reviewer",
        "glm-4");
  }

  @Test
  void validFindingConstructs() {
    CandidateFinding f = valid();
    assertThat(f.side()).isEqualTo(Side.RIGHT);
    assertThat(f.suggestion()).isNull();
    assertThat(f.preExistingSuspect()).isFalse();
  }

  @Test
  void contextCategoryExistsInEnum() {
    // 05§1.1 v1.3.1：context 漏进枚举曾导致 CrossChecker 输出全部被拒（01§8 四轮缺陷①），锁死
    assertThat(FindingCategory.values()).contains(FindingCategory.CONTEXT);
  }

  @Test
  void nullCategoryRejected() {
    assertThatNullPointerException()
        .isThrownBy(
            () ->
                new CandidateFinding(
                    "f_x",
                    "run_x",
                    null,
                    "t",
                    "d",
                    "fm",
                    "f",
                    1,
                    null,
                    "e",
                    null,
                    Severity.NIT,
                    false,
                    null,
                    "r",
                    "m"));
  }

  @Test
  void badIdPrefixRejected() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new CandidateFinding(
                    "x_01",
                    "run_01",
                    FindingCategory.BUG,
                    "t",
                    "d",
                    "fm",
                    "f",
                    1,
                    null,
                    "e",
                    null,
                    Severity.NIT,
                    false,
                    null,
                    "r",
                    "m"));
  }

  @Test
  void badRunIdPrefixRejected() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new CandidateFinding(
                    "f_01",
                    "nope",
                    FindingCategory.BUG,
                    "t",
                    "d",
                    "fm",
                    "f",
                    1,
                    null,
                    "e",
                    null,
                    Severity.NIT,
                    false,
                    null,
                    "r",
                    "m"));
  }

  @Test
  void blankTitleRejected() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new CandidateFinding(
                    "f_01",
                    "run_01",
                    FindingCategory.BUG,
                    " ",
                    "d",
                    "fm",
                    "f",
                    1,
                    null,
                    "e",
                    null,
                    Severity.NIT,
                    false,
                    null,
                    "r",
                    "m"));
  }

  @Test
  void nonPositiveLineRejected() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new CandidateFinding(
                    "f_01",
                    "run_01",
                    FindingCategory.BUG,
                    "t",
                    "d",
                    "fm",
                    "f",
                    0,
                    null,
                    "e",
                    null,
                    Severity.NIT,
                    false,
                    null,
                    "r",
                    "m"));
  }

  @Test
  void confidenceOutOfRangeRejected() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new CandidateFinding(
                    "f_01",
                    "run_01",
                    FindingCategory.BUG,
                    "t",
                    "d",
                    "fm",
                    "f",
                    1,
                    null,
                    "e",
                    null,
                    Severity.NIT,
                    false,
                    1.5,
                    "r",
                    "m"));
  }

  @Test
  void nullSideDefaultsToRight() {
    CandidateFinding f = valid();
    assertThat(f.side()).isEqualTo(Side.RIGHT);
  }
}
