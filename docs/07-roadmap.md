# 07 · 实施路线图与任务拆解

> 文档版本：v1.7（2026-10-27，M4e 第二半 + M4f-full：CLI/server 平台选择与 GitLab webhook（ADR-0013）；产品事件史 JSONL→PG→CrossChecker（ADR-0012）；真实写操作 pending）
> 文档版本：v1.6（2026-10-27，M4e 第一半：cra-git-gitlab 适配器 + ADR-0011 + GitLab 边界表落地记录）
> 文档版本：v1.5（2026-10-27，M4 收口：M4a/M4b/M4c/M4d 合入 + context 用例 2→6 + 官方 L3 全量重跑）
> 文档版本：v1.4（2026-10-26，M4b 服务化落地 + M4c push 直推 + M4d 夜间存量落地记录与验收证据）
> 文档版本：v1.3.1（2026-10-25，响应第四轮评审缺陷 ⑥及终审建议）
> **v1.2.1**：DONE 完整标记、Summary issue comment 载体、锚行滑窗、Wilson 修正等 7 项。
> **v1.3**：T1.4 新段落解析；M4a 签字规矩；M4f 拆 f-lite/f-full。
> **v1.3.1**：M1 验收 6 与 04§2.4 口径统一（**负向不变量断言**，seed best-effort 局限）；M4f-lite 验收关联对抗集第 4 类；T3.2 对抗集扩充含 context 用例。
> **v1.7**：M4f-full 落地（事件史 JSONL→PG→CrossChecker，ADR-0012）；未接外部数据源显式列出。
> 面向后续开发 agent：按里程碑顺序执行，**未达验收标准不进入下一阶段**。
> **提交前核查纪律（三条腿，四轮 34 项缺陷的教训成文）**：
> 1. **数字**：任何写入任务/验收的阈值、预算、概率、百分比——独立推演一遍（第三轮 6 项缺陷的来源）；
> 2. **端点**：任何点名的 API 端点/平台行为——对照官方文档核查一遍（第三轮另一来源）；
> 3. **跨文档传播**：任何新增角色/类别/段落/标记——对全部文档 grep 一遍，核对 schema（05）、算术与速查表（04§2、03§6）、验证与上下文模型（03§4、04§1）、评测与验收（06、07）、workflow 与配置（05§4-5）**全部接上**（第四轮 6 项缺陷中 4 项的来源——v1.3 造了发动机没接变速箱）。
>
> **基线冻结与变更预算（第五轮裁决采纳，治理规则）**：docs/00–11 已冻结为 **v1 基线**（2026-10-25，五轮评审 40 项缺陷闭环）——五轮缺陷曲线显示矛盾已从"想法"转移到"语料自身规模"（13 份约 2000 行互相高密度引用的契约文档，整批改动的笛卡尔积核对成本超过收益）。此后：① 只在**评审红或实现撞墙**时定点修订；② 新规则/新选型一律走 **ADR**（10§6 机制，≤10 行一篇）；③ **语料变更预算**——每里程碑非红驱动的文档改动 ≤1 个 PR；④ 评审节奏从"版本号整批对抗"切换为**红即修**。让 M0 尽快产生真实代码，反向校准全部算术与端点假设。

---

## 0. 总览

| 里程碑 | 主题 | 周期（估） | 核心产出 |
|---|---|---|---|
| M0 | 骨架与最小闭环 | 1 周 | 单 agent 端到端：PR → diff → LLM 评审 → 回帖 + trace artifact + commit 级幂等闸 |
| M1 | 工程化与账本 | 2 周 | 指纹 v2 账本（幂等/收敛/防污染）、REVIEW.md（base 读取）、深度档位、大 diff 分 chunk、**最小评测集 + fixture 规格** |
| M2 | **隔离审查 + 独立复核 + pre-PR**（核心） | 2 周 | 多视角并行 reviewer（隔离）、单强验证者独立复核、仲裁 judge、收敛控制、**本地 pre-PR（实跑验证）**、注入防御、LLM 并发限流 |
| M3 | 评测闭环 + 投票增量 | 1.5 周 | 聚合 CI 门禁三级分级、处置采集周报、完整数据集、**双异构验证者投票（最小样本量 + 数据门禁）** |
| M4 | 服务化与扩展（可选池） | 按需 | webhook server、push 模式、风险分级建议、夜间存量评审、GitLab 适配 |

依赖链：M0 → M1 → M2 → M3；M4 各项独立可插队。

**M2 形态说明（对首轮评审缺陷 ⑧ 的回应，v1.1 保留）**：保留 2 视角并行 BugHunter——① 并行多 agent 找 bug 是 Anthropic 博客明确的生产实践；② 多视角隔离审查是立项核心需求；③ 边际成本可控（chunk 化后每视角增量约 80k token）。被降级的是证据最弱的双验证者投票（M3 数据门禁增量）。

## 1. M0：骨架与最小闭环

**目标**：补完原项目设想的端到端流。技术栈 Java 17 / Spring Boot 3.x（DI+配置；M4b 起服务壳用 web/JDBC）/ Maven 多模块。**仅 PR 触发，draft 过滤**（05§4）。

**任务**：

- [ ] T0.1 建仓：按 02§3 建模块骨架与依赖关系，根 pom 定 Java 17；
- [ ] T0.2 cra-llm：`LlmGateway` 最小实现——单模型、chat+JSON 双模式、超时重试、并发信号量（8）、token 计数；
- [ ] T0.3 cra-git-github：`fetchDiff`（compare API）+ `postReview`（含锚定校验与逐条降级最小版）+ `readFile`；WireMock 录制回放单测；
- [ ] T0.4 cra-agents：单一 `GeneralReviewer` prompt（04§4.1 简化版），UNTRUSTED 定界从第一天内建；
- [ ] T0.5 cra-core：线性 pipeline：assemble → review(×1) → publish；**commit 级幂等闸**（02§4.1：账本存在该 commit 的 **status=DONE 标记**才全量跳过——run 收尾必须发 Summary issue comment 带 DONE 标记，05§1.3/§2.2）；
- [ ] T0.6 cra-runner-cli：`cra review` + 参数解析 + 退出码（05§3）；
- [ ] T0.7 自举 workflow（05§4：PR 触发 + draft 过滤 + maven 缓存分发 + artifact 上传）跑通真实回帖。

**验收**：
1. fixture 仓库提 PR，2 分钟内收到 inline comment + **Summary issue comment（文末含 DONE 标记）**；位置非法的 finding 出现在 Summary 而非导致 422；
2. `--dry-run` 全程零写操作（单测断言）；
3. 同一 commit 重复触发（含 CI 重跑）不产生重复 comment（DONE 闸生效）；**人为掐断发布中途的 run（无 DONE）→ 重跑补发缺失条目、已发布不重发**（v1.2.1 新增用例，防"其余 finding 永久静默"）；
4. trace artifact 可下载且含 brief 全文；
5. LLM 重试与 GitHub 退避单测通过；CI 全绿。

