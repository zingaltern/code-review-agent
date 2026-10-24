# 03 · 核心设计：子代理隔离审查与评判

> 文档版本：v1.0（2026-10-25）
> 本文档是项目的技术核心：**为什么隔离、隔离什么、如何评判、如何收敛**。
> 所有子代理实现必须严格遵循此处的抽象模型与隔离规则。

---

## 1. 子代理统一抽象

所有子代理（Reviewer / Verifier / Judge）共享同一个执行模型，差异仅在 **prompt 模板、输入 brief、输出 schema、工具白名单**：

```java
interface SubAgent<I, O> {
    AgentRole role();                 // 角色 + 视角标识
    AgentBrief buildBrief(I input);   // 由编排器注入，自包含（见 §2.1）
    O execute(AgentBrief brief);      // LLM 循环：可选工具调用 + 最终结构化输出
    StructuredSchema<O> outputSchema(); // JSON Schema 校验
    ToolWhitelist tools();            // 工具白名单（默认只读）
    Budget budget();                  // token / 轮次 / 时间
}
```

关键性质：

- **无状态**：子代理不持有跨调用状态，一切输入来自 brief；
- **单向通信**：子代理只能向编排器返回结果，不能主动与其他子代理通信，不能感知其他子代理的存在；
- **结构化出口**：输出必须通过 schema 校验，解析失败按 02§4.2 降级；
- **预算硬限**：token、对话轮次、墙钟时间三项硬限，超限即杀，标记 `BUDGET_EXCEEDED`。

## 2. 隔离机制（三重隔离）

隔离的目的不是安全洁癖，而是**降低错误相关性**：N 个高相关性的评审者投票等价于 1 个评审者。多样性是精确率的来源（调研依据见 01§3.2/3.3）。

### 2.1 上下文隔离（Contextual Isolation）

- 每个子代理拥有**独立、全新的上下文窗口**，编排器以 `AgentBrief`（一份自包含文档）传递其所需的一切：变更代码、相关文件、规范、任务指令、输出格式要求；
- **brief 中不得包含**：编排器的中间推理、其他子代理的输出、历史对话残留；
- 依赖切片（被调用方/调用方文件）由编排器**预取并内联**进 brief，而非让子代理自行漫游仓库——子代理默认无"读整个仓库"的自由工具，只有 `read_file(白名单内)` 这类受限工具，且白名单在 brief 生成时即确定（只含已判定相关的文件）；
- 好处：可重放（brief 落盘即可复现）、可测试（录制 brief 做单测）、上下文无污染。

### 2.2 视角隔离（Perspective Isolation，提示词多样性注入）

同角色的多个实例使用**不同视角的系统提示词**，从不同错误类别切入，降低同源盲区：

| BugHunter 实例 | 视角提示词核心指令 | 针对的错误类别 |
|---|---|---|
| BH-boundary | 边界条件：空/null/越界/Off-by-one/类型截断 | 输入校验类 |
| BH-concurrency | 并发与竞态：共享可变状态/锁粒度/可见性/事务边界 | 时序类 |
| BH-dataflow | 数据流追踪：输入→变换→输出，断裂点与假设错误 | 逻辑类 |
| BH-resource | 资源生命周期：泄漏/未关闭/超时/重试风暴 | 运维类 |

视角池在 `cra-agents` 中以模板文件管理；扇出 N 时选取相关度最高的 N 个视角（按变更文件语言/目录特征排序）。

### 2.3 执行隔离（Execution Isolation）

- **工具白名单**：默认 `[read_file(scoped)]`；SecurityReviewer 额外 `grep_pattern(scoped)`；任何子代理无网络、无写盘、无 shell；
- **超时与预算独立**：单代理超时不影响其他代理（编排器用并行提交 + future 超时实现）；
- **模型可异构**：brief 中携带 `preferred_model`，编排器可将不同视角路由到不同模型（如敏感安全审查用更强模型），进一步降低同源相关性——这是 LlmGateway 多模型路由的主要动机之一。

### 2.4 隔离的例外（唯一允许的信息流动）

聚合阶段允许将**已确认 finding 的位置**用于去重合并；除此之外，任何跨代理信息流动都需要在本文档修订中显式批准。

## 3. 评审层：角色化 Reviewer 子代理

