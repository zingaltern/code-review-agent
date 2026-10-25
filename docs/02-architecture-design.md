# 02 · 总体架构设计

> 文档版本：v1.3.1（2026-10-25，响应第四轮评审缺陷 ①③⑤；规范包 08–11 入库时 §3 登记 cra-archtest）
> 前置阅读：`01-background-and-research.md`
> **v1.1**：新增 §5 部署形态与状态归属；触发收窄为仅 PR；LLM 并发与降级语义补全。
> **v1.2**：指纹 v2（commit 闸 + 代码内容锚）；截断丢弃进指标；触发边界表。
> **v1.2.1**：§4.1 commit 闸增加 **status=DONE 完整发布判定**（发布中途崩溃的 run 与完整发布此前不可区分）+ 锚行选取规则与 **±5 行滑窗容错匹配**；§4.4 run 预算上调 **500k**、阶梯重校 **70%/85%**（v1.2 算术 330k/400k=82.5% 坐在旧 80% 档之上，截断护栏会常态化误报）。

---

## 1. 架构总览

一句话定位：**挂在 Git 平台事件上的多子代理评审流水线**——编排器组装上下文并生成评审计划，相互隔离的评审子代理并行找问题，候选发现交给独立复核验证子代理与仲裁 judge 评判，仅存活的发现被聚合发布回 Git 平台，全链路留痕、可度量、可回放。

```
┌────────────────────────────────────────────────────────────────────────┐
│  L5 接入层 Trigger（M0–M3 仅 pull_request；CLI 本地模式 M2 起）          │
└───────────────┬────────────────────────────────────────────────────────┘
                │ ReviewRequest(repo, pr, depth, config)
┌───────────────▼────────────────────────────────────────────────────────┐
│  L4 编排层 Orchestrator（单次评审 = 一个 ReviewRun）                      │
│  ① 事件解析与幂等检查(账本见§5)  ② 上下文组装器 ContextAssembler         │
│  ③ 评审计划 Planner（路由/扇出度/预算分配）                               │
│  ④ 扇出-汇聚 Fan-out/Join  ⑤ 收敛控制 ConvergenceGuard                   │
│  ⑥ 聚合器 Aggregator（去重/分级/排序/nit预算）  ⑦ 发布 Publisher          │
└───────┬───────────────────────────────┬────────────────────────────────┘
        │ AgentBrief(自包含)             │ AgentBrief(自包含)
┌───────▼───────────────┐   ┌───────────▼──────────────┐
│ L3 评审子代理层         │   │ L3' 评判子代理层           │
│ ReviewerAgent ×N 并行  │   │ VerifierAgent(M2:1强模型,  │
│ ├ BugHunter(多视角)    │   │  M3:2异构,按文件批处理)     │
│ ├ SecurityReviewer     │   │ JudgeAgent(分歧/不可得仲裁) │
│ ├ TestReviewer         │   └──────────────────────────┘
│ ├ ConventionReviewer   │
│ └ PerfReviewer         │
└───────┬───────────────┘
        │ CandidateFinding(JSON)
┌───────▼────────────────────────────────────────────────────────────────┐
│  L2 基础设施层                                                           │
│  LlmGateway(多模型路由/并发限流/结构化输出/重试降级/成本核算)               │
│  GitPlatformAdapter(diff/文件树/PR API/inline comment + 本地jgit blame)  │
│  状态账本(§5：GitHub评论 / SQLite / PG，按形态)  配置服务                  │
└────────────────────────────────────────────────────────────────────────┘
```

