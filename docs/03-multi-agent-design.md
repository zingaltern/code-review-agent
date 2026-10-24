# 03 · 核心设计：子代理隔离审查与评判

> 文档版本：v1.2.1（2026-10-25，写入评审三功能框架映射）
> **v1.1**：放弃"盲评"改为独立复核；验证者多样性为硬性前提；溯源表诚实化。
> **v1.2**：反锚定校验降级为 trace 质控信号；抽查确定性抽样。
> **v1.2.1**：与 01§3.4 三功能框架建立命名映射——本项目验证层 = **double check**（挑错）的工程化；评审层新增 **CrossChecker** 角色占位（对齐上下文的 cross check，输入非代码，M4f 落地）。

## 0. 三功能框架映射（v1.2.1 新增，框架见 01§3.4）

评审的三个功能（挑错 / 对齐上下文 / 担责）在本设计中的载体：**挑错 = 本文档全部机制**——隔离并行发现（§3）+ 独立复核 double check（§4，"全新上下文的子 agent 独立复查"）+ 仲裁；**对齐上下文 = 被动承担**（Summary 概述 + explainer），主动 cross check 由 §3 表中 CrossChecker（M4f）承接——它与其他 reviewer 的本质区别是**输入不含 diff**：看的是上下游变更史、产品事件史、数值参照系，防"表面正确的代码 + 错误的上下文假设"（如会话超时值与下游默认值矛盾这类读代码读不出来的问题）；**担责 = 明确出界**：所有子代理只产出 finding 建议，approval 签字永远是人（01§2.5）。

---

## 1. 子代理统一抽象

所有子代理（Reviewer / Verifier / Judge）共享同一执行模型，差异仅在 **prompt 模板、输入 brief、输出 schema、工具白名单、模型档位**：

```java
interface SubAgent<I, O> {
    AgentRole role();                 // 角色 + 视角/模板版本标识
    AgentBrief buildBrief(I input);   // 由编排器注入，自包含
    O execute(AgentBrief brief);      // LLM 循环：可选受限工具 + 最终结构化输出
    StructuredSchema<O> outputSchema();
    ToolWhitelist tools();            // 默认只读
    Budget budget();                  // token / 轮次 / 时间 三重硬限
}
```

关键性质：

- **无状态**：一切输入来自 brief；brief 全文落盘可复现；
- **单向通信**：只能向编排器返回结果，不感知其他子代理存在；
- **结构化出口**：输出过 schema 校验，失败按 02§4.2 降级；
- **预算硬限**：超限即杀，标记 `BUDGET_EXCEEDED`。

## 2. 隔离机制（三重隔离）

隔离的目的：**降低错误相关性**。"N 个高相关性评审者投票等价于 1 个评审者"这条公理**同时适用于评审层和验证层**——v1.0 只在评审层做了多样性、却给验证层配了同模板同模型的两个副本，是自体瓦解；v1.1 修正（见 §2.3、§4）。

### 2.1 上下文隔离

- 每个子代理独立全新上下文，编排器以自包含 `AgentBrief` 传递一切所需；
- brief 不含编排器中间推理、不含其他子代理输出；
- 依赖切片由编排器预取内联，子代理仅有 `read_file(白名单)` 受限工具（白名单在 brief 生成时确定）；
- 好处：可重放、可测试、无上下文污染。

### 2.2 视角隔离（评审层提示词多样性）

同角色多实例使用不同视角系统提示词：

| BugHunter 实例 | 视角 | 针对错误类别 |
|---|---|---|
| BH-boundary | 边界条件：空/null/越界/Off-by-one/类型截断 | 输入校验类 |
| BH-concurrency | 并发与竞态：共享可变状态/锁粒度/事务边界 | 时序类 |
| BH-dataflow | 数据流追踪：输入→变换→输出断裂点 | 逻辑类 |
| BH-resource | 资源生命周期：泄漏/未关闭/超时/重试风暴 | 运维类 |

### 2.3 执行隔离与模型异构（v1.1 扩展到验证层）

- 工具白名单默认 `[read_file(scoped)]`；SecurityReviewer 加 `grep_pattern(scoped)`；任何子代理无网络、无写盘、无 shell；
- 超时与预算独立，单代理失败不传染；
- **模型异构路由**：不同角色/实例可路由到不同模型。v1.0 只把异构给了 reviewer 视角（不需要统计独立性的层）；v1.1 起**验证层的多样性是硬性要求**——凡配置 ≥2 个验证者，其模型家族与指令模板**必须不同**，否则配置校验直接失败（宁可单验证者+仲裁，不要两个相关性 0.9 的"独立"验证者）。

