# 01 · 需求背景与调研总结

> 文档版本：v1.0（2026-10-25）
> 阶段：需求熟悉与调研（本文档为后续开发 agent 的输入，请先通读全文再动手）

---

## 1. 项目背景

### 1.1 从哪来：原项目 openai-code-review

原仓库 `zingaltern/openai-code-review`（2025-03 创建）是一个 Java 技术栈的代码评审组件骨架：

| 维度 | 现状 |
|---|---|
| 技术栈 | Java 8、Spring Boot 2.7.12、Maven 多模块 |
| 模块 | `openai-code-review-sdk`（核心）、`openai-code-review-test`（验证工程） |
| 核心逻辑 | `OpenAiCodeReview#main`：ProcessBuilder 执行 `git diff HEAD~1 HEAD` 并打印 diff |
| 集成方式 | GitHub Actions：push/PR 到 master → checkout(fetch-depth=2) → `mvn install` → `java -jar` 运行 SDK |
| 完成度 | **仅骨架**。LLM 调用、评审结果结构化、GitHub 评论回帖均未实现 |

原项目的意图很清晰：**CI 触发 → 取 diff → LLM 评审 → 回帖 GitHub**。这条最小闭环思路是对的，但停留在第一步。

### 1.2 到哪去：为什么需要全新项目

2026 年业界共识已经变化，单次"diff → LLM → 评论"的初级形态有三个致命问题：

1. **误报噪声大**——单模型单视角评审，幻觉结论直接污染评论区，开发者很快学会忽略它；
2. **无验证机制**——发现（finding）没有独立复核，"看起来像 bug"和"确实是 bug"不分；
3. **无反馈闭环**——评审质量不可度量、不可回归，prompt 改动全凭感觉。

Anthropic 2026 年发布的内部实践（见 §2）证明了**多子代理并行评审 + 独立验证者投票**是解决上述问题的有效路径，并给出了完整的方法论。本项目就是在原项目的定位上，按该最佳实践重新设计的新一代代码评审智能体。

### 1.3 与原项目的关系（重要约束）

- **演进而非重写**：新项目继承原项目的定位（挂在 Git 平台 CI 上的评审组件）与集成思路（GitHub Actions 触发）；
- **仓库全新**：新项目为独立仓库，**禁止向原 `openai-code-review` 仓库推送任何内容**；原仓库仅作为调研参考；
- **功能超集**：原项目设想的"取 diff → LLM 评审 → 回帖"闭环，是新项目里程碑 M1 的功能子集。

## 2. 核心输入一：Anthropic《Solving Code Review at Anthropic》要点提炼

来源：https://claude.dev/blog/solving-code-review-at-anthropic/（2026 年发布）

### 2.1 问题定义

- Claude 编写了 Claude Code 约 90% 的代码，monorepo 变更量从 ~2,500/天（5 月）暴涨到 30,000+/天（9 月），人工逐行评审不可持续；
- 重新定义问题：不是"是否有人读过这段代码"，而是"**这个风险该由哪一层检查（自动化或人工）来兜住**"；
- 评审从"终点门禁"变为"**伴随式检查**"（类比写代码时的拼写检查）；
- 稀缺资源是**人的时间与注意力**；北极星指标：大部分 PR 最多只需 1 个人参与（甚至只有作者本人），同时 revert 率、事故率作为护栏指标不恶化。

### 2.2 分层流水线架构（本项目架构的直接蓝本）

1. **Pre-PR 自验证**：作者侧 agent 先跑测试/构建/lint，必须**展示输出而非声称完成**；再用第二个子代理以全新上下文复检 diff。据估计自验证让结果好 2~3 倍；
2. **PR 上的 AI 评审**：
   - **多个 agent 并行找 bug**；
   - **独立的 verifier agent 对每条 finding 投票，只有存活者（获得足够共识）才被发布**——这是降噪的关键机制；
   - 风格/清理类发现经 A/B 测试后被砍掉（噪声大于价值）；
   - finding 以 inline comment 呈现，标注 Important / Nit / Pre-existing，按严重度排序；
   - 每条 finding 必须被修复或给出理由后 resolved；
   - 管理员可按仓库选择 Quick / Standard / Deep 评审深度；
