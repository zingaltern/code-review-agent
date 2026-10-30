package org.cra.llm.api;

/** cra-llm 装配工厂（10§4：组合根只调工厂、把产物作为构造参数注入 core）。 */
public final class LlmAssembly {

  private LlmAssembly() {}

  /**
   * 创建生产网关（JDK HttpClient 传输）。
   *
   * @param config 网关配置
   * @return LlmGateway 实例
   */
  public static LlmGateway createGateway(LlmGatewayConfig config) {
    return new org.cra.llm.internal.DefaultLlmGateway(
        config, new org.cra.llm.internal.JdkHttpTransport(), Thread::sleep);
  }
}