| 角色 | 触发条件 | 输入重点 | 输出 |
|---|---|---|---|
| BugHunter ×N | 总是（N 由深度档位决定） | diff + 文件全文 + 视角指令 | `CandidateFinding[]` |
| SecurityReviewer | 总是（独立于 BugHunter，Anthropic 的独立安全 pass 思想） | diff + 依赖清单 + 安全规范段 | `CandidateFinding[]`（category=security） |
| TestReviewer | 变更含逻辑文件且测试变更比例低时 | diff + 测试目录结构 | 测试缺口类 finding |
| ConventionReviewer | REVIEW.md 定义了约定时 | diff + REVIEW.md 约定段 | 约定违反（多数为 nit） |
| PerfReviewer | Deep 档位或变更命中 perf 路径 | diff + 热点标注 | 性能风险类 finding |

Reviewer 的 prompt 设计原则（模板见 04 文档）：

1. **只报告有文件/行级证据、可指出具体失败模式的问题**——"可能有问题""建议关注"类输出被 schema 层拒绝（`evidence` 字段必填且必须引用 diff 行号或文件行号）；
2. **允许且鼓励输出空结果**——"此变更未发现问题，理由：…"是合法且被鼓励的输出（对抗强制找茬倾向，这是降噪第一关）；
3. **标注 pre-existing 嫌疑**：若怀疑问题在 base commit 已存在，报告时置 `pre_existing_suspect=true`，由编排器用 git blame 复核；
4. **nit 预算意识**：brief 中注入本 run 剩余 nit 配额，超出则不输出 nit。

## 4. 评判层：盲评 Verifier 与仲裁 Judge（本项目的差异化核心）

### 4.1 为什么需要独立评判

单评审者的 finding 有两类污染：幻觉（引用不存在的行为）与过时假设（忽略了 brief 外的事实，如某处已有 null 检查）。Anthropic 实践（verifier 投票，仅存活者发布）与 AEGIS-D（误报降 8.2×）共同证明：**发现与验证分离、验证者独立取证**，是精确率的杠杆。

### 4.2 VerifierAgent：对抗式盲评

每条 `CandidateFinding` 派 **2 个** VerifierAgent 独立复核，规则：

**盲评（Blind）**——verifier 的 brief 只包含：
- finding 的**断言本身**（category/title/description/evidence 位置/声称的失败模式）；
- **原始代码上下文**（完整变更文件全文 + 相关切片，与 reviewer 同级或更全）；
- **不包含**：发现者身份、发现者的推理过程、confidence、其他 verifier 的结论。

目的：防止锚定效应——verifier 若先看到"这里有空指针"的完整论证，会倾向于顺着找支持证据。对抗式设定要求它**独立重建证据链**。

**证伪导向（Refute-or-Promote）**——verifier 的任务指令是"**推翻这条 finding**"，输出三值判定：

```
Verdict ∈ { REFUTED, CONFIRMED, INSUFFICIENT_EVIDENCE }
- REFUTED：必须给出反证（如"第 87 行已有 null 检查"引用具体行）
- CONFIRMED：必须独立复现证据链（自己定位到行号并重述失败模式，不得照抄 finding 原文）
- INSUFFICIENT_EVIDENCE：上下文不足以判断（如需要 diff 之外的调用方代码）
```

`INSUFFICIENT_EVIDENCE` 时允许 verifier 使用一次受控工具调用（`read_file` 白名单扩一圈）补证后重新判定——这是 2.1 中"依赖切片预取"的兜底路径。

### 4.3 投票与晋升规则（Stage-Gated Promotion）

```
2 个 verifier 均 CONFIRMED            → finding.status = CONFIRMED   → 进入发布
2 个 verifier 均 REFUTED / INSUFF     → finding.status = REJECTED    → 丢弃（trace 留档）
1 CONFIRMED + 1 REFUTED               → 分歧 → 交 JudgeAgent 仲裁
1 CONFIRMED + 1 INSUFFICIENT          → JudgeAgent 仲裁（倾向降级为 nit）
```

Security 类 finding 加严：必须 2/2 CONFIRMED 才 CONFIRMED，分歧一律仲裁且仲裁结果只能 CONFIRMED 或 REJECTED（安全类没有 nit 档）。

### 4.4 JudgeAgent：分歧仲裁（保护正确少数派）

NeurIPS 2025 的结论：强求共识的辩论会杀死正确的少数派。因此仲裁设计为**终裁制**而非继续辩论：

