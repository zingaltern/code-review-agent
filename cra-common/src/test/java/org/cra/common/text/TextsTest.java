package org.cra.common.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

/** 跨模块文本/校验辅助的边界单测（7/8 位 sha、200 字符截断、空白校验）。 */
class TextsTest {

  @Test
  void shortShaHandlesNullBlankAndLengths() {
    assertThat(Texts.shortSha(null, 7, "")).isEmpty();
    assertThat(Texts.shortSha("   ", 7, "?")).isEqualTo("?");
    assertThat(Texts.shortSha("abc", 7, "")).isEqualTo("abc");
    assertThat(Texts.shortSha("abcdefgh", 7, "")).isEqualTo("abcdefg");
    assertThat(Texts.shortSha("abcdefgh", 8, "")).isEqualTo("abcdefgh");
  }

  @Test
  void truncateAddsEllipsisOnlyWhenOverLimit() {
    assertThat(Texts.truncate(null, 200)).isEmpty();
    assertThat(Texts.truncate("abc", 200)).isEqualTo("abc");
    String exact = "x".repeat(200);
    assertThat(Texts.truncate(exact, 200)).isEqualTo(exact);
    assertThat(Texts.truncate("x".repeat(201), 200))
        .hasSize(201)
        .endsWith("…")
        .startsWith("x".repeat(200));
  }

  @Test
  void requireNonBlankRejectsNullOrBlankWithFieldName() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> Texts.requireNonBlank(null, "title"))
        .withMessage("title must be non-blank");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> Texts.requireNonBlank("  ", "file"))
        .withMessage("file must be non-blank");
    Texts.requireNonBlank("ok", "title");
  }

  @Test
  void firstNonBlankReturnsFirstMeaningfulValueOrNull() {
    assertThat(Texts.firstNonBlank(null, "", "  ", "token")).isEqualTo("token");
    assertThat(Texts.firstNonBlank(null, "", "  ")).isNull();
    assertThat(Texts.firstNonBlank()).isNull();
  }
}