3. **安全评审**：独立的阻塞性 pass，存在未处理安全 finding 则不可合并；后续 commit 修复后自动清除；
4. **强制 CI**：lint、类型、多平台测试、构建；
5. **基于风险的人工审批**：分类器将 PR 分为 Low（检查绿了即可 0 人审批）/ Medium（1 人）/ High（2 人，如计费、CI 配置）；agent 的批准**永远不算人工批准**；审批绑定具体 commit；
6. **合并队列 + 灰度发布**：对最新 main 重跑检查；先内部员工 → latest → stable；
7. **快速回滚**：特性开关秒级关闭；事后复盘必问"哪一层本该拦住它"。

### 2.3 让评审者变强：反馈闭环

- **事故即评测集**：历史引发事故的 PR 经专家标注后形成评测集，评审系统每天在其上打分；prompt/工具/模型的改动只有分数不降才保留（回归门禁）；
- **噪声度量**：将每条 comment 按人类反应分类（fixed / wrong / intentional / ignorable）；同时对已知干净的 PR 跑评审测误报；
- **收敛控制**（解决 agent 作者与评审者无限循环，曾有 PR 隔夜跑了 50+ 轮）：轮次上限（3 轮后重新考虑方案本身）、后期只对增量 diff 复审、只标记 bug 类别而非逐实例、同一 finding 不重复上报；git blame 显示 ~59% 被标记代码是存量代码 → 引入 Pre-existing 标注；夜间 agent 评审存量代码；
- 效果：6~9 月合并 PR 翻倍且 revert 占比降 40%+；一轮过率 65%→78%，5 轮以上 PR 从 8.9%→1.5%，平均评审轮次 2.08→1.32。

### 2.4 工程规范类实践

- `CLAUDE.md`（对应本项目的仓库级配置文件）：只放技术栈、约定、目标/非目标，保持短小；评审者负责标记约定违反与过期内容；
- **`REVIEW.md` 由代码 owner 撰写**：定义"这个目录里什么样的改动算失败"、nit 上限、可跳过路径、要求文件/行级证据、允许明确回答"无需修改"；
- 重复出现的 finding 在源头修（lint 规则 / skill / 指令文件），而不是在评审里反复提；
- 敏感路径用 CODEOWNERS + 分支保护；新 commit 使既有审批失效；合并队列、开关、灰度、一步回滚；
- 漏报复盘时问"模型当时缺什么上下文"，然后补进配置/技能/检查项。

### 2.5 人的位置

- 人负责：决定做什么（方向与品味）、高风险代码与架构范式评审、读证据（测试输出/截图/finding）、对结果负责；
- "你合并你拥有"，通过小变更、可逆性、按爆炸半径定审查强度、无责复盘来对冲风险；
- **AI 只发布 finding，不发布批准**——approval 永远是人的决策（低风险仓库可由 owner 选择性放开 0 人合并，但需要熔断器：revert/事故指标异常时自动收紧）。

## 3. 核心输入二：业界与学术调研

### 3.1 商业 AI CR 工具格局（2025-2026）

| 工具 | 特点 | 对本项目的启示 |
|---|---|---|
| Greptile | 全代码库索引 + 语义检索，擅长跨文件 bug，价格高 | 跨文件上下文是高价值方向，但索引基础设施可后置（M4+） |
| CodeRabbit | 免费额度大、平台集成广，但**噪声偏多**是主要抱怨 | 噪声是 AI CR 的头号产品风险，验证投票机制必须前置到 M2 |
| Qodo / BugBot | 聚焦测试生成与 bug 专项 | 测试覆盖类 reviewer 作为独立子代理 |
| Graphite / Augment 等 | 均在做"第一道过滤 + 人工聚焦高风险"定位 | 定位一致：AI 做第一道，人做高风险决策 |

