# 02 · 总体架构设计

> 文档版本：v1.0（2026-10-25）
> 前置阅读：`01-background-and-research.md`

---

## 1. 架构总览

一句话定位：**挂在 Git 平台事件上的多子代理评审流水线**——编排器组装上下文并生成评审计划，N 个相互隔离的评审子代理并行找问题，候选发现交给相互隔离的盲评验证子代理对抗式复核并投票，仅存活的发现被聚合发布回 Git 平台，全链路留痕、可度量、可回放。

```
┌────────────────────────────────────────────────────────────────────────┐
│  L5 接入层 Trigger                                                      │
│  GitHub Actions 入口 / Webhook(Phase2) / CLI 手动                        │
└───────────────┬────────────────────────────────────────────────────────┘
                │ ReviewRequest(repo, pr, depth, config)
┌───────────────▼────────────────────────────────────────────────────────┐
│  L4 编排层 Orchestrator（单次评审 = 一个 ReviewRun）                      │
│  ① 事件解析与幂等检查  ② 上下文组装器 ContextAssembler                   │
│  ③ 评审计划 Planner（路由/扇出度/预算分配）                               │
│  ④ 扇出-汇聚 Fan-out/Join  ⑤ 收敛控制 ConvergenceGuard                   │
│  ⑥ 聚合器 Aggregator（去重/分级/排序/nit预算）  ⑦ 发布 Publisher          │
└───────┬───────────────────────────────┬────────────────────────────────┘
        │ AgentBrief(自包含)             │ AgentBrief(自包含,盲评)
┌───────▼───────────────┐   ┌───────────▼──────────────┐
│ L3 评审子代理层         │   │ L3' 评判子代理层           │
│ ReviewerAgent ×N 并行  │   │ VerifierAgent ×2/finding │
│ ├ BugHunter(多视角)    │   │ （对抗式证伪、盲评）        │
│ ├ SecurityReviewer     │   │ JudgeAgent(分歧仲裁,少量) │
│ ├ TestReviewer         │   └──────────────────────────┘
│ ├ ConventionReviewer   │
│ └ PerfReviewer         │
└───────┬───────────────┘
        │ CandidateFinding(JSON)
┌───────▼────────────────────────────────────────────────────────────────┐
│  L2 基础设施层                                                           │
│  LlmGateway(多模型路由/结构化输出/重试降级/成本核算)                       │
│  GitPlatformAdapter(diff/文件树/PR API/inline comment, 限流重试)          │
│  ReviewStore(SQLite→PG: run/finding/vote/trace)                          │
│  ConfigService(REVIEW.md + 部署 YAML)                                    │
└─────────────────────────────────────────────────────────────────────────┘
```

