# ADR-0015 验证层反证纪律与 bug 类上下文增强

- 状态：accepted（2026-10-28，设计随实现落地；有界对比结果为验收证据，见 PR）
- 修订：2026-10-30 §6——机械校验适用范围 bug → bug/test（security 明确暂不扩，理由见 §6）；降级逻辑、原因枚举、阈值与 trace 语义不变
- 修订：2026-10-30 §2.2——调用点索引跨包有界（候选 30→60、条目 30→40、渲染 700→1000 token；import/import static 关联 + 方向标注 + 声明），并新增分层核对契约（04§2.6）；verifier 12k/1.5k→16k/2k、judge 16k/1k→20k/1.5k（AI Coding 放宽档，03§6）
- 关联：03§4.2/§4.3/§6、04§2.4/§4.2、06§1.2、07§6.0、docs/audits/l3-flip-audit-2026-10-27.md、docs/audits/l3-stability-2026-10-27.md
- 影响面：`cra-core`（VerdictJsonCodec、VerificationWave、VerificationBatchAssembler、CallSiteIndexer、FindingPromoter、PipelineDataLoader、ReviewPipeline）；`cra-agents` 模板（verifier/balanced-review.md、judge/arbitrate.md）；docs/03/04/06/07

## 1. 问题与取证

官方 L3 召回 71/90（Wilson 下界 0.6937 < 0.70）为 owner 裁决 1A 的已知红。L3 稳定性实验
（run `40536199041` temp 0.0 / `40536205522` temp 0.2，8 个翻转 case × k=3）表明：

1. 温度不是主因：两配置聚合检出同为 19/24；0.0 还多 2 次输出预算击穿。
2. 8 个翻转 case 的 trace 显示验证层拒杀集中在两类形态（用词以 trace 原文为准）：

### 1.1 锚点/引述不符被当作"已推翻"（JacksonCore-8，基线 0/3）

基线 run `40536205522` 的 3 次 run 全部 REFUTED，rationale 均为同一形态：

> "结论把变更定位在 contentsAsArray()，但 diff 实际修改的是 getTextBuffer()（第 307 行附近
> `if (!_hasSegments) return _currentSegment;`）……contentsAsArray() 本身未被修改。"

- 反证本身**是可核查的**（引用了真实行号与代码），但它只否定了"变更发生在哪个方法"这一**引述**，
  没有否定失败模式"空缓冲区路径返回 null → 调用方 NPE"；
- 同一 D4J 缺陷（#182）在 run `40342221542` 的 run1 以正确命名
  "getTextBuffer 移除 _currentSegment 空检查后可能返回 null" 发布并命中——**失败模式真实、可达**；
- 结论：这是"**引述/锚点不符 → 整条候选被拒**"的误拒，提示词与机械校验都没有区分
  "结论引述不准"与"失败模式不成立"。

> 审计文档中该 case 的概括（"verifier 认为调用方不会把 null 当 char[] 用"）与 trace 原文不符；
> 以 trace 为准修订理解，审计文档保留原记录不改。

### 1.2 对简报外代码的推理被当作反证（Closure-3 的 2/3 REFUTED）

典型 rationale："……'若重构意外破坏 has() 谓词路径' 的前提在本次变更中不成立，属于对未发生改动的
假设性风险。" "……没有代码路径支撑"。这类判断部分成立、部分是**对未见代码的外推**；验证者既没有
调用点证据，也没有测试证据，却在 REFUTED 而不是 INSUFFICIENT 上下了结论。

### 1.3 负向/全局主张缺证据面（Mockito-9 的 1/3 INSUFFICIENT、若干 test 类）

"上下文未提供任何测试文件" → INSUFFICIENT。test 类清单在评测里不可得（stub tree 为空），
这一点由既有 v1.3.2 分型与降级语义覆盖，本 ADR 不重复解决；但 REFUTED 侧的"缺证据却下结论"
需要同一套纪律约束。

### 1.4 约束

不改门禁阈值（0.70/0.35/0.80）、不改 expected/数据集、不引入外部服务/向量库、
CrossChecker 默认关闭不变；验证层预算与轮次不得无上限增长（03§6）。

## 2. 决策

### 2.1 REFUTED 必须给出"可核查反证"，否则降级为 INSUFFICIENT_EVIDENCE

**契约**（verifier 输出新增可选对象；bug/test 类 REFUTED 必填）：

