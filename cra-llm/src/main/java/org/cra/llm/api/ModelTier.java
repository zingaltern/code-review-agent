package org.cra.llm.api;

/**
 * 模型档位（05§5 cra.llm.models 三档；T2.9）。
 *
 * <p>档位是编排侧的路由意图（角色 → 档位），网关负责把意图解析为具体模型端点并执行降级链 （02§4.5）：请求档位 → fallback-chain 依序兜底。M2 消费约定：
 * BugHunter/Security 等评审角色走 standard，verifier/judge 走 strong（03§4/03§6）， light 档供降档与降级链兜底（02§4.4
 * ≥70% 砍 light 角色）。
 */
public enum ModelTier {
  /** 标准档：评审主力模型（05§5 models.standard）。 */
  STANDARD,
  /** 强档：独立复核/仲裁（05§5 models.strong，M2 verifier 单强模型，T2.4）。 */
  STRONG,
  /** 轻档：低风险小任务与降级兜底（05§5 models.light）。 */
  LIGHT
}
