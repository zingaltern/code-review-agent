package org.cra.core.internal.pipeline;

import java.util.ArrayList;
import java.util.List;
import org.cra.agents.api.BugHunterDefinition;
import org.cra.agents.api.ReviewCrew;
import org.cra.common.context.ReviewPolicy;
import org.cra.common.git.DiffFile;
import org.cra.common.git.DiffResult;
import org.cra.common.run.Depth;
import org.cra.core.api.CrossCheckerOptions;

/**
 * 评审路由与专角色触发判定（02§4.3/02§4.4/03§3/03§6）：只回答“派发谁”，不执行派发。
 *
 * <p>从 {@link ReviewStage} 拆出，使档位/触发规则的变更与扇出执行解耦；返回序即派发优先级序。
 */
final class ReviewRoutingPolicy {

  private ReviewRoutingPolicy() {}

  /**
   * 视角路由（02§4.3 档位表：quick 1 / standard 2 / deep 3–4；返回序 = 派发优先级序）。
   *
   * @param depth 生效档位
   * @return 该档位的视角列表
   */
  static List<BugHunterDefinition.Perspective> routePerspectives(Depth depth) {
    return switch (depth) {
      case QUICK -> List.of(BugHunterDefinition.Perspective.DATAFLOW);
      case STANDARD ->
          List.of(
              BugHunterDefinition.Perspective.DATAFLOW, BugHunterDefinition.Perspective.BOUNDARY);
      case DEEP ->
          List.of(
              BugHunterDefinition.Perspective.DATAFLOW,
              BugHunterDefinition.Perspective.BOUNDARY,
              BugHunterDefinition.Perspective.CONCURRENCY,
              BugHunterDefinition.Perspective.RESOURCE);
    };
  }

  /** 70% 档砍 1 视角（下限 1，02§4.4）。 */
  static List<BugHunterDefinition.Perspective> degradePerspectives(
      List<BugHunterDefinition.Perspective> base) {
    if (base.size() <= 1) {
      return List.copyOf(base);
    }
    return base.subList(0, base.size() - 1);
  }

  /**
   * TestReviewer 触发判定（03§3：逻辑文件变更且测试变更比例低）。 测试文件 = src/test/ 路径；比例阈值 0.5 为实现默认（文档未钉死，覆盖声明如实说明）。
   *
   * @param diff 变更 diff
   * @return true 触发
   */
  static boolean testReviewerTriggered(DiffResult diff) {
    int logic = 0;
    int test = 0;
    for (DiffFile file : diff.files()) {
      if (isTestPath(file.path())) {
        test++;
      } else {
        logic++;
      }
    }
    return logic > 0 && (test == 0 || (double) test / (test + logic) < 0.5);
  }

  /**
   * ConventionReviewer 触发判定（03§3：REVIEW.md 定义约定时）。
   *
   * @param policy 评审策略
   * @return true 触发
   */
  static boolean conventionTriggered(ReviewPolicy policy) {
    return !policy.conventions().isEmpty();
  }

  /**
   * PerfReviewer 触发判定（03§3：Deep 档；perf 路径判定待 REVIEW.md 扩展，M2 以 Deep 档触发并在覆盖声明说明）。
   *
   * @param depthPlan 深度计划
   * @return true 触发
   */
  static boolean perfTriggered(DepthPlan depthPlan) {
    return depthPlan.effectiveDepth() == Depth.DEEP;
  }

