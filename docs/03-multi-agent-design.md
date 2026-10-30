# 03 · 核心设计：子代理隔离审查与评判

> 文档版本：v1.3.1（2026-10-25，响应第四轮评审缺陷 ②③④）
> **v1.1**：放弃"盲评"改为独立复核；验证者多样性为硬性前提；溯源表诚实化。
> **v1.2**：反锚定校验降级为 trace 质控信号；抽查确定性抽样。
> **v1.2.1**：三功能框架映射（01§3.4）；CrossChecker 角色占位。
> **v1.3**：§3.1 CrossChecker 两模式详设；A/B 赛马映射；轮次打回语义。
> **v1.3.1**：§3.1 修正"输入不含 diff"的自我矛盾表述（diff 参与假设推导，本质区别是**额外上下文输入 + 检查对象不同**）+ 局限声明（切片启发式，召回无保证）；**§4.2 verifier/judge brief 按 category 分型组装**（context 类注入 cited commit diff 节选与 REVIEW.md 条目——否则验证层对 context 证据失明，必然 INSUFFICIENT→拒杀）；§6 补 CrossChecker 预算行。
> **v1.4**（2026-10-27，M4f-full，ADR-0012）：§3.1 模式 A 输入 b 增**产品事件史**（工单/事故复盘 JSONL → PG）；§4.2 context 类增事件条目预取与 `event <id>` 引用；§6 预算行改为"变更史/事件史合计 ≤4k"。
> **v1.4.1**（2026-10-28，ADR-0015）：§4.2 bug 类增**确定性调用点索引**与 **REFUTED 反证纪律/机械校验**（无可核查反证 → 降级 INSUFFICIENT 交 Judge）；§4.3 晋升流增降级分支；§4.4 Judge 增反证审查；§6 速查表补索引预算说明。阈值/数据集不变。
> **v1.4.2**（2026-10-30，ADR-0015 §6）：反证机械校验适用范围 bug → **bug/test**（security 维持 20% 抽查保险丝，暂不扩）；§4.2 与 §4.3 同步口径，降级逻辑/阈值/trace 语义不变。
> **v1.4.3**（2026-10-30，02§4.4）：评审范围默认不含测试文件；§3 补 TestReviewer 的触发比例与 brief 输入口径（按过滤前全量 diff / 仍列出被策略跳过的测试文件）。

## 0. 三功能框架映射（框架与案例见 01§3.4）

评审的三个功能在本设计中的载体：**挑错 = 本文档全部机制**——隔离并行发现（§3）+ 独立复核 double check（§4，"全新上下文的子 agent 独立复查"，财务与审计分开，不让刚写完代码的 agent 审自己）+ 仲裁；**对齐上下文 = §3.1 CrossChecker**（cross check：检查隐含假设与外部证据的矛盾，额外上下文输入 = 变更史/产品段落，diff 仍参与假设推导）；**担责 = 明确出界**：所有子代理只产出 finding 建议，approval 签字永远是人（01§2.5）。

方法级映射（v1.3 补）：

- **多视角并行 + 交叉投票**（指导 2.2-3）→ §3 角色化并行 + M3 双异构验证者投票；
- **A/B 赛马对账**（指导 2.2-2，两个独立结果 + 第三方找差异）→ 本项目对应形态即 M3 双异构验证者（两个独立判定）+ Judge（找差异终裁）；对代码评审**不新增**独立的赛马机制（成本翻倍，与并行多视角收益重叠）；
- **收敛上限 + 人踩刹车**（指导 2.2-4/5）→ §5 轮次上限 3，人的职责是对不收敛说"停"；
- **fresh context 独立复查**（指导 2.2-1）→ §2.1 上下文隔离是全设计地基。

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
| **CrossChecker**（详设见 §3.1，v1.3） | M4f-lite / f-full | **diff（假设推导用）+ 额外上下文输入**：上下游变更史、产品背景段、产品事件史（M4f-full，ADR-0012） | "代码本身正确但与上下文假设矛盾"类 finding（category=context） |

