# 07 · 实施路线图与任务拆解

> 文档版本：v1.0（2026-10-25）
> 面向后续开发 agent：按里程碑顺序执行，每个里程碑有明确验收标准；**未达验收标准不进入下一阶段**。

---

## 0. 总览

| 里程碑 | 主题 | 周期（估） | 核心产出 |
|---|---|---|---|
| M0 | 骨架与最小闭环 | 1 周 | 单 agent 端到端：PR → diff → LLM 评审 → 回帖 |
| M1 | 结构化与工程化 | 1 周 | Finding schema、幂等、trace、REVIEW.md、深度档位 |
| M2 | **多子代理隔离评审 + 盲评验证**（核心） | 2 周 | 并行多视角 reviewer、对抗式盲评 verifier、仲裁 judge |
| M3 | 评测与反馈闭环 | 1.5 周 | 评测集、回归门禁、噪声度量、badcase 流程 |
| M4 | 服务化与扩展（可选池） | 按需 | webhook server、风险分级建议、夜间存量评审、GitLab 适配 |

依赖链：M0 → M1 → M2 → M3；M4 各项相互独立可插队。

## 1. M0：骨架与最小闭环

**目标**：复刻并补完原项目设想的端到端流（原项目只写到"打印 diff"），技术栈 Java 17 / Spring Boot 3.x（仅 DI+配置）/ Maven 多模块。

**任务**：

- [ ] T0.1 建仓：按 02§3 建 Maven 多模块骨架（cra-common/llm/git/git-github/context/agents/core/runner-cli/store 先建空模块与依赖关系），根 pom 定 Java 17；
- [ ] T0.2 cra-llm：`LlmGateway` 最小实现——单模型、chat + JSON 双模式、超时重试、token 计数落 trace；
- [ ] T0.3 cra-git-github：`fetchDiff`（compare API）+ `postReview`（PR Review API，inline comment）+ `readFile`；单测用 WireMock 打 API 录制回放；
- [ ] T0.4 cra-agents：单一 `GeneralReviewer` prompt（基于 04§4.1 骨架简化版），结构化输出 Finding；
- [ ] T0.5 cra-core：线性 pipeline（无并行无验证）：assemble → review(×1) → publish；
- [ ] T0.6 cra-runner-cli：`cra review` 命令 + 参数解析 + 退出码语义（05§3）；
- [ ] T0.7 自举 workflow（05§4）跑通：对测试 PR 真实回帖。

**验收**：
1. 在 fixture 测试仓库提 PR，2 分钟内收到机器评审 inline comment（哪怕质量一般）；
2. `cra review --dry-run` 全程零写操作；
3. 单测覆盖 llm 重试与 github 限流退避逻辑；CI 全绿。

## 2. M1：结构化与工程化

**目标**：把 M0 的"能跑"变成"可信、可配、可查"。

**任务**：

- [ ] T1.1 Finding/ReviewRun/Trace 完整数据模型（05§1）+ cra-store SQLite 落库与迁移脚本；
- [ ] T1.2 幂等：run 幂等键、published_comment 去重、重放补发（02§4.1）；
- [ ] T1.3 ContextAssembler：L0/L1 分层 + token 估算与裁剪 + 裁剪声明进 Summary（04§1-2）；
- [ ] T1.4 REVIEW.md 解析器（04§3）+ 优先/跳过路径生效 + 解析容错；
- [ ] T1.5 深度档位 quick/standard/deep（参数化扇出度，此阶段 reviewer 仍为 1 个角色）；
- [ ] T1.6 大 diff 分 chunk 策略与硬上限（02§4.4）；
- [ ] T1.7 nit 预算与 severity 排序、pre-existing 的 blame 复核。

**验收**：
1. 同一 PR 重复触发不产生重复 comment；
2. 2000+ 行 diff 的 PR 正常完成且 Summary 如实声明覆盖范围；
3. REVIEW.md 改 nit budget=0 后该 PR 不再出现 nit；
4. 任意 run 可凭 trace + briefs 离线复现（dry-run 重放结果一致）。

## 3. M2：多子代理隔离评审 + 盲评验证（项目核心，质量优先）

**目标**：03 文档全量落地。这是本项目区别于普通 LLM CR 脚本的分水岭。

**任务**：

