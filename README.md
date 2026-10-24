# code-review-agent

**基于多子代理隔离评审与独立复核验证的代码评审智能体。**

本项目是 [openai-code-review](https://github.com/zingaltern/openai-code-review)（2025）的继任者：继承其"挂在 Git 平台 CI 上的评审组件"定位，按 [Anthropic《Solving Code Review at Anthropic》](https://claude.dev/blog/solving-code-review-at-anthropic/)（2026）的最佳实践重新设计架构。差异化核心：**子代理隔离审查 + 独立复核评判**（验证层具体构造为自主设计，以评测数据为唯一裁判，见 docs/03 §7 诚实溯源）。

> ⚠️ 仓库纪律：本仓库为全新独立仓库，**严禁向原 openai-code-review 仓库推送任何内容**；远端操作规范见 [docs/00](docs/00-branching-and-workflow.md) §8。
> 📌 当前版本：**技术方案 v1.1**（2026-10-25，已按方案评审 8 项缺陷修订；修订对照见 [docs/01 §8](docs/01-background-and-research.md)）。

## 核心理念

1. **精确率优先，但有召回护栏**——每条 finding 必须经独立复核（M3 起为异构双验证者投票）存活才发布；同时评测集设绝对召回下限与零发布护栏，防止系统被优化成沉默；
2. **隔离产生多样性，多样性产生精确率**——评审子代理在上下文、视角、执行三重隔离下并行；验证者多样性是投票成立的前提（模型家族 + 指令模板必须异构），不满足则退化为单验证者 + 仲裁；
3. **不可度量则不可改进**——GitHub 评论账本、trace artifact、k=3 统计化回归门禁、处置追踪，从第一天内建。

## 流水线一图流

```
PR 事件 ─→ 幂等检查(GitHub评论账本) ─→ 上下文组装(窗口/条件全文/切片/base版REVIEW.md)
        ─→ 评审计划 ─→ 并行扇出：BugHunter×N(多视角隔离) + Security/Test/Convention/Perf
        ─→ 候选 finding 按文件分组 ─→ 独立复核(强模型,反锚定约束) ─→ (分歧/不可得/抽查)→ Judge 终裁
        ─→ 聚合(去重/分级/nit预算/pre-existing@jgit blame) ─→ 锚定校验→inline回帖/Summary降级
        ─→ trace 上传 artifact ─→ 指纹入账本（幂等与收敛的事实源）
```

## 文档导航（v1.1，开发阶段从这里读起）

| 文档 | 内容 | 读者 |
|---|---|---|
| [00 分支管理与工作流](docs/00-branching-and-workflow.md) | trunk-based 分支模型、命名/提交/PR 规范、agent 红线、大厂流程取舍 | **开发执行前必读**（另见根目录 AGENTS.md） |
| [01 背景与调研](docs/01-background-and-research.md) | 原项目分析、Anthropic 实践提炼（含诚实归属）、需求定义与召回护栏、**v1.1 修订对照表** | 所有人必读 |
| [02 总体架构](docs/02-architecture-design.md) | 分层架构、数据流、**部署形态与状态归属（GitHub 评论账本）**、幂等/降级/并发预算 | 开发必读 |
| [03 多代理核心设计](docs/03-multi-agent-design.md) | 三重隔离、角色 reviewer、**独立复核 verifier + 仲裁 judge**（M2/M3 分期）、收敛控制、诚实溯源表 | 开发必读（核心） |
| [04 上下文与提示词](docs/04-context-and-prompt-design.md) | 条件注入分层、**预算算术示例**、REVIEW.md base 读取与**注入防御**、prompt 模板 | 开发必读 |
| [05 数据模型与集成](docs/05-data-and-integration.md) | Finding/账本指纹 schema、SPI（jgit blame/锚定降级）、CLI、**仅 PR 触发 workflow**、安全检查单 | 开发必读 |
| [06 评测与反馈闭环](docs/06-eval-and-feedback.md) | **k=3 统计门禁、召回下限与零发布护栏**、Defects4J 冷启动、badcase 飞轮 | 开发必读 |
| [07 路线图](docs/07-roadmap.md) | M0–M4（**pre-PR 进 M2、投票降 M3 数据门禁**）、任务拆解、验收标准、工程纪律 | 开发执行入口 |

## 技术栈

Java 17 · Spring Boot 3.x（仅 DI/配置）· Maven 多模块 · OpenAI 兼容 LLM 网关（模型无关 + 并发限流 + 异构路由）· jgit（blame/本地模式）· GitHub REST API + 评论账本 · SQLite（本地模式）→ PostgreSQL（服务化）

## 快速开始（M0 完成后可用，当前为设计阶段）

```bash
# CI 模式（GitHub Actions 内，见 docs/05 §4）
cra review --repo "$GITHUB_REPOSITORY" --pr "$PR_NUMBER" --base "$BASE_SHA" --head "$HEAD_SHA" --depth standard

# 本地 pre-PR 自评审（M2 起；默认 dry-run，见 docs/05 §3）
cra review --local --base main --depth standard --dry-run=true
```

## 状态

- [x] 调研与需求定义（2026-10）
- [x] 技术方案 v1.0 → **v1.1**（按 8 项评审缺陷修订：状态账本、验证层重设计、预算重算、召回护栏、jgit blame、注入防御、里程碑重排）
- [ ] M0 骨架与最小闭环
- [ ] M1 工程化与账本（含最小评测集）
- [ ] M2 隔离审查 + 独立复核 + pre-PR（核心）
- [ ] M3 评测闭环 + 双验证者投票（数据门禁）
- [ ] M4 服务化与扩展
