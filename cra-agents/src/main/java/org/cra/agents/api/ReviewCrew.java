package org.cra.agents.api;

import java.util.List;

/**
 * M2 评审班组（T2.2/T2.3，03§3 角色表）：BugHunter 视角实例 + 四专角色。 视角数量由编排器按深度档位路由（02§4.3：quick 1 / standard 2 /
 * deep 3–4）； 专角色触发条件由编排器判定（Security 每 chunk 必跑；Test/Convention 条件触发；Perf Deep 档）。 全部为纯定义（执行在
 * cra-core 隔离运行时）；null = 该角色未装配（编排器跳过）。
 *
 * @param bugHunters BugHunter 视角定义（派发序 = 优先级序，编排器按档位取前 N 个）
 * @param securityReviewer SecurityReviewer（独立 pass，按 chunk 扇出，03§6 v1.2）
 * @param testReviewer TestReviewer（条件触发：逻辑变更且测试变更比例低）
 * @param conventionReviewer ConventionReviewer（条件触发：REVIEW.md 定义约定时；轻档）
 * @param perfReviewer PerfReviewer（条件触发：Deep 档；按 chunk）
 * @param crossChecker CrossChecker（M4f-lite：配置开启且 standard/deep 档时全 PR 单实例；null = 未装配/关闭）
 */
public record ReviewCrew(
    List<SubAgent<ReviewAgentInput>> bugHunters,
    SubAgent<ReviewAgentInput> securityReviewer,
    SubAgent<ReviewAgentInput> testReviewer,
    SubAgent<ReviewAgentInput> conventionReviewer,
    SubAgent<ReviewAgentInput> perfReviewer,
    SubAgent<ReviewAgentInput> crossChecker) {

  public ReviewCrew {
    bugHunters = List.copyOf(bugHunters == null ? List.of() : bugHunters);
  }

  /**
   * M2 形态兼容构造（CrossChecker 未装配；M3 各线现有构造点不改即可编译）。
   *
   * @param bugHunters BugHunter 视角定义
   * @param securityReviewer SecurityReviewer
   * @param testReviewer TestReviewer
   * @param conventionReviewer ConventionReviewer
   * @param perfReviewer PerfReviewer
   */
  public ReviewCrew(
      List<SubAgent<ReviewAgentInput>> bugHunters,
      SubAgent<ReviewAgentInput> securityReviewer,
      SubAgent<ReviewAgentInput> testReviewer,
      SubAgent<ReviewAgentInput> conventionReviewer,
      SubAgent<ReviewAgentInput> perfReviewer) {
    this(bugHunters, securityReviewer, testReviewer, conventionReviewer, perfReviewer, null);
  }

  /**
   * 仅 BugHunter 的班组（T2.2 形态，专角色未装配）。
   *
   * @param bugHunters 视角定义
   * @return 班组
   */
  public static ReviewCrew ofBugHunters(List<SubAgent<ReviewAgentInput>> bugHunters) {
    return new ReviewCrew(bugHunters, null, null, null, null, null);
  }
}
