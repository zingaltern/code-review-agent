package org.cra.llm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.cra.common.exception.CraConfigException;
import org.junit.jupiter.api.Test;

/** {@link LlmParsing} 共享解析面：null 契约、去重与非法值组装期失败（PR #67 评审跟进）。 */
class LlmParsingTest {

  private static final java.util.function.Function<String, String> MODE_PARSER =
      raw -> {
        if (!raw.equals("assumption") && !raw.equals("sniff")) {
          throw new IllegalArgumentException("非法模式: " + raw);
        }
        return raw;
      };

  @Test
  void parseCsvSetNullRawFailsWithEmptyMessageNotNpe() {
    assertThatThrownBy(
            () ->
                LlmParsing.parseCsvSet(
                    null, MODE_PARSER, (raw, e) -> new CraConfigException(e.getMessage()), "不能为空"))
        .isInstanceOf(CraConfigException.class)
        .hasMessageContaining("不能为空");
  }

  @Test
  void parseCsvSetParsesDeduplicatesAndRejectsIllegalTokens() {
    Set<String> modes =
        LlmParsing.parseCsvSet(
            "assumption,sniff,assumption",
            MODE_PARSER,
            (raw, e) -> new CraConfigException(e.getMessage()),
            "empty");
    assertThat(modes).containsExactlyInAnyOrder("assumption", "sniff");

    assertThatThrownBy(
            () ->
                LlmParsing.parseCsvSet(
                    "assumption,bogus",
                    MODE_PARSER,
                    (raw, e) -> new CraConfigException("非法: " + e.getMessage()),
                    "empty"))
        .isInstanceOf(CraConfigException.class)
        .hasMessageContaining("bogus");
  }
}
