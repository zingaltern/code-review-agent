package org.cra.agents.api;

import java.util.List;

/** cra-agents 装配工厂（10§4）。 */
public final class AgentsAssembly {

  private AgentsAssembly() {}

  /**
   * 创建 M2 评审班组（T2.2/T2.3）：BugHunter 四视角（派发序 = dataflow → boundary → concurrency → resource，
   * 视角路由按深度档位由编排器执行，02§4.3）+ 四专角色（Security/Test/Convention/Perf，03§3）+ CrossChecker（M4f-lite，
   * 启用与档位联动由编排器判定——装配齐备 ≠ 触发，05§5 agents.crosschecker.enabled 默认 false）。
   *
   * @return ReviewCrew
   */
  public static ReviewCrew createReviewCrew() {
    org.cra.agents.internal.TemplateLoader loader = new org.cra.agents.internal.TemplateLoader();
    return new ReviewCrew(
        List.of(
            new BugHunterDefinition(BugHunterDefinition.Perspective.DATAFLOW, loader),
            new BugHunterDefinition(BugHunterDefinition.Perspective.BOUNDARY, loader),
            new BugHunterDefinition(BugHunterDefinition.Perspective.CONCURRENCY, loader),
            new BugHunterDefinition(BugHunterDefinition.Perspective.RESOURCE, loader)),
        new SecurityReviewerDefinition(loader),
        new SpecialistReviewers.TestReviewer(loader),
        new SpecialistReviewers.ConventionReviewer(loader),
        new SpecialistReviewers.PerfReviewer(loader),
        new CrossCheckerDefinition(loader));
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
