# 07 · 实施路线图与任务拆解

> 文档版本：v1.1（2026-10-25，响应方案评审缺陷 ①⑤⑧）
> **v1.1 修订要点**：**里程碑重排**——pre-PR 本地自评审（博客中杠杆率最高的机制，估计 2~3 倍收益）从 M3 提前进 M2；异构双验证者投票从 M2 核心**降级为 M3 的数据门禁增量**（M2 = 多视角隔离审查 + 单强验证者独立复核 + 仲裁）；最小评测集前置到 M1（修复 M2 验收依赖 M3 数据集的死锁）；验收体系加入召回下限与零发布护栏；门禁全部改为 k=3 统计判定。
> 面向后续开发 agent：按里程碑顺序执行，**未达验收标准不进入下一阶段**。

---

## 0. 总览

| 里程碑 | 主题 | 周期（估） | 核心产出 |
|---|---|---|---|
| M0 | 骨架与最小闭环 | 1 周 | 单 agent 端到端：PR → diff → LLM 评审 → 回帖 + trace artifact |
| M1 | 工程化与账本 | 1.5 周 | GitHub 评论账本（幂等/指纹）、REVIEW.md（base 读取）、深度档位、大 diff 分 chunk、**最小评测集** |
| M2 | **隔离审查 + 独立复核 + pre-PR**（核心） | 2 周 | 多视角并行 reviewer（隔离）、单强验证者独立复核、仲裁 judge、收敛控制、**本地 pre-PR 模式**、注入防御、LLM 并发限流 |
| M3 | 评测闭环 + 投票增量 | 1.5 周 | k=3 统计门禁、处置采集周报、完整 bad-PR 集、**双异构验证者投票（数据门禁引入）** |
| M4 | 服务化与扩展（可选池） | 按需 | webhook server、push 模式、风险分级建议、夜间存量评审、GitLab 适配 |

依赖链：M0 → M1 → M2 → M3；M4 各项独立可插队。

**M2 形态说明（对评审缺陷 ⑧ 的回应）**：评审建议 M2 只做"单 reviewer + 单 verifier + judge + pre-PR"。本项目保留 **2 视角并行 BugHunter**，理由：① 并行多 agent 找 bug 是 Anthropic 博客明确的生产实践（有背书）；② 多视角隔离审查是本项目立项的核心需求（用户明确要求"子 agent 隔离性审查"）；③ 边际成本可控（chunk 化后每视角增量约 80k token，04§2 算术内）。**被降级的是证据最弱的部分**（双验证者投票）——M2 单验证者 + 重要级 REFUTED 抽查保险丝，M3 用拒真率数据决定是否引入第二验证者。

## 1. M0：骨架与最小闭环

**目标**：补完原项目设想的端到端流，技术栈 Java 17 / Spring Boot 3.x（仅 DI+配置）/ Maven 多模块。**仅 PR 触发**（02§5）。

**任务**：

- [ ] T0.1 建仓：按 02§3 建模块骨架与依赖关系，根 pom 定 Java 17；
- [ ] T0.2 cra-llm：`LlmGateway` 最小实现——单模型、chat+JSON 双模式、超时重试、并发信号量（8）、token 计数；
- [ ] T0.3 cra-git-github：`fetchDiff`（compare API）+ `postReview`（含 **§2.2 锚定校验与逐条降级**的最小版）+ `readFile`；WireMock 录制回放单测；
- [ ] T0.4 cra-agents：单一 `GeneralReviewer` prompt（04§4.1 简化版），UNTRUSTED 定界从第一天内建；
- [ ] T0.5 cra-core：线性 pipeline：assemble → review(×1) → publish；发布前**账本去重的最小实现**（拉 bot 历史评论比对 commit_sha，同 sha 跳过）；
- [ ] T0.6 cra-runner-cli：`cra review` + 参数解析 + 退出码（05§3）；
- [ ] T0.7 自举 workflow（05§4：仅 PR 触发 + **artifact 上传**）跑通真实回帖。

**验收**：
1. fixture 仓库提 PR，2 分钟内收到 inline comment；位置非法的 finding 出现在 Summary 而非导致 422；
2. `--dry-run` 全程零写操作（单测断言）；
3. 同一 PR 重复触发不重复回帖（账本最小版生效）；
4. trace artifact 在 Actions 页面可下载且含 brief 全文；
5. LLM 重试与 GitHub 退避单测通过；CI 全绿。

## 2. M1：工程化与账本

**任务**：