共识：AI CR 的正确姿势是**人工评审前的第一道过滤**，而非替代人工；配置可调、噪声可控是留存关键。

### 3.2 多 Agent 验证机制的学术证据

| 研究 | 结论 | 采纳方式 |
|---|---|---|
| AEGIS-D（OpenReview） | 多 agent 辩论 + 投票达到 92.5% precision，误报比单 judge 基线降 8.2× | "发现→盲评验证→投票"两段式结构 |
| Refute-or-Promote（arXiv 2604.19049） | 对抗式、阶段门控的多 agent 评审，针对协作辩论漏掉的误报类别 | verifier 的任务设定为**证伪**而非确认 |
| Debate or Vote（NeurIPS 2025） | 纯辩论不一定优于多数投票；需设计"保护正确少数派"机制 | 投票为主、辩论为辅；分歧时进入**仲裁 judge** 而非强求共识 |
| Anthropic 内部实践 | 独立 verifier 投票，仅存活的 finding 发布 | 同构，作为生产验证案例 |

### 3.3 结论：三条设计公理

1. **精确率优先**：宁可漏报也不误报——开发者信任一旦失去极难重建；所有 finding 必须经过独立验证才能发布；
2. **隔离产生多样性，多样性产生精确率**：评审子代理必须在上下文、提示词视角、执行环境三重隔离下并行，验证子代理必须盲评；
3. **不可度量则不可改进**：从第一天起就要有结构化 finding 存储、comment 处置追踪和回归评测集。

## 4. 需求定义

### 4.1 目标（Goals）

- **G1 端到端闭环**：PR/push 触发 → 多子代理隔离评审 → 独立验证投票 → 结构化 finding 以 inline comment 回帖 GitHub，全程幂等可重放；
- **G2 低噪声**：发布的 finding 经 ≥2 名盲评 verifier 独立确认（对抗式证伪）；目标发布精确率 ≥80%（人工处置中 fixed/intentional 占比），评测集上误报率可持续回归；
- **G3 可配置**：仓库级 `REVIEW.md` 定义评审规则（什么算失败、nit 上限、跳过路径）；评审深度 Quick/Standard/Deep 可选；
- **G4 可演进**：模型无关（OpenAI 兼容协议）、平台可扩展（GitHub 先行，GitLab/Gerrit 预留）、单机 CLI 先行、服务化预留；
- **G5 可观测**：每次评审的完整 trace（上下文、子代理输入输出、投票记录、成本/耗时）落盘可查。

### 4.2 非目标（Non-Goals，明确不做）

- 不做自动修复 PR（评审是本项目的全部；修复属于后续项目）；
- 不做人工审批的替代（只输出 finding 与风险分级**建议**，approval 永远是人/平台策略的决策）；
- 不做 IDE 插件、不做全代码库语义索引（M4 之后再说）；
- 不做多租户 SaaS 平台化（先服务好自己的仓库）。

### 4.3 关键用户与场景

| 角色 | 场景 |
|---|---|
| PR 作者（主要用户） | 提交 PR 后数分钟内收到精确、分级的 inline 评审意见；Pre-existing 的历史问题被标注而非甩锅 |
| 仓库 owner / 管理员 | 用 `REVIEW.md` 定制评审策略；选择评审深度；查看噪声与精确率报表 |
| 平台 SRE / 架构组 | 部署维护组件；配置模型路由与预算；接入新 Git 平台 |

### 4.4 成功指标（验收口径）