### 2.4 隔离的例外

聚合阶段允许使用已确认 finding 的位置做去重合并；除此之外跨代理信息流动需修订本文档显式批准。

## 3. 评审层：角色化 Reviewer 子代理

| 角色 | 触发条件 | 输入重点 | 输出 |
|---|---|---|---|
| BugHunter ×N | 总是（N 由深度档位决定） | diff + 窗口/全文 + 视角指令 | `CandidateFinding[]` |
| SecurityReviewer | 总是（独立 pass） | diff + 依赖清单 + 安全规范段 + 文件全文 | `CandidateFinding[]`（category=security） |
| TestReviewer | 逻辑文件变更且测试变更比例低 | diff + 测试目录结构 | 测试缺口类 |
| ConventionReviewer | REVIEW.md 定义约定时 | diff + REVIEW.md 约定段 | 约定违反（多为 nit） |
| PerfReviewer | Deep 或命中 perf 路径 | diff + 热点标注 | 性能风险类 |
| **CrossChecker**（M4f 占位，v1.2.1 新增） | M4f（对齐上下文的 cross check，01§3.4） | **非 diff**：上下游近期变更史（调用方/被调方 commit）、产品事件史（事故/工单）、数值与配置参照系 | "代码本身正确但与上下文假设矛盾"类 finding（数量级不对、与下游默认值冲突） |

Reviewer prompt 原则（模板见 04）：

1. 只报告有文件/行级证据、可说明"触发条件→错误结果"的问题；`evidence` 必须引用行号；
2. **空结果合法且被鼓励**——但注意：召回护栏（01§4.4）在系统层面防"鼓励空结果"退化为"全部空结果"；
3. pre-existing 嫌疑置 `pre_existing_suspect=true`，由编排器用本地 jgit blame 复核（05§2）；
4. brief 注入剩余 nit 配额。

## 4. 评判层：独立复核 Verifier 与仲裁 Judge（v1.1 重写）

### 4.1 设计承认与出发点

- **"盲评"已废弃**：finding 的 `description/failure_mode` 就是发现者的完整论证；要么 verifier 看到完整论证（锚定风险仍在，"盲"是装饰），要么剥离论证（verifier 失去待验证的断言内容）。与其假装看不见，不如**放开看、但约束其必须独立重建证据链**；
- **证伪导向已降级**：v1.0 的"尝试推翻它"指令 × 双确认门槛会系统性拒真（错杀真 bug）。M2 改为**平衡式独立复核**（同等寻找支持与反驳证据）；纯证伪指令保留给 M3 的第二验证者作为**指令差异化的手段**（与第一验证者形成视角互补，而非双份同构）；
- **投票是 M3 增量而非 M2 默认**：投票的统计收益依赖验证者错误去相关（AEGIS-D 的前提，见 01§3.2 域声明）；在做不到异构前，单强模型验证者 + 仲裁是诚实且资源合理的形态。

### 4.2 VerifierAgent：独立复核（M2 形态：每文件批次 × 1 个强模型验证者）

**批处理单元**：同一文件的候选 finding 合并为一个验证 brief（上下文按文件组织，天然省 token，见 04§2 算术）。

**输入**：finding 全文（含发现者论证——不假装盲）+ 所在文件全文 + 该文件相关切片 + REVIEW.md 证据要求段。

**指令核心（平衡式复核）**：

```
你是独立复核员。下面是若干条待复核的代码评审结论及其原始代码上下文。
对每一条：以同等强度寻找支持证据与反驳证据，独立作出判定。

判定要求：
- CONFIRMED：你必须 (a) 自己定位到证据行号（不得照抄结论中的引用），
  (b) 用你自己的话重述失败模式——禁止复述结论原文的表述，
  (c) 主动检查结论未提及的上下文（如上游已有防护、不可能触发的路径）后仍成立；
- REFUTED：给出具体反证（行号 + 引用），如已有防护、行号张冠李戴、触发条件不可达；
- INSUFFICIENT_EVIDENCE：当前上下文无法判断，用 read_file 补查一次后仍无法判断。
```

