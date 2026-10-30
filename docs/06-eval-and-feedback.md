# 06 · 评测体系与反馈闭环

> 文档版本：v1.3.1（2026-10-25，响应第四轮评审缺陷 ④⑥）
> **v1.2**：处置信号修正（删 GraphQL-only resolve）；聚合 Wilson CI 门禁 + 三级分级；Defects4J 双侧数据集；转化漏斗护栏。
> **v1.2.1**：Wilson 数值按正确公式修正；L1 升级语义与 L2 聚合护栏；阈值分档；Defects4J 净化工序。
> **v1.3.1**：对抗集增**第 4 类 context 用例**（v1.3 CrossChecker 的召回此前仅靠 M4f-lite 单一 fixture 自证，无度量归属）；L1 注入用例口径与 04§2.4 统一为**负向不变量断言**（seed best-effort 局限）。

---

## 1. 三层度量体系

| 层 | 问题 | 数据源 | 频率 |
|---|---|---|---|
| L1 在线噪声度量 | 发布的 finding 人类怎么看？ | **GitHub 账本**（bot 评论的 reply/reaction + 后续 commit） | 每周 |
| L2 离线评测集回归 | 改动 prompt/模型后质量变了吗？ | 本地 fixture 数据集，**聚合 CI 判定** | 按改动风险分级（04§2.4） |
| L3 运行健康度 | 系统稳吗、贵吗、沉默吗？ | run stats + trace artifact | 持续 |

### 1.1 L1 在线噪声度量（GitHub 账本实现；信号表 v1.2 修正）

**信号可得性事实**：PR review thread 的 resolve 状态是 GraphQL-only，REST v3 无读取端点——**本项目锁 REST v3（05§2.3），resolve 信号不采**；v1.1 的"不改直接 resolve = intentional（自动）"不可实现，已删。

修正后的处置分类（每条已发布 finding，账本 `cra:` 标记锚定）：

| 处置 | 判定信号（按优先级） |
|---|---|
| **fixed** | 涉及行在后续 commit 被修改且与 suggestion 模式匹配（自动初判） |
| **wrong** | 回复含 `:wrong:` / `not-a-bug` 标签（REST replies 端点可读） |
| **intentional** | 回复 `:intentional:` 标签；**或** PR 合并时涉及行未被修改（合并事件回填二分类：改了→fixed、没改→intentional/ignored） |
| **ignored** | 合并 7 天后仍无任何信号（自动，分母含此噪声，报表注明口径） |

**run 级满意度信号（v1.2.1 载体明确后启用）**：Summary 改为 issue comment 后有独立 reaction API（05§2.2）——作者对 Summary 的 👍/👎 记录为该 run 满意度，进周报趋势，不参与单条 finding 处置分类。

`cra-eval report --repo … --since 7d` 本地定时跑：经 `CommentSource` 端口（05§2.1，ADR-0005）拉账本与处置信号聚合（双端点合并，05§2.3；周频低量，限流无虞——评测 job 本身用离线 fixture，不拉 GitHub）。

核心指标与**最小样本量门槛（v1.2 新增——个人项目月均几十条 finding，小样本指标在噪声内掷硬币）**：

```
发布精确率 = (fixed + intentional) / 有信号样本      目标 ≥80%；评估前提：有信号样本 ≥30，不足则只报数值不下结论
误报率     = wrong / 有信号样本                       目标 ≤15%；同上
拒真率     = REFUTED 中被 Judge/事后推翻比例            评估前提：拒真评估池 ≥20（03§4.3 T3.6 门槛同源）
候选→确认转化率 = candidates→confirmed→published 漏斗 + suppressed_by_truncation   每周趋势
零发布率   = finding=0 的 run 占比                     趋势护栏：连续两周上行触发复盘
```

#### 1.1.1 实现注记（T3.4/T3.5/T3.6，2026-10-26 落地）

- **取数面**：`cra-eval report` 经 `CommentSource` 端口（05§2.1，ADR-0005，构造器注入）拉账本；PR 发现用
  `listPullRequests`（GitHub `GET /pulls?state=all&sort=updated`），账本用
  `GET /pulls/{n}/comments` + `GET /issues/{n}/comments` 双端点合并；人类 reply 从同一 pulls/comments 端点按
  `in_reply_to_id` 关联 bot 评论；Summary 满意度用 `GET /issues/comments/{id}/reactions`。**resolve 仍不采**（GraphQL-only）。
  离线回放 `--ledger-fixture` 走同一聚合路径（测试/脱敏样例），报告 `data_source` 如实标注。
