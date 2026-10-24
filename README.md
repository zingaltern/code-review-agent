# code-review-agent

**基于多子代理隔离评审与盲评验证的代码评审智能体。**

本项目是 [openai-code-review](https://github.com/zingaltern/openai-code-review)（2025）的继任者：继承其"挂在 Git 平台 CI 上的评审组件"定位，按 [Anthropic《Solving Code Review at Anthropic》](https://claude.dev/blog/solving-code-review-at-anthropic/)（2026）的最佳实践重新设计架构，核心差异化在**子代理隔离审查 + 对抗式盲评验证投票**。

> ⚠️ 仓库纪律：本仓库为全新独立仓库，**严禁向原 openai-code-review 仓库推送任何内容**。

## 核心理念

1. **精确率优先**——宁可漏报不误报；每条 finding 必须经过 ≥2 名盲评验证者对抗式复核并投票存活，才被发布到 PR；
2. **隔离产生多样性，多样性产生精确率**——评审子代理在上下文、视角、执行三重隔离下并行；验证者盲评（看不到发现者的推理），分歧由仲裁 judge 终裁；
3. **不可度量则不可改进**——全链路 trace 落盘、comment 人工处置追踪、离线评测集回归门禁，从第一天内建。

## 流水线一图流

```
PR 事件 ─→ 幂等检查 ─→ 上下文组装(L0 diff/L1 全文/L2 切片/L3 REVIEW.md)
        ─→ 评审计划 ─→ 并行扇出：BugHunter×N(多视角) + Security/Test/Convention/Perf
        ─→ 候选 finding 归一化 ─→ 盲评验证 ×2(证伪导向) ─→ 投票 ─→(分歧)→ Judge 仲裁
        ─→ 聚合(去重/分级/nit预算/pre-existing) ─→ GitHub inline 回帖 + Summary ─→ trace 落盘
```

## 文档导航（设计已完成，进入开发阶段从这里读起）

| 文档 | 内容 | 读者 |
|---|---|---|
| [01 背景与调研](docs/01-background-and-research.md) | 原项目分析、Anthropic 实践提炼、业界/学术调研、需求定义与技术选型 | 所有人必读 |
| [02 总体架构](docs/02-architecture-design.md) | 分层架构、端到端数据流、模块划分、幂等/降级/预算、部署演进 | 开发必读 |
| [03 多代理核心设计](docs/03-multi-agent-design.md) | 三重隔离、角色化 reviewer、盲评 verifier、投票与仲裁、收敛控制 | 开发必读（核心） |
| [04 上下文与提示词](docs/04-context-and-prompt-design.md) | 分层上下文、REVIEW.md 规范、prompt 模板体系与反模式 | 开发必读 |
| [05 数据模型与集成](docs/05-data-and-integration.md) | Finding/Run/Trace schema、GitPlatformAdapter SPI、CLI 契约、Actions 集成 | 开发必读 |
| [06 评测与反馈闭环](docs/06-eval-and-feedback.md) | 三层度量、评测集与回归门禁、badcase 飞轮 | 开发必读 |
| [07 路线图](docs/07-roadmap.md) | M0-M4 里程碑、任务拆解、验收标准、工程纪律 | 开发执行入口 |

## 技术栈

Java 17 · Spring Boot 3.x（仅 DI/配置）· Maven 多模块 · OpenAI 兼容协议 LLM 网关（模型无关：GLM/DeepSeek/Qwen/Claude/GPT 均可路由）· SQLite（→PG）· GitHub REST API

## 快速开始（M0 完成后可用，当前为设计阶段）

```bash
# CI 模式（GitHub Actions 内，见 docs/05 §4）
cra review --repo "$GITHUB_REPOSITORY" --pr 42 --base "$BASE_SHA" --head "$HEAD_SHA" --depth standard

# 本地干跑（不回帖）
cra review --local --base main --depth quick --dry-run=true
```

## 状态

- [x] 调研与需求定义（2026-10）
- [x] 技术方案设计（本文档集 v1.0）
- [ ] M0 骨架与最小闭环
- [ ] M1 结构化与工程化
- [ ] M2 多子代理隔离评审 + 盲评验证（核心）
- [ ] M3 评测与反馈闭环
- [ ] M4 服务化与扩展
