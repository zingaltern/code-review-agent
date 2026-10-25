# 07 · 实施路线图与任务拆解

> 文档版本：v1.3.1（2026-10-25，响应第四轮评审缺陷 ⑥及终审建议）
> **v1.2.1**：DONE 完整标记、Summary issue comment 载体、锚行滑窗、Wilson 修正等 7 项。
> **v1.3**：T1.4 新段落解析；M4a 签字规矩；M4f 拆 f-lite/f-full。
> **v1.3.1**：M1 验收 6 与 04§2.4 口径统一（**负向不变量断言**，seed best-effort 局限）；M4f-lite 验收关联对抗集第 4 类；T3.2 对抗集扩充含 context 用例。
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

**目标**：补完原项目设想的端到端流。技术栈 Java 17 / Spring Boot 3.x（仅 DI+配置）/ Maven 多模块。**仅 PR 触发，draft 过滤**（05§4）。

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

- [ ] T2.1 SubAgent 抽象与隔离运行时（03§1）：brief 注入、工具白名单、三重预算硬限、失败隔离；
- [ ] T2.2 BugHunter 四视角模板（03§2.2）+ 视角路由 + 并行扇出/汇聚（并发 8）；
- [ ] T2.3 Security/Test/Convention/Perf 接入；**Security/Perf 按 chunk 扇出**（03§6 v1.2 修正——独享全量 brief 超 2 倍预算，不可行）；
- [ ] T2.4 VerifierAgent（balanced-review 模板，04§4.2）：文件批次组织、三值判定、INSUFFICIENT 受控补查；**反锚定校验器作为质控信号实现**（输出 PASS/FAIL 入 trace，不阻断晋升；归一化与统计口径按 03§4.2 v1.2）；
- [ ] T2.5 判定与晋升规则（03§4.3 M2 形态）：含 important 级 REFUTED 确定性抽查保险丝（sha256(run_id|finding_id) mod 100 < 20，可复现）与验证不可得 → UNVERIFIED 不发布语义；
- [ ] T2.6 JudgeAgent：INSUFFICIENT 仲裁 + 抽查复核 + **上下文按案件界定**（≤3 文件，03§6）；
- [ ] T2.7 ConvergenceGuard：轮次上限 3、diff-only 复审、指纹去重（03§5）；
- [ ] T2.8 精确去重 merge；语义去重留 M3；
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

## 4. M3：评测闭环 + 投票增量

**任务**：

- [ ] T3.1 评测框架完善：k 次运行调度、**聚合 Wilson CI 判定**、容差带、指标报告（06§1.2）；
- [ ] T3.2 bad 扩到 ≥30 + clean ≥20（Defects4J 双侧流水线批量生成 + `cra-eval annotate`）+ 对抗集扩充（含 context 类用例 ≥2，06§1.2 第 4 类）；
- [ ] T3.3 回归门禁三级分级落地（04§2.4）：L1 快检进 CI（每 PR）、L2 本地跑 + CI 校验报告哈希、L3 里程碑/大改；
- [ ] T3.4 处置采集与周报：`cra-eval report` 拉账本（双端点合并、无 resolve 信号）聚合全部指标含漏斗与截断率（06§1.1）；
- [ ] T3.5 badcase 固化命令与流程（06§2）；
- [ ] **T3.6 双异构验证者投票（v1.2 加门槛）**：触发条件 = 拒真评估池 ≥20 样本且推翻率 ≥10%，或处置样本 ≥30 且精确率 <80%；**样本量不足时不判定、不开启**（继续单验证者+抽查）；实施时 `require-distinct-model-family` 硬校验 + 语义去重。

**验收**：
1. 故意改坏一个 prompt → L2/L3 门禁红（聚合下界跌破容差带或零发布护栏触发）；还原 → 绿；
2. 首份周报产出四类指标 + 漏斗 + 样本量标注（不足门槛处注明"不下结论"）；`badcase add` 后条目出现在下次回归；
3. 若 T3.6 开启：同模型家族配置被启动校验拒绝；开启前后 A/B 数据入档。

## 5. M4：可选池（按需排期，互不依赖）

- **M4a 风险分级建议（v1.3 吸收三条签字规矩与分级表，01§3.4 担责）**：输出 Low/Medium/High 建议（只建议不审批——**agent 永远不算 approver**）；三条规矩入设计：① agent 赞成票不计为审批（已有原则）；② **approve 只对特定 commit 有效，新 commit 必须重审**——与账本 DONE/指纹的 commit 绑定语义同源（05§1.3），建议输出绑定 commit_sha；③ **指挥 agent 写代码的人必须在签字名单上**（PR 模板提示项）；分级参考（Anthropic 形态）：低风险（纯文档/功能开关，测试全过 + 可信作者 → 建议 0 人签）/ 中风险（常规业务逻辑、系统提示词 → 1 人）/ 高风险（核心高危路径、难恢复的关键配置 → 2 人）；签字语义 = 核实**意图/假设/设计**而非逐行读过（01§3.4）；
- **M4b webhook 服务化**：cra-server（Spring Boot web + 队列 + PG 账本），CLI/服务共用 core；顺带消除 L2 门禁荣誉制局限（服务端签名报告，04§2.4）；承担 M4f-full 的事件史采集定时任务；
- **M4c push 模式**：直推 commit 的评审（commit-comment summary 模式）；
- **M4d 夜间存量评审**：产出 issue 而非 PR comment；
- **M4e GitLab/Gerrit 适配 + 语义索引/跨文件上下文**；
- **M4f CrossChecker（v1.3 按 03§3.1 详设拆两阶段）**：
  - **M4f-lite（零服务依赖，可独立排期）**：模式 A 子集（隐含假设清单 + jgit 上下游近 8 周变更史 + REVIEW.md Product context/Known incidents 段）+ 模式 B 全量（Sniff tests 段执行）；复用 CI checkout 的本地 jgit 与现有验证晋升管线，与流水线同构；数据前提 = T1.4 解析器新段落支持；
  - **M4f-full**：产品事件史采集管道（工单/事故复盘结构化入库，M4b 定时任务）——"检索矛盾事件"的完整数据面； tribal knowledge 不可见是硬边界（01§3.4），本项只覆盖已文档化部分；
  - 验收（lite）：构造复现"会话超时案例"模式的 fixture（diff 本身无问题 + REVIEW.md Known incidents 含六周前定制条目 + 上下游路径含相关 commit）→ CrossChecker 产出 category=context 的 confirmed finding 并引用矛盾来源；该 fixture 同步固化为对抗集第 4 类用例（06§1.2）进 L3 门禁——context 召回有持续度量，不靠单例自证。

## 6. 工程纪律（对所有开发 agent 生效）

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