- **处置分类优先级（实现口径）**：① reply 标签（同一评论多条时取最新；同刻按 wrong > intentional > fixed）；
  ② 合并后锚定行被后续 commit 改动 → fixed（自动初判：`compare(评论 commit…PR head)` 的 LEFT 侧 hunk 覆盖锚定行；
  重命名/删除/二进制按整文件"涉及"处理）；③ 合并满 7 天、行未改且无标签 → ignored；④ 合并未满 7 天 / 未合并 /
  compare 不可得 → pending（**不进分母**）。06 原表"合并时行未改 → intentional"与"7 天无信号 → ignored"存在口径重叠；
  实现以 **ignored 承接"合并未改"的自动判定**（intentional 只来自显式 `:intentional:` 标签）——否则 ignored 永远为 0，
  "分母含此噪声"的声明失去对象；两套分母同时输出，口径差异可见。
- **两套分母**：主口径 = fixed+wrong+intentional+ignored（06§1.1 字面"分母含 ignored 噪声"，门槛/T3.6 判定用此）；
  附口径 = fixed+wrong+intentional（只含有向处置）。两套都输出（JSON + 文本），避免单一口径掩盖噪声。
- **fixed 是初判**：行变更只证明"作者动过"，不证明"按建议修复"；报告逐条给出信号（reply 标签 / line-changed /
  merged-unmodified / unknown-change），供人工复核。Summary 降级的 finding 无独立评论、无法锚定信号，**不参与处置分母**。
- **拒真池与漏斗来自 trace**（账本 run 标记只含 published/suppressed，无 candidate/confirmed/REFUTED）：解析
  `promotion` / `judge` / `run_end` 事件；dry-run 不计。拒真评估池 = Judge 可读的 REFUTED 抽查样本
  + 事后 badcase（label=missed 且原判定 REFUTED）——按 finding_id 去重，Judge 不可得样本不进池。门槛 20 不变。
- **零发布率与满意度来自账本**：零发布率 = run 完成标记中 `findings=0` 占比；连续两周上行需本期 + 两期历史报告
  （`--previous`），不足三期标"趋势不可判定"。满意度 = reaction 落在 run 标记的 Summary 评论上（👍/👎），
  不参与单 finding 分类。
- **T3.6 门槛评估**：`report` 的 `dual_verifier_gate` 段输出两个条件和结论；样本不足时条件 `met=false` 且
  verdict 含"样本不足，不下结论"。**当前不开启**（首份评估见 07 T3.6 记录）——不得为"完成 T3.6"放松门槛；
  数据触发后才实施 `require-distinct-model-family` 硬校验 + 语义去重。
- **离线与依赖**：`report` 本地跑无服务依赖；真实模式需只读 token（`--token` / `GITHUB_TOKEN` / `GH_TOKEN`）；
  `--trace-dir` 指向本地 `.cra/runs` 或 CI artifact 解包目录。平台取数失败 → 退出码 2，不产出"空报告假装成功"。

### 1.2 L2 离线评测集与统计化门禁（v1.2 重写判定口径）

**数据集**（构成与来源见 §3）：

1. **bad-PR 集**：Defects4J buggy 侧生成的缺陷引入 diff + 标注 expected findings；
2. **clean-PR 集**：Defects4J fixed 侧 + 项目 git 历史的良性改动（真实代码变更，非纯文档——纯文档对代码评审器是平凡输入，分母为零）；
3. **对抗集**：微型 fixture（≤3 文件）——① 幻觉诱导；② **注入用例**（diff/注释藏指令，零容忍）；③ 拒真陷阱；④ **context 用例（v1.3.1 新增）**：会话超时案例模式构造（diff 本身无缺陷 + REVIEW.md Known incidents 含矛盾条目 + 上下游路径含相关 commit）→ 期望 CrossChecker 检出 category=context——context 召回自此有门禁度量归属，不靠 M4f-lite 单一 fixture 自证（03§3.1 局限声明）。

**判定规则（聚合统计，v1.2.1 数值与口径修正）**：

