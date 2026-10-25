package org.cra.common.finding;

/**
 * 候选 finding 类别（05§1.1）。context 为 v1.3.1 补登（CrossChecker 输出类别，缺它其全部输出会被 schema 校验拒收）；context 类默认
 * important 档（业务/产品矛盾不产 nit）。
 */
public enum FindingCategory {
  /** 逻辑/数据流/边界类缺陷（BugHunter 输出） */
  BUG,
  /** 安全缺陷（SecurityReviewer，独立 pass） */
  SECURITY,
  /** 测试缺口类（TestReviewer） */
  TEST,
  /** 约定违反（ConventionReviewer，多为 nit） */
  CONVENTION,
  /** 性能风险类（PerfReviewer） */
  PERFORMANCE,
  /** 上下文矛盾类（CrossChecker，M4f；默认 important 档） */
  CONTEXT
}
