package org.cra.agents.api;

/**
 * 子代理模型档位（03§6 速查表：standard/strong/light）。M2 记录进 run plan 与 trace； 实际路由（per-model RPM/降级链）随 T2.9
 * 落地（05§5 cra.llm.models）。
 */
public enum AgentTier {
  /** 标准档（BugHunter/Test/Perf 默认） */
  STANDARD,
  /** 强档（Security/Verifier/Judge） */
  STRONG,
  /** 轻档（Convention 等受限角色；run 预算 ≥70% 时先砍，02§4.4） */
  LIGHT
}