```
观测单元 = (样本, run) 的 0/1；全部样本 × k 次运行聚合为一个比例

├─ bad-PR 种子缺陷检出率：聚合比例的 Wilson 95% 置信区间
│    下界 ≥ 阈值才通过（阈值分档维护：L3 standard 档 M2: 0.60 / M3: 0.70；
│    L2 quick 档检出率天然更低，各档维护独立基线，禁止跨档比较）
│    功效核算（v1.2.1 按正确 Wilson 公式修正）：p=0.85、n=12×3=36 观测
│      → Wilson 下界 = 0.700（v1.2 误写 0.733——那是 Wald 公式的结果）；
│      通过需 p̂ ≥ 28/36，P(X≥28 | Bin(36,0.85)) ≈ 0.92–0.93
│      （合格系统随机挂验收概率 <8%，仍远优于 v1.1 联合判定的 ≈0.61）
├─ clean-PR 误报：聚合每 run 平均误报数 ≤ 基线 + 0.5 条（容差随分母声明）
├─ 对抗集 ①：聚合拒绝率 ≥ 80%（幻觉用例）
├─ 对抗集 ②（注入）：CI 内 L1 用**负向不变量断言**（04§2.4——seed 是 best-effort，
│    快照全等不可依赖；断言"输出与发布集合不出现注入目标行为"，天然抗抖动）；
│    L2/L3 保持行为判定零容忍——任意一次目标行为出现即失败
├─ 对抗集 ④（context，v1.3.1）：M4f-lite 起纳入 L3 门禁，聚合检出率随报告输出
│    （M4f 未启用时跳过不计入分母）
└─ 零发布护栏（v1.3.2 M3 修订：**样本级**）：全部运行零 confirmed 的**样本**占比 ≤ 1/12
     （30 样本 ≤2 个、12 样本 ≤1 个）——某样本 k 次运行全部零 confirmed = 系统性沉默，
     这是护栏的设计本意；v1.2.1 的观测级口径在 LLM 运行间波动下必然假红（M2 4/36、
     L2 12/60、L3 11/90 的零发布观测均无一样本级全零，全部是单次波动）。观测级占比
     保留为报告观察指标（趋势用），不参与门禁判定
```

**分级执行（04§2.4，v1.2.1 语义修正）**：L1 快检**红了不阻塞、只升级**（自动追加 L2 复核，L2 也红才阻塞）；注入用例固定种子快照对比；L2 零发布护栏聚合口径；本地跑 + 报告哈希的**荣誉制局限如实承认**（防篡改不防伪造，彻底消除待 M4b 服务端签名）。**门禁权威优先于门禁频率**：宁可 L2 降档到本地跑，不可让强制 CI 全量门禁因成本被绕过——被绕过的门禁等于没有门禁。

**M3 判定实现注记（T3.1，2026-10-25）**：`cra-eval` 的报告内嵌门禁结论（`gate.passed` + 逐项 `checks`，状态 `pass|red|skipped|reported`），文本与 JSON 同源；阈值分档如下（各档独立基线，禁止跨档比较）：

| 档位 | 本次样本面 | bad 检出（Wilson 95% 下界） | 其他判定项 |
|---|---|---|---|
| L1 快检 | 注入 3 + 幻觉 2，k=1 quick | 不判定（skipped） | 注入违例 = 0；幻觉拒绝率 ≥0.80；固定子集完整性（注入 ≥3、幻觉 ≥2） |
| L2 复核 | bad ≥12 × k=2 quick（扩集后 bad 30 × k=2 = 60 观测） | **≥0.35**（M3 扩集后重冻结：30 case 实测 31/60=51.7%、下界 0.393，原 0.40 基线即红；0.35 留 ≈4pp 波动余量，改坏 prompt 实测 0.067 仍可捕获） | clean 每 run 均值 ≤1.0+0.5（分母 = 完成观测）；零发布**样本级** ≤1/12 |
| L3 全量 | bad ≥12 × k=3 standard = ≥36 观测（T3.2 扩集后 ≥90） | ≥0.70（M2 为 0.60） | 同上 + 幻觉 ≥0.80 + 注入零容忍 + context（M4f 启用时 ≥0.80） |

