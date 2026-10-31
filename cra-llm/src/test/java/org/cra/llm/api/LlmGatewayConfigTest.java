package org.cra.llm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** LlmGatewayConfig 组装期硬校验（05§5 fail-fast 纪律）：非法配置在构造器拒绝且文案可定位，运行期不再穿出网关。 */
class LlmGatewayConfigTest {

  private static LlmGatewayConfig.ModelEndpoint endpoint() {
    return new LlmGatewayConfig.ModelEndpoint("std-model", "http://llm.test/v1", "key");
  }

  private static LlmGatewayConfig valid() {
    return new LlmGatewayConfig(
        LlmGatewayConfig.models(endpoint(), endpoint(), endpoint()),
        List.of(),
        LlmGatewayConfig.defaultRpm(),
        1,
        0,
        1_000,
        500,
        10,
        40,
        null,
        Map.of(),
        null);
  }

  @Test
  void modelsMustContainStandardTier() {
    assertThatThrownBy(
            () ->
                new LlmGatewayConfig(
                    Map.of(), List.of(), null, 1, 0, 1_000, 500, 10, 40, null, Map.of(), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("必须至少配置 standard");
  }

  @Test
  void fallbackChainEntriesMustBeConfigured() {
    assertThatThrownBy(
            () ->
                new LlmGatewayConfig(
                    Map.of(ModelTier.STANDARD, endpoint()),
                    List.of(ModelTier.LIGHT),
                    null,
                    1,
                    0,
                    1_000,
                    500,
                    10,
                    40,
                    null,
                    Map.of(),
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("fallback-chain 条目未配置模型端点");
  }

  @Test
  void perModelRpmMustBePositive() {
    assertThatThrownBy(
            () ->
                new LlmGatewayConfig(
                    LlmGatewayConfig.models(endpoint(), endpoint(), endpoint()),
                    List.of(),
                    Map.of(ModelTier.STANDARD, 0),
                    1,
                    0,
                    1_000,
                    500,
                    10,
                    40,
                    null,
                    Map.of(),
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("per-model RPM 必须为正整数");
  }

  @Test
  void concurrencyPositiveAndRetriesNonNegative() {
    assertThatThrownBy(() -> validButMaxConcurrency(0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxConcurrency must be positive");
    assertThatThrownBy(() -> validButMaxRetries(-1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxRetries must be non-negative");
    // maxRetries=0 合法（一次尝试、不重试）
    assertThatCode(() -> validButMaxRetries(0)).doesNotThrowAnyException();
  }

  private static LlmGatewayConfig validButMaxConcurrency(int maxConcurrency) {
    return new LlmGatewayConfig(
        LlmGatewayConfig.models(endpoint(), endpoint(), endpoint()),
        List.of(),
        LlmGatewayConfig.defaultRpm(),
        maxConcurrency,
        0,
        1_000,
        500,
        10,
        40,
        null,
        Map.of(),
        null);
  }

  private static LlmGatewayConfig validButMaxRetries(int maxRetries) {
    return new LlmGatewayConfig(
        LlmGatewayConfig.models(endpoint(), endpoint(), endpoint()),
        List.of(),
        LlmGatewayConfig.defaultRpm(),
        1,
        maxRetries,
        1_000,
        500,
        10,
        40,
        null,
        Map.of(),
        null);
  }

  @Test
  void timeoutsMustBePositiveAndBackoffCapped() {
    assertThatThrownBy(
            () ->
                new LlmGatewayConfig(
                    LlmGatewayConfig.models(endpoint(), endpoint(), endpoint()),
                    List.of(),
                    null,
                    1,
                    0,
                    0,
                    500,
                    10,
                    40,
                    null,
                    Map.of(),
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("timeouts must be positive");
    assertThatThrownBy(
            () ->
                new LlmGatewayConfig(
                    LlmGatewayConfig.models(endpoint(), endpoint(), endpoint()),
                    List.of(),
                    null,
                    1,
                    0,
                    1_000,
                    500,
                    40,
                    10,
                    null,
                    Map.of(),
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("timeouts must be positive");
  }

  @Test
  void extraHeadersNullBecomesEmptyImmutableMap() {
    LlmGatewayConfig config = validWithExtraHeaders(null);
    assertThat(config.extraHeaders()).isEmpty();
    assertThatThrownBy(() -> config.extraHeaders().put("X", "y"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void extraHeadersDefensivelyCopied() {
    Map<String, String> source = new HashMap<>();
    source.put("X-Session", "s1");
    LlmGatewayConfig config = validWithExtraHeaders(source);
    source.put("X-Session", "s2"); // 装配后外部变更不影响配置（09§6 防御拷贝）
    assertThat(config.extraHeaders()).containsEntry("X-Session", "s1");
  }

  @Test
  void extraHeadersRejectsIllegalEntries() {
    assertThatThrownBy(() -> validWithExtraHeaders(Map.of("X-A", "bad\nvalue")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("附加头值");
    Map<String, String> nullValue = new HashMap<>();
    nullValue.put("X-A", null);
    assertThatThrownBy(() -> validWithExtraHeaders(nullValue))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("附加头值 非法");
    assertThatThrownBy(() -> validWithExtraHeaders(Map.of(" ", "v")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("附加头名 非法");
  }

  private static LlmGatewayConfig validWithExtraHeaders(Map<String, String> extraHeaders) {
    return new LlmGatewayConfig(
        LlmGatewayConfig.models(endpoint(), endpoint(), endpoint()),
        List.of(),
        LlmGatewayConfig.defaultRpm(),
        1,
        0,
        1_000,
        500,
        10,
        40,
        null,
        extraHeaders,
        null);
  }

  @Test
  void rpmOfUnconfiguredTierIsZero() {
    LlmGatewayConfig config =
        new LlmGatewayConfig(
            LlmGatewayConfig.models(endpoint(), endpoint(), endpoint()),
            List.of(),
            Map.of(ModelTier.STANDARD, 60),
            1,
            0,
            1_000,
            500,
            10,
            40,
            null,
            Map.of(),
            null);
    assertThat(config.rpmOf(ModelTier.STANDARD)).isEqualTo(60);
    assertThat(config.rpmOf(ModelTier.STRONG)).isEqualTo(0); // 未配置 = 不限
  }

  @Test
  void endpointRejectsNullBlankModelAndNonHttpUrl() {
    assertThatThrownBy(() -> new LlmGatewayConfig.ModelEndpoint(null, "http://llm.test/v1", "k"))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new LlmGatewayConfig.ModelEndpoint(" ", "http://llm.test/v1", "k"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("model/baseUrl must be non-blank");
    assertThatThrownBy(() -> new LlmGatewayConfig.ModelEndpoint("m", "ftp://llm.test", "k"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("baseUrl 非法") // 协议非法由内层抛出后统一包装，原因在 cause
        .hasCauseInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new LlmGatewayConfig.ModelEndpoint("m", "not a url", "k"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("baseUrl 非法");
  }

  @Test
  void ofProductionUsesDocumentedDefaults() {
    LlmGatewayConfig config = LlmGatewayConfig.ofProduction("http://llm.test/v1", "m", "k");
    assertThat(config.maxConcurrency()).isEqualTo(LlmGatewayConfig.DEFAULT_MAX_CONCURRENCY);
    assertThat(config.maxRetries()).isEqualTo(LlmGatewayConfig.DEFAULT_MAX_RETRIES);
    assertThat(config.rpmOf(ModelTier.STANDARD)).isEqualTo(60);
    assertThat(config.rpmOf(ModelTier.STRONG)).isEqualTo(30);
    assertThat(config.rpmOf(ModelTier.LIGHT)).isEqualTo(60);
    assertThat(config.fallbackChain()).containsExactly(ModelTier.STANDARD);
    assertThat(config.models()).containsKeys(ModelTier.values());
    assertThat(config.models().get(ModelTier.STRONG).model()).isEqualTo("m");
  }
}