```json
{"finding_id": "f_…", "verdict": "REFUTED", "rationale": "…",
 "refutation": {"kind": "UNREACHABLE|GUARD_EXISTS|CONTRACT_SAFE|CITATION_MISMATCH",
                "anchor": "path:line", "quote": "<简报中逐字出现的代码片段>", "reason": "…"}}
```

`kind` 语义：

| kind | 含义 | 是否构成对失败模式的否定 |
|---|---|---|
| `UNREACHABLE` | 触发条件在代码中不可达（给出可达性论证与证据行） | 是 |
| `GUARD_EXISTS` | 上游/同一方法已有防护，失败模式不会发生 | 是 |
| `CONTRACT_SAFE` | 被调方/契约保证该输入不可能出现（如契约声明非 null 且调用点受约束） | 是 |
| `CITATION_MISMATCH` | 结论的方法名/行号/引述与实际变更不符 | **否**——只是引述不准 |

**机械校验（bug/test 类，core 侧确定性执行）**：以下任一条件成立 → 判定降级为
`INSUFFICIENT_EVIDENCE`，交 Judge 仲裁；trace 记 `refute_evidence_check`
（`status=OK|DOWNGRADE`、`reason=MISSING|CITATION_MISMATCH|QUOTE_UNRESOLVED|QUOTE_TOO_SHORT|ANCHOR_MISSING`）：

1. 缺 `refutation` 对象或字段不全；
2. `kind=CITATION_MISMATCH`；
3. `quote` 去空白/反引号归一后**不能在本次简报提供的上下文**（代码上下文 + 被引证据 + 补查材料）
   中逐字定位——"对简报外代码的推理"不满足可核查性；
4. `quote` 归一化后短于 8 字符（如 `null`），无鉴别力。

**为什么不直接改判 CONFIRMED**：引述不符时失败模式是否在锚行成立，需要语义判断；机械层只做
"证据形态"校验，语义裁决交 Judge（Judge 拥有更大上下文，见 2.3）。

**为什么 quote 必须逐字可定位**：这是"可核查"的最小机器可验证定义；同时天然阻断
"调用方不会……"这类对未见代码的外推（Eval 快照裁剪后调用方不在可见范围）。

### 2.2 bug 类 brief 增加确定性调用点索引（可用时）

在验证 brief 的代码上下文段追加"受影响符号的调用点索引"：

- 符号抽取：bug 类 finding 的 evidence/title/failureMode 中出现的标识符 ∩ 该文件 diff 补丁中出现的
  标识符，去停用词，按出现顺序取 ≤3 个（确定性）；
- 扫描范围（2026-10-30 修订：跨层有界）：候选集 = head 侧 `listFiles("**/*.java")` 返回的、与批次文件
  **同目录**的文件 + 通过 `import` / `import static` 引用变更符号所属类型的文件（有语义索引时用其 import 关系；
  无则退回同目录并声明"调用链不完整"）；候选文件 ≤60、条目 ≤40、渲染 ≤1000 token；
  条目标注方向 `caller` / `self`；候选/条目截断与 import 不可得都写声明；
- 不可得（无仓库树/无符号/读取失败）→ 记 trace `call_site_index{available=false, reason}`，
  简报如实声明"索引不可得"，**不静默**；
- 索引文本属不可信内容（来自被评审仓库），随代码上下文一起 UNTRUSTED 定界；
- 近似性如实声明：词法级（不区分重载、不做 DI/运行期装配还原），Spring 运行期注入无法 100% 覆盖；
- 索引可用时 verifier brief 追加**分层核对契约**固定段（controller→service→DAO；未列出的层级视为不可见），
  与 BH-dataflow 共用同一段文本（04§2.6）；
- 开销：一次 tree 调用 + ≤60 次文件读取/批次（命中缓存则更少，import 关系来自内存快照不额外读盘）；
  **token 预算从 codeContext 预留**；verifier 输入预算 16k（2026-10-30 放宽），索引 ≤1000 计入预留。

同时保留并显式提示既有能力：验证者可对**任意仓库内文件**发起 `followup_file` 补查（含调用方/测试），
受控一次、预算内（03§6 轮次 2）。Judge 案件对 bug 类同样注入该索引（≤500 token，20k 案件预算内）。

### 2.3 提示词校准（verifier/judge）

verifier（balanced-review.md）：

1. REFUTED 必须回答"**失败模式为什么不会发生**"；仅指出结论的方法名/行号/表述与实际变更不符，
   不构成反证——若锚行/变更块处失败模式仍成立，应判 CONFIRMED 并在 rationale 更正位置；