- **检出数值锚点（单测锁定）**：32/36 = 88.9% → Wilson 下界 0.747（M2 实测，过 M3 0.70）；30/36 = 83.3% → 下界 0.681（过 M2 的 0.60、不过 M3 的 0.70）。即阈值升级后同一表现会被判红——这是 M3 的意图，不是缺陷。
- **clean 误报**：基线 1.0 条/run 为 M3 首设（本节只规定"基线 + 0.5"形式，未规定基线数值；M1/M2 报告未单列该基线），首次 clean 实跑后由 owner 冻结（M3 验收 1 的还原运行实测 0.69 条/16 观测，低于首设基线）；分母 = 完成观测——失败 run 不计 0 误报，避免"失败越多误报越低"的沉默激励（07§6 纪律 6）。
- **零发布护栏**：口径未改（观测级 ≤1/12）。M2 实测 4/36 = 11.1% 是已知红；M3 在未改 prompt 的代码上实测 L2 24 观测 3/24 = 12.5%（eval run 40207399714），验收 1 的还原运行 5/24 = 20.8%，同样是红——按 owner 纪律**如实呈现、不自行放宽**（口径修订须走本节定点修订，不是评测门禁线的职责）。
- **context 类**：M4f 未启用时 `context_detection` = skipped、不计分母；启用后按 ≥0.80 首设阈值判定（对抗 ④ 只规定"纳入 L3 门禁"，未给数值，取对抗 ① 同档）。
- **失败 run**：全部观测无完成标记 → `run_failures` 红（不得静默为绿）；部分失败 → `reported`（不参与判定，数值含 0 贡献并在报告声明）。
- **bug-only 检出（v1.3.2，观察指标）**：只计入 category=bug 的 expected 命中；`test` OR 条目（06§3 规则 4）的贡献不计入——用于区分「发现 bug 面」与「仅指出缺测试」，**不进任何门禁判定**（报告字段 `bug_only_detection`，文本输出同名行）。
- **验证层反证纪律观察项（v1.4，ADR-0015）**：trace 的 `refute_evidence_check`（bug/test 类 REFUTED 的 `OK|DOWNGRADE` 与 reason 分布；test 类于 v1.4.2 纳入）、`call_site_index`（`available`/`entries`）与 judge 调用数增量作为**报告观察指标**输出，用于复盘误拒与索引可得率；**不参与门禁判定**，阈值/口径不变。降级后的判定按 INSUFFICIENT 语义进入既有仲裁路径，门禁只消费最终 confirmed/发布集合。
- **per-run 指标**：报告新增 `run_metrics`（`tokens_in`/`tokens_out` 的 sum 与 mean、`latency_ms` mean，来源 trace 的 `run_end`）与逐观测 token/耗时字段——07§3 验收 6 的 "≈330k/500k=66%" 算术对照自此有字段可查，不再只能粗判。无 `run_end` 的观测不进均值分母（`observations_with_stats` 标注）。
- **扩集后复核（M3 已执行，v1.3.2）**：T3.2 合并后 L2 实测 31/60=51.7%（下界 0.393）→ **L2 基线重冻结为 0.35**；**零发布护栏改样本级**（owner 裁决，依据：观测级在运行间波动下必然假红，三项实测均无样本级全零）。clean 基线 1.0 维持（L3 实测 0.60、L2 0.875 均低于基线）。

**T3.1/T3.3 验收 1 证据（2026-10-25，Actions workflow_dispatch：`set=l2, k=2, depth=quick, tier=l2`）**：

| 运行 | commit | 原始报告 SHA-256 | bad 检出（Wilson 下界） | clean 平均误报（分母） | 零发布 | 最终门禁（0.40 重判） |
|---|---|---|---|---|---|---|
| 改坏 verifier（balanced-review 加"总是 REFUTED"） | 6f8ad57 | `1580cb7281cde8c29057a264dbf6b3a768e0b56346dcd6c92fb8e295d019a97e`（run 40208634584） | 4/24 = 16.7%（0.0668） | 0.00（16 观测） | 19/24 = 79.2% | **红**（检出 + 护栏） |
| 还原（`git revert`） | d6d0fb7 | `a19c9c5dd195afa33f3425b86178621afd0cf43e17bcd8b15f3b3ddb061c34f8`（run 40209488028） | 17/24 = 70.8%（0.5083） | 0.69（16 观测） | 5/24 = 20.8% | **红**（仅护栏；检出/clean 已绿） |

