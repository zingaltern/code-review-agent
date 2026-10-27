# ADR-0014 验证层反证纪律与 bug 类上下文增强

- 状态：accepted（2026-10-28，设计随实现落地；有界对比结果为验收证据，见 PR）
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

**契约**（verifier 输出新增可选对象；bug 类 REFUTED 必填）：

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

**机械校验（bug 类，core 侧确定性执行）**：以下任一条件成立 → 判定降级为
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
- 扫描范围：head 侧 `listFiles("**/*.java")` 返回的、与批次文件**同目录**的候选文件（≤30 个），
  逐文件读取（复用 run 级 `path|sha` 内容缓存），按 `\b符号\b` 匹配行，条目 ≤30、渲染 ≤700 token；
- 不可得（无仓库树/无符号/读取失败）→ 记 trace `call_site_index{available=false, reason}`，
  简报如实声明"索引不可得"，**不静默**；
- 索引文本属不可信内容（来自被评审仓库），随代码上下文一起 UNTRUSTED 定界；
- 开销：一次 tree 调用 + ≤30 次文件读取/批次（命中缓存则更少）；**token 预算从 codeContext 预留**，
  verifier 输入预算不变（≤12k）。

同时保留并显式提示既有能力：验证者可对**任意仓库内文件**发起 `followup_file` 补查（含调用方/测试），
受控一次、预算内（03§6 轮次 2）。Judge 案件对 bug 类同样注入该索引（≤500 token，16k 案件预算内）。

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
- 新增成本：bug 类 REFUTED 无有效反证时多一次 Judge 仲裁（≤16k in / ≤1k out/案）。
  上界 = 每 run bug 类 REFUTED 候选数；L3 实测单 case 该类 1–3 条，属可控增量；
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
  `40536205522`，改后同参数各跑一次；指标 = 检出、REFUTED/INSUFFICIENT 计数、
  JacksonCore-8 是否 CONFIRMED、零发布观测、token；
- `mvn -B -ntp verify` 全绿；L2 按 04§2.4 重跑并作为 PR 最后一个提交；全量 L3 只在
  owner 批准后执行。

## 5. 已知局限

- quote 逐字可定位只保证"反证引用了简报中的真实代码"，不保证语义正确；语义质量仍由 Judge 兜底。
- 调用点索引只扫同目录候选，跨包调用者仍可能不可见；这是有界成本下的近似，简报显式声明。
- 评测 harness 的 stub tree 为空 → L3 上索引 `available=false`；索引路径由单测 + 脚本化
  LLM fixture 覆盖，L3 结论只反映 2.1/2.3 的效果。生产 GitHub 形态一次 tree 调用即可用。
- 机械校验对非 bug 类不生效（security 类 REFUTED 仍走既有抽查保险丝）；是否扩展到全部类别
  留待数据（拒真池）积累后决定。