**反锚定行为约束及其度量（v1.2 重定义）**：prompt 层仍要求"自己定位行号 + 用自己的话重述失败模式"（行为约束）；机器侧的**引用新颖性校验降级为 trace 质控信号**，不再阻断晋升——理由：单行证据类真缺陷（一行 off-by-one，Java 高频）的正确复述必然与 finding 原文高度重叠，硬校验要么压召回、要么迫使验证者填充无关引用"过关"，校验退化为可绕过的新颖性游戏，**引用新颖性 ≠ 独立验证**。降级后的语义：

- 校验器输出 `anti_anchor_check = PASS | FAIL` 记入 finding 与 trace，**FAIL 不影响晋升**（CONFIRMED 照常发布）；
- FAIL 率按验证者/模板/模型维度聚合进周报（06§1.3）：基线漂移（如新 prompt 使 FAIL 率骤升）= 锚定回退的预警信号，走 badcase 复盘流程而非自动拒真；
- 归一化定义（供统计，消除歧义）：行号引用统一抽取 `\bL?(\d{1,4})\b` 与 `:(\d{1,4})` 模式归一为数字集合；代码片段按去空白行比较；重叠率 = 交集引用数 / rationale 引用总数。

### 4.3 判定与晋升规则

**M2（单验证者 + 仲裁）**：

```
CONFIRMED                      → 发布
REFUTED                        → 丢弃（trace 留档，进入拒真统计）
INSUFFICIENT（补查后仍不能判）   → Judge 仲裁（仲裁者拥有更大上下文配额）
security/important 级 REFUTED   → 抽查比例进 Judge 复核（防单个验证者错杀，
                                 比例可配，默认 20%）——召回侧的保险丝
验证不可得（超时/预算击穿，缩上下文重试 1 次仍失败）
                               → UNVERIFIED，默认不发布（02§4.2）
```

**抽查的确定性抽样（v1.2 补定义，可复现）**：命中与否由 `sha256(run_id | finding_id) mod 100 < spotcheck_pct` 决定——同 run 重放结果一致、trace 与账本可审计，禁止随机数与时间因素。

**M3（异构双验证者投票，数据门禁开启）**：两个验证者**模型家族不同、指令模板不同**（V1 平衡复核 + 强模型；V2 证伪导向 + 异构模型）：

```
2/2 CONFIRMED → 发布；2/2 REFUTED → 丢弃
混合/含 INSUFFICIENT → Judge 终裁（CONFIRMED / REJECTED / DOWNGRADE_TO_NIT）
security 类：仅 2/2 CONFIRMED 或 Judge CONFIRMED 才发布
```

**开启门禁（v1.2 加最小样本量）**：拒真评估池（REFUTED 总量 × 抽查 + Judge 仲裁记录）**样本 ≥20** 且其中被推翻比例 ≥10%，**或**已发布 finding 处置样本 ≥30 且发布精确率 <80% 时，引入第二验证者；样本量不足时**不判定**（继续单验证者 + 抽查），避免在噪声内掷硬币决定头号特性去留。

### 4.4 JudgeAgent：一次性终裁（自主设计，无生产背书）

- 适用于：INSUFFICIENT 仲裁、M3 分歧终裁、重要级 REFUTED 抽查；
- brief = finding + 验证者判定与证据 + 更大配额的代码上下文；
- 指令明确："分歧时其中一方可能是对的，裁决哪方证据链成立，不折中、不求共识"（Debate or Vote 的结论：辩论不必然优于投票，故仲裁是一次性的，不做多轮辩论）；
- 强模型档位；调用次数少（仅上述场景），成本可控。

### 4.5 端到端判定流（伪代码）

```java
List<CandidateFinding> cands = fanOutReviewers(plan, context);       // 并行隔离
Map<FileKey, List<CandidateFinding>> byFile = groupByIdentity(cands); // 同位置粗去重后按文件分组
for (var e : byFile.entrySet()) {
    List<CandidateFinding> merged = mergeDuplicates(e.getValue());
    VerifierReport r = verify(e.getKey(), merged);                    // 1(M2)/2(M3) 个验证者
    for (CandidateFinding f : merged) {
        f.status = switch (r.verdictOf(f)) {                          // §4.3 规则
            case CONFIRMED -> CONFIRMED;
            case REFUTED -> securityOrImportant(f) && spotCheck()
                           ? judge(f, r) : REJECTED;
            case INSUFFICIENT, UNAVAILABLE -> judge(f, r);            // 仲裁或 UNVERIFIED
        };
        if (f.status == CONFIRMED) publishable.add(f);
    }
}
```

`groupByIdentity` 去重：M2 精确去重（同 file ±3 行同 category）；M3 增语义去重（description 嵌入相似度 >0.9）。

