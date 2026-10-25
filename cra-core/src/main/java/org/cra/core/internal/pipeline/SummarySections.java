package org.cra.core.internal.pipeline;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Summary 覆盖声明段落（04§4.3 Summary 固定结构的"覆盖声明"槽；验收 4：降档、截断计数、覆盖范围在 Summary 如实声明）。 每行一条事实声明；列表类内容超过 5
 * 个折叠为"等 N 个"。
 */
public final class SummarySections {

  private static final int MAX_LISTED = 5;

  private SummarySections() {}

  /** 覆盖声明输入（一次 run 的声明性事实）。 */
  public record CoverageInput(
      DepthPlan depthPlan,
      int filesChanged,
      int filesReviewed,
      Collection<String> skippedPaths,
      Collection<String> contextNotes,
      Collection<String> trimDecisions,
      Collection<String> plannerNotes,
      Collection<String> policyWarnings,
      Collection<String> blameNotes,
      int nitDropped,
      int suppressedByTruncation) {}

  /**
   * 生成覆盖声明行。
   *
   * @param input 声明输入
   * @return 每行一条的声明列表（无前缀符号）
   */
  public static List<String> lines(CoverageInput input) {
    List<String> out = new ArrayList<>();
    DepthPlan plan = input.depthPlan();
    out.add(
        "评审深度：%s（窗口 ±%d，条件全文%s）"
            .formatted(
                plan.effectiveDepth().name().toLowerCase(),
                plan.windowLines(),
                plan.conditionalFullText() ? "开" : "关"));
    plan.notes().forEach(n -> out.add("档位说明：" + n));
    out.add(
        "覆盖：评审 %d/%d 个变更文件%s"
            .formatted(
                input.filesReviewed(),
                input.filesChanged(),
                input.skippedPaths().isEmpty()
                    ? ""
                    : "；跳过 " + fold(input.skippedPaths()) + "（skip/hard-cap 裁剪）"));
    input.plannerNotes().forEach(n -> out.add("范围：" + n));
    input.contextNotes().forEach(n -> out.add("上下文：" + n));
    input.trimDecisions().forEach(n -> out.add("裁剪：" + n));
    input.policyWarnings().forEach(n -> out.add("策略警告：" + n));
    input.blameNotes().forEach(n -> out.add("pre-existing：" + n));
    out.add("验证：M1 未启用独立验证层（候选经指纹去重与锚定校验后直接聚合发布，Verifier 随 M2 接入）");
    if (input.nitDropped() > 0) {
      out.add("nit 超预算丢弃 " + input.nitDropped() + " 条候选");
    }
    if (input.suppressedByTruncation() > 0) {
      // 02§4.4：截断必须可见——"发 N 条丢 M 条"不允许对护栏 invisible
      out.add("因预算截断丢弃 " + input.suppressedByTruncation() + " 条候选（未发布）");
    }
    return out;
  }

  private static String fold(Collection<String> values) {
    List<String> list = List.copyOf(values);
    String shown = String.join("、", list.subList(0, Math.min(MAX_LISTED, list.size())));
    return list.size() > MAX_LISTED ? shown + " 等 " + list.size() + " 个" : shown;
  }
}
