package org.cra.agents.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** VerificationItem 构造校验（05§1.1）：category/severity 空白拒绝，suggestion null 归一。 */
class VerificationItemTest {

  private static VerificationItem item(String category, String severity) {
    return new VerificationItem("f_01", category, severity, "t", "d", "f", "e", null);
  }

  @Test
  void rejectsBlankCategoryAndSeverity() {
    assertThatThrownBy(() -> item("", "important"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("category");
    assertThatThrownBy(() -> item("bug", " "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("severity");
  }

  @Test
  void normalizesNullSuggestion() {
    assertThat(item("bug", "important").suggestion()).isEmpty();
  }
}