分层依赖规则：L5 → L4 → (L3, L3') → L2，严禁跨层与反向依赖；L3/L3' 对 L4 只通过 `AgentBrief`（输入）与结构化结果（输出）交互，看不到彼此。

## 2. 端到端数据流（一次 PR 评审的完整时序）

```
PR:opened/synchronize ─→ Trigger(CI job / CLI)
 1. 幂等检查：从账本(§5)读该 PR 上 bot 已发布 finding 指纹——
    GitHub 形态 = 拉取 bot 自身历史评论解析内嵌指纹；本地/服务形态 = 查库
 2. 拉取变更集：diff(compare API)、变更文件元信息、PR 元信息（含 base/head SHA）
 3. 上下文组装（分层与预算见 04§1-2）：diff + hunk 窗口 + 条件全文
    + 依赖切片 + base 分支的 REVIEW.md + 账本中该 PR 已发布指纹
 4. Planner 生成 ReviewPlan：分 chunk、路由角色、定扇出度与预算、
    按 diff-only 规则裁剪范围（只评审增量）
 5. Fan-out：并行调度隔离的 ReviewerAgent（并发上限见 §4.5）
 6. 收集 CandidateFinding（schema 强校验，失败重试≤2 后丢弃）
 7. Verify：候选按文件分组批处理 → 每组独立复核（M2：1 个强模型
    Verifier；M3：2 个异构验证者投票）→ 分歧或验证不可得 → Judge 仲裁
 8. Aggregate：去重合并、severity 定档、排序、nit 预算、风格类默认丢弃、
    pre-existing 经本地 jgit blame 复核
 9. Publish：锚定校验（非法位置降级 Summary）→ GitHub PR Review
   （inline comments + Summary，指纹内嵌于评论）→ 失败按条重试（05§2）
10. Trace 上传：CI 形态经 actions/upload-artifact 持久化（§5）
```

## 3. 模块划分（Maven 多模块）

```
code-review-agent/
├── cra-common/            # 纯 POJO：Finding/Brief/Verdict/Plan/RunStatus 等
├── cra-llm/               # LlmGateway：模型路由、并发限流、重试降级、成本核算
├── cra-git/               # GitPlatformAdapter SPI + LocalRepoContext(jgit blame)
│   └── cra-git-github/    # GitHub 实现（diff/PR API/inline comment/账本解析）
├── cra-context/           # ContextAssembler：diff 解析、窗口/全文策略、切片、预算裁剪
├── cra-agents/            # 子代理定义：角色 prompt 模板、工具白名单、输出 schema
├── cra-core/              # Orchestrator：Planner/Fan-out/Verify/Aggregate/Publish/Convergence
├── cra-runner-cli/        # CLI 入口（CI 模式 + M2 起本地 pre-PR 模式）
├── cra-store/             # 账本抽象：GitHub 评论账本（经 CommentSource 端口取数，ADR-0005）/ SQLite（服务形态 PG）
├── cra-eval/              # 评测框架：数据集、k 次运行统计门禁、报表
├── cra-archtest/          # (M0 起) 构建期架构守护：仅 ArchUnit 测试，无主代码、不被依赖（10§5）
└── cra-server/            # (M4) webhook 常驻服务、队列、多仓库
```

依赖方向：`cra-runner-cli → cra-core → {cra-agents, cra-context, cra-llm, cra-git, cra-store, cra-common}`；所有模块 → `cra-common`。

**对照原项目**：`openai-code-review-sdk` 的职责拆进 `cra-core + cra-agents + cra-git-github + cra-llm`；`openai-code-review-test` 的角色由 `cra-eval` + 自举 workflow 承担。

## 4. 关键机制设计

### 4.1 幂等与重放（v1.2.1：完整发布判定 + 滑窗容错）

- **第一道闸（commit 级幂等，只认完整发布，v1 基线修正：完成标记 = `status ∈ {DONE, DEGRADED}`）**：run 收尾时随 Summary 评论发布完成标记（05§1.3）——**降级完成也是完整发布**（部分角色超时但 run 正常收尾并发布了全部应发内容），对它全量重评既浪费又违背幂等初衷；重跑同 commit 时，仅当账本存在该 commit 的完成标记才全量跳过（只更新 Summary）。发布 3/8 条后崩溃或被 `cancel-in-progress` 掐断的 run **没有任何完成标记**——重跑走指纹级补发（已发布的不重发、缺失的补发），杜绝"其余 5 条永久静默丢失"；
- **第二道闸（指纹级，防跨 push 重复）**：指纹 v2 键**只含确定性成分，不含任何 LLM 生成文本**：

```
fingerprint = sha256( file_path | category | anchor_line_content_hash | anchor_context_hash )

锚行选取规则（v1.2.1 补定义）：finding 行区间的首行（与 schema 的 line 字段一致）——
  锚行不由 LLM 逐次自选，消除多行 finding 两次运行锚在相邻行的漂移；
anchor_line_content_hash : 锚行内容（去首尾空白）哈希——上方插行不改变行内容；
anchor_context_hash      : 锚行 ±2 行内容联合哈希——区分同文件同类别的不同问题（诊断用）
```

- **滑窗容错匹配（v1.2.1 新增）**：精确键未命中时，在新代码锚行 **±5 行窗口**内逐行计算内容哈希，与账本指纹集合求交集——命中即判同指纹抑制。覆盖两类漂移：finding 行区间首行选取偏移、后续 push 改到锚行邻近行；改到**锚行本身**则内容已变，视为新问题重发（符合语义）。滑窗仍未命中但同 file 同 category 且行区间重叠 → 记 `possible_duplicate` 入 trace 供人工复核，不自动抑制；
- 解析失败/无有效标记的兜底分层（05§1.3）：退回第一道闸（commit 级），**不退到"空账本全量重发"**；
- trace 支持 dry-run 离线重放。

### 4.2 失败隔离与降级

- 单个 ReviewerAgent 超时/失败不阻塞整体：该角色缺失时 run 标记 `DEGRADED`，Summary 如实声明；
- **验证不可得语义（v1.1 补全，消除未定义行为）**：Verifier 超时/预算击穿 → 缩小上下文重试 1 次 → 仍失败则该 finding 状态 `UNVERIFIED`，默认**不发布**（trace 留档，Summary 计数声明）；`--publish-unverified=labeled` 配置可改为带 `UNVERIFIED` 标签发布。仲裁 judge 不可得同理；
- LlmGateway 降级链：主模型 → 备用模型 → quick 深度静默 / standard 深度 DEGRADED 完成；
- 任何未预期异常：run 落盘 `FAILED` + trace，CI 以非零码退出但**不阻塞 PR**（评审组件自身故障不应阻塞交付）。

### 4.3 评审深度档位

| 档位 | 触发 | BugHunter 实例 | 验证 | 上下文 | 延迟目标 |
|---|---|---|---|---|---|
| Quick | label `cra:quick` / 默认小 diff（<300 行） | 1 | 1（标准模型快速复核，高置信阈值） | L0+L1 窗口 | ≤2min |
| Standard | 默认 | 2（不同视角） | 1 强模型（M2）/2 异构（M3） | 窗口+条件全文+按需切片 | ≤5min* |
| Deep | label `cra:deep` | 3–4 + 全角色 | 同 Standard + 分歧必仲裁 | 更宽窗口+全文+切片 | ≤15min |

\* 延迟目标的成立假设：≤2k diff 行、≤10 文件、LLM 并发 8；超限 PR 自动降档并在 Summary 声明（04§2）。

### 4.4 成本与预算

- 预算三层：子代理级（token/轮次/时间）→ chunk 级 → run 级（默认 **500k**）；LlmGateway 硬性执行，超限杀代理记 `BUDGET_EXCEEDED`；
- **run 级降档阶梯（v1.2.1 阈值重校）**：消耗 <70% 正常（旗舰算术示例 330k/500k = 66%，正常 PR 不触发任何降档——v1.2 的 400k 预算下该示例为 82.5%，坐在旧 80% 档之上，会使 top-10 截断与 `suppressed_by_truncation>0` 报警常态化，属护栏"狼来了"）；≥70% → 砍 light 角色与 1 个视角；≥85% → 候选 finding 按 severity 排序只验证 top-10；100% → 终止剩余代理，DEGRADED 收尾；
- **截断必须可见（梯度沉默护栏）**：top-10 截断与预算终止产生的**丢弃候选数**计入 run stats（`suppressed_by_truncation`）并在 Summary 中逐条声明"因预算截断未验证 N 条候选"；该计数进入 L3 健康度（06§1.3）与门禁趋势监控——"发 1 条丢 9 条"不允许对护栏 invisible；
- 大 diff 策略：>2k 行自动降为 Quick 质量；>8k 行只评审 REVIEW.md `priority_paths` 并声明覆盖范围。

### 4.5 LLM 并发与限流（v1.1 新增）

- LlmGateway 内置信号量并发上限（默认 8）与每模型 RPM 限制（配置项，见 05§5）；
- 429/5xx 指数退避（最多 3 次）；排队超时按 §4.2 降级；
- GitHub API 与 LLM 端限流相互独立配置。

## 5. 部署形态与状态归属（v1.1 重写）

**v1.0 的错误**：曾写 M0–M2"状态由 Actions 持有"——GitHub Actions 运行器用后即焚，run 之间不保留任何文件或数据库。所有依赖跨 run 状态的机制（幂等、收敛指纹、处置回填、trace 可查）在纯 CI 形态下必须有**运行器之外的事实源**。v1.1 确立"账本"抽象，按形态选择实现：

| 形态 | 里程碑 | 状态账本（事实源） | trace 归宿 |
|---|---|---|---|
| CI 内嵌（GitHub Actions） | M0–M3 | **GitHub 评论账本**：bot 自身发布的 PR 评论即事实源（已发布 finding、指纹、run 标识内嵌于评论体） | `actions/upload-artifact`（保留 90 天），可选回传 data 分支 |
| CLI 本地（pre-PR 自评审） | M2 起 | 本地 SQLite（`~/.cra/ledger.db`） | 本地 `runs/` 目录 |
| Webhook 常驻服务 | M4 | PostgreSQL | 服务端存储 |

**GitHub 评论账本语义**（CI 形态的承重设计）：

1. **可重建状态**：已发布 finding 及其指纹（解析 bot 历史评论中的 HTML 注释标记，解析规则见 05§1.3）、处置信号（对这些评论的 reply/reaction 事件，06§1.1 采集）、PR 级收敛历史（哪些 finding 已在早前 run 发布过）；
2. **放弃的状态**：未发布候选、子代理中间输出、跨 run 的 trace 查询——接受随 artifact 保留期丢失；评价：这些是调试便利而非机制依赖，机制（幂等/收敛/处置）全部只依赖可重建部分；
3. **回灌**：workflow 末尾上传 trace artifact；`cra-eval report` 从 GitHub API 拉账本聚合周报，**无需任何常驻服务**；
4. **评论生命周期收尾（v1.2.1 修正载体）**：新 run 发布成功后，对同 PR 上一轮（旧 head_sha）的 bot Summary **issue comment**（05§2.2，可 PATCH 编辑）加前缀 `[已被 run_xxx 取代]`；上一轮的 inline 评论由 GitHub 自动标 outdated（保持可见，不删除——处置采集依赖其存在；resolve 状态为 GraphQL-only，本项目锁 REST v3，不操作 resolve）。

**触发边界与已知限制（v1.2 新增，均为 GitHub 平台行为，显式声明而非假装支持）**：

| 场景 | 行为 | 处置 |
|---|---|---|
| 冲突 PR（mergeable=conflicting） | `pull_request` workflow **不触发**（merge ref 无法创建，官方行为）——最需要反馈的 PR 会静默无评审 | 无法在触发层解决；owner 解决冲突后的 synchronize 会正常触发；限制写入 README 与部署文档 |
| draft PR | 照常触发（浪费成本与噪声） | workflow 加 `draft == false` 条件 + `ready_for_review` 类型触发（05§4） |
| fork PR | `GITHUB_TOKEN` secrets 不可用（pull_request_target 语义另论） | 当前个人仓库无碍；多协作者时代再引入 App token，写入限制声明 |

设计约束：**cra-core 全部逻辑与形态无关**（输入 ReviewRequest，输出 ReviewResult + 待发布 comments），CLI 与 server 只是两种壳；账本经 `cra-store` 的 `ReviewLedger` SPI 访问。

## 6. 横切关注点

- **安全**：密钥仅环境/密管读取；子代理工具白名单只读；**不可信内容（diff、代码注释、finding 文本）一律定界注入 + 输出转义**（04§3、05§6）；REVIEW.md 一律读 base 分支；
- **可观测**：`run_id` 贯穿全链路；每次 run 结束输出 summary 指标行（findings_total/confirmed/rejected/unverified/降级情况/成本/耗时/覆盖率声明）；
- **测试策略**：编排逻辑单测；子代理 LLM 录制回放；端到端 fixture 仓库 + dry-run 断言；**不可信输入注入用例进对抗集**；
- **自举**：本仓库自身 PR 由本项目评审。

## 7. 与原项目元素的映射清单

| 原项目元素 | 去向 |
|---|---|
| `OpenAiCodeReview#main`（ProcessBuilder 跑 git diff） | `cra-git-github` 的 diff 获取（compare API）；**本地 jgit 用于 blame 与本地模式**——v1.0"完全不依赖本地 git"的说法收回：CI 内 checkout 本就存在，blame 无 API 只能本地做（05§2） |
| `openai-code-review-sdk` 打 jar 在 CI 运行 | `cra-runner-cli`（增加参数、幂等、trace、artifact 上传） |
| push + PR 双触发 workflow | **M0–M3 仅 PR 触发**；push 直推评审（commit-comment 模式）排入 M4（05§4） |
| `openai-code-review-test` 验证工程 | `cra-eval` + 自举 workflow |
| 根 pom Java 8 / SB 2.7 | Java 17 / Spring Boot 3.x（仅 DI 与配置） |

## 8. 已定案与未决问题

已定案（v1.1）：jgit 引入（diff 解析 + blame 双用途）；账本三形态归属（§5）；push 触发移 M4；验证不可得语义（§4.2）。

未决（开发中定案）：
1. artifact 回传 data 分支是否默认开启（倾向默认关闭，按需开）；
2. `cra-server` 技术栈（倾向 Spring Boot web，团队熟悉度）；
3. M3 双验证者的异构组合具体配对（同家族不同版本 vs 跨家族），由 M2 末的分歧率数据决定。
