package org.cra.llm.api;

import java.util.Optional;

/**
 * LlmGateway：外部 LLM 服务的唯一接触面（09§3 *Gateway 语义）——路由/限流/重试/降级/核算 全部收敛在此（02§3 L2）。M0
 * 最小实现：单模型、chat+JSON 双模式、超时重试、并发信号量、 token 计数；模型路由与 per-model RPM 于 T2.9 扩展（05§5）。
 */
public interface LlmGateway {

  /**
   * 发起一次调用。任何失败以 UNAVAILABLE 结果对象返回（09§4：LlmGateway 内部完成退避重试， 底层 IOException/HTTP 异常绝不抛穿编排层）。
   *
   * @param request 调用请求
   * @return 结果对象（OK 或 UNAVAILABLE）
   */
  LlmResponse complete(LlmRequest request);

  /**
   * 网关生效配置（编排层据此推导外层有界等待 backstop：requestTimeoutMs × (maxRetries+1)）。
   *
   * <p>默认空 = 测试桩/未知实现，调用方按无配置回退值处理；不得用于改变路由/限流行为。
   *
   * @return 配置（未知实现为空）
   */
  default Optional<LlmGatewayConfig> config() {
    return Optional.empty();
  }
}
