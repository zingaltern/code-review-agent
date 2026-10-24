# 04 · 上下文工程与提示词设计

> 文档版本：v1.0（2026-10-25）
> 前置阅读：`02-architecture-design.md`、`03-multi-agent-design.md`

---

## 1. 分层上下文模型

上下文是评审质量的燃料，也是成本的大头。组装器（ContextAssembler）按层裁剪：

| 层 | 内容 | 获取方式 | 何时注入 |
|---|---|---|---|
| L0 | 变更 diff（unified，带 hunk 头与行号） | GitHub compare API / git diff | 总是，全部角色 |
| L1 | 变更文件**全文**（base 与 head 两版） | Git blob API | 总是（预算内）；预算外时给"变更 hunk ±40 行"窗口 |
| L2 | 依赖切片：直接 import 的本项目文件、被变更函数的调用方（grep 即可，M 阶段不做索引） | 适配器文件树 + 受限 grep | 按角色：BugHunter-dataflow / Security / Verifier 默认注入；其余按需 |
| L3 | 仓库规范：`REVIEW.md`、`AGENTS.md`/`CLAUDE.md`（截取相关目录段）、依赖清单（安全审查用） | 仓库根/目录读取 | 相关时注入 |
| L4 | 历史记忆：该 PR 已发布 finding 指纹、该文件近期 confirmed finding | ReviewStore | 收敛控制与 pre-existing 判定用；**不注入**给新 reviewer（防锚定） |

裁剪优先级（预算不够时依次丢弃）：L2 非核心切片 → L1 的 base 版全文（保 head）→ nit 类角色的整段任务。**L0 与变更文件 head 全文永不丢弃。**

## 2. Token 预算

- 每 brief 组装后计算 token 估算（字符/3.5 近似即可，不依赖分词器），与该角色预算比较后决定裁剪；
- run 级总预算默认 400k token（Standard 深度），超出触发降档（减视角、缩切片）而非失败；
- 一切裁剪决策记入 trace（哪层被丢、为什么），保证"评审覆盖了什么"在 Summary 中可如实声明。

## 3. REVIEW.md 规范（仓库级配置，给代码 owner 的接口）

仓库根或任意目录可放置 `REVIEW.md`，采用 Markdown 标题约定解析（不发明新 DSL）：

```markdown
# Review Policy: <目录/仓库范围>

## What counts as failure        <!-- 该范围内的硬性失败标准，评审必报 -->
- 所有对外 API 必须处理 LimitExceeded（参考 legacy/OrderService 的反例）

## Nit budget: 3                 <!-- 单次评审 nit 上限，缺省 3，0 表示禁止 nit -->

## Priority paths                <!-- 大 diff 裁剪时优先保留的路径 glob -->
- billing/**
- auth/**

## Skip paths                    <!-- 完全跳过（生成物、vendor 等） -->
- generated/**
- '**/*.snap'

## Evidence requirements         <!-- 证据要求覆写 -->
- 必须引用 diff 行号；不接受"可能有"表述

## Conventions                   <!-- 约定清单，ConventionReviewer 逐条对照 -->
- 新增公共方法必须有 Javadoc @apiNote
- 禁止直接使用 Fastjson（历史包袱，用 Jackson）
```

解析器（cra-context 内 `ReviewPolicyParser`）：按标题分节、固定关键字识别、其余视为自由文本整段注入对应子代理 brief。解析失败按最小默认策略运行并在 Summary 警告——**配置错误不能阻塞评审**。

## 4. Prompt 模板体系

模板放 `cra-agents/src/main/resources/prompts/`，占位符用 `{mustache}`，**模板即代码**：改动走 PR + 评测回归（见 06）。

```
prompts/
├── shared/
│   ├── output-contract.md      # 结构化输出契约（所有子代理共享的 JSON 说明与反例）
│   └── evidence-rules.md       # 证据规则：行级引用、失败模式重述、空结果合法
├── reviewer/
│   ├── bug-hunter/{boundary,concurrency,dataflow,resource}.md
│   ├── security.md  test.md  convention.md  perf.md
├── verifier/
│   └── refute.md               # 对抗式证伪指令
└── judge/
    └── arbitrate.md
```