**测试文件与评审范围（v1.4.3，02§4.4）**：测试文件（`src/test/**`、`*Test.java`、`*Tests.java`）默认不进入评审 chunk（不消耗评审 token）；TestReviewer 不受影响地保留两项输入语义——① 触发比例按**过滤前全量 diff** 统计（用过滤后计划会让"生产+测试同改"的 PR 比例恒 0 → 误触发）；② brief 的"本次变更的测试相关文件"清单同时列出按策略跳过的测试文件，故"某行为缺测试覆盖"这一负向主张仍有正向证据面。`--review-tests` / `CRA_REVIEW_TESTS=true` 恢复测试文件评审；REVIEW.md `Skip paths` 与其叠加生效。

Reviewer prompt 原则（模板见 04）：

1. 只报告有文件/行级证据、可说明"触发条件→错误结果"的问题；`evidence` 必须引用行号；
2. **空结果合法且被鼓励**——但注意：召回护栏（01§4.4）在系统层面防"鼓励空结果"退化为"全部空结果"；
3. pre-existing 嫌疑置 `pre_existing_suspect=true`，由编排器用本地 jgit blame 复核（05§2）；
4. brief 注入剩余 nit 配额。

### 3.1 CrossChecker 详设（v1.3 新增，v1.3.1 修正表述与接缝）

**定位**：double check 查不出的一类问题——破坏改动的上下文**不在 diff 里**（01§3.4 会话超时案例：代码隐含假设"后台任务都在常规会话内跑完"，六周前的企业定制恰好破坏它，CI/AI/人都看不见）。**与其他 reviewer 的本质区别（v1.3.1 修正表述）：检查对象与证据来源不同**——它检查的是"变更的隐含假设 vs 外部证据（变更史/产品背景）的矛盾"，证据主体在代码之外；**diff 仍参与**（用于推导隐含假设，不给 diff 推导立即崩坏），并额外注入变更史与产品段两类上下文。它不是"纯度更高的 reviewer"，是**带额外上下文输入与不同检查对象的 reviewer**。

**模式 A：隐含假设核对（assumption check）**

```
输入：
  a. 本次变更 diff 的精简视图（hunk 头 + 增删行，用于推导隐含假设）
  b. 上下游路径近期变更史：jgit log 近 N 周（默认 8）内，
     调用方/被调方路径（依赖切片同源的路径集）的 commit 摘要
     + 产品事件史（M4f-full，ADR-0012）：与变更路径/commit/关键词相关的
       工单/事故复盘摘要（服务端 PG，近 N 周，最多 20 条）
  c. REVIEW.md 产品背景段（04§3.1：Product context / Known incidents）

流程：从 a 推导隐含假设清单（超时/容量/并发/顺序/环境类）
  → 对每个假设检索 b/c 中近期的矛盾事件
  → 命中即产出 finding（category=context，默认 important 档，引用矛盾事件
     的具体来源：commit sha / event id / REVIEW.md 条目——证据规则与 §3 相同）
```

**模式 B：嗅探检查集执行（sniff test set）**

owner 在 REVIEW.md `## Sniff tests` 段落沉淀经验性检查（数量级/口径/常见 pattern，如"超时类参数不得超过 X""报表两张表的口径必须一致"），CrossChecker 逐条对照变更——**人的 cross check 写下来即外推为机器的 double check**（01§3.4 规律的执行端）；ConventionReviewer 处理代码约定，CrossChecker 处理业务/数值类嗅探，二者检查性质不同。

**局限声明（v1.3.1 新增，诚实边界）**：模式 A 的"上下游路径集"来自 grep 一跳切片——**会话超时案例中"六周前的企业定制"是否落入切片路径集是启发式的，context 类召回无结构性保证**；度量上不依赖单一 fixture 自证：对抗集设第 4 类 context 用例（06§1.2），context 检出率进 L3 门禁指标。

**分阶段落地（07 M4f）**：

- **M4f-lite**：模式 A 的 b（git 变更史）+ c（REVIEW.md 段落）+ 模式 B——**零服务依赖**（jgit 在 CI checkout 已有，REVIEW.md 在仓库内），与现有流水线同构；
- **M4f-full**（2026-10-27 落地，ADR-0012）：产品事件史采集管道——`EventHistorySource`（JSONL 本地/对象存储）→ `IncrementalEventHistoryCollector`（窗口/水位/幂等/有上限重试）→ PG `product_event` → `EventHistoryQuery` 检索 → CrossChecker 材料注入 + verifier/judge 按 `event <id>` 预取。**默认关闭**（`CRA_SERVER_EVENT_HISTORY_ENABLED=false`）；Jira/Confluence 等连接器是实现 `EventHistorySource` 的后续扩展，不在第一版。tribal knowledge 不可见仍是硬边界（01§3.4）。