## 2. M1：工程化与账本

**任务**：

- [ ] T1.1 Finding/ReviewRun/Trace 完整模型（05§1）+ `ReviewLedger` SPI + GitHub 账本实现（**指纹 v2 标记 + 解析器规则**，05§1.3）；本地 SQLite 实现；
- [ ] T1.2 幂等与收敛账本化：指纹 v2 键（**代码内容锚，不含 LLM 文本与绝对行号**，02§4.1）；解析兜底退 commit 闸；**过期评论收尾**（新 run 后 PATCH 上一轮 Summary 加 `[已被 run_xxx 取代]` 前缀，02§5）；
- [ ] T1.3 ContextAssembler：L0/L1 分层（窗口默认 ±30 + 条件全文）+ token 估算裁剪 + 覆盖声明进 Summary；
- [ ] T1.4 REVIEW.md：base SHA 读取 + PR 内变更检测警告 + 解析容错（04§3）；**v1.3：解析器支持新段落类型**（Product context / Known incidents / Sniff tests——M4f-lite 的数据前提，段落缺失时 CrossChecker 相关检查优雅跳过）；
- [ ] T1.5 深度档位 quick/standard/deep 参数化（此阶段 reviewer 仍单角色）；
- [ ] T1.6 大 diff 分 chunk + 降档阶梯 + **截断丢弃计数进 stats/Summary**（02§4.4）；
- [ ] T1.7 nit 预算、severity 排序、pre-existing 的 jgit blame 复核 + 无本地仓库降级声明（05§2）；
- [ ] T1.8 最小评测集：**Defects4J 双侧生成 bad 12 + clean 8** + 对抗 10（注入 3 / 幻觉 5 / 拒真 2，微型 fixture）（06§3）；`cra-eval run` 可执行单次评测并出聚合指标；
- [ ] **T1.9 整仓 fixture 规格与工具链（v1.2 新增）**：快照裁剪打包、加载器（构造 ReviewRequest 跑流水线）、断言器（位置 ±5 行 + 类别 + 断言关键词）、`cra-eval annotate` 半自动标注——这是子系统级工程，M2/M3 全部召回验收的数据前提。

**验收**：
1. 连续 push 两次：第二次只评审增量、无重复 comment；漂移用例三连：**上方插 10 行**（锚行内容不变 → 抑制）、**改锚行 ±2 行内非锚行内容**（滑窗 ±5 命中 → 抑制）、**改锚行本身**（内容已变 → 判新问题，符合语义）；
2. 账本污染测试：构造含伪造 `<!-- cra:… -->` 代码注释的 PR，解析器忽略正文标记（05§1.3 规则 1/2 生效，单测+集成各一）；
3. 过期收尾：第二次 push 后上一轮 **Summary issue comment** 带 `[已被 run_xxx 取代]` 前缀（PATCH /issues 端点），inline 未被删除（处置采集依赖仍在）；
4. 2000+ 行 diff 正常完成且降档、截断计数、覆盖范围在 Summary 如实声明；
5. 在 PR 中修改 REVIEW.md 加 `Skip paths: src/**`，评审不受影响且 Summary 出现策略变更警告；
6. 对抗集注入用例：以**负向不变量断言**判定（04§2.4——断言输出与发布集合不出现注入目标行为，天然抗抖动；seed 是 best-effort 不可依赖），3 次运行零目标行为；
7. T1.8/T1.9 就位（含测试 hunk 过滤与 recheck 通道，06§3），`cra-eval run` 出首份聚合指标报告（检出率 + Wilson 区间，**数值用正确 Wilson 公式**）。

## 3. M2：隔离审查 + 独立复核 + pre-PR（核心）

**任务**：

- [x] T2.1 SubAgent 抽象与隔离运行时（03§1）：brief 注入、工具白名单、三重预算硬限、失败隔离；**已落地（2026-10-25）：ScopedToolSet 隔离边界实现层硬编码（白名单/路径越界即 TOOL_VIOLATION）、token/轮次/时间/输出四重预算硬限（BUDGET_EXCEEDED）、失败隔离收敛结果对象绝不抛穿扇出层、tool_call/tool_result/agent_budget_exceeded 事件进 trace**；
- [x] T2.2 BugHunter 四视角模板（03§2.2）+ 视角路由 + 并行扇出/汇聚（并发 8）；**已落地：四视角模板（dataflow/boundary/concurrency/resource）+ 档位路由（quick 1/standard 2/deep 4，派发序即优先级序）+ 70% 档砍 1 视角 + 跨代理精确合并（in-run-exact 入 trace）**；
- [x] T2.3 Security/Test/Convention/Perf 接入；**Security/Perf 按 chunk 扇出**（03§6 v1.2 修正——独享全量 brief 超 2 倍预算，不可行）；**已落地：Security 每 chunk 必跑/Test 逻辑变更触发/Convention 策略定义时触发/Perf Deep 档，专角色 null = 未装配跳过；M2 骨架（verifier/judge 模板占位 + pre-PR --local --exec 骨架）随本线先行，真实模板与实现由验证线/路由线落地后取代**；
- [x] T2.4 VerifierAgent（balanced-review 模板，04§4.2）：文件批次组织、三值判定、INSUFFICIENT 受控补查；**反锚定校验器作为质控信号实现**（输出 PASS/FAIL 入 trace，不阻断晋升；归一化与统计口径按 03§4.2 v1.2）；**已落地（2026-10-25）：文件合批单 brief（founder 论证+全文+diff 切片）、followup_file 受控补查一次（编排器预取注入）、LLM 不可得缩上下文重试 1 次、反锚定按 v1.2 归一化口径（FAIL=重叠率 1.0）入 trace/finding**；
- [x] T2.5 判定与晋升规则（03§4.3 M2 形态）：含 important 级 REFUTED 确定性抽查保险丝（sha256(run_id|finding_id) mod 100 < 20，可复现）与验证不可得 → UNVERIFIED 不发布语义；**已落地：PromotionEngine 五岔（confirmed 发布/refuted 拒真统计/insufficient→Judge/security·important 抽查保险丝 `--spot-check-percent` 默认 20 同 run 可复现/unavailable→UNVERIFIED 不发布）；`publish-unverified=labeled` 生效语义落地**；
- [x] T2.6 JudgeAgent：INSUFFICIENT 仲裁 + 抽查复核 + **上下文按案件界定**（≤3 文件，03§6）；**已落地：arbitrate 一次性终裁无第三态；judge 不可得时仲裁案回 UNVERIFIED、抽查案维持 REFUTED（均不发布）**；
- [x] T2.7 ConvergenceGuard：轮次上限 3、diff-only 复审、指纹去重（03§5）；**已落地：轮次按账本完成标记计数（同 head 重跑不计），第 4 轮停止评审并在 Summary 建议打回；非首轮 diff-only 只评审上轮完成 head 以来增量（锚定/blame/policy 用全量 PR diff），增量取不到回退全量并记 degradation**；
- [x] T2.8 精确去重 merge；语义去重留 M3；**已落地：run 内同 file ±3 行同 category 链式聚类、代表元确定性**；
- [x] T2.9 LlmGateway 模型路由（standard/strong/light）+ per-model RPM + 降级链（02§4.5）；**已落地（2026-10-25）：三档端点解析 + 60s 滑窗 per-model RPM + fallback-chain 换端点（重试耗尽/排队超时/4xx 均试链上下一档），降级注记入 LlmResponse 供 stats/Summary**；
- [x] **T2.10 pre-PR 本地模式（v1.2 补实质）**：`cra review --local` 默认**实跑 `mvn -B -ntp verify`（命令可配）并展示真实输出**（退出码 + 失败摘录置顶报告——对齐博客"必须展示输出而非声称完成"，docs/00 §4 自检即现成命令）；随后本地评审（SQLite 账本、默认 dry-run）+ 建议清单；**已落地：本地 jgit 适配器（diff 与 GitHub 同构）+ 输出实时回显/全量落盘/摘录置顶 + 退出码 4（实跑失败，05§3 v1.3.2）+ 无密钥如实跳过评审；验收 8 e2e（注入挂测试分支）实跑通过**；
- [x] T2.11 prompt 模板版本化并与 run 关联（05§1.2）；**已落地：trace `template_versions` 事件 + SQLite `prompt_template_version` 表（随发布成功落库）**。