| 指标 | 定义 | 目标（M2 结束时） |
|---|---|---|
| 发布精确率 | 已发布 comment 中人工处置为 fixed / intentional（合理）的比例 | ≥ 80% |
| 误报率 | 人工处置为 wrong 的比例 | ≤ 15% |
| 单 PR 评审延迟 | 触发到回帖完成的 p95 | ≤ 5 分钟（标准深度） |
| 单 PR 成本 | LLM token 折算成本均值 | 有预算硬上限，可配置 |
| 评测集回归 | 历史 bad PR 上的检出率 | 建立 baseline，每次改动不下降 |

## 5. 技术选型决策

### 5.1 决策矩阵（编排语言/框架）

| 方案 | 优势 | 劣势 | 结论 |
|---|---|---|---|
| **Java 17 + Spring Boot 3.x + 自研轻量编排**（选定） | 与原项目及团队技能连续；CI jar 运行方式已验证；agent 核心循环（LLM 调用+工具+循环）本就轻量，自研可控可测；JVM 生态适合长期服务化 | LLM 生态不如 Python 丰富 | ✅ 选定 |
| Python + LangGraph/CrewAI | agent 生态最丰富 | 与原项目断裂；动态类型在结构化 finding/pipeline 上易失控 | 备选（若团队转 Python 可平移设计） |
| TypeScript + Claude Agent SDK | 与 Claude Code 生态最近 | 运行时分发依赖重；团队无积累 | 不选 |

**选型原则**：不引入重编排框架。子代理 = "自包含 brief + 有限工具白名单 + LLM 循环 + 结构化输出"，这个抽象用 ~几百行 Java 即可实现，且测试性、可控性远好于框架黑盒。LLM 访问统一走 OpenAI 兼容协议（可路由 GLM / DeepSeek / Qwen / Claude / GPT 系端点），Spring AI 或 LangChain4j 仅在需要时作为 HTTP 客户端依赖引入，核心流程不绑定。

### 5.2 其他关键选型

- **Git 平台**：GitHub（REST API v3 + webhook），抽象 `GitPlatformAdapter` 接口；
- **结构化输出**：LLM JSON mode / function calling 强约束 Finding schema，Jackson 反序列化 + schema 校验；
- **存储**：单机阶段用本地 SQLite（评审记录、finding、投票、成本），服务化阶段平移 PostgreSQL；
- **配置**：仓库内 `REVIEW.md`（随代码版本化）+ 部署侧 YAML（模型路由、预算、深度档位）；
- **可观测**：结构化 JSON 日志 + 每次评审一个 run 目录（NDJSON trace），先不引入重型 APM。

## 6. 风险与对策

| 风险 | 影响 | 对策 |
|---|---|---|
| LLM 误报仍高，用户流失 | 高 | 验证投票前置（M2 核心）；发布门槛宁严勿松；pre-existing 单独标注 |
| 大 PR 上下文爆炸 / 成本失控 | 高 | 分层上下文 + 分块评审 + token 预算硬限 + 深度档位 |
| 子代理相互"感染"导致相关性错误 | 中 | 三重隔离设计（见 03 文档）；verifier 盲评 |
| GitHub API 限流 / 幂等问题 | 中 | 适配器层限流重试；comment 持久化去重，重放不重发 |
| agent 循环不收敛（隔夜 50 轮那种） | 中 | 轮次上限、diff-only 复审、finding 去重，见 03 §6 |

## 7. 参考资料

- Anthropic 博客：Solving Code Review at Anthropic — https://claude.dev/blog/solving-code-review-at-anthropic/
- 原项目仓库：zingaltern/openai-code-review（本地调研克隆，勿推送）
- AEGIS-D: Multi-Agent Debate System for High-Precision — https://openreview.net/pdf?id=fMyVTW7Bof
- Refute-or-Promote: Adversarial Stage-Gated Multi-Agent Review — https://arxiv.org/html/2604.19049v1
- Debate or Vote: Which Yields Better Decisions in Multi-Agent LLM Systems? — NeurIPS 2025
- 业界工具横评：Greptile《Best AI Code Review Tools》、DeployHQ《AI Code Review Tools Compared》等（2025-2026）