**信任与隔离**：REVIEW.md 产品背景段沿用 base 分支读取 + owner 审批信任模型（04§3.2）；上下游 commit message 与产品事件摘要属不可信输入，UNTRUSTED 定界（04§3.3）；事件 `raw_json` 只入库、不注入 brief；CrossChecker 产出的 finding 走与其他角色相同的验证晋升管线（§4，**brief 按 category 分型组装见 §4.2**）——cross check 的发现同样要过 double check，不享有免检。

## 4. 评判层：独立复核 Verifier 与仲裁 Judge（v1.1 重写）

### 4.1 设计承认与出发点

- **"盲评"已废弃**：finding 的 `description/failure_mode` 就是发现者的完整论证；要么 verifier 看到完整论证（锚定风险仍在，"盲"是装饰），要么剥离论证（verifier 失去待验证的断言内容）。与其假装看不见，不如**放开看、但约束其必须独立重建证据链**；
- **证伪导向已降级**：v1.0 的"尝试推翻它"指令 × 双确认门槛会系统性拒真（错杀真 bug）。M2 改为**平衡式独立复核**（同等寻找支持与反驳证据）；纯证伪指令保留给 M3 的第二验证者作为**指令差异化的手段**（与第一验证者形成视角互补，而非双份同构）；
- **投票是 M3 增量而非 M2 默认**：投票的统计收益依赖验证者错误去相关（AEGIS-D 的前提，见 01§3.2 域声明）；在做不到异构前，单强模型验证者 + 仲裁是诚实且资源合理的形态。

### 4.2 VerifierAgent：独立复核（M2 形态：每文件批次 × 1 个强模型验证者）

**批处理单元**：同一文件的候选 finding 合并为一个验证 brief（上下文按文件组织，天然省 token，见 04§2 算术）。

**输入（v1.3.1：按 category 分型组装）**：

- **代码类**（bug/security/test/convention/performance）：finding 全文（含发现者论证——不假装盲）+ 所在文件全文 + 该文件相关切片 + REVIEW.md 证据要求段；
- **bug 类调用点索引（v1.4，ADR-0015；2026-10-30 跨层版）**：上述代码类输入 + 确定性预取的**受影响符号调用点索引**（finding 文本标识符 ∩ 被改动文件文本，≤3 个符号）。候选集 = **同目录文件 + 通过 `import` / `import static` 引用变更符号所属类型的文件**（有语义索引时用其 import 关系；无则退回同目录并声明"调用链不完整"）。上限：候选文件 **≤60**、条目 **≤40**、渲染 **≤1000 token**（从 codeContext 预算内预留；原 30/30/700）。条目标注方向 `caller` / `self`；候选截断、条目截断、import 不可得都写声明，不静默。不可得（仓库树为空/无符号）→ trace `call_site_index{available=false}` 并在 brief 如实声明。索引是**近似**：词法级匹配（不区分重载、不做 DI/运行期装配还原），Spring 运行期注入调用无法 100% 覆盖；验证者可对可见索引中的调用方/测试文件发起 `followup_file` 补查（受控一次、预算内）。索引可用时 brief 增**分层核对契约**固定段（controller→service→DAO；未列出的层级视为不可见），BH-dataflow 与 verifier 共用同一段文本（04§2.6）。
- **context 类**：finding 全文 + **finding 引用的矛盾证据原文**——被引 commit 的 diff 节选（jgit show，编排器预取内联，每 commit ≤1k token）+ 被引事件史条目（M4f-full，ADR-0012：按 `event <id>` 预取摘要/类型/时间/来源/commit_refs/paths，每批 ≤5 条、≤1.5k token）+ REVIEW.md 对应 Product context/Known incidents 条目 + 变更文件全文（矛盾最终作用于代码）。context 类证据在代码之外，若按代码类模板组装，verifier 必然 INSUFFICIENT → Judge 同样失明 → 拒杀——**分型组装是 CrossChecker（03§3.1）能在验证管线里活下来的前提**；Judge brief 同规则分型。