**验收（L3 全量口径：k=3、聚合 Wilson CI 判定，06§1.2；数据集 = T1.8）**：
1. 对抗集幻觉用例：聚合拒绝率 ≥80%；
2. **召回下限（聚合）**：bad 12 种子检出率 Wilson 95% 下界 ≥0.60（L3 standard 档阈值，quick 档另维护基线，06§1.2）；**零发布护栏（聚合）**：全部 样本×运行 观测零 confirmed 占比 ≤1/12；
3. 反锚定质控链路：trace 中 PASS/FAIL 可查、周报可聚合；**单行 off-by-one 类种子样本不因 FAIL 被拒**（FAIL 不阻断的直接验证）；
4. 注入防御回归：M1 验收 5/6 持续通过；
5. 单 reviewer 注入超时 → run DEGRADED 完成并声明；验证不可得 → UNVERIFIED 计数出现在 Summary；预算截断 → `suppressed_by_truncation` 进 Summary；
6. Standard 档（≤2k 行、≤10 文件）p95 ≤5min；**成本实测对照 04§2.2 算术（≈330k / 500k 预算 = 66%，不得触发 70% 降档）**，偏差 >30% 必须回改文档；
7. clean 8（真实代码样本）误报均值对比 M1 单 reviewer 基线下降 ≥30%（分母为真实代码 diff，可判定）；
8. `cra review --local` 在一个注入了会挂测试的分支上：报告**如实展示测试失败输出**且退出码非零；在正常分支输出建议清单——pre-PR 不是空壳的直接证据。

> **M2 验收执行记录（2026-10-25，L3 口径 k=3 standard，eval 40202853864 + 40205519252）**：验收 1/3/4/5/6/7/8 通过；**验收 2 部分通过**——召回 32/36=88.9%（Wilson 95% 下界 0.747 ≥ 0.60）✅；零发布护栏 4/36=11.1% > 1/12 **机械口径红**——4 个观测分布于 3 个 case 的单/双次运行，**无样本级全零**（无系统性沉默），性质为 LLM 运行间波动；**owner 裁决接受记录（方案 A）**，M3 暂不启动。配套说明：① 三处断言校准（bad-Closure-3/Mockito-2/Mockito-4，commit c84537c，依据 findings_brief 取证）修正了 M1 标注"实现标识符关键词+行号偏离"的系统性偏差；② 拼合口径=未修改 5 case 沿用 40202853864 的 15 观测 + 校准 3 case 按新断言离线重判（本轮 21 观测），两轮代码差异仅评测报告字段（不影响评审行为）；③ k=3 下观测级 1/12 与"系统性沉默"语义的口径差不在本轮修订（留待 M3 数据门禁一并校准，见 06§1.2）。

## 4. M3：评测闭环 + 投票增量

**任务**：

- [ ] T3.1 评测框架完善：k 次运行调度、**聚合 Wilson CI 判定**、容差带、指标报告（06§1.2）；
- [ ] T3.2 bad 扩到 ≥30 + clean ≥20（Defects4J 双侧流水线批量生成 + `cra-eval annotate`）+ 对抗集扩充（含 context 类用例 ≥2，06§1.2 第 4 类）；
- [ ] T3.3 回归门禁三级分级落地（04§2.4）：L1 快检进 CI（每 PR）、L2 本地跑 + CI 校验报告哈希、L3 里程碑/大改；
- [x] T3.4 处置采集与周报：`cra-eval report` 拉账本（双端点合并、无 resolve 信号）聚合全部指标含漏斗与截断率（06§1.1；实现注记见 06§1.1.1）；
- [x] T3.5 badcase 固化命令与流程（06§2；`badcase.json` 记录来源，draft→annotate confirm 后进回归）；
- [ ] **T3.6 双异构验证者投票（v1.2 加门槛）**：触发条件 = 拒真评估池 ≥20 样本且推翻率 ≥10%，或处置样本 ≥30 且精确率 <80%；**样本量不足时不判定、不开启**（继续单验证者+抽查）；实施时 `require-distinct-model-family` 硬校验 + 语义去重。**门槛评估查询已随 T3.4 report 落地**（`dual_verifier_gate`）；2026-10-26 首份评估——拒真池 0（<20）、处置样本 0（<30）→ 样本不足，不开启，未实施硬校验（数据触发后再做）。

**验收**：
1. 故意改坏一个 prompt → L2/L3 门禁红（聚合下界跌破容差带或零发布护栏触发）；还原 → 绿；
2. 首份周报产出四类指标 + 漏斗 + 样本量标注（不足门槛处注明"不下结论"）；`badcase add` 后条目出现在下次回归；
3. 若 T3.6 开启：同模型家族配置被启动校验拒绝；开启前后 A/B 数据入档。

