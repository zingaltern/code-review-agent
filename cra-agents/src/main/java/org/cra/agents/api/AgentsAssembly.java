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

  /**
   * 创建 VerifierAgent 定义（07 T2.4，03§4.2 平衡式独立复核）。
   *
   * @return VerifierAgentDefinition
   */
  public static VerifierAgentDefinition createVerifier() {
    return new VerifierAgentDefinition(new org.cra.agents.internal.TemplateLoader());
  }

  /**
   * 创建 JudgeAgent 定义（07 T2.6，03§4.4 一次性终裁）。
   *
   * @return JudgeAgentDefinition
   */
  public static JudgeAgentDefinition createJudge() {
    return new JudgeAgentDefinition(new org.cra.agents.internal.TemplateLoader());
  }
}
