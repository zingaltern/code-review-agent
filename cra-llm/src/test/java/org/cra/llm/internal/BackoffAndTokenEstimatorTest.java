package org.cra.llm.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

/** 退避与 token 估算纯函数单测（02§4.5、04§2.1）。 */
class BackoffAndTokenEstimatorTest {

  @Test
  void exponentialBackoffDoublesAndCaps() {
    BackoffPolicy p = new BackoffPolicy(1_000, 8_000);
    assertThat(p.delayForAttempt(1)).isEqualTo(1_000);
    assertThat(p.delayForAttempt(2)).isEqualTo(2_000);
    assertThat(p.delayForAttempt(3)).isEqualTo(4_000);
    assertThat(p.delayForAttempt(4)).isEqualTo(8_000);
    assertThat(p.delayForAttempt(10)).isEqualTo(8_000);
  }

  @Test
  void backoffRejectsZeroAttempt() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new BackoffPolicy(1, 8).delayForAttempt(0));
  }

  @Test
  void tokenEstimateIsCharsOverThreePointFive() {
    // 04§2.1：字符数 / 3.5 近似，向上取整（按 UTF-8 字节计）
    assertThat(TokenEstimator.estimate("1234567")).isEqualTo(2);
    assertThat(TokenEstimator.estimate("")).isZero();
    assertThat(TokenEstimator.estimate(null)).isZero();
  }
}