结论：改坏 prompt 使检出 Wilson 下界从 0.508 跌破 0.40（0.067），门禁红；还原后检出与 clean 两项回绿，但**整体仍红于零发布护栏（5/24 > 1/12）**——与 M2 的 4/36、本轮基线的 3/24 同类，属既有 quick 档沉默基线（正常运行间波动），不是 prompt 改动引入。按 owner 纪律未放宽口径：整体"还原 → 绿"取决于护栏口径修订（本节定点修订）或修复 quick 档沉默（T3.2 数据集/视角路由），不是评测门禁线可自行决定的事项。报告哈希为原始产物；阈值变更后的结论由 `cra-eval gate --report <json> --tier l2` 离线重判（不重跑 LLM，只重判同一批观测）。

prompt/模型改动与 run 关联（`prompt_template_versions`），线上问题定位到版本。

### 1.3 L3 运行健康度

延迟 p95、成本均值、降级率、schema 违规率、验证分歧率、零发布率趋势、**候选→确认转化漏斗与截断丢弃率**（`suppressed_by_truncation > 0` 的 run 占比趋势上行 = 预算压力正在吃掉召回，梯度沉默护栏）、锚定降级率、账本可疑标记计数（`LEDGER_SUSPECT`）。

## 2. 反馈闭环（漏报怎么办）

```
线上漏报/误报 badcase
  → 复盘 trace artifact：该子代理当时的 brief
  → 归因：
     上下文缺失   → ContextAssembler 补切片规则
     规则缺失     → REVIEW.md 增补（owner 走审批，04§3.2）
     视角缺失     → 新增/调整 BugHunter 视角模板
     模型能力     → 调整模型路由档位
     重复出现     → 源头修：lint 规则 / CI 检查，不在评审反复提
  → badcase 固化为评测集新条目：cra-eval badcase add <run_ref> <finding_id> --label missed|false-positive|relabeled
     （v1.2.1 增 relabeled：评测样本本身标错的出口——clean 集复核改标走此标签，
       改标后样本换集、当次统计作废重跑，防止误标样本的"FP"污染向沉默优化施压）
  → 回归门禁防再犯
```

`run_ref` 支持 artifact 引用（CI 形态）与本地路径（本地模式）。

**实现注记（T3.5）**：`cra-eval badcase add` 先解析 run trace（`run_start` 的 repo/base/head/pr、`promotion`
的 verifier 判定），再定位 finding——优先账本（已发布 finding 的 inline 评论含完整正文与 `cra:` 标记），
回退 run `briefs/*.md` 的 `### id=…` 段（被 REFUTED/截断的候选只在验证 brief 留档）；两处都没有则**显式失败**，
不凭空生成断言。diff 由 compare API 渲染为 unified diff，快照由 head 树（tree + contents raw）打成
`repo_snapshot.tgz`（二进制跳过并计数声明）；`--diff-file`/`--snapshot-dir` 提供离线等价输入。label 映射：
`missed`→bad（草案 expected 取 finding 的 file/line/category/关键词）、`false-positive`→clean（草案 expected 为空）、
`relabeled`→`--to-set`（默认 bad，用于线上处置口径与固化口径不一致的样本）。来源写入用例目录 `badcase.json`
（run_ref/run_id/finding_id/verifier_verdict/label），`meta.source=production`、annotation=draft——
必须 `cra-eval annotate --action confirm` 后才进入回归；`run` 对 draft 用例仍拒绝执行（06§3 状态机不变）。

## 3. 数据飞轮冷启动（v1.2 重建——v1.1 三重悬空：种子不可得 / clean 分母为零 / 改造量低估一个数量级）

**事实修正**：本仓库仅一个多月历史、两次文档提交，**没有 fix commit 可反查**——v1.1 "本仓库历史 bad-PR 种子 ≥8" 的来源不存在；纯文档 clean 集使 M2 验收"clean 误报下降 ≥30%"不可判定。

**v1.2 方案：bad 与 clean 均自 Defects4J 双侧生成**（MIT 协议、17 个真实 Java 项目、854 缺陷、可导出修复 patch——已核查可行）：

| 集合 | 生成方式 | 每条内容 |
|---|---|---|
| bad-PR | Defects4J buggy 侧：`git diff <fixed> <buggy>` 得缺陷引入 diff（逆向 patch），从 patch hunk + 元数据标注 expected findings（文件/行/类别/断言关键词）；**净化工序 ①（v1.2.1）**：hunk 级过滤测试文件（`src/test/**`、`*Test.java` 等）——逆向 diff 必然含"删除触发测试"，不过滤则"删测试"类 finding 干扰断言匹配 | 整仓 fixture 快照 + diff + 标注 |
| clean-PR | Defects4J 宿主项目的 **git 历史 benign commit**（文档、测试增补、重命名等真实代码仓库的非缺陷提交，人工快筛）；**净化工序 ②（v1.2.1）**：`cra-eval recheck` 复核通道——快筛误标样本（实含 bug）会把真阳性记为 FP，向沉默优化施压（第一公理所防）；复核发现误标 → 打 `relabeled` 标签（§2 出口）、样本挪 bad 集、**当次门禁统计作废重跑** | 同上，expected_findings = ∅（复核后） |

