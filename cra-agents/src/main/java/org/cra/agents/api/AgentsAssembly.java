package org.cra.agents.api;

/** cra-agents 装配工厂（10§4）。 */
public final class AgentsAssembly {

  private AgentsAssembly() {}

  /**
   * 创建 M0 通用评审子代理定义。
   *
   * @return GeneralReviewerDefinition
   */
  public static GeneralReviewerDefinition createGeneralReviewer() {
    return new GeneralReviewerDefinition(new org.cra.agents.internal.TemplateLoader());
  }
}