- **test 类（v1.3.2 新增分型）**：finding 全文 + 变更文件上下文 + **仓库测试文件清单**（head 侧 `listFiles("**/test/**/*.java")`，≤80 行，超出注明总数）。理由（M3 取证）：test 类 finding 的核心主张「某行为无测试覆盖」是**负向/全局主张**——验证上下文只有 main 文件时无法证否 → 必然 INSUFFICIENT → Judge 按证据不足拒（L3 实测 judge 的 53 条 REJECTED 中 48 条为此类）。注入清单后验证者能核对「是否已有覆盖」，未列出的行为走 followup_file 受控补查。清单不可得（listFiles 失败）→ 降级为空并记 trace `test_index`（available=false），验证按原形态进行、结论如实标注。

**指令核心（平衡式复核）**：

```
你是独立复核员。下面是若干条待复核的代码评审结论及其原始代码上下文。
对每一条：以同等强度寻找支持证据与反驳证据，独立作出判定。

判定要求：
- CONFIRMED：你必须 (a) 自己定位到证据行号（不得照抄结论中的引用），
  (b) 用你自己的话重述失败模式——禁止复述结论原文的表述，
  (c) 主动检查结论未提及的上下文（如上游已有防护、不可能触发的路径）后仍成立；
- REFUTED：必须给出可核查反证，并输出 refutation{kind,anchor,quote,reason}；
  quote 必须逐字摘自简报提供的上下文。只指出结论引述/方法名/行号不符
  （kind=CITATION_MISMATCH）不构成反证——若锚行处失败模式仍成立应判 CONFIRMED 并更正位置；
  对简报外代码的推测（"调用方不会……""假设性风险"）不是反证，需要证据时用 followup_file；
- INSUFFICIENT_EVIDENCE：当前上下文无法判断，用 read_file/followup_file 补查一次后仍无法判断。
```

**REFUTED 反证纪律与机械校验（v1.4，ADR-0015）**：bug/test 类候选的 REFUTED 缺 `refutation`、`kind=CITATION_MISMATCH`、`quote` 无法在本次简报可见上下文（代码上下文 + 被引证据 + 补查材料）中逐字定位、或 `quote` 归一化后短于 8 字符时，core 侧 `RefuteEvidenceGate` 将判定**降级为 INSUFFICIENT_EVIDENCE**交 Judge 仲裁，trace 记 `refute_evidence_check{status=DOWNGRADE, reason}`（status 区分 `OK` 通过 / `DOWNGRADE` 降级 / `NOT_APPLICABLE` 不适用）。理由（L3 取证，run `40536205522`）：JacksonCore-8 的 3 次 REFUTED 全部是"结论把变更定位在 contentsAsArray 而非 getTextBuffer"的**引述不符**——可核查、但没有否定失败模式；同一缺陷在 run `40342221542` 以正确命名发布并命中。**test 类于 v1.4.2 纳入**（ADR-0015 §6）：bad-JacksonCore-8 r1 的 test 类候选（"空缓冲区场景无测试覆盖"）同样被 CITATION_MISMATCH 直接拒掉；security 类仍走 20% 抽查保险丝，暂不纳入机械校验。机械层只校验证据形态（确定性、可重放），语义裁决交 Judge；不直接改判 CONFIRMED（避免放大误报）。

**反锚定行为约束及其度量（v1.2 重定义）**：prompt 层仍要求"自己定位行号 + 用自己的话重述失败模式"（行为约束）；机器侧的**引用新颖性校验降级为 trace 质控信号**，不再阻断晋升——理由：单行证据类真缺陷（一行 off-by-one，Java 高频）的正确复述必然与 finding 原文高度重叠，硬校验要么压召回、要么迫使验证者填充无关引用"过关"，校验退化为可绕过的新颖性游戏，**引用新颖性 ≠ 独立验证**。降级后的语义：

- 校验器输出 `anti_anchor_check = PASS | FAIL` 记入 finding 与 trace，**FAIL 不影响晋升**（CONFIRMED 照常发布）；
- FAIL 率按验证者/模板/模型维度聚合进周报（06§1.3）：基线漂移（如新 prompt 使 FAIL 率骤升）= 锚定回退的预警信号，走 badcase 复盘流程而非自动拒真；
- 归一化定义（供统计，消除歧义）：行号引用统一抽取 `\bL?(\d{1,4})\b` 与 `:(\d{1,4})` 模式归一为数字集合；代码片段按去空白行比较；重叠率 = 交集引用数 / rationale 引用总数。

### 4.3 判定与晋升规则