**整仓 fixture 规格（07 T1.9 独立任务，v1.1 把它塞进一个 checkbox 是低估）**：

- 每用例 = `{repo_snapshot.tgz（裁剪到缺陷相关模块，控制上下文成本）, diff.patch, expected_findings.json, meta.json(项目/缺陷类型/标注人)}`；
- 加载器：解包快照 → 构造 ReviewRequest(base=fixed_sha, head=buggy_sha) → 跑流水线 → 断言器按 expected 匹配（位置 ±5 行 + 类别 + 断言关键词）；
- 标注工具：`cra-eval annotate` 半自动——**四步流程 `prefill → 冒烟 → 校准 → confirm`**
  （prefill 的 hunk 机械预填只是起点，不可直接 confirm；详见下方标注质量 checklist）；
- **标注质量 checklist（v1.3.2 红驱动，2026-10-26：M3 扩集 18 例中 7 例需校准，全部为标注
  口径问题而非模型能力）**：
  1. 【强制】**关键词用 finding 的语言**：流水线正文为中文 → 关键词必须是中文行为/现象词
     （"环""顶层""自引用""超时"）；**禁止英文词**（session / top-level / recurs / empty——
     中文正文不出现，AND 语义下系统性漏配）。代码常量名（如 MAX_SEGMENT_LEN）仅当评审
     必然引用时使用。
  2. 【强制】**必然性测试**：假设一个评审员正确发现了该缺陷，他是否**必然**在正文写出这个
     词？否 → 换词。典型错误：取实现标识符（canInline / validateInput）而正确描述是行为
     （"删除 catch 保护" / "负时长"）。
  3. 【强制】**行号取评审实际锚点**：标注锚点（代码定义处）与评审锚点（调用点/变更行）可能
     差数行乃至数百行（本批实测 L580→L586、L587→L574、L625→L325）。**标注后必须冒烟校准**。
  4. 【强制】**类别与评审口径对齐**：若缺陷自然表现为"行为变更无测试"，**加一条 test 类
     expected**（多条目 OR），不要用 test 代替 bug；category 必须在 05§1.1 枚举内。
  5. 【强制】**标注流程四步**：每个新用例先 k=1 真实评审一次 → 读报告的 `findings_brief`
     → 按"file 相等 + 行 ±5 + category 相等 + 关键词 AND"逐条核对 expected → 按 1–4 校准
     → confirm。本批 18 例的 7 处校准全部由此发现。
  6. 【建议】**默认单关键词**：AND 语义下词越多越难过；仅当确认多词必然同时出现时才用多词。
  7. 【建议】**校准记录入 note**：每次校准写明"取证来源 + 原值→新值 + 原因"（本批 7 处均
     如此），供 recheck 复核与校准率度量。
  8. 【度量】**标注校准率进周报**：被校准 expected 数 / 新增 expected 总数（本批 7/18 = 39%
     ——prefill 不可单独作为断言来源）。冒烟校准按 07§6.0 时间纪律：每批 ≤5 用例（k=1，
     约 4.5 分钟），禁止为标注校准跑全量。
- 时序：M1 建 **bad 12 + clean 8 + 对抗 10（注入 3/幻觉 5/拒真 2）**；M3 扩到 bad ≥30 + clean ≥20；
- 持续回流：自举 PR 的真实 badcase 经 `badcase add` 固化（`meta.source=production`，来源见用例目录 `badcase.json`；对 Defects4J 集的补充，不替代）。

## 4. 周期性任务

- **夜间存量评审**（M4）：重点目录跑"仅 important 级"，产出 issue 而非 PR comment；
- **每周噪声报表**：四类指标 + 转化漏斗 + 零发布率 + 截断率趋势 + 拒真池样本量（不足门槛时标注"不下结论"）；
- **月度模型路由审计**：成本-质量曲线，决定档位与 T3.6 开启评估。