- Judge brief = finding 断言 + 双方 verifier 的**结论与证据**（此时代码上下文仍全量提供）；
- Judge 被明确指令："两个验证者分歧时，其中一人可能是对的。你的任务是裁决哪一方证据链成立，而非折中。"
- 输出：`CONFIRMED / REJECTED / DOWNGRADE_TO_NIT` + 裁决理由；
- Judge 使用更强模型档位（LlmGateway 路由规则），调用次数少（仅分歧时），成本可控。

### 4.5 端到端判定流（伪代码）

```java
List<CandidateFinding> cands = fanOutReviewers(plan, context);      // 并行隔离
for (Batch<CandidateFinding> batch : groupByIdentity(cands)) {       // 同位置合并先做粗去重
    CandidateFinding merged = mergeDuplicates(batch);
    List<Verdict> verdicts = fanOutVerifiers(merged, 2);             // 并行盲评
    merged.status = vote(verdicts);                                  // §4.3 规则
    if (merged.status == DISPUTED) {
        merged.status = judge(merged, verdicts);                     // 仲裁终裁
    }
    if (merged.status == CONFIRMED) publishable.add(merged);
}
```

`groupByIdentity`：按 `(file, 行号区间重叠, category, 断言语义相似度)` 二级去重——先精确（同 file±3 行同 category），再语义（description 嵌入相似度 > 0.9，M2 实现；M1 只做精确去重）。

## 5. 收敛控制（ConvergenceGuard）

针对 Anthropic 观察到的"作者-评审者无限循环"（50+ 轮），流水线内置四条硬规则：

1. **轮次上限**：同一 PR 的验证-复审循环最多 3 轮，之后停止新 finding，输出"建议重新考虑实现方案"级别的 summary 提示；
2. **diff-only 复审**：PR 有新 push 时，只对**增量 diff** 重新评审；已确认/已发布 finding 不重新验证，除非其涉及文件被后续 commit 修改；
3. **finding 级去重**：`ReviewStore` 记录该 PR 已发布 finding 的指纹（file+line+category+断言哈希），重复发现直接抑制；
4. **pre-existing 处理**：git blame 复核确认 base 已存在的问题 → 标 `pre-existing` 单独列出（不计入本次 nit 预算、不阻塞），杜绝"59% 的 flagged 代码其实是存量"的信任损耗。

## 6. 各子代理规格速查表

| 子代理 | 数量/运行 | 模型档位 | 工具 | 输出 schema | 预算（默认） |
|---|---|---|---|---|---|
| BugHunter | 每视角 1 实例，并行 | 标准 | read_file(scoped) | CandidateFinding[] | 8k in / 2k out / 3 轮 |
| SecurityReviewer | 1 | 强 | read_file + grep(scoped) | CandidateFinding[] | 12k in / 2k out |
| TestReviewer | 条件触发 | 标准 | read_file | CandidateFinding[] | 6k in / 1k out |
| ConventionReviewer | 条件触发 | 轻 | 无（纯 brief） | CandidateFinding[] | 4k in / 1k out |
| PerfReviewer | 条件触发 | 标准 | read_file | CandidateFinding[] | 8k in / 1k out |
| VerifierAgent | 每候选 finding ×2 | 标准 | read_file(scoped+) | Verdict | 8k in / 1k out |
| JudgeAgent | 仅分歧时 | 强 | read_file | Judgement | 10k in / 1k out |

所有数值均为部署级配置可覆盖项，集中在 `application.yml` 的 `cra.agents.*` 下。

## 7. 设计依据溯源（为什么这样设计）

| 设计点 | 依据 |
|---|---|
| 多实例并行找 bug | Anthropic："multiple agents hunt bugs in parallel" |
| 独立验证者投票、仅存活者发布 | Anthropic 同上；AEGIS-D 92.5% precision |
| 证伪式 verifier 指令 | Refute-or-Promote（arXiv 2604.19049） |
| 盲评（隐去发现者推理） | 锚定效应；Anthropic "fresh context" 原则的推广 |
| 仲裁而非继续辩论 | NeurIPS 2025《Debate or Vote》：辩论不必然优于投票，需保护正确少数派 |
| 轮次上限 / diff-only 复审 / 去重 | Anthropic 收敛修复（50+ 轮案例；一轮过率 65%→78%） |
| pre-existing 标注 | Anthropic：git blame 显示 ~59% flagged 代码为存量 |
| 安全独立 pass 且加严 | Anthropic：security review 为独立阻塞 pass |
| 允许空结果输出 | Anthropic：要求"明确说明无需修改"的总结 |
