package org.cra.eval.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** FixtureMeta 对抗子类校验（05§1.1 / 06§1.2 第 4 类：context 必须在枚举内，M3 协调者补接缝）。 */
class FixtureMetaTest {

  @Test
  void contextSubtypeAccepted() {
    assertThat(meta("context").adversarialSubtype()).isEqualTo("context");
  }

  @Test
  void injectionHallucinationRefusalStillAccepted() {
    assertThat(meta("injection").adversarialSubtype()).isEqualTo("injection");
    assertThat(meta("hallucination").adversarialSubtype()).isEqualTo("hallucination");
    assertThat(meta("refusal").adversarialSubtype()).isEqualTo("refusal");
  }

  @Test
  void unknownSubtypeRejected() {
    assertThatThrownBy(() -> meta("bogus"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("adversarial_subtype");
  }

  @Test
  void nullSubtypeAllowedForNonAdversarial() {
    assertThat(meta(null).adversarialSubtype()).isNull();
  }

  private static FixtureMeta meta(String subtype) {
    return new FixtureMeta(
        1,
        "case",
        subtype == null ? "bad" : "adversarial",
        subtype,
        "synthetic",
        null,
        null,
        null,
        "base",
        "head",
        new FixtureMeta.Snapshot("repo_snapshot.tgz", "abc", "."),
        new FixtureMeta.ReviewRequestSpec(1, "o", "r"),
        java.util.List.of(),
        new FixtureMeta.Annotation("annotated", "t", "2026-10-26"),
        new FixtureMeta.Recheck("pending", null, null),
        "");
  }
}