**M2（单验证者 + 仲裁）**：

```
CONFIRMED                      → 发布
REFUTED                        → 丢弃（trace 留档，进入拒真统计）
bug/test 类 REFUTED 无可核查反证 → 降级 INSUFFICIENT（ADR-0015）→ Judge 仲裁
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
- brief = finding + 验证者判定与证据 + 更大配额的代码上下文；bug 类案件附带与 verifier 同源的调用点索引（≤500 token，ADR-0015）；
- 指令明确："分歧时其中一方可能是对的，裁决哪方证据链成立，不折中、不求共识"（Debate or Vote 的结论：辩论不必然优于投票，故仲裁是一次性的，不做多轮辩论）；
- **反证审查（v1.4，ADR-0015）**：先判断复核员的反证是否直接否定失败模式；仅引述/方法名/行号不符**不构成推翻**——回到锚行核对失败模式本身，成立则 CONFIRMED（可更正位置），不成立才 REJECTED；简报外代码的推测不能作为裁决依据；
- 强模型档位；调用次数少（仅上述场景，加 bug 类无证据 REFUTED 降级），成本可控。

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

1. **轮次上限（v1.3 语义强化）**：同一 PR 验证-复审循环最多 3 轮——机器互相挑刺天然不收敛（scope expansion：不停挑 edge case、加 guardrail、扩 scope），**三轮仍不收敛 = 设计问题，不是修改问题**：停止一切新 finding，Summary 明确建议作者**打回重新设计而非继续改**；人在这一关的职责是踩刹车（01§3.4 反模式 3）；
2. **diff-only 复审**：PR 新 push 只评审增量 diff；已发布 finding 不重验证，除非涉及文件被后续 commit 修改；指纹从账本读取（02§5）；
3. **finding 级去重**：该 PR 已发布 finding 指纹（账本）命中即抑制；
4. **pre-existing 处理**：本地 jgit blame 复核（无本地仓库时降级为不标注并在 Summary 声明）；确认后标 `pre-existing` 单独列出，不占 nit 预算、不阻塞。

## 6. 各子代理规格速查表（v1.2 修正 Security/Perf/Judge 的上下文策略，预算为默认值、部署配置可覆盖）

| 子代理 | 数量/运行 | 模型档位 | 工具 | 输入预算 | 输出预算 | 轮次 |
|---|---|---|---|---|---|---|
| BugHunter | 每视角 1，按 chunk 并行 | 标准 | read_file(scoped) | ≤24k/chunk | ≤4k（v1.3.2：2k→4k） | 3 |
| SecurityReviewer | **按 chunk 并行**（每 chunk 1 实例，聚合合并） | 强 | read_file+grep(scoped) | ≤24k/chunk | ≤2k | 3 |
| TestReviewer | 条件触发 | 标准 | read_file | ≤16k（2026-10-30：12k→16k） | ≤4k（v1.3.2：1k→2k→4k） | 2 |  
| ConventionReviewer | 条件触发 | 轻 | 无 | ≤8k | ≤1k | 1 |
| PerfReviewer | 条件触发，按 chunk | 标准 | read_file | ≤16k/chunk | ≤1k | 2 |
| VerifierAgent | M2:1 / M3:2异构 | 强(M2)；V1强+V2异构(M3) | read_file(scoped+) | **≤16k/文件批次**（代码类；context 类与索引在同一预算内裁剪，2026-10-30：12k→16k）；**bug 类调用点索引从 codeContext 预算内预留 ≤1000 token（v1.4.2，ADR-0015 修订）** | **≤2k**（含 refutation 对象；2026-10-30：1.5k→2k） | 2 |
| JudgeAgent | 仅仲裁/抽查 | 强 | read_file | **≤20k/案件**（分型组装同 verifier；**bug 类 + 调用点索引 ≤500 token**；2026-10-30：16k→20k） | **≤1.5k**（2026-10-30：1k→1.5k） | 2 |
| **CrossChecker**（v1.3.1 补行；v1.3.2 输出 1.5k→4k；v1.4 事件史） | M4f 触发，1 实例 | 标准 | read_file(scoped) | ≤16k（diff 精简视图 ~8k + **变更史/事件史合计 ≤4k**（事件优先 ≤2k）+ 产品段 ≤2k） | ≤4k（v1.3.2：1.5k→4k） | 2 |

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