- [ ] T1.1 Finding/ReviewRun/Trace 完整模型（05§1）+ `ReviewLedger` SPI + GitHub 账本实现（指纹 HTML 注释读写，05§1.3）；本地 SQLite 实现（本地模式用）；
- [ ] T1.2 幂等与收敛账本化：指纹级去重、同 commit 全命中仅更新 Summary、部分命中补发（02§4.1）；
- [ ] T1.3 ContextAssembler：L0/L1 分层（**窗口默认 ±30 + 条件全文**，04§1）+ token 估算裁剪 + 覆盖声明进 Summary；
- [ ] T1.4 REVIEW.md：base SHA 读取 + PR 内变更检测警告 + 解析容错（04§3）；
- [ ] T1.5 深度档位 quick/standard/deep 参数化（此阶段 reviewer 仍单角色）；
- [ ] T1.6 大 diff 分 chunk + 降档阶梯（>2k 降 Quick 质量、>8k priority_paths，02§4.4）；
- [ ] T1.7 nit 预算、severity 排序、pre-existing 的 **jgit blame** 复核 + 无本地仓库降级声明（05§2）；
- [ ] **T1.8 最小评测集（v1.1 前置，修复死锁）**：对抗集 v0 ≥10（幻觉 5 / 注入 3 / 拒真 2）+ clean 集 ≥10 + 本仓库历史 bad-PR 种子 ≥8；`cra-eval run` 可执行单次评测并出指标。

**验收**：
1. 连续 push 两次（synchronize），第二次只评审增量、无重复 comment（指纹账本生效）；
2. 2000+ 行 diff 正常完成且降档与覆盖范围在 Summary 如实声明；
3. 在 PR 中修改 REVIEW.md 加 `Skip paths: src/**`，评审**不受影响**且 Summary 出现策略变更警告（注入防御验收①）；
4. 对抗集注入用例 3 次运行零行为改变（注入防御验收②）；
5. T1.8 数据集就位且 `cra-eval run` 出首份指标报告。

## 3. M2：隔离审查 + 独立复核 + pre-PR（核心）

**目标**：03 文档主体落地。评审与评判分离、隔离机制成立、pre-PR 闭环可用。

**任务**：

- [ ] T2.1 SubAgent 抽象与隔离运行时（03§1）：brief 注入、工具白名单（read_file scoped）、三重预算硬限、失败隔离；
- [ ] T2.2 BugHunter 四视角模板（03§2.2）+ 视角路由 + 并行扇出/汇聚（并发 8）；
- [ ] T2.3 Security/Test/Convention/Perf 按触发条件接入；security pass 独立（03§3）；
- [ ] T2.4 VerifierAgent（**balanced-review 模板，04§4.2**）：文件批次组织、三值判定、INSUFFICIENT 受控补查、**反锚定输出约束校验器**（rationale 与 finding 文本重叠率检测——替代 v1.0"断言剥离器"，可判定、可单测）；
- [ ] T2.5 判定与晋升规则（03§4.3 M2 形态）：含 **important 级 REFUTED 抽查保险丝（默认 20%）** 与**验证不可得 → UNVERIFIED 不发布**语义；
- [ ] T2.6 JudgeAgent：INSUFFICIENT 仲裁 + 抽查复核，一次性终裁（03§4.4）；
- [ ] T2.7 ConvergenceGuard：轮次上限 3、diff-only 复审、指纹去重（03§5）；
- [ ] T2.8 精确去重 merge；语义去重留 M3 TODO；
- [ ] T2.9 LlmGateway 模型路由（standard/strong/light）+ per-model RPM + 降级链（02§4.5）；
- [ ] **T2.10 pre-PR 本地模式（v1.1 从 M3 提前）**：`cra review --local`，本地 SQLite 账本、默认 dry-run、`--fix-suggest` 输出修复建议清单（不自动改码）；对应博客 pre-PR verification 中"第二个子代理以全新上下文复检 diff"的角色由本地评审承担；
- [ ] T2.11 prompt 模板版本化并与 run 关联（05§1.2）。

