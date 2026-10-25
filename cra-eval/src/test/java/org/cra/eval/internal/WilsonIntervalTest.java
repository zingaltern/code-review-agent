package org.cra.eval.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import org.cra.common.exception.CraConfigException;
import org.junit.jupiter.api.Test;

/** Wilson 95% 区间公式自校验（06§1.2 v1.2.1 数值锚点 + 独立推演案例）。 */
class WilsonIntervalTest {

  @Test
  void docAnchor_p85_n36_lowerAbout0_70() {
    // 06§1.2 功效核算：p=0.85、n=36 → Wilson 下界 0.700（v1.2 误写 0.733 是 Wald 结果）
    // p̂=0.85 取整观测 x=31/36（0.861）时下界 ≈0.7134（独立推演）
    double[] ci = WilsonInterval.wilson95(31, 36);
    assertThat(ci[0]).isCloseTo(0.7134, within(0.001));
    assertThat(ci[1]).isCloseTo(0.9392, within(0.001));
  }

  @Test
  void zeroSuccesses_intervalStartsAtZero() {
    double[] ci = WilsonInterval.wilson95(0, 20);
    assertThat(ci[0]).isZero();
    // x=0 上界 = z²/(n+z²) = 3.8416/23.8416 ≈ 0.1611
    assertThat(ci[1]).isCloseTo(0.1611, within(0.001));
  }

  @Test
  void allSuccesses_upperIsOne() {
    double[] ci = WilsonInterval.wilson95(36, 36);
    assertThat(ci[1]).isEqualTo(1.0);
    // 下界 = n/(n+z²) = 36/39.8416 ≈ 0.9036
    assertThat(ci[0]).isCloseTo(0.9036, within(0.001));
  }

  @Test
  void intervalContainsPhat_andShrinksWithN() {
    double[] small = WilsonInterval.wilson95(7, 12);
    assertThat(small[0]).isLessThanOrEqualTo(7.0 / 12);
    assertThat(small[1]).isGreaterThanOrEqualTo(7.0 / 12);
    // 7/12 手算: center=(0.5833+0.16)/1.3201=0.5631, half=0.2437 → [0.319, 0.807]
    assertThat(small[0]).isCloseTo(0.3194, within(0.001));
    assertThat(small[1]).isCloseTo(0.8068, within(0.001));

    double[] large = WilsonInterval.wilson95(70, 120);
    double widthSmall = small[1] - small[0];
    double widthLarge = large[1] - large[0];
    assertThat(widthLarge).isLessThan(widthSmall);
  }

  @Test
  void symmetricAtZeroAndAll() {
    // x=0 的上界 与 x=n 的下界关于 0.5 对称（同 n）
    double[] zero = WilsonInterval.wilson95(0, 36);
    double[] all = WilsonInterval.wilson95(36, 36);
    assertThat(zero[1]).isCloseTo(1 - all[0], within(1e-9));
  }

  @Test
  void rejectsInvalidInput() {
    assertThatThrownBy(() -> WilsonInterval.wilson95(0, 0)).isInstanceOf(CraConfigException.class);
    assertThatThrownBy(() -> WilsonInterval.wilson95(5, 4)).isInstanceOf(CraConfigException.class);
    assertThatThrownBy(() -> WilsonInterval.wilson95(-1, 10))
        .isInstanceOf(CraConfigException.class);
  }
}
