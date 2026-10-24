# code-review-agent

**基于多子代理隔离评审与独立复核验证的代码评审智能体。**

本项目是 [openai-code-review](https://github.com/zingaltern/openai-code-review)（2025）的继任者：继承其"挂在 Git 平台 CI 上的评审组件"定位，按 [Anthropic《Solving Code Review at Anthropic》](https://claude.dev/blog/solving-code-review-at-anthropic/)（2026）的最佳实践重新设计架构。差异化核心：**子代理隔离审查 + 独立复核评判**（验证层具体构造为自主设计，以评测数据为唯一裁判，见 docs/03 §7 诚实溯源）。

> ⚠️ 仓库纪律：本仓库为全新独立仓库，**严禁向原 openai-code-review 仓库推送任何内容**；远端操作规范见 [docs/00](docs/00-branching-and-workflow.md) §8。
> 📌 当前版本：**技术方案 v1.2**（2026-10-25；v1.1 闭环首轮 8 项、v1.2 闭环二轮 9 项评审缺陷，对照表见 [docs/01 §8](docs/01-background-and-research.md)）。

## 核心理念

1. **精确率优先，但有召回护栏**——每条 finding 必须经独立复核存活才发布；评测集设聚合召回下限（Wilson 置信区间判定）、零发布护栏与**梯度沉默护栏**（截断丢弃可见），防止系统被优化成沉默；
2. **隔离产生多样性，多样性产生精确率**——评审子代理在上下文、视角、执行三重隔离下并行；验证者多样性是投票成立的前提，不满足则退化为单验证者 + 仲裁；
3. **不可度量则不可改进，且门禁必须可持续**——GitHub 评论账本（指纹 v2，代码内容锚）、k 次运行聚合 CI 判定、三级分级评测门禁（快检进 CI / 标准本地跑 / 全量里程碑），从第一天内建。

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
| [01 背景与调研](docs/01-background-and-research.md) | 原项目分析、Anthropic 实践提炼（含诚实归属）、需求定义与召回护栏、**两轮修订对照表** | 所有人必读 |
| [02 总体架构](docs/02-architecture-design.md) | 分层架构、数据流、部署形态与状态归属（**指纹 v2 账本**）、幂等/降级/并发预算、触发边界 | 开发必读 |
| [03 多代理核心设计](docs/03-multi-agent-design.md) | 三重隔离、角色 reviewer、独立复核 verifier + 仲裁 judge（M2/M3 分期）、收敛控制、诚实溯源表 | 开发必读（核心） |
| [04 上下文与提示词](docs/04-context-and-prompt-design.md) | 条件注入分层、**全角色预算算术 + 评测门禁成本分级**、REVIEW.md base 读取与注入防御 | 开发必读 |
| [05 数据模型与集成](docs/05-data-and-integration.md) | Finding/账本指纹与**解析器防污染规则**、SPI（jgit blame/锚定降级）、CLI、workflow（draft 过滤）、安全检查单 | 开发必读 |
| [06 评测与反馈闭环](docs/06-eval-and-feedback.md) | **聚合 Wilson CI 门禁 + 三级分级**、Defects4J 双侧数据集、整仓 fixture 规格、转化漏斗护栏 | 开发必读 |
| [07 路线图](docs/07-roadmap.md) | M0–M4（pre-PR 实跑验证进 M2、投票降 M3）、任务拆解、验收标准、工程纪律 | 开发执行入口 |

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
- [x] 技术方案 v1.0 → v1.1 → **v1.2**（两轮对抗式评审 17 项缺陷闭环；v1.2 重点：指纹 v2 防污染账本、聚合 CI 门禁与评测成本分级、Defects4J 双侧数据集、梯度沉默护栏、Security 算术修正、pre-PR 实跑验证）
- [ ] M0 骨架与最小闭环
- [ ] M1 工程化与账本（含最小评测集与 fixture 规格）
- [ ] M2 隔离审查 + 独立复核 + pre-PR 实跑（核心）
- [ ] M3 评测闭环 + 双验证者投票（最小样本量 + 数据门禁）
- [ ] M4 服务化与扩展

### 已知平台限制（02§5）

冲突 PR 的 workflow 不触发（GitHub 官方行为，冲突解决后恢复）；fork PR 无 secrets（多协作者时引入 App token）；draft PR 已过滤不评审。