**验收（k=3 统计判定，对抗集/clean 集用 T1.8 数据）**：
1. 对抗集幻觉用例：每条在 3 次运行中 ≥2 次被 REFUTED（硬门槛）；
2. **召回下限**：bad-PR 种子（≥8 条）检出率 3 次均值 ≥60%，且每个样本 3 次中 ≥2 次产出 ≥1 条 confirmed（**零发布护栏**——"什么都不发"不再能通过验收）；
3. 反锚定校验器单测：构造"rationale 抄袭 finding 原文"的样本必须被检出；
4. 注入防御回归：M1 验收 3/4 持续通过；
5. 单 reviewer 注入超时 → run 以 DEGRADED 完成、Summary 声明；验证不可得 → UNVERIFIED 计数出现在 Summary；
6. Standard 档（≤2k 行、≤10 文件）p95 ≤5min、run 预算降档阶梯生效（04§2 算术实测核对）；
7. clean 集误报数对比 M1 单 reviewer 基线**下降 ≥30%**（评判层存在价值的直接证据）；
8. `cra review --local` 在本仓库一个真实分支上完成本地自评审并输出建议清单。

## 4. M3：评测闭环 + 投票增量

**任务**：

- [ ] T3.1 评测框架完善：k=3 多次运行调度、多数/均值判定、容差带、指标报告（06§1.2 全规则）；
- [ ] T3.2 bad-PR 完整集 ≥30（Defects4J 改造 + 本仓库 fix commit 反查）+ 对抗集扩充；
- [ ] T3.3 回归门禁 CI job：prompt/模型/编排改动强制过门禁（06§1.2）；
- [ ] T3.4 处置采集与周报：`cra-eval report` 拉账本聚合精确率/误报率/零发布率/拒真率（06§1.1）；
- [ ] T3.5 badcase 固化命令与流程（06§2）；
- [ ] **T3.6 双异构验证者投票（数据门禁开启，v1.1 降级至此）**：仅当 M2 末拒真率 ≥10% 或发布精确率 <80% 时实施；`require-distinct-model-family` 配置硬校验（05§5）；语义去重（嵌入相似度）一并实施。

**验收**：
1. 故意改坏一个 prompt → k=3 门禁红（检出率均值降 >2pp 或零发布护栏触发）；还原 → 绿；
2. 首份周报产出四类指标；`badcase add` 后的条目出现在下次回归；
3. 若 T3.6 开启：双验证者配置下同模型家族的配置被启动校验拒绝；开启后拒真率与精确率的 A/B 对比数据入档（决定去留）。

## 5. M4：可选池（按需排期，互不依赖）

- **M4a 风险分级建议**：Low/Medium/High 建议（只建议不审批）；
- **M4b webhook 服务化**：cra-server（Spring Boot web + 队列 + PG 账本），CLI/服务共用 core；
- **M4c push 模式**：直推 commit 的评审（commit-comment summary 模式，不依赖 PR diff）；
- **M4d 夜间存量评审**：产出 issue 而非 PR comment；
- **M4e GitLab/Gerrit 适配** + 语义索引/跨文件上下文（Greptile 路线）。

## 6. 工程纪律（对所有开发 agent 生效，v1.1 增补第 7 条）

1. **文档即契约**：实现与文档冲突时，要么改实现要么提 PR 改文档并说明原因，禁止静默偏离；
2. **测试先行**：隔离机制（反锚定校验、预算硬限、白名单、定界注入）必须有直接单测；
3. **LLM 调用不进单测**：录制回放；真实调用只在自举 workflow 与评测 job；
4. **成本意识**：新增 LLM 调用点的 PR 写明预算档位与预计调用量，并更新 04§2 算术；
5. **trace 完整**：新增子代理/编排步骤，trace 事件先行；
6. **验收先看方向**：任何"通过不发评论让指标变好"的改动直接拒绝（零发布护栏的纪律化表述）；
7. **不可信输入纪律**：任何进入 brief 的外部内容（diff/注释/commit msg/finding/验证输出）必须过 UNTRUSTED 定界，review 时逐条检查。

## 7. 风险看板（v1.1 更新）

| 风险 | 状态 | 关注点 |
|---|---|---|
| 验证层成本 | 设计已对冲（文件批处理 + 单验证者起步 + 按门禁扩容） | M2 末实测成本/延迟 vs 04§2 算术 |
| 系统性拒真 | M2 平衡指令 + 抽查保险丝 + 召回下限门禁 | 拒真率数据决定 T3.6 |
| 上下文不足 → INSUFFICIENT 涌现 | 窗口策略 + 条件全文 + 受控补查 | 对抗集拒真用例监控 |
| 账本解析被评论编辑破坏 | 指纹只增不删语义；解析失败按空账本保守处理（宁可重复也不漏发） | M1 验收 1 |
| 评测集小、门禁波动 | k=3 统计判定 + 容差带；数据集每周回流 | T3.2 |
| GitHub API/锚定变更 | SPI 隔离 + 逐条降级兜底 | M0 验收 1 |
