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

  @Test
  void parseMaxConcurrencyDefaultsAndBounds() {
    // 05§5 max-concurrency：空 = 默认 8；合法域 1..64（有效并发另受线程池宽度约束）
    assertThat(LlmParsing.parseMaxConcurrency(null, "CRA_LLM_MAX_CONCURRENCY"))
        .isEqualTo(LlmGatewayConfig.DEFAULT_MAX_CONCURRENCY);
    assertThat(LlmParsing.parseMaxConcurrency("  ", "CRA_LLM_MAX_CONCURRENCY"))
        .isEqualTo(LlmGatewayConfig.DEFAULT_MAX_CONCURRENCY);
    assertThat(LlmParsing.parseMaxConcurrency("16", "CRA_LLM_MAX_CONCURRENCY")).isEqualTo(16);
    assertThat(LlmParsing.parseMaxConcurrency("1", "CRA_LLM_MAX_CONCURRENCY")).isEqualTo(1);
    assertThat(LlmParsing.parseMaxConcurrency("64", "CRA_LLM_MAX_CONCURRENCY")).isEqualTo(64);

    for (String invalid : new String[] {"0", "65", "-1", "abc"}) {
      assertThatThrownBy(() -> LlmParsing.parseMaxConcurrency(invalid, "CRA_LLM_MAX_CONCURRENCY"))
          .isInstanceOf(CraConfigException.class)
          .hasMessageContaining("CRA_LLM_MAX_CONCURRENCY 必须在 1..64");
    }
  }

  @Test
  void threeTierConfigOverloadCarriesConcurrency() {
    LlmGatewayConfig explicit =
        LlmParsing.threeTierConfig(
            "https://llm.example/v1",
            "sk",
            "m",
            null,
            null,
            java.util.List.of(),
            LlmGatewayConfig.defaultRpm(),
            null,
            3);
    assertThat(explicit.maxConcurrency()).isEqualTo(3);

    // 旧签名保留：委托默认并发（02§4.5）
    LlmGatewayConfig legacy =
        LlmParsing.threeTierConfig(
            "https://llm.example/v1",
            "sk",
            "m",
            null,
            null,
            java.util.List.of(),
            LlmGatewayConfig.defaultRpm(),
            null);
    assertThat(legacy.maxConcurrency()).isEqualTo(LlmGatewayConfig.DEFAULT_MAX_CONCURRENCY);
  }
}
