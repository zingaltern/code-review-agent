package org.cra.core.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * JudgeArbiter 纯函数守护（08§5）：brief 文件名 slug 的取正性。hashCode 恰为 Integer.MIN_VALUE 时 Math.abs 仍为负（旧实现产出
 * "case--83648"），位掩码形态必须落入 [0, 100000)。
 */
class JudgeArbiterTest {

  /** hashCode 恰为 Integer.MIN_VALUE 的构造亲证串（碰撞搜索所得，非随机样本）。 */
  private static final String MIN_HASH_WITNESS = "aAgaB9q";

  @Test
  void witnessHashCodeIsIntegerMinValue() {
    assertThat(MIN_HASH_WITNESS.hashCode()).isEqualTo(Integer.MIN_VALUE);
  }

  @Test
  void briefSlugStaysNonNegativeOnMinValueHashCode() {
    // MIN_VALUE & 0x7fffffff = 0；旧 Math.abs 形态此处为 "case--83648"
    assertThat(JudgeArbiter.briefSlug(MIN_HASH_WITNESS)).isEqualTo("case-0");
  }

  @Test
  void briefSlugSuffixAlwaysWithinRange() {
    for (String id : new String[] {"f_001", "x", "中文id", MIN_HASH_WITNESS, "aAgaB9r", "f_999999"}) {
      String slug = JudgeArbiter.briefSlug(id);
      assertThat(slug).startsWith("case-");
      int suffix = Integer.parseInt(slug.substring("case-".length()));
      assertThat(suffix).isBetween(0, 99_999);
    }
  }
}