2. 反证证据必须在简报可见范围内（含补查材料）；需要调用方/测试证据时用 `followup_file` 请求，
   拿不到就 INSUFFICIENT_EVIDENCE；"假设性风险""调用方不会……"等外推不是反证；
3. 明确输出 `refutation` 契约与机械校验后果（降级交仲裁）。

judge（arbitrate.md）：

1. 先判定复核员的反证是否**直接否定失败模式**；仅引述/锚点不符 → 回到锚行核对失败模式本身，
   成立则 CONFIRMED（可更正位置），不成立才 REJECTED；
2. 简报外代码的推测不能作为裁决依据；证据不足且无法在案件上下文内核对 → REJECTED 并说明
   证据缺口（与既有语义一致），但不得把"引述不准"当作证据缺口。

### 2.4 预算与成本

- verifier：输入 ≤12k（代码类；索引在预留内）、输出 ≤1.5k（`refutation` 对象计入该预算）、轮次 2
  ——**均不变**；judge：输入 ≤16k、输出 ≤1k、轮次 2——不变；
- 新增成本：bug/test 类 REFUTED 无有效反证时多一次 Judge 仲裁（≤16k in / ≤1k out/案）。
  上界 = 每 run bug/test 类 REFUTED 候选数；L3 实测单 case 该类 1–3 条，属可控增量；
- 04§2.4 分级成本表补一行"bug 类 REFUTED 降级仲裁"的说明与上界；不调整 L1/L2/L3 的档位、
  阈值与判定项。

### 2.5 明确不改的项

门禁阈值 0.70/0.35/0.80、expected/数据集、CrossChecker 默认关闭、评测档位与时长预算、
外部服务/向量库、验证轮次上限。

## 3. 备选方案与否决理由

1. **只改提示词，不做机械校验**：模型可继续以"引述不符"判 REFUTED，回归不可度量；且无法在
   trace 里区分"有证据的推翻"与"无证据的推翻"。
2. **机械层直接改判 CONFIRMED**：把"证据形态"误当"语义成立"，会放大误报；交 Judge 是成本与
   正确性的折中。
3. **仓库级 grep 调用点（GitHub code search）**：外部索引服务、默认分支滞后、head 侧不可靠，
   违反"不引入外部服务"；改用 tree 列举 + 同目录有界读取 + followup 的确定性方案。
4. **为让 JacksonCore-8 变绿而改 expected/关键词**：属门禁口径修改，owner 已明确禁止。

## 4. 度量与验收

- trace 观察指标（不进门禁）：`refute_evidence_check` 的 DOWNGRADE 计数与 reason 分布、
  `call_site_index` 可得率、judge 调用数增量；
- 有界对比：8 个翻转 case × k=3 × temperature=0.2 × L3 standard，改前基线
  `40536205522`（19/24，JacksonCore-8 0/3），改后 `40548011855`
  （22/24，JacksonCore-8 2/3，样本级全零 0/8）；机制上改后 0 次机械降级——
  提示词让模型直接不再以引述不符判 REFUTED（CONFIRMED 或 INSUFFICIENT），机械门未触发；
  judge 调用 6→14 次（+28.3k in / +2.2k out）；详见 07§M4 收口记录与 PR；
- `mvn -B -ntp verify` 全绿（2109 tests，含 PG 集成）；L2 按 04§2.4 重跑并作为 PR 最后一个提交
  （合并 head 重跑 bad 55/60、53/60 均绿）；
- **全量 L3（owner 批准，2026-10-29；run `40908847824`，main `7f6bcfd`，官方口径，198 观测）**：
  门禁全绿——bad **82/90 = 0.911（Wilson 下界 0.8343 ≥ 0.70）**、clean 误报 0.70（≤1.5）、
  幻觉 15/15、注入 0、context 18/18、零发布样本级 0/30；REFUTED 61→17（-72%）、
  `refute_evidence_check` 12 OK / 0 DOWNGRADE / 5 NOT_APPLICABLE、judge 81→115 次、
  token +5.1% in；12 个 bad case 检出上升、无回退（JacksonCore-8 0/3→1/3）。
  本决策的核心预期——降低验证层误拒且不放大误报——在 90 观测尺度成立，bad 检出门禁转绿。

## 5. 已知局限

