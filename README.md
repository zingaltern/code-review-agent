# code-review-agent

**基于多子代理隔离评审与独立复核验证的代码评审智能体。**

本项目是 [openai-code-review](https://github.com/zingaltern/openai-code-review)（2025）的继任者：继承其"挂在 Git 平台 CI 上的评审组件"定位，按 [Anthropic《Solving Code Review at Anthropic》](https://claude.dev/blog/solving-code-review-at-anthropic/)（2026）的最佳实践重新设计架构。差异化核心：**子代理隔离审查 + 独立复核评判**（验证层具体构造为自主设计，以评测数据为唯一裁判，见 docs/03 §7 诚实溯源）。

> ⚠️ 仓库纪律：本仓库为全新独立仓库，**严禁向原 openai-code-review 仓库推送任何内容**；远端操作规范见 [docs/00](docs/00-branching-and-workflow.md) §8。
> 📌 当前版本：**v1 基线**（2026-10-25 冻结；五轮评审 40 项缺陷全部闭环，对照表见 [docs/01 §8](docs/01-background-and-research.md)。治理：基线冻结、增量走 ADR、每里程碑非红驱动文档改动 ≤1 PR、红即修——见 [docs/07](docs/07-roadmap.md) 头部）。

## 核心理念

**评审三功能框架（docs/01 §3.4，按博主视频设计指导整合）**：AI 把"读代码"捆绑的三件事拆散——**挑错**交给机器 double check（全新上下文子代理独立复查）；**对齐上下文**靠 cross check 从非代码角度核对（CrossChecker 两模式，03§3.1：隐含假设核对 + sniff test set）；**担责**的 approve 签字永远是人（签字 = 核实意图/假设/设计，非逐行读过）。**"写下来→边界外推"**：人的 cross check 经验写入 REVIEW.md 产品背景/嗅探段，即外推为机器的 double check。在此框架下：

1. **精确率优先，但有召回护栏**——每条 finding 必须经独立复核存活才发布；聚合召回下限（Wilson CI 判定）、零发布护栏与梯度沉默护栏（截断丢弃可见）防止系统被优化成沉默；
2. **隔离产生多样性，多样性产生精确率**——评审子代理三重隔离并行；验证者多样性是投票成立的前提，不满足则退化为单验证者 + 仲裁；
3. **不可度量则不可改进，且门禁必须可持续**——GitHub 评论账本（指纹 v2 + DONE 完整标记）、聚合 CI 门禁三级分级（快检哨兵/标准门禁/里程碑全量）。

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
| [01 背景与调研](docs/01-background-and-research.md) | 原项目分析、Anthropic 实践提炼、**三功能框架与反模式清单（§3.4）**、需求定义、三轮修订对照表 | 所有人必读 |
| [02 总体架构](docs/02-architecture-design.md) | 分层架构、数据流、部署形态与状态归属（指纹 v2 + DONE 账本）、幂等/降级/并发预算、触发边界 | 开发必读 |
| [03 多代理核心设计](docs/03-multi-agent-design.md) | 三重隔离、角色 reviewer、**CrossChecker 两模式详设（§3.1）**、独立复核 + 仲裁、收敛控制、诚实溯源表 | 开发必读（核心） |
| [04 上下文与提示词](docs/04-context-and-prompt-design.md) | 条件注入分层、全角色预算算术 + 评测门禁三级分级、REVIEW.md（**含产品背景/事件/嗅探段**）与注入防御 | 开发必读 |
| [05 数据模型与集成](docs/05-data-and-integration.md) | Finding/账本指纹与**解析器防污染规则**、SPI（jgit blame/锚定降级）、CLI、workflow（draft 过滤）、安全检查单 | 开发必读 |
| [06 评测与反馈闭环](docs/06-eval-and-feedback.md) | **聚合 Wilson CI 门禁 + 三级分级**、Defects4J 双侧数据集、整仓 fixture 规格、转化漏斗护栏 | 开发必读 |
| [07 路线图](docs/07-roadmap.md) | M0–M4（pre-PR 实跑验证进 M2、投票降 M3）、任务拆解、验收标准、工程纪律（**提交前三条腿核查**） | 开发执行入口 |
| [08 测试策略](docs/08-test-strategy.md) | 测试金字塔、CI 门禁演进表（M0→M3）、录制回放选型、19 条验收场景的测试设计 | 开发必读 |
| [09 代码规范](docs/09-code-standards.md) | Spotless+google-java-format、命名/异常/日志/并发约定、Java 17 用法示例 | 开发必读 |
| [10 架构规范](docs/10-architecture-standards.md) | 模块依赖矩阵、包结构/DI 约定、**ArchUnit 8 条守护**、ADR 机制（含 4 篇补录）、豁免流程 | 开发必读 |
| [11 扩展性契约](docs/11-extensibility-charter.md) | 扩展点总表与稳定性分级、SPI 演进规则、新角色/新平台接入 checklist | 开发必读 |
| [99 视频指导存档](docs/99-video-guide-reference.md) | 三功能框架整理稿（含**来源与证据等级声明**：单一博主、AI 生成内容，低于生产实践与学术引用） | 附录参考 |

## 技术栈

Java 17 · Spring Boot 3.x（仅 DI/配置）· Maven 多模块 · OpenAI 兼容 LLM 网关（模型无关 + 并发限流 + 异构路由）· jgit（blame/本地模式）· GitHub REST API + 评论账本 · SQLite（本地模式）→ PostgreSQL（服务化）

## 快速开始（M2 部分能力可用，见 docs/07）

```bash
# CI 模式（GitHub Actions 内，见 docs/05 §4）
cra review --repo "$GITHUB_REPOSITORY" --pr "$PR_NUMBER" --base "$BASE_SHA" --head "$HEAD_SHA" --depth standard

# 本地 pre-PR 自评审（M2；默认实跑 mvn -B -ntp verify + dry-run + SQLite 账本，见 docs/05 §3）
# 实跑失败 → 报告展示真实失败输出并退出 4（05§3）；无 CRA_LLM_API_KEY 时评审段如实跳过
cra review --local --base main --depth standard
```

## 状态

- [x] 调研与需求定义（2026-10）
- [x] 技术方案 v1.0 → … → v1.3.1 → **v1 基线冻结**（五轮评审 40 项缺陷闭环，对照表见 docs/01 §8）
- [x] **开发规范包 docs/08–11**（测试策略 / 代码规范 / 架构规范+ADR / 扩展性契约——【强制/建议/可选】三级标注为效率与规范的平衡机制，强制项全部可自动化守护）
- [x] M0 骨架与最小闭环（含 cra-archtest 架构守护、Spotless 接入、docs/adr/ 目录创建）——代码与测试全部落地（2026-10-25，本地 squash 合入 8 个 feat 分支）；验收 1/3/4 的实跑部分待远端与密钥配置后自举验证（远端操作见 docs/00 §10，仅 owner 执行）
- [x] M1 工程化与账本（含最小评测集与 fixture 规格、REVIEW.md 新段落解析）——v0.2.0（2026-10-25），验收 7/7
- [ ] M2 隔离审查 + 独立复核 + pre-PR 实跑（核心）——**T2.9 三档路由 / T2.10 本地 pre-PR（含验收 8 e2e 实跑）/ T2.11 模板版本化 / T2.1 隔离运行时（工具白名单/三重预算硬限/失败隔离）/ T2.2 BugHunter 四视角路由与并发扇出 / T2.3 Security/Test/Convention/Perf 已落地**；验证晋升线（T2.4–T2.8）待合入
- [ ] M3 评测闭环 + 双验证者投票（最小样本量 + 数据门禁）
- [ ] M4 服务化与扩展（a 风险分级+签字规矩 / b 服务化 / c push / d 夜间 / e 多平台 / **f CrossChecker（lite/full 两阶段）**）

### 已知平台限制（02§5）

冲突 PR 的 workflow 不触发（GitHub 官方行为，冲突解决后恢复）；fork PR 无 secrets（多协作者时引入 App token）；draft PR 已过滤不评审。