### 4.1 BugHunter 模板骨架（以 dataflow 视角为例）

```markdown
# System
你是资深代码评审专家，专注【数据流正确性】视角。你将看到一次代码变更。
你的任务：追踪变更中每个输入的变换路径，找出会导致**错误行为**的断裂点。

## 铁律
1. 只报告你能给出 文件+行号 证据、并说清【触发条件→错误结果】的问题；
2. 风格/命名/偏好类问题一律不报（本系统有独立约定检查）；
3. 没有问题是完全合法的输出——宁缺毋滥；
4. 怀疑问题在变更前就存在时，置 pre_existing_suspect=true。

## 输出契约
{>output-contract.md}
{>evidence-rules.md}
输出 JSON 数组，元素 schema：{finding_schema}

# User（由 brief 组装）
## 变更说明
{pr_title} / {commit_messages}
## 仓库评审策略（本目录适用段）
{review_policy_section}
## Diff
{diff_l0}
## 变更文件全文
{files_l1}
## 相关代码切片
{slices_l2}
```

### 4.2 Verifier 模板骨架（refute.md）

```markdown
# System
你是独立代码审计员。你将看到一条【待验证的代码评审结论】。
你的任务是**尝试推翻它**：假设它是错的，去找反证。

## 判定规则
- CONFIRMED：你独立定位到证据行，并用自己的话重述了失败模式（禁止照抄结论原文）；
- REFUTED：你找到具体反证（引用行号），如已有的防护、错误的行号引用、不可能触发的路径；
- INSUFFICIENT_EVIDENCE：所给上下文无法判断，且你用 read_file 补查后仍无法判断。

## 铁律
- 你看不到也不需要知道这条结论是谁提出的；
- 结论中提到的证据必须自己重新定位核实，不得采信其描述。

# User
## 待验证结论
{finding_assertion_only}   ← 只含断言与位置，不含发现者推理
## 代码上下文
{files_l1} {slices_l2}     ← 与发现者同级或更全
```

### 4.3 Judge 模板骨架（arbitrate.md）

```markdown
# System
你是终审仲裁员。两名独立审计员对同一条评审结论给出了相反判定。
其中一人可能是对的。你的任务是裁决哪一方的证据链成立——不是折中，不是求共识。

## 输出
CONFIRMED / REJECTED / DOWNGRADE_TO_NIT + 裁决理由（引用双方证据行号）

# User
{finding_assertion}
## 审计员 A 的判定与证据 {verdict_a}
## 审计员 B 的判定与证据 {verdict_b}
## 代码上下文 {context}
```

### 4.4 Summary 模板（发布到 PR 的总评）

固定结构：变更概述（模型 2 句话）→ 按严重度排序的 finding 列表（Important / Nit / Pre-existing 分组，各条带 file:line 链接）→ **覆盖声明**（实际评审的文件范围、被裁剪的部分、降级情况如实说明）→ 空结果时的明确陈述："本次变更未发现需要修复的问题，已按 X 视角审查 N 个文件"。

## 5. Prompt 反模式清单（评审 prompt 评审标准）

开发与改动 prompt 时自查，命中任一条视为不合格：

1. ❌ 允许无证据输出（"如果你觉得有问题就报"）；
2. ❌ 惩罚空结果（隐含"必须找出问题"的压力 → 强制找茬 → 噪声）；
3. ❌ 让 reviewer 输出 approval/评分（本项目只产出 finding，见 01§4.2 非目标）；
4. ❌ 在 verifier prompt 中出现发现者推理过程或"该发现由 XX 模型给出"；
5. ❌ 把整个仓库塞进上下文（应分层按需）；
6. ❌ 风格类、清理类建议进入默认发布集（Anthropic A/B 结论：噪声大于价值，除非 REVIEW.md 显式要求）。

## 6. 结构化输出实现要点

- 使用 OpenAI 兼容 `response_format=json_schema`（或 function calling）强约束；
- `Finding` 每个字段在 schema 中带 description（模型对字段语义理解主要靠它）；
- 解析失败 → 携带校验错误信息重试（≤2 次）→ 仍失败丢弃该子代理输出并记 `SCHEMA_VIOLATION`；
- **禁止**在重试中放宽 schema；禁止手工正则修复 JSON。
