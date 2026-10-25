package org.cra.eval.internal;

import java.util.ArrayList;
import java.util.List;

/**
 * 单样本单次 run 的观测（黑盒输出面解析结果）。
 *
 * @param caseId 用例
 * @param set 集合
 * @param subtype 对抗子类可空
 * @param runIndex 第几次运行（0 基）
 * @param completed run 是否完整收尾（捕获到 Summary DONE/DEGRADED 完成标记，05§1.3）
 * @param runStatus DONE | DEGRADED | failed（无完成标记）
 * @param findings 全部已发布观测（inline + Summary 降级）
 * @param failureReason 失败原因摘要（完成 run 为空串）
 * @param runStats per-run token/耗时（trace run_end；缺失为 EMPTY）
 */
public record CaseObservation(
    String caseId,
    String set,
    String subtype,
    int runIndex,
    boolean completed,
    String runStatus,
    List<ObservedFinding> findings,
    String failureReason,
    RunStats runStats) {

  public CaseObservation {
    findings = List.copyOf(findings == null ? List.of() : findings);
    runStatus = runStatus == null ? "failed" : runStatus;
    failureReason = failureReason == null ? "" : failureReason;
    runStats = runStats == null ? RunStats.EMPTY : runStats;
  }

  /** 兼容无统计字段的构造（单测/旧调用）。 */
  public CaseObservation(
      String caseId,
      String set,
      String subtype,
      int runIndex,
      boolean completed,
      String runStatus,
      List<ObservedFinding> findings,
      String failureReason) {
    this(
        caseId,
        set,
        subtype,
        runIndex,
        completed,
        runStatus,
        findings,
        failureReason,
        RunStats.EMPTY);
  }

  /** 便捷：过滤 inline 观测。 */
  public List<ObservedFinding> inlineFindings() {
    List<ObservedFinding> out = new ArrayList<>();
    for (ObservedFinding f : findings) {
      if (ObservedFinding.INLINE.equals(f.source())) {
        out.add(f);
      }
    }
    return out;
  }

  /**
   * 单次 run 的统计（05§1.2 run stats 子集）。
   *
   * @param tokensIn 输入 token
   * @param tokensOut 输出 token
   * @param latencyMs 耗时（毫秒）
   * @param available 是否取到 run_end（false 时三值为 0 且不进均值分母）
   */
  public record RunStats(int tokensIn, int tokensOut, long latencyMs, boolean available) {

    /** 缺失占位。 */
    public static final RunStats EMPTY = new RunStats(0, 0, 0, false);
  }
}
