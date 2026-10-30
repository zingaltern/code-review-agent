package org.cra.core.api;

import java.util.Objects;

/**
 * M1 编排参数（05§5 review.* 与 agents.budget 的运行时形态；由组合根从 CLI/环境装配）。
 *
 * @param runTotalTokens run 级总预算 token（agents.budget.run-total-tokens，默认 500k，02§4.4）
 * @param degradeAtFirst 第一降档阈值（agents.budget.degrade-at[0]，默认 0.70：砍 light 角色与 1 视角——M1 单角色映射为剩余
 *     chunk 简化上下文）
 * @param degradeAtSecond 第二降档阈值（agents.budget.degrade-at[1]，默认 0.85：候选按 severity 排序只取 top-10）
 * @param largeDiffLines 大 diff 降档线（review.large-diff-lines，默认 2000：>线降为 Quick 质量，02§4.4）
 * @param hardCapLines 硬上限（review.hard-cap-lines，默认 8000：>线仅评审 priority_paths）
 * @param nitBudgetDefault nit 预算默认值（review.nit-budget-default，默认 3；REVIEW.md 显式配置优先）
 * @param publishUnverified UNVERIFIED 发布策略（review.publish-unverified：never|labeled；M2 起 labeled 为带
 *     UNVERIFIED 标签发布的生效路径，02§4.2）
 * @param importantRefutedSpotCheckPercent important/security 级 REFUTED 抽查保险丝百分比（03§4.3，
 *     agents.verifier.important-refuted-spotcheck 的百分数形态；sha256(run_id|finding_id) mod 100 < pct）
 * @param includeTestFiles 是否把测试文件纳入评审范围（02§4.4；默认 false——测试正确性由构建与测试自身保障，评审只为 生产代码与文档投入
 *     token；REVIEW.md 的 Skip paths 仍叠加生效）
 */
public record PipelineOptions(
    long runTotalTokens,
    double degradeAtFirst,
    double degradeAtSecond,
    int largeDiffLines,
    int hardCapLines,
    int nitBudgetDefault,
    String publishUnverified,
    int importantRefutedSpotCheckPercent,
    boolean includeTestFiles) {

  /** run 级预算默认（02§4.4 v1.2.1：500k）。 */
  public static final long DEFAULT_RUN_TOTAL_TOKENS = 500_000;

  /** important/security 级 REFUTED 抽查保险丝默认百分比（03§4.3 important-refuted-spotcheck=0.20）。 */
  public static final int DEFAULT_SPOTCHECK_PERCENT = 20;

  /** 测试文件是否进入评审范围的默认值（02§4.4：默认不评审测试代码）。 */
  public static final boolean DEFAULT_INCLUDE_TEST_FILES = false;

  /** 规范构造：校验阈值与枚举取值（05§5 默认值；非法配置在组装期即失败，05§3 退出码 2）。 */
  public PipelineOptions {
    Objects.requireNonNull(publishUnverified, "publishUnverified");
    if (runTotalTokens <= 0) {
      throw new IllegalArgumentException("runTotalTokens must be positive: " + runTotalTokens);
    }
    if (degradeAtFirst <= 0 || degradeAtSecond <= degradeAtFirst || degradeAtSecond > 1.0) {
      throw new IllegalArgumentException(
          "degrade thresholds must satisfy 0 < first < second <= 1: %s/%s"
              .formatted(degradeAtFirst, degradeAtSecond));
    }
    if (largeDiffLines <= 0 || hardCapLines <= largeDiffLines) {
      throw new IllegalArgumentException(
          "largeDiffLines/hardCapLines must be positive and hardCap > large: %d/%d"
              .formatted(largeDiffLines, hardCapLines));
    }
    if (nitBudgetDefault < 0) {
      throw new IllegalArgumentException(
          "nitBudgetDefault must be non-negative: " + nitBudgetDefault);
    }
    if (!"never".equals(publishUnverified) && !"labeled".equals(publishUnverified)) {
      throw new IllegalArgumentException(
          "publishUnverified must be never|labeled: " + publishUnverified);
    }
    if (importantRefutedSpotCheckPercent < 0 || importantRefutedSpotCheckPercent > 100) {
      throw new IllegalArgumentException(
          "importantRefutedSpotCheckPercent must be in [0,100]: "
              + importantRefutedSpotCheckPercent);
    }
  }

  /**
   * 05§5 默认参数。
   *
   * @return 默认 PipelineOptions
   */
  public static PipelineOptions defaults() {
    return new PipelineOptions(
        DEFAULT_RUN_TOTAL_TOKENS,
        0.70,
        0.85,
        2000,
        8000,
        3,
        "never",
        DEFAULT_SPOTCHECK_PERCENT,
        DEFAULT_INCLUDE_TEST_FILES);
  }
}
