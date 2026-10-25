package org.cra.common.run;

import java.util.List;

/**
 * run 级统计（05§1.2 stats 的 M0 子集；Summary 指标行与 trace 汇总的数据源，02§6）。
 *
 * @param findingsCandidate 候选 finding 数
 * @param findingsPublished 实际发布数（inline + Summary 降级）
 * @param skippedDuplicate 指纹去重抑制数（账本已发布）
 * @param anchorFallback 锚定降级进 Summary 的条数
 * @param tokensIn 输入 token 总量（usage 汇总，04§2.1）
 * @param tokensOut 输出 token 总量
 * @param latencyMs run 总耗时（口径：进入 main → 最后一次写调用返回，08§6）
 * @param degradations 降级事件列表（如 verifier-retry-once；M0 事件为空或 schema-retry）
 */
public record ReviewStats(
    int findingsCandidate,
    int findingsPublished,
    int skippedDuplicate,
    int anchorFallback,
    long tokensIn,
    long tokensOut,
    long latencyMs,
    List<String> degradations) {

  public ReviewStats {
    degradations = List.copyOf(degradations == null ? List.of() : degradations);
  }
}
