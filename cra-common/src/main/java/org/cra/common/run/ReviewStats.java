package org.cra.common.run;

import java.util.List;

/**
 * run 级统计（05§1.2 stats；Summary 指标行与 trace 汇总的数据源，02§6）。
 *
 * @param findingsCandidate 候选 finding 数（run 内精确去重 merge 前）
 * @param findingsPublished 实际发布数（inline + Summary 降级；含 labeled UNVERIFIED）
 * @param skippedDuplicate 指纹去重抑制数（账本已发布）
 * @param confirmed 复核判定 CONFIRMED（含 Judge 翻案）数（05§1.2 stats.confirmed）
 * @param rejected 复核推翻（REFUTED 或 Judge REJECTED）数（05§1.2 stats.rejected，拒真统计口径）
 * @param unverified 验证不可得且未发布数（05§1.2 stats.unverified；labeled 发布的不计——已发布）
 * @param anchorFallback 锚定降级进 Summary 的条数
 * @param preExisting 经 jgit blame 复核确证为 pre-existing 的条数（05§2；无本地仓库时不计并声明）
 * @param suppressedByTruncation 预算降档阶梯截断丢弃的候选数（02§4.4 梯度沉默护栏；进 Summary 与 L3 健康度）
 * @param tokensIn 输入 token 总量（usage 汇总，04§2.1；含验证/仲裁调用）
 * @param tokensOut 输出 token 总量
 * @param latencyMs run 总耗时（口径：进入 main → 最后一次写调用返回，08§6）
 * @param degradations 降级事件列表（如 verifier-retry-once、budget-degrade-70）
 * @param securityPublished 已发布 security 类 finding 数（--security-block 退出码 3 判据，05§3）
 * @param coverage 覆盖声明（05§1.2 coverage）
 */
public record ReviewStats(
    int findingsCandidate,
    int findingsPublished,
    int skippedDuplicate,
    int confirmed,
    int rejected,
    int unverified,
    int anchorFallback,
    int preExisting,
    int suppressedByTruncation,
    long tokensIn,
    long tokensOut,
    long latencyMs,
    List<String> degradations,
    int securityPublished,
    Coverage coverage) {

  public ReviewStats {
    degradations = List.copyOf(degradations == null ? List.of() : degradations);
    coverage = coverage == null ? new Coverage(0, 0, "") : coverage;
  }
}