> **M3 验收执行记录（2026-10-26，L3 口径 k=3 standard，eval 40218288324，186 观测全部完成）**：
>
> **工程交付**：T3.1（聚合 Wilson 判定 + 容差带 + per-run 指标）、T3.3（L1/L2/L3 三级门禁 + verify/gate 离线重判）、T3.2（bad 30 / clean 20 / 对抗 12 含 context 2）、T3.4（周报四类指标+漏斗+样本门槛）、T3.5（badcase add）、T3.6（门槛评估：**不开启**，样本不足如实标注）——四线全部合入。
>
> **L3 门禁判定：四红三绿**（门禁机制全部正常工作，被度量系统的质量缺口被真实暴露）：
> - ❌ bad 召回 **53/90 = 58.9%，Wilson 95% 下界 0.486 < 0.70**（8 个 case 全 miss：Closure-3/4/6、Gson-4、JacksonCore-4/8、Mockito-7/8）；
> - ❌ 零发布 **11/90 = 12.2% > 1/12**——但**样本级全零 = 0/30**（与 M2 4/36、L2 12/60 同源，均为运行间波动）；
> - ❌ context 检出 **0/6**——standard 档下小 diff 自动降 quick（02§4.3）→ CrossChecker 不触发（deep 冒烟 2/2 检出）；**context 门禁与自动降档存在结构性冲突**；
> - ✅ clean 误报 0.60 条/run（≤1.5）；✅ 幻觉拒绝 86.7%（≥80%）；✅ 注入负向不变量零违反（9 观测）；✅ 186/186 观测完成（无失败）。
>
> **验收 1**（L2 改坏 prompt）：改坏 verifier → 检出下界 0.508→0.067 跌破 0.40，门禁红 ✅；还原 → 检出/clean 回绿，但零发布护栏 5/24 仍红（口径待裁决）——整体「还原→绿」未达成。
> **验收 2**（周报 + badcase）：✅ 周报四类指标+漏斗+样本量标注（真实仓库 0/0 如实「不下结论」；合成回放 38 样本 33/38=0.868）；badcase add 端到端（draft→confirm→回归）。
> **验收 3**（T3.6 开启）：未开启（拒真池 0/处置样本 0，样本不足不下结论）；同家族硬校验未实施（不得放松门槛）。
>
> **结论（2026-10-26 更新）：M3 验收 2 通过**——标注质检校准（06§3 checklist，14 个 bad case 的 expected 修正）后：
> - **召回**：L3 90 观测 **78/90 = 86.7%，Wilson 95% 下界 0.7813 ≥ 0.70**（agent 完整观测）；协调者独立离线复核（历史 200 字 findings_brief 口径，保守下界）74/90、WL 0.7306——**两口径均达标**；L2 52/60（WL 0.7583 ≥ 0.35）。
> - **零发布**：11/90 观测、**样本级全零 = 无**（护栏绿）。
> - **context**：0/6 仍为结构性缺口（standard 档下小 diff 自动降 quick → CrossChecker 不触发），待数据层修复（context 用例 diff 造大 ≥300 行）或 06§1.2 注记按 deep 跑——不影响本次召回达标判定（06§1.2：M4f 启用后才纳入门禁）。
> - **剩余 12 个 L3 未命中**：11 个零发布（LLM 运行间方差，标注不可救）+ 1 个 SUMMARY_FALLBACK 无类别——如实记录，不再试图用断言修复。
> - 原四项待裁决状态：① 零发布样本级 ✅ 已生效；② L2 0.35 ✅ 已生效；③ 召回缺口 ✅ 已解决（标注质量校准 +12 观测）；④ context 降档冲突 ⏳ 待修（数据层造大 diff 或口径注记）。

> **官方 L3 重跑（2026-10-26，opencode-go / deepseek-v4.1-flash，temperature=0.2，reasoning_effort=none，commit `4074097`，run 40342221542）**：
> - **召回 78/90 = 86.7%，Wilson 95% 下界 0.7813 ≥ 0.70 → 官方达标** ✅（与标注校准后的离线重判一致，离线结论被官方重跑证实）
> - 零发布**样本级 0/30 = 0% ≤ 1/12** ✅（观测级 8/90 = 8.9% 作观察指标）；幻觉拒绝 15/15 = 100% ✅；注入不变量 0 违反 ✅；clean 误报 0.68 ≤ 1.5 ✅；186 观测全部完成 ✅
> - **context 4/6 = 66.7%（Wilson [0.30, 0.91]）< 0.80 → 红**：CrossChecker 已可触发（B 方案生效——两 context 用例 diff 造大至 440/444 行，standard 不再降档），4/6 属 2 case × 3 run 的运行间波动；**阈值 0.80 是 06§1.2 的首设值**（"启用后按 ≥0.80 首设阈值判定"——对抗 ④ 只规定"纳入 L3 门禁"、数值取对抗 ① 同档，无数据推导），n=6（2 case × 3 run）的统计功效不足以支撑该判定——待 owner 裁决（① 接受记录；② 扩充 context 用例至 ≥4 个（06§1.2 的 ≥2 是下限）；③ 按 n=6 功效调整阈值）。**（2026-10-27 校准：该项已闭环——context 用例扩至 6 例（adv-ctx-01…06），官方 L3 全量重跑 context 18/18 = 1.000 ≥ 0.80；0.80 阈值本身未动，见本节末「M4 收口记录」。）**
> - **M3 验收 2：召回子项官方通过；context 子项未达标（质量缺口，非结构性）**

> **context 4/6 归因与修复验证（2026-10-26，commit `8a5c9de`，run 40355527535）**：
> - **归因（非运行间波动）**：ctx-02 的 run0/run1 各有一条 `{agent: cross-checker, termination: budget_exceeded, tokens: {in: 12617-12661, out: 2122-2255}}`——**输出预算 1.5k 击穿、输入 12.6k 未超 16k**；该视角 findings 未产出 → 两观测 miss。ctx-01 三次与 ctx-02 run2 均正常。
> - **修复**：CrossChecker 输出 1.5k→4k（输入维持 16k，撤销此前误判输入不足而加的 24k）；同源风险一并处理 TestReviewer 2k→4k、BugHunter 2k→4k（03§6 预算表与测试断言同步；Security 实测未击穿，保持 2k）。
> - **验证**：2 context case × k=3 = 6 观测（standard + crosschecker + temperature=0.2），**context 检出 6/6 = 1.000 ≥ 0.80 通过**，无 budget_exceeded，各 run 发布 5-7 条（非零发布）。该子集 run 整体退出码 5 仅因 `bad_detection: 无 bad 观测不可判定`（子集不含 bad 的必然结果），context 门禁本身绿。
> - **结论**：官方 L3（40342221542）唯一红项（context 4/6）闭环。**n=6 的统计功效局限仍在**——06§1.2 的 context ≥2 是下限，建议 M4 数据扩充时补到 ≥4 个用例后重判阈值（待 owner 决定是否排期）**（2026-10-27 校准：已排期并落地——context 用例扩至 6 例，官方 L3 18/18 = 1.000；0.80 阈值未重判、未改动）**。

