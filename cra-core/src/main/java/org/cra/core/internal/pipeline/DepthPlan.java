package org.cra.core.internal.pipeline;

import java.util.List;
import org.cra.common.run.Depth;

/**
 * 生效深度计划（DepthPolicy.resolve 产物；02§4.3 档位表 + 04§2.2 大 PR 降档）。
 *
 * @param effectiveDepth 生效档位
 * @param windowLines L1 窗口 ±N
 * @param conditionalFullText 条件全文开关
 * @param fullFileMaxLines 全文注入行数上限
 * @param priorityOnly true 时仅评审 priority_paths（hard-cap 触发）
 * @param notes 降档/自动选档说明（进 trace 与 Summary 覆盖声明）
 */
public record DepthPlan(
    Depth effectiveDepth,
    int windowLines,
    boolean conditionalFullText,
    int fullFileMaxLines,
    boolean priorityOnly,
    List<String> notes) {

  public DepthPlan {
    notes = List.copyOf(notes == null ? List.of() : notes);
  }
}