分层依赖规则：L5 → L4 → (L3, L3') → L2，严禁跨层与反向依赖；L3/L3' 对 L4 只通过 `AgentBrief`（输入）与结构化结果（输出）交互，看不到彼此。

## 2. 端到端数据流（一次 PR 评审的完整时序）

```
PR:synchronize ─→ Trigger(CI job / CLI)
 1. 幂等检查：ReviewStore 中 (repo, commit, run_type) 已 SUCCESS 则跳过
 2. 拉取变更集：diff、变更文件全文、commit list、PR 元信息（含 base SHA）
 3. 上下文组装：
    - L0 diff（必选）
    - L1 变更文件全文（预算内全给）
    - L2 依赖切片（import/调用方，按 reviewer 需要懒加载）
    - L3 仓库规范 REVIEW.md / AGENTS.md / .cra.yml
    - 历史记忆：该文件过往 finding（用于 pre-existing 判定与去重）
 4. Planner 生成 ReviewPlan：
    - 按文件语言/路径/大小分块（chunk），路由到 reviewer 角色集合
    - 按深度档位决定扇出度（见 §4.3）
    - 分配 token 预算与超时
 5. Fan-out：并行调度隔离的 ReviewerAgent（每 chunk × 每角色 × 深度决定实例数）
 6. 收集 CandidateFinding（JSON schema 强校验，解析失败重试≤2 次后丢弃）
 7. Verify：每条候选 finding 派 2 个盲评 VerifierAgent 独立证伪
    → 全部 confirm → confirmed；全 reject → 丢弃；分歧 → JudgeAgent 仲裁
 8. Aggregate：同位置去重合并、severity 定档（important/nit/pre-existing）、
    按 (severity, confidence) 排序、nit 上限裁剪、风格类默认丢弃
 9. Publish：GitHub PR Review（inline comments 绑定 file+line）+ Summary；
    已发布 comment 持久化，重放不重发
10. Trace 落盘：run 目录 NDJSON（每步输入/输出/token/耗时）
```

其中 5→7 是本项目区别于所有"单次调用"方案的核心，详细设计见 `03-multi-agent-design.md`。

## 3. 模块划分（Maven 多模块）

延续原项目的多模块组织方式，模块前缀 `cra-`（code-review-agent）：

```
code-review-agent/
├── cra-common/            # 纯 POJO：Finding/Brief/Verdict/Plan/RunStatus 等数据模型与工具
├── cra-llm/               # LlmGateway：模型路由、chat/JSON 调用、重试降级、成本核算
├── cra-git/               # GitPlatformAdapter SPI
│   └── cra-git-github/    # GitHub 实现（diff 解析、PR API、inline comment、限流）
├── cra-context/           # ContextAssembler：diff 解析、文件树、依赖切片、token 预算裁剪
├── cra-agents/            # 子代理定义：角色 prompt 模板、工具白名单、结构化输出 schema
├── cra-core/              # Orchestrator：Planner/Fan-out/Verify/Aggregate/Publish/Convergence
├── cra-runner-cli/        # CLI 入口（CI 内运行；替代原项目 java -jar 直跑 main 的方式）
├── cra-store/             # ReviewStore：SQLite 实现 + schema 迁移（服务化时换 PG 实现）
├── cra-eval/              # 评测框架：数据集加载、重放、精确率/检出率报表、回归门禁
└── cra-server/            # (Phase 2, 先占位) webhook 常驻服务、队列、多仓库
```

依赖方向（简化）：`cra-runner-cli → cra-core → {cra-agents, cra-context, cra-llm, cra-git, cra-store, cra-common}`；所有模块 → `cra-common`。

**对照原项目**：`openai-code-review-sdk` 的职责被拆进 `cra-core + cra-agents + cra-git-github + cra-llm`；`openai-code-review-test` 的角色由 `cra-eval` + 仓库内 `.github/workflows` 自举（吃自己的狗粮：本项目自身的 PR 由自己评审）。

## 4. 关键机制设计

### 4.1 幂等与重放

- ReviewRun 以 `(repo, commit_sha, trigger, depth)` 为幂等键；
- Publisher 发布前检查 store 中该 run 已发布的 comment（含 GitHub comment id），重放只补发缺失部分；
- 所有 LLM 调用与子代理输入输出写入 trace，支持离线重放调试（不真实回帖的 dry-run 模式）。

### 4.2 失败隔离与降级

- 单个 ReviewerAgent 超时/失败不阻塞整体：该角色缺失时 run 标记 `DEGRADED`，Summary 中如实说明；
- LlmGateway 降级链：主模型 → 备用模型 → （Quick 深度下）跳过验证直接打 `UNVERIFIED` 标签发布或完全静默（按配置，默认静默）；
- 任何未预期异常：run 落盘 `FAILED` + trace，CI 侧以非零码退出但不阻塞 PR（评审组件自身故障不应阻塞交付）。

### 4.3 评审深度档位

| 档位 | 触发 | BugHunter 实例 | 验证者 | 上下文 | 目标延迟 |
|---|---|---|---|---|---|
| Quick | label `cra:quick` / 默认对小 diff | 1 | 1（快速档：单验证者+高置信阈值） | L0+L1 | ≤2min |
| Standard | 默认 | 2（不同视角） | 2 盲评 | L0+L1+按需L2 | ≤5min |
| Deep | label `cra:deep` / owner 配置 | 3~4 + 全角色 | 2 盲评+分歧仲裁 | 全量+切片 | ≤15min |

### 4.4 成本与预算

- Planner 在计划期分配每子代理 token 预算（输入+输出），LlmGateway 硬性执行，超限终止该代理并记 `BUDGET_EXCEEDED`；
- run 级总预算兜底；成本随 trace 落盘，`cra-eval` 汇总每 PR 成本报表；
- 大 PR（diff > 阈值，默认 2000 行）策略：按目录/模块分 chunk 并行评审 → 跨 chunk 聚合去重；超过硬上限（默认 8000 行）时只评审 risk 路径（见 REVIEW.md `priority_paths`）并在 Summary 声明覆盖范围。

### 4.5 配置体系

两级配置，职责分离：

| 层 | 文件 | 内容 | 变更者 |
|---|---|---|---|
| 仓库级 | `REVIEW.md`（仓库根或目录级） | 什么算失败、nit 上限、跳过路径、优先路径、证据要求 | 代码 owner，随 PR 版本化 |
| 部署级 | `application.yml` + 环境变量 | 模型路由/密钥、预算、深度默认、开关、降级策略 | 运维/架构组 |

`REVIEW.md` 规范与解析见 `04-context-and-prompt-design.md` §3。

## 5. 部署形态演进

| 阶段 | 形态 | 说明 |
|---|---|---|
| M0-M2 | **CI 内嵌**（GitHub Actions step） | 与原项目一致：workflow 里 `java -jar cra-runner-cli`；零运维，状态由 Actions 持有 |
| M3+ | **CLI 本地** | 开发者本地 `cra review --base main` 在提 PR 前自评审（对应 Anthropic 的 pre-PR verification 思想） |
| M4+ | **Webhook 服务**（cra-server） | 常驻服务 + 队列，承接多仓库、增量复审、夜间存量评审；CLI 与服务共用 cra-core |

设计约束：**cra-core 全部逻辑与部署形态无关**（输入只有 ReviewRequest，输出只有 ReviewResult + 待发布 comments），CLI 与 server 只是两种壳。

## 6. 横切关注点

- **安全**：LLM 密钥仅从环境/密管读取；子代理工具白名单默认只读（读 repo 文件、读 git 历史），禁网络禁写盘；diff 内容属于代码资产，日志与 trace 落盘前脱敏（可选正则掩码 secrets 模式）；
- **可观测**：`run_id` 贯穿全链路日志；trace 目录结构 `runs/{repo}/{run_id}/trace.ndjson`；每次 run 结束输出 summary 指标行（findings_total/confirmed/rejected/降级情况/成本/耗时）；
- **测试策略**：cra-common/cra-core 单元测试覆盖核心编排逻辑；子代理用 LLM **录制回放**（record/replay mock）保证 CI 内可测不花钱；端到端用一个 fixture 仓库 + dry-run 模式断言 finding 集合；
- **自举**：本仓库自身的 PR 启用本项目评审（GitHub Actions），真实使用即持续评测。

## 7. 与原项目文件的映射清单（供开发 agent 对照）

| 原项目元素 | 去向 |
|---|---|
| `OpenAiCodeReview#main`（ProcessBuilder 跑 git diff） | `cra-git-github` 的 diff 获取（改为 GitHub API，不再依赖本地 git 进程与 fetch-depth=2 的脆弱假设） |
| `openai-code-review-sdk` 打 jar 在 CI 运行 | `cra-runner-cli`（相同思路，增加参数、幂等、trace） |
| `.github/workflows/main-maven-jar.yml` | 新仓库 `.github/workflows/review.yml`（on: pull_request，跑本项目 CLI） |
| `openai-code-review-test` 验证工程 | `cra-eval` fixture 仓库 + 自举 workflow |
| 根 `pom.xml` Java 8 / SB 2.7 | 升级 Java 17 / Spring Boot 3.x（仅用其 DI 与配置，不启 web） |

## 8. 本文档范围内的未决问题（开发前需在 issue 中定案）

1. `cra-server` 是否在 M4 直接基于 Spring Boot web，还是用轻量 HTTP 框架——倾向前者（团队熟悉度）；
2. diff 解析自研（jgitPatch 解析）还是引 jgit 全量——倾向自研轻量 unified diff 解析器（可控、可测）；
3. 结构化输出失败时的降级 JSON 修复策略（重试 with error feedback vs 直接丢弃）——M1 用重试≤2 实现，M2 根据实测数据定。