- [ ] T2.1 SubAgent 抽象与隔离运行时（03§1）：brief 注入、工具白名单（read_file scoped 实现）、token/轮次/时间三重预算硬限、失败隔离；
- [ ] T2.2 BugHunter 四视角模板（03§2.2）+ 视角选择路由 + 并行扇出/汇聚；
- [ ] T2.3 SecurityReviewer / TestReviewer / ConventionReviewer / PerfReviewer 按触发条件接入；
- [ ] T2.4 VerifierAgent：盲评 brief 生成器（**断言剥离器**：只留断言不留推理——重点单测）+ refute 模板 + 三值判定 + INSUFFICIENT 时的受控补证；
- [ ] T2.5 投票晋升规则与 security 加严（03§4.3）；
- [ ] T2.6 JudgeAgent 仲裁（分歧触发，强模型档）；
- [ ] T2.7 ConvergenceGuard：轮次上限、diff-only 复审、finding 指纹去重（03§5）；
- [ ] T2.8 精确去重 merge（同位置同类别），语义级去重留 TODO；
- [ ] T2.9 LlmGateway 模型路由（standard/strong/light）+ 异构模型分配到视角；
- [ ] T2.10 prompt 模板版本化，与 run 关联。

**验收**：
1. 对抗集 v0（06§3.4，≥10 条）：幻觉 finding 100% 被 verifier 拒绝（这是硬门槛）；
2. 盲评隔离单测：verifier brief 中断言剥离器输出不含发现者 reasoning 字段（正则+人工抽查断言）；
3. 单 reviewer 失败（注入超时）时 run 以 DEGRADED 完成并在 Summary 声明；
4. 标准 depth 延迟 p95 ≤5min、run 预算硬限生效（超限代理被杀且记录）；
5. 与 M1 相比 clean-PR 上误报数下降 ≥30%（M2 的存在意义）。

## 4. M3：评测与反馈闭环

**任务**：

- [ ] T3.1 数据集框架：加载/重放/断言引擎（expected_findings 匹配器）；
- [ ] T3.2 bad-PR v0（≥30）+ clean-PR v0（≥20）+ 对抗集扩充（06§3）；
- [ ] T3.3 回归门禁 CI job（06§1.2 规则），prompt 改动强制过门禁；
- [ ] T3.4 在线处置采集：`:wrong:`/`:intentional:` 标签监听（scheduled job 或 workflow）、fixed 自动初判、周报 `cra-eval report`；
- [ ] T3.5 badcase 固化命令与流程（06§2）；
- [ ] T3.6 运行健康度聚合与分歧率告警阈值。

**验收**：
1. 改坏一个 prompt（故意）→ 回归门禁红；还原 → 绿；
2. 首份周报产出：精确率、误报率、分视角噪声分布；
3. badcase `add` 后出现在下一次回归运行中。

## 5. M4：可选池（按需排期，互不依赖）

- **M4a 风险分级建议**：规则+LLM 分类器输出 Low/Medium/High 建议（只建议不审批，01§4.2）；
- **M4b webhook 服务化**：cra-server（Spring Boot web + 队列 + 多仓库配置），CLI 与其共用 core（02§5 约束）；
- **M4c 夜间存量评审**：重点目录 nightly，产出 issue（06§4）；
- **M4d GitLab/Gerrit 适配**：实现 GitPlatformAdapter SPI 即可接入；
- **M4e 语义去重与跨文件索引**：finding 嵌入相似度去重；代码库索引供跨文件上下文（Greptile 路线，成本高后置）。

## 6. 工程纪律（对所有开发 agent 生效）

1. **文档即契约**：02-06 文档是设计契约，实现与文档冲突时，要么改实现要么提 PR 改文档并说明原因，禁止静默偏离；
2. **测试先行**：隔离机制（brief 剥离、预算硬限、白名单）必须有直接单测，不接受"集成里顺便测了"；
3. **LLM 调用不进单测**：一律录制回放；真实调用只在自举 workflow 与评测 job 中发生；
4. **成本意识**：任何新增 LLM 调用点的 PR 必须写明预算档位与预计调用量；
5. **trace 完整**：新增任何子代理/编排步骤，trace 事件先行，否则 review 不通过（自己评审自己时会被拦截——这正确）。

## 7. 风险看板（随里程碑滚动更新）

| 风险 | 当前状态 | 关注点 |
|---|---|---|
| 验证层成本翻倍 | 设计已对冲（verifier 只处理候选而非全量；quick 档单验证者） | M2 结束时核算真实成本比 |
| 断言剥离器质量不足（盲评被击穿） | M2 验收 1 直接考察 | 剥离器规则与单测 |
| 评测集太小导致回归门禁波动 | 冷启动方案已定（06§3） | 数据集每周增量 |
| GitHub API 变更 | 适配器 SPI 隔离 | M4d 顺带验证抽象有效性 |