> **证据口径与官方门禁（如实声明，2026-10-26）**：官方 L3 报告（CI 40218288324，commit `fa3d9e5`）仍为 **53/90**——这是唯一带报告哈希的门禁证据。78/90 是对同一批 186 观测的**离线重判**（标注校准不改变评审行为，等价于用新断言重算同一批证据），可用 `cra-eval gate` 复现，但**不能替代官方门禁证据**；若要以 78/90 更新官方结论，须重跑一轮 L3（约 39 分钟，待 owner 批准）。另：78/90 中一部分观测由新增的 `test` OR 条目命中（"仅报缺测试"）；**bug-only 检出**（只计 category=bug 的 expected）作为观察指标随报告输出（`bug_only_detection`），不计入任何门禁。
>
> **四项待 owner 裁决（数据已齐备）**：
> 1. 零发布护栏改**样本级**口径（全部实测：M2 4/36 无样本级全零、L2 12/60 仅 1/30、L3 11/90 全零 0/30——观测级 1/12 在 LLM 运行间波动下必然假红；改样本级后三项全绿且忠实「防系统性沉默」本意）；
> 2. L2 quick 基线重冻结（0.40 在扩集 60 观测下基线即红：31/60 的 WL=0.393；建议 0.35，改坏 prompt 实测 0.067 仍可捕获）；
> 3. 召回缺口 58.9% vs 0.70——接受记录 / 数据集难度校准（8 个全 miss case 需 findings_brief 取证归因）/ 工程提升召回（prompt/视角/上下文）；
> 4. context 门禁与自动降档冲突——context 用例 diff 造大 ≥300 行（数据适配，推荐）/ 06§1.2 注记 context 子集按 deep 跑 / CrossChecker quick 档也触发（与 03§6 冲突）。
>
> **过程发现（已修入 main）**：评测 stub 的 contents 路径前缀硬编码（M1 起潜伏：评测形态 REVIEW.md 与文件全文恒 404，评审员一直只见 diff）→ 正则修复；eval CLI 的 `--workspace`/`--crosschecker` 从未透传 → 全链路补齐；context 用例快照补 `.git` 三提交历史与 meta SHA 对齐（CrossChecker 变更史可检索，deep 冒烟 2/2 检出）。

> **四项裁决落地记录（2026-10-26，owner 裁决后执行）**：
> 1. **零发布护栏改样本级** ✅ 已落地（06§1.2 v1.3.2 定点修订 + `EvalReport.ZeroPublish` 样本级字段 + `GateEvaluator` 判定 + 测试）：观测级保留为报告观察指标。L3 的 11/90 在样本级口径下为 0/30 → 绿。
> 2. **L2 quick 基线重冻结 0.35** ✅ 已落地（06§1.2 表格 + `GatePolicy` 常量 + 测试）：扩集实测 31/60（下界 0.393）在 0.35 下绿；改坏 prompt（0.067）仍可捕获。
> 3. **数据集难度校准** ✅ 已落地（7 处 expected 校准，commit 0e59391/备忘 d232a5e）：L3 findings_brief 取证显示 **8 个全 miss case 全部是断言口径问题，无 LLM 盲区**（评审实际精确描述了缺陷：行号差 6 超 ±5、英文关键词对中文正文漏配、bug 判成 test、category=null）。校准后子集 13/24（校准前 0/24）；拼合 L3 全量约 66/90 = 73%，Wilson 下界 ≈0.63。
> 4. **工程提升召回**：三个实验的结论——**strong 档 verifier/judge（deepseek-reasoner）无提升**（12/24 vs 13/24：验证层非召回瓶颈）；**标准档视角 2→3 不可行**（04§2.2 预算 52%→71% 会触发 70% 降档阶梯，自我抵消）；**prompt 分类/行号纪律无提升**（我的子集 11/24 噪声内；owner 的 A/B 复测同样无增益且零发布变差 → **改动已回退**，commit 28e372b，纪律本身保留在 06/07 的教训中）。修复了 Verifier/Judge 的 `modelTier=STRONG` 回填（03§6 对齐，正确性修复）。**召回瓶颈 = D4J 缺陷本身的发现难度**：30 case 中约 8 个难 case 的真实召回 ~50%（三轮子集 11-13/24 的波动区间），非口径/非验证层。
> **当前状态**：M3 验收 2 的召回子项以**拼合口径**约 0.63（<0.70），三项口径/数据问题已解决；剩余缺口需 reviewer 侧换强模型（需文档修订 + 成本评估）或按「接受记录」处理——待 owner 最终裁决。**（2026-10-27 注：该拼合口径已被官方 L3 重跑 78/90 与 M4 后 198 观测重跑 71/90 取代，见本节末「M4 收口记录」；本段保留为决策时点的历史记录。）**
> **过程勘误（owner 并行修复，2026-10-26）**：① 一次 8 case 全 `exit=2`（24 秒内构造期全崩）的根因 = **空白 `CRA_LLM_MODEL_STRONG` 环境变量导致 CLI 启动失败**（我初判为偶发，不准确）——owner 已修（739603a：空白 env 归一为未配置）；② eval workflow 的 `model_strong` 输入默认值尾随空格同批清理（0219445）。

## 5. M4：可选池（按需排期，互不依赖）

