package org.cra.llm.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.cra.llm.internal.DefaultLlmGateway;
import org.junit.jupiter.api.Test;

/** 装配工厂（10§4）：组合根唯一装配出口。 */
class LlmAssemblyTest {

  @Test
  void createGatewayWiresDefaultGatewayWithJdkTransport() {
    LlmGateway gateway =
        LlmAssembly.createGateway(
            LlmGatewayConfig.ofProduction("http://llm.test/v1", "std-model", "key"));
    assertThat(gateway).isInstanceOf(DefaultLlmGateway.class);
    assertThat(gateway.config()).isPresent();
  }
}
