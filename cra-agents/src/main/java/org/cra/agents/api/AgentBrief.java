package org.cra.agents.api;

import java.util.Objects;

/**
 * 自包含简报（03§1/03§2.1：一切输入来自 brief，不含编排器中间推理与其他子代理输出； brief 全文落盘可复现，由编排层持久化）。
 *
 * @param role 角色
 * @param systemPrompt 系统提示词（模板渲染结果，含 UNTRUSTED 指令层级声明）
 * @param userPrompt 用户内容（diff/上下文均已定界）
 * @param templateVersion 模板版本（内容哈希，05§1.2 prompt_template_versions 关联 run）
 * @param tools 工具白名单
 * @param budget 预算硬限
 * @param modelTier 模型档位（03§6；编排器映射到 cra-llm 的 ModelTier 路由，T2.9）
 */
public record AgentBrief(
    AgentRole role,
    String systemPrompt,
    String userPrompt,
    String templateVersion,
    ToolWhitelist tools,
    AgentBudget budget,
    AgentTier modelTier) {

  public AgentBrief {
    Objects.requireNonNull(role, "role");
    if (systemPrompt == null || systemPrompt.isBlank()) {
      throw new IllegalArgumentException("systemPrompt must be non-blank");
    }
    if (userPrompt == null || userPrompt.isBlank()) {
      throw new IllegalArgumentException("userPrompt must be non-blank");
    }
    Objects.requireNonNull(templateVersion, "templateVersion");
    Objects.requireNonNull(tools, "tools");
    Objects.requireNonNull(budget, "budget");
    modelTier = modelTier == null ? AgentTier.STANDARD : modelTier;
  }

  /**
   * M0/M1 形态兼容构造（standard 档）。
   *
   * @param role 角色
   * @param systemPrompt 系统提示词
   * @param userPrompt 用户内容
   * @param templateVersion 模板版本
   * @param tools 工具白名单
   * @param budget 预算硬限
   */
  public AgentBrief(
      AgentRole role,
      String systemPrompt,
      String userPrompt,
      String templateVersion,
      ToolWhitelist tools,
      AgentBudget budget) {
    this(role, systemPrompt, userPrompt, templateVersion, tools, budget, AgentTier.STANDARD);
  }
}
