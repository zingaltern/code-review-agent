package org.cra.agents.api;

/**
 * 子代理三重预算硬限（03§1/03§6：token/轮次/时间；超限即杀记 BUDGET_EXCEEDED，02§4.4—— 预算执行器随 M2 隔离运行时落地，M0 由网关与编排器边界约束）。
 *
 * @param maxInputTokens 单次输入 token 上限
 * @param maxOutputTokens 输出 token 上限
 * @param maxTurns 最大轮次
 * @param timeoutMs 时间硬限（毫秒）
 */
public record AgentBudget(long maxInputTokens, long maxOutputTokens, int maxTurns, long timeoutMs) {

  public AgentBudget {
    if (maxInputTokens <= 0 || maxOutputTokens <= 0 || maxTurns <= 0 || timeoutMs <= 0) {
      throw new IllegalArgumentException("budget values must be positive");
    }
  }
}
