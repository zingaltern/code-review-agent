package org.cra.core.internal.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 确定性抽查保险丝（03§4.3 v1.2：sha256(run_id|finding_id) mod 100 < pct，可复现）。 */
class SpotCheckFuseTest {

  @Test
  void sameInputsAlwaysSameDecision() {
    // 同 run 重放结果一致——回归「随机数/时间因素禁止」的纪律
    for (String id : new String[] {"f_1", "f_abc", "f_deadbeef"}) {
      boolean first = SpotCheckFuse.hit("run_20261025", id, 20);
      for (int i = 0; i < 10; i++) {
        assertThat(SpotCheckFuse.hit("run_20261025", id, 20)).isEqualTo(first);
      }
    }
  }

  @Test
  void boundaryPercentagesAreExhaustive() {
    assertThat(SpotCheckFuse.hit("run_x", "f_1", 0)).isFalse();
    assertThat(SpotCheckFuse.hit("run_x", "f_1", 100)).isTrue();
  }

  @Test
  void hitRateConvergesToConfiguredPercentage() {
    // 10000 个 id 上 20% 档命中率应落在 [18%,22%]（哈希均匀性的统计锚）
    long hits =
        java.util.stream.LongStream.range(0, 10_000)
            .filter(i -> SpotCheckFuse.hit("run_x", "f_" + i, 20))
            .count();
    assertThat(hits).isBetween(1_800L, 2_200L);
  }

  @Test
  void runIdParticipatesSoDifferentRunsSampleDifferentFindings() {
    // 同 finding 集在不同 run 下抽样集合不应完全一致（run_id 参与哈希）
    long diff =
        java.util.stream.LongStream.range(0, 1_000)
            .filter(
                i ->
                    SpotCheckFuse.hit("run_a", "f_" + i, 50)
                        != SpotCheckFuse.hit("run_b", "f_" + i, 50))
            .count();
    assertThat(diff).isGreaterThan(400); // 独立抽样下约一半不同
  }
}