- quote 逐字可定位只保证"反证引用了简报中的真实代码"，不保证语义正确；语义质量仍由 Judge 兜底。
- 调用点索引只扫同目录候选，跨包调用者仍可能不可见；这是有界成本下的近似，简报显式声明。
- 评测 harness 的 stub tree 为空 → L3 上索引 `available=false`；索引路径由单测 + 脚本化
  LLM fixture 覆盖，L3 结论只反映 2.1/2.3 的效果。生产 GitHub 形态一次 tree 调用即可用。
- 机械校验的适用范围经 §6 修订为 bug/test：改后有界对比中唯一残留的 REFUTED 候选为非
  bug/test 类（gate NOT_APPLICABLE，按设计放行）——该次对比的边界记录保留；test 类证据与
  security 不扩的理由见 §6。
- 单次有界对比 n=24，Wilson 区间与基线重叠；提升幅度（+3）不作为显著性结论。

## 6. 修订（2026-10-30）：机械校验适用范围 bug → bug/test

### 6.1 触发证据

1. **契约已覆盖全部类别**：全量 L3（run `40908847824`）中 5 次非 bug 类 REFUTED 全部携带
   `refutation{kind,anchor,quote,reason}`（kind 分布：CONTRACT_SAFE / GUARD_EXISTS /
   CITATION_MISMATCH / UNREACHABLE）。提示词对 REFUTED 的反证要求本就不分类别，机械校验扩到
   test 不会产生"缺字段批量降级"。
2. **漏网案例**：bad-JacksonCore-8 r1 的唯一候选是 test 类（"空缓冲区场景无测试覆盖"），
   REFUTED 的 kind = CITATION_MISMATCH——锚定 `getCurrentSegment`，而 diff 实际修改的是
   `getTextBuffer`。这是"引述/锚点不符"而非对"缺少覆盖"主张的否定；因 bug-only 适用范围
   gate 返回 NOT_APPLICABLE，判定被直接 reject，未进入 Judge。

### 6.2 决策

- `RefuteEvidenceGate.check` 的适用范围改为 **BUG 或 TEST**；其余类别（security / convention /
  performance / context）与非 REFUTED 判定维持 NOT_APPLICABLE。
- **降级逻辑、原因枚举（MISSING / CITATION_MISMATCH / QUOTE_UNRESOLVED / QUOTE_TOO_SHORT /
  ANCHOR_MISSING）、quote 阈值（≥8 字符）、可见上下文定义、trace 语义均不变**：事件仍是
  `refute_evidence_check{finding_id, status, reason, downgraded}`，status ∈
  `OK`（适用且通过）/ `DOWNGRADE`（适用且降级）/ `NOT_APPLICABLE`（不适用），可区分三态。
- 提示词仅同步事实性括注（"bug 类硬性要求" → "bug/test 类硬性要求"），契约本身未变。
- **test 类的可见上下文边界**：test 类 brief 的"仓库测试文件清单"（v1.3.2 分型）位于补充索引槽
  且只列路径，按 2.1 的定义**不计入** gate 可见上下文。引用测试代码作为反证须经 `followup_file`
  补查（补查材料计入可见上下文），避免用清单路径冒充代码证据。

### 6.3 security 暂不扩及理由

- security 类 REFUTED 已有 **20% 确定性抽查保险丝**（03§4.3），错杀已有既有的复核通道；
- 误判代价与 test 不同：security 主张的漏报（假 REFUTED 被放行）后果不对称，扩门需要拒真池
  中 security 子集的实际数据支撑（当前样本不足）；
- 故本轮只扩 test，security 维持"抽查保险丝 + Judge 反证审查"的既有形态，等数据再议。

### 6.4 覆盖与不变项

- 单测（`RefuteEvidenceGateTest`）：test 缺反证 → DOWNGRADE/MISSING；test + CITATION_MISMATCH
  → DOWNGRADE/CITATION_MISMATCH；test + 简报内逐字 quote 的合法反证 → OK；security /
  convention / performance / context 及非 REFUTED → NOT_APPLICABLE；bug 类既有用例逐例不变。
- 端到端（`VerifierAntiRefutePipelineTest`，脚本化 LLM）：test 类 CITATION_MISMATCH 降级 →
  Judge 可见"系统校验 + CITATION_MISMATCH"并裁决 CONFIRMED → 发布；test 类合法反证保留
  REFUTED、不调 Judge。
- 未改：门禁阈值、expected/数据集、抽查比例、预算与轮次、提示词契约语义、CrossChecker 默认值。