## 5. 收敛控制（ConvergenceGuard）

1. **轮次上限**：同一 PR 验证-复审循环最多 3 轮，之后停止新 finding，Summary 提示"建议重新考虑实现方案"；
2. **diff-only 复审**：PR 新 push 只评审增量 diff；已发布 finding 不重验证，除非涉及文件被后续 commit 修改；指纹从账本读取（02§5）；
3. **finding 级去重**：该 PR 已发布 finding 指纹（账本）命中即抑制；
4. **pre-existing 处理**：本地 jgit blame 复核（无本地仓库时降级为不标注并在 Summary 声明）；确认后标 `pre-existing` 单独列出，不占 nit 预算、不阻塞。

## 6. 各子代理规格速查表（v1.2 修正 Security/Perf/Judge 的上下文策略，预算为默认值、部署配置可覆盖）

| 子代理 | 数量/运行 | 模型档位 | 工具 | 输入预算 | 输出预算 | 轮次 |
|---|---|---|---|---|---|---|
| BugHunter | 每视角 1，按 chunk 并行 | 标准 | read_file(scoped) | ≤24k/chunk | ≤2k | 3 |
| SecurityReviewer | **按 chunk 并行**（每 chunk 1 实例，聚合合并） | 强 | read_file+grep(scoped) | ≤24k/chunk | ≤2k | 3 |
| TestReviewer | 条件触发 | 标准 | read_file | ≤12k | ≤1k | 2 |
| ConventionReviewer | 条件触发 | 轻 | 无 | ≤8k | ≤1k | 1 |
| PerfReviewer | 条件触发，按 chunk | 标准 | read_file | ≤16k/chunk | ≤1k | 2 |
| VerifierAgent | M2:1 / M3:2异构 | 强(M2)；V1强+V2异构(M3) | read_file(scoped+) | ≤12k/文件批次 | ≤1.5k | 2 |
| JudgeAgent | 仅仲裁/抽查 | 强 | read_file | ≤16k/**案件** | ≤1k | 2 |

**v1.2 修正说明**：v1.1 的算术示例只测了 BH/Verifier——按 04§2.2 假设（10 文件 × 250 行），Security 若独享"全 diff + 全部变更文件全文" brief ≈ 55k，超自身预算 2 倍以上，要么静默裁剪（安全角色看不全）要么 DEGRADED。v1.2 起 **Security/Perf 与 BH 同构：chunk 化扇出 + 窗口上下文**，仅对 REVIEW.md `priority_paths` 与安全敏感路径（auth/crypto/secret/依赖清单变更）注入该 chunk 的文件全文（该 chunk 预算内）；**Judge 上下文按案件界定**（finding + 判定证据 + 涉事文件，非全 PR），完整算术见 04§2.2。

## 7. 设计依据溯源（v1.1 诚实化）

| 设计点 | 依据类型 | 说明 |
|---|---|---|
| 多实例并行找 bug | **Anthropic 原文** | "multiple agents hunt bugs in parallel" |
| 独立验证者投票、仅存活者发布 | **Anthropic 原文**（存在性）；**本项目构造**（实现细节） | 博客未公开验证者数量/指令/独立性构造；本项目的验证者形态是自主设计 |
| 轮次上限 / diff-only 复审 / finding 去重 | Anthropic 原文 | 收敛修复实践 |
| pre-existing 标注（59% 存量） | Anthropic 原文 | 实现路径为本项目自定（jgit blame） |
| 安全独立 pass 加严 | Anthropic 原文 | 加严规则细节为自定 |
| 允许空结果输出 | Anthropic 原文 | "明确说明无需修改" |
| **独立复核 + 反锚定输出约束** | **自主设计** | 替代 v1.0"盲评"（概念不成立）；锚定抑制靠输出约束而非信息隐藏 |
| **平衡式复核指令（弃纯证伪）** | **自主设计** | Refute-or-Promote（C/C++ 域）的思想借鉴 + 拒真风险的修正 |
| **Judge 一次性终裁** | **自主设计** | Debate or Vote 支持"投票>辩论"，故仲裁不做辩论循环 |
| **验证者异构硬性要求** | **自主设计** | 由"N 个高相关性投票者等价于 1 个"公理推出；AEGIS-D 的误报下降依赖多样性前提（数学验证域，间接证据） |
| 三重隔离、模型异构路由 | 自主设计 | 工程化构造 |