  /**
   * CrossChecker 触发判定（03§3 角色表 + 02§4.3 档位联动 + 05§5
   * 配置）：agents.crosschecker.enabled=true、standard/deep 档（quick 不触发）、班组已装配，且至少一个模式有数据可查。
   *
   * <p>数据门槛（07 T1.4：段落缺失时相关检查优雅跳过）：模式 A 需 Product context / Known incidents 非空，或变更史/事件史 可得；模式 B 需
   * Sniff tests 非空。
   *
   * @param depthPlan 深度计划（生效档位）
   * @param policy base 分支 REVIEW.md 策略
   * @param crew 班组（crossChecker=null 表示未装配）
   * @param options 配置（enabled/modes）
   * @param history 变更史（无本地仓库时 available=false）
   * @return true 派发 1 实例
   */
  static boolean crossCheckTriggered(
      DepthPlan depthPlan,
      ReviewPolicy policy,
      ReviewCrew crew,
      CrossCheckerOptions options,
      CrossCheckHistory history) {
    return crossCheckTriggered(depthPlan, policy, crew, options, history, CrossCheckEvents.none());
  }

  /**
   * CrossChecker 触发判定（M4f-full：产品事件史也是模式 A 的数据面）。
   *
   * @param events 产品事件史（未启用/未命中时 {@link CrossCheckEvents#none()}）
   * @return true 派发 1 实例
   */
  static boolean crossCheckTriggered(
      DepthPlan depthPlan,
      ReviewPolicy policy,
      ReviewCrew crew,
      CrossCheckerOptions options,
      CrossCheckHistory history,
      CrossCheckEvents events) {
    if (options == null || !options.enabled() || crew == null || crew.crossChecker() == null) {
      return false;
    }
    if (depthPlan.effectiveDepth() == Depth.QUICK) {
      return false;
    }
    Availability data = availability(policy, history, events);
    boolean assumption =
        options.modeEnabled(CrossCheckerOptions.Mode.ASSUMPTION) && data.assumptionData();
    boolean sniff = options.modeEnabled(CrossCheckerOptions.Mode.SNIFF) && data.sniffData();
    return assumption || sniff;
  }

  /** 两个模式各自是否有数据可查（触发判定与未触发原因共用，避免口径漂移）。 */
  private record Availability(boolean assumptionData, boolean sniffData) {}

  private static Availability availability(
      ReviewPolicy policy, CrossCheckHistory history, CrossCheckEvents events) {
    boolean assumptionData =
        !policy.productContext().isEmpty()
            || !policy.knownIncidents().isEmpty()
            || history.hasData()
            || (events != null && events.hasData());
    boolean sniffData = !policy.sniffTests().isEmpty();
    return new Availability(assumptionData, sniffData);
  }

  static List<String> activeModes(boolean assumption, boolean sniff) {
    List<String> modes = new ArrayList<>();
    if (assumption) {
      modes.add(CrossCheckerOptions.Mode.ASSUMPTION.id);
    }
    if (sniff) {
      modes.add(CrossCheckerOptions.Mode.SNIFF.id);
    }
    return List.copyOf(modes);
  }

  /** 未触发原因（覆盖声明必须可见，07 T1.4）。 */
  static String crossCheckSkipReason(
      DepthPlan depthPlan, ReviewPolicy policy, ReviewCrew crew, CrossCheckHistory history) {
    return crossCheckSkipReason(depthPlan, policy, crew, history, CrossCheckEvents.none());
  }

  static String crossCheckSkipReason(
      DepthPlan depthPlan,
      ReviewPolicy policy,
      ReviewCrew crew,
      CrossCheckHistory history,
      CrossCheckEvents events) {
    if (crew.crossChecker() == null) {
      return "班组未装配 cross-checker";
    }
    if (depthPlan.effectiveDepth() == Depth.QUICK) {
      return "quick 档不触发（02§4.3 档位联动；M4f-lite/full 仅 standard/deep）";
    }
    Availability data = availability(policy, history, events);
    if (!data.assumptionData() && !data.sniffData()) {
      return "REVIEW.md 未配置 Product context/Known incidents/Sniff tests，且变更史/事件史为空或不可得（07 T1.4 段落缺失优雅跳过）";
    }
    return "配置的 mode 与可用数据不匹配（05§5 agents.crosschecker.mode）";
  }

  static boolean isTestPath(String path) {
    return path.startsWith("src/test/") || path.contains("/src/test/");
  }
}
