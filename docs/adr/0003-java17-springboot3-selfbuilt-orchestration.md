# ADR-0003 Java 17 + Spring Boot 3.x（仅 DI/配置）+ 自研轻量编排

- 状态：accepted（补录历史决策，素材自 01§8 修订记录，2026-10-25）
- 背景：技术选型决策矩阵见 01§5.1；原项目为 Java 8 / SB 2.7。
- 决策：Java 17 + Spring Boot 3.x（仅组合根的 DI 与配置，R6 守护）+ Maven 多模块（cra-*）+ 自研轻量编排。
- 理由：与原项目及团队技能连续；agent 核心循环本就轻量，自研可控可测。
- 替代方案：Python + LangGraph/CrewAI（生态丰富但与原项目断裂、pipeline 易失控）、TS + Claude Agent SDK（分发重）。
- 后果：LLM 生态靠 OpenAI 兼容协议补齐（01§4.1 G4，模型无关）；"禁编排框架"成为依赖红线（09§7）。