- [x] **M4a 风险分级建议（v1.3 吸收三条签字规矩与分级表，01§3.4 担责）**：输出 Low/Medium/High 建议（只建议不审批——**agent 永远不算 approver**）；三条规矩入设计：① agent 赞成票不计为审批（已有原则）；② **approve 只对特定 commit 有效，新 commit 必须重审**——与账本 DONE/指纹的 commit 绑定语义同源（05§1.3），建议输出绑定 commit_sha；③ **指挥 agent 写代码的人必须在签字名单上**（PR 模板提示项）；分级参考（Anthropic 形态）：低风险（纯文档/功能开关，测试全过 + 可信作者 → 建议 0 人签）/ 中风险（常规业务逻辑、系统提示词 → 1 人）/ 高风险（核心高危路径、难恢复的关键配置 → 2 人）；签字语义 = 核实**意图/假设/设计**而非逐行读过（01§3.4）。**已落地（2026-10-26）**：`SignoffAdvisor`（cra-core，确定性路径规则，不调用 LLM）输出三档 + 建议人数 + 命中规则证据；`SignoffAdvice`/`SignoffBinding`（cra-common）承载 commit 绑定与 stale 判定（账本 `completedRuns` 发布序比对，与 05§1.3 同源）；ReviewResult 携 `signoffAdvice`，Summary 增"签字建议（建议，非审批）"段（固定三条规矩文案），旧 Summary 被新 commit 取代时追写 stale 声明；本地 pre-PR 报告同源渲染；配置 `agents.signoff`（05§5：enabled/trusted-authors + 运行时 `--pr-author`/`--tests-status`）。规则：高 = 核心高危路径或难恢复关键配置；中 = 常规业务逻辑/系统提示词/REVIEW.md 策略；低 = 纯文档或仅功能开关且测试 passed + 可信作者（不满足自动升中）；trace 事件 `signoff_advice`（05§1.4）。**验收（fixture 靶场 [PR #2](https://github.com/zingaltern/cra-fixture/pull/2)，2026-10-26）**：高风险 diff（`src/main/java/com/example/auth/AuthService.java` + `deploy/prod.yml` + workflow pin）→ Summary “风险分级：高风险 → 建议 2 人签字”，绑定 commit `f4677b2`，规则证据 `critical-config-path`/`high-risk-path`（run 40330786736）；追加重提交 `2eeaabc` → 旧 Summary 被 PATCH 为 `[已被 run_… 取代]` 并追写“签字建议已 stale：绑定 commit f4677b2 的建议在 2eeaabc 出现后失效，须重新核实”（run 40331155107，账本 `completedRuns` 比对），新 Summary 绑定新 commit；rebase 到最新 main 后第三个提交 `50657eb` 复跑（run 40333573185）再次产出高风险 2 人签 + 绑定 `50657eb`，并将 `2eeaabc` 的旧建议标 stale。
- **M4b webhook 服务化**：cra-server（Spring Boot web + 队列 + PG 账本），CLI/服务共用 core；顺带消除 L2 门禁荣誉制局限（服务端签名报告，04§2.4）；承担 M4f-full 的事件史采集定时任务；**已落地（2026-10-26，ADR-0008）**：`cra-server` 第二组合根（`POST /webhooks/github` HMAC-SHA256 验签 → pull_request 子集 → PG delivery 幂等 → 有界队列/503 背压 → 异步 `CoreAssembly` 评审）；`cra-store` 增 `PostgresLedger`（05§1.5 五表 + 手写幂等 DDL）；`ReportSignature`（cra-common）+ `/api/v1/reports/attest` + `cra-eval verify --signature`；docker-compose/PG/健康检查/优雅停机见 `docs/deployment-server.md`；事件史采集仅接口 + `@Scheduled` 骨架（Noop，M4f-full 实现；**2026-10-27 已接真实采集，见本节 M4f-full**）；`/healthz`/`/readyz` 不引入 Actuator；PG 测试 = CI service 容器优先，Testcontainers 回退（08§3.1 修订）；
  > **M4b 验收执行记录（2026-10-26/27，本地复现 + 真实 GitHub 验收）**：代码 head `7d149ec`（已合入 `origin/main 8a5c9de`）执行 `CRA_TEST_PG_URL=jdbc:postgresql://127.0.0.1:55434/cra_test mvn -B -ntp verify` → BUILD SUCCESS，107s，1695 用例（cra-server 34 / cra-store 25 / cra-eval 106 / archtest 1131）；PG 契约 10 例与 `ServerHttpIntegrationTest` 6 例实际执行（Skipped=0）。覆盖：webhook 202 / duplicate 200 / 错签 401 / 非法 payload 400 / 非 PR 与 draft 200 ignored、delivery 幂等、报告签发 200 / 无 token 401 / 未知 commit 409、`ReportSignature` 与 `cra-eval verify --signature` 篡改退出码 5（`VerifyCommandTest` 5 例）。**本线未改 cra-core/cra-llm/prompts，但改了评测门禁代码（VerifyCommand/ReportHasher）**；eval-l2 是否触发按 CI 规则（L1 红或匹配路径）判定。**2026-10-27 已在私有 scratch 仓库完成真实 GitHub 端到端验收**（真实 webhook + 临时 cloudflared tunnel + compose + PG；opened/synchronize→202→PG DONE、duplicate、同 head SKIPPED、draft/非 PR 200、错签 401、非法 payload 400、attest verify exit 0 / 篡改 exit 5；详见 `docs/deployment-server.md` §9）；此前“未复跑 compose”的记录保留为历史。**PR 状态（2026-10-27）**：PR #5 已 squash 合并 main `1298682`；CI verify + eval-l1 绿（eval-l2 按规则跳过）。
- **M4c push 模式**：直推 commit 的评审（commit-comment summary 模式）。**已落地（2026-10-26）**：`cra review --push`（无 PR；载体 = 单条 commit comment，全部 finding 以详情进正文，DONE 标记在末位）；base 沿首父链查 commit comment 账本取"上一完成 commit"（`--lookback` 默认 20，首次 = 仓库初版，`--base`/`--baseline` 可显式指定，本地仓库不可得时拒绝启动）；账本键 `repo + commit`（05§1.3 v1.4 键控扩展）；workflow `review-push` job（`branches:[main]` 分支过滤 + 跳过 merge commit 与 bot 提交 + 同 ref 串行）。**执行记录（2026-10-26）**：三提交 fixture 仓库 + 本地 GitHub/LLM stub 实跑——C2 首推 base=C1、产出 1 条 commit comment（正文含 `### 发现详情`、`status=DONE`）；同 C2 重跑 `SKIPPED`、评论数仍 1；C3 推送 base 解析为**上一完成 commit C2**（不是简单的 HEAD^）、产出第 2 条 comment；同 C3 重跑 `SKIPPED`、评论数仍 2——载体与幂等均符合 05§2.4；**真实 GitHub 复验（2026-10-27）**：私有 scratch 仓库 main 直推 `8b2cf27` → workflow run 40488612581，base 解析为根 `25ce74c`，commit comment `206848589` 含 `### 发现详情` + `status=DONE`；再推 `9198ddb` → run 40488832763，base 解析为上一完成 commit `8b2cf27`，comment `206848762` 含 DONE；重跑同 run → `commit 9198ddb 已有完成标记（DONE），全量跳过`，评论数仍 1；
- **M4d 夜间存量评审**：产出 issue 而非 PR comment。**已落地（2026-10-26）**：`cra sweep --since 30d --max-prs 10`（扫描窗口与上限可配，深度默认 quick）；窗口内每个历史 PR 复用完整流水线，产出**逐 PR issue**（Summary + 全部 finding 详情 + DONE 标记）与**扫描汇总 issue**（范围/逐 PR 结果/tokens/截断与降级声明）；幂等 = head commit 的 DONE 闸（重复夜间跑 → SKIPPED，全部跳过时不产汇总）；独立 nightly workflow（cron 18:00 UTC + workflow_dispatch，issues:write）。**执行记录（2026-10-26）**：同一 fixture 上首轮 sweep 评审 PR #7/#8 → issue #100/#101（均含详情与 DONE）+ 汇总 issue #102；第二轮对同一批 PR 均 `SKIPPED`、issue 总数仍 3（零新 issue）——幂等与"第二跑不重复"符合 05§2.5；**收口补强（2026-10-27）**：汇总声明窗口内 PR 总数/本次评审数/被 `--max-prs` 截断数，汇总发布失败以退出码 2 收尾（逐 PR issue 不回滚）；**真实 GitHub 复验（2026-10-27）**：私有 scratch 仓库 workflow_dispatch 两轮（run 40489412861 / 40489683682）——首轮窗口 4、选 2、截断 2，PR #4/#3 → issue #5/#6（含 DONE）+ 汇总 #7（声明总 4/评审 2/截断 2）；次轮两 PR 均 SKIPPED、reviewed=0、issue 总数仍 3；
- **M4e GitLab/Gerrit 适配 + 语义索引/跨文件上下文**；**GitLab 第二半已接线（2026-10-27，ADR-0011/0013）**：`cra-git-gitlab`（`GitPlatformAdapter` + `CommentSource`，不依赖 `cra-git-github`）+ CLI `--platform github|gitlab` / `CRA_GIT_PLATFORM`（`cra review` 与 `cra sweep` 均按平台装配；GitLab token 只从 `CRA_GITLAB_TOKEN`/`_FILE`，拒绝 `--token`）+ server `CRA_SERVER_PLATFORM=github|gitlab`（GitLab 模式 `/webhooks/gitlab`：`X-Gitlab-Token` 验签、open/reopen/update-with-oldrev → 评审、merge/close/审批/元数据/draft → 200 ignored + reason、`X-Gitlab-Event-UUID`/payload 摘要幂等）。**已验证**：`mvn verify` 全绿；CLI 平台选择 16 例 + 平台接线 1 例（WireMock → GitLab adapter + CommentSource → DONE 闸零重复）；server 平台/解析/验签 26 例 + GitLab webhook HTTP+PG 集成 5 例；真实 GitLab **读**路径对 `gitlab-org/cli` 公共项目实跑（compare 2 文件/20 patch 行、tree 331 md、raw README 13,772 字符）。**真实写操作（MR discussion/note、commit comment、issue）仍 pending：无 owner token**，逐项验收清单见 `docs/deployment-gitlab.md` §12；Gerrit、语义索引/跨文件上下文未做；
- **M4f CrossChecker（v1.3 按 03§3.1 详设拆两阶段）**：
  - [x] **M4f-lite（零服务依赖，可独立排期）**：模式 A 子集（隐含假设清单 + jgit 上下游近 8 周变更史 + REVIEW.md Product context/Known incidents 段）+ 模式 B 全量（Sniff tests 段执行）；复用 CI checkout 的本地 jgit 与现有验证晋升管线，与流水线同构；数据前提 = T1.4 解析器新段落支持；**已落地（2026-10-26）：`cross-checker` 角色 + 单次 LLM 调用（预算 16k/4k/2 轮——v1.3.2 于 `8a5c9de` 将输出预算 1.5k→4k，03§6 已同步）+ `LocalRepoContext.recentCommits` jgit 实现（窗口以 head committer time 为锚，排除本 PR 自身 commit）+ `agents.crosschecker` 配置（默认关闭，standard/deep 档 1 实例，quick/无数据优雅跳过并进覆盖声明）+ 矛盾来源引用格式权威定义（`ContextCitation`：`commit <sha> path <path>` / `REVIEW.md <段名>: <条目>`）+ verifier/judge 分型组装解析该格式；验收 fixture（真实 git 仓库 + REVIEW.md Known incidents）端到端产出 category=context 的 confirmed finding，见 `CrossCheckPipelineTest`**；
  - [x] **M4f-full（2026-10-27 落地，ADR-0012）**：产品事件史采集管道——`EventHistorySource`（JSONL 本地文件/`file:`/对象存储 `http(s)` 预签名 URL）→ `IncrementalEventHistoryCollector`（窗口 = max(请求窗口, 水位−overlap)、幂等键 `(repo,event_id)`、有上限重试、行级失败不推进水位）→ PG `product_event` + `event_collection_checkpoint`（`server-0002`）→ `EventHistoryQuery` 检索（repo/paths/commit/关键词/时间）→ CrossChecker 材料注入（与变更史共享 ≤4k，事件优先 ≤2k）+ verifier/judge 按 `event <id>` 预取；调度 = `CRA_SERVER_EVENT_HISTORY_ENABLED/CRON`（默认关闭，cron 组装期校验）；采集幂等/增量/重试/无数据跳过、PG 契约（CI service 容器）、CrossChecker 事件矛盾 fixture（脚本化 LLM）均有测试。**未接外部数据源**：Jira、Confluence、ServiceNow/服务台、Slack/Teams 事故频道、PagerDuty、GitHub Issues（作为事件源）——均留作 `EventHistorySource` 实现。tribal knowledge 不可见是硬边界（01§3.4），本项只覆盖已文档化部分；CLI 评测组合根未装配事件端口，L2/L3 不验证事件史路径（04§2.4）；
  - 验收（lite）：构造复现"会话超时案例"模式的 fixture（diff 本身无问题 + REVIEW.md Known incidents 含六周前定制条目 + 上下游路径含相关 commit）→ CrossChecker 产出 category=context 的 confirmed finding 并引用矛盾来源；该 fixture 同步固化为对抗集第 4 类用例（06§1.2）进 L3 门禁——context 召回有持续度量，不靠单例自证。**执行记录（2026-10-26）**：单测 + 本地 fixture e2e 通过（07§5 门禁开闸条件：M3 T3.1+T3.3 落地前不做 L3 全量验收）；fixture 格式与对抗集对齐由协调者在合并后统一处理（本线未改 cra-eval/dataset）**（2026-10-27 校准：已对齐——对抗集 context 用例 adv-ctx-01…06，PR #4 `6b5241b`；k=3 18/18 = 1.000）**；增量实测：标准 10 文件规模下单次输入 ≈12.6k token（diff 7.2k + 变更史 2.7k + 产品段 1.8k + 系统提示余量），输出预算 1.5k——对照 04§2.2 的 ≈14k in / ≈20k run 增量，属保守上限内的实测值**（2026-10-27 校准：输出预算已于 `8a5c9de` 调整为 4k；04§2.2 当前口径为 ≤16k in / ≤4k out，run 增量按该节重算值，上句为 2026-10-26 执行时点的历史记录）**。

### M4 收口记录（2026-10-27）

- **PR #3（M4c/M4d）**：合并 main `425006d` 解 README 冲突；L2 报告在收口 head `9008229` 重跑——bad 47/60 = 0.783（Wilson 下界 0.6638 ≥ 0.35）、clean 0.78、零发布样本级 2/30、100/100 完成，门禁绿；CI 全绿后 squash 合并 `c113d33`。
- **PR #5（M4b）**：合并 main `c113d33` 解 5 处 docs 冲突（08§5 场景编号统一为 M4c/M4d=20/21、M4b=22–26）；本地 1695 tests / 107s、HTTP 集成 6 例；PR CI verify + eval-l1 绿（L2 按规则跳过），squash 合并 `1298682`。
- **M4b/M4c/M4d 真实 GitHub 验收（2026-10-27）**：私有 scratch 仓库 `zingaltern/cra-acceptance-20261027` 上完成 webhook→PG、push commit comment、nightly issue/汇总/截断/幂等全链路；未改阈值/expected/prompt，未发现需修复的代码缺陷。证据与观察（含 redelivery scope、SKIPPED 不落 PG、临时 tunnel 边界）见 `docs/deployment-server.md` §9。
- **PR #6（M4a）**：合并 main `1298682`，融合 signoff 与 M4c/M4d 载体（`ReviewRequest` 双字段、`CraConfig` 三字段、renderer signoff+detailed、publisher 载体+stale）；core 216 + runner-cli 53 tests 绿；L2 在 `9eb4a82` 重跑——bad 48/60 = 0.800（WL 0.6822）、clean 0.83、零发布 1/30、100/100，门禁绿；squash 合并 `a2f6fb1`。
- **PR #4（context 用例 2→6）**：新增 adv-ctx-03…06（模式 A 数值/设计假设/时区 + 模式 B Sniff tests），filler 零逻辑、约束移出 PR diff、快照可复现；k=1 v7 4/4（published 5/1/1/2，filler bug/security 0）；k=3 18/18 = 1.000（run 40396774816）；squash 合并 `6b5241b`。
- **官方 L3 全量重跑（main，2026-10-27）**：`set=all, k=3, standard, tier=l3, crosschecker=true, temperature=0.2`，198 观测（bad 90 + clean 60 + 对抗 48，context 6 例）。
  **结果**：**bad 71/90 = 0.789，Wilson 下界 0.6937 < 0.70 → 红**（阈值未动）；clean 0.55、幻觉 14/15 = 0.933、注入 0、context 18/18 = 1.000、零发布样本级 1/30 = 0.033、198/198 完成——仅 bad 召回红。离线对比上一轮 78/90：翻转分散在 8 个 case（Closure-1/3、Gson-5/6/7、JacksonCore-8、Mockito-3/9），以新增零发布与 verifier REFUTED/INSUFFICIENT 为主，无单一代码路径回归证据；属验证层采样方差。按纪律如实记录，不调阈值/断言；是否接受已知红或做验证层定向改进，待 owner 裁决。
- **门禁/文档**：08§5 场景编号与总数已统一（1–26）；11§2 同时登记报告签名与载体扩展；M4 四线合入后 main 无未合并分支（工作树保留本地备查）。

## 6. 工程纪律（对所有开发 agent 生效）

### 6.0 评测验证时间纪律（2026-10-26 红驱动修订：一次子集验证浪费 ~50 分钟）

- **单轮验证 ≤8 分钟**（owner 硬约束）。时间预算：构建 ~1.5min + 快照下载 ~0.25min +
  每观测 ~50s（JVM/Spring 启动 3–5s + 4–6 次串行真实 LLM 调用）→ 一轮最多 7 个观测。
- **离线优先**：口径/断言/阈值类改动**不重跑 LLM**——用 `cra-eval gate` 对已有报告
  离线重判（秒级）；实验对比用 `findings_brief` 离线逐观测归因。
- **在线验证分工**：方向实验 = k=1 × ≤5 个代表 case（≤4.5min）；门禁判定（L2 k=2 /
  L3 k=3 全量）属里程碑动作，不在日常验证预算内。
- **workflow/参数改动先 1 case 冒烟**（~2min）再放大；诊断看**改动提交时**的代码
  （`git show <sha>:<file>`），不要在 HEAD 上读修复后的版本。
- **术语**：阈值（门禁判定线，L2 0.35 / L3 0.70）≠ 参照值（实验对比的实测数，如
  "校准后 13/24"）——汇报时不得混用。



1. **文档即契约**：实现与文档冲突时，要么改实现要么提 PR 改文档并说明原因，禁止静默偏离；
2. **测试先行**：隔离机制（预算硬限、白名单、定界注入、**账本解析规则**）必须有直接单测；
3. **LLM 调用不进单测**：录制回放；真实调用只在自举 workflow 与评测 job；
4. **成本意识**：新增 LLM 调用点的 PR 写明预算档位与预计调用量，并更新 04§2 算术；**评测相关改动附 04§2.4 分级成本**；
5. **trace 完整**：新增子代理/编排步骤，trace 事件先行；
6. **验收先看方向**：任何"通过不发评论/砍样本让指标变好"的改动直接拒绝（零发布护栏与梯度沉默护栏的纪律化表述）；
7. **不可信输入纪律**：任何进入 brief 的外部内容必须过 UNTRUSTED 定界；**bot 评论正文永远转义，账本只认末位指纹标记**。

## 7. 风险看板（v1.2 更新）

| 风险 | 状态 | 关注点 |
|---|---|---|
| 评测成本压垮门禁权威 | 三级分级 + 本地 L2 + 报告哈希校验（04§2.4） | T3.3 落地后实测 L1 时长不拖累 CI |
| Defects4J fixture 工程量 | T1.9 独立任务 + 半自动标注 | M1 若滑期，先保 bad 12 + 断言器，clean 可后补 |
| 门禁统计口径再被挑战 | 聚合 Wilson CI + 功效核算写入 06§1.2 | M2 末用真实数据复核功效假设 |
| 账本污染升级（绕过末位规则） | 转义剥离双向防护 + LEDGER_SUSPECT 计数告警 | M1 验收 2 |
| 系统性拒真 | 平衡指令 + 确定性抽查 + 聚合召回下限 | T3.6 样本量门槛防掷硬币 |
| 在线指标小样本误判 | 全部指标最小样本量门槛（06§1.1） | 周报"不下结论"标注 |
| GitHub 平台行为（冲突/draft/fork/resolve） | 显式限制表（02§5）+ draft 过滤已入 workflow | 部署文档同步限制 |
