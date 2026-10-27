# 11 · 扩展性契约

> 文档版本：v1.4（2026-10-27，M4f-full：产品事件源 `EventHistorySource` + 事件检索/存储 `EventHistoryQuery`/`EventHistoryStore` 两行登记，ADR-0012；§7 M4f-full 转为已登记并实现）
> 文档版本：v1.3（2026-10-27，M4e 第一半：Git 平台适配 / CommentSource 两行登记 `cra-git-gitlab`（ADR-0011）；Gerrit 与语义索引未做）
> 文档版本：v1.2（2026-10-26，M4b：账本 PG 形态落地 + 报告签名信封登记；M4c/M4d：载体扩展登记——Git 平台适配 / CommentSource / LocalRepoContext / ReviewLedger 四行补 commit-comment 与 issue 载体及键控方法，§7 对应两项转为"已登记并实现"）
> v1.1（2026-10-25，v1.0 入库后协调修订：§3 澄清 1 的 ADR 载体与 10§6 统一为 docs/adr/ 目录机制，消除同批文档间的定义冲突）
> 前置阅读：`02-architecture-design.md`、`03-multi-agent-design.md`、`04-context-and-prompt-design.md`、`05-data-and-integration.md`、`07-roadmap.md`
> 定位：本文是本项目扩展点的**唯一登记表**——目标一句话："想接新平台 / 新角色时，照 checklist 做就不会漏"。
> **诚实声明**：这是个人项目，无对外 API 冻结承诺，不发布 SDK。本契约管的是**未来的自己与开发 agent 在接扩展点时怎么不搞坏既有机制**（幂等 / 收敛 / 验证 / 门禁）。本文不引入新机制，全部内容汇总自仓内既有设计（02/03/04/05/06/07）。

---

## 1. 平衡原则

- 扩展点少而稳定：开放扩展点仅 §2 登记的 7 类（另显式登记 2 处内部接缝），新增扩展点须先修订本文档【强制】；
- 每个扩展点配一份最小实现集 checklist——不配 checklist 的扩展点不得标记为"稳定"或"演进"【强制】；
- 宁可内部实现不开放，不可开了不管：未登记的接缝一律视为内部（§3），变更不通知【强制】；
- 本文档自身按演进级管理：修订本文的 PR 同样走 07 头部三条腿核查【建议】。

划界依据（为什么是这 7 类开放）：开放 = 已有多个已知形态 / 实现（平台、账本三形态、持续新增的角色与模板）且接入动作可预期；内部 = 当前只有一个实现，且语义与全局机制（预算算术、幂等闸、覆盖声明）强耦合，开放即诱导旁路。

## 2. 扩展点总表

新增扩展点时的登记模板【强制】：名称、接口或机制（含定义文档引用）、稳定性等级、当前实现、最小实现集 checklist、接入程序（指向本文 §N）——六列齐全才算登记。

| 扩展点 | 接口或机制 | 稳定性 | 当前实现 | 新增实现的最小集 |
|---|---|---|---|---|
| Git 平台适配 | `GitPlatformAdapter`（05§2.1） | 稳定 | `cra-git-github`：compare diff / PR API / inline comment / 账本解析（02§3、05§2.3）；**M4c/M4d 载体扩展已落地**（commit comment / issue）；**M4e 第一半 `cra-git-gitlab`**（ADR-0011，不依赖 github）：compare diff / discussions / MR notes / commit comments / issues，端点差异与边界表见 `docs/deployment-gitlab.md` | 四方法：`fetchDiff` + `readFile` + `postReview` + `fetchBotComments`（签名出处 05§2.1）；`listFiles` / `fetchDispositionSignals` 按需；**M4c/M4d 载体（05§2.4/§2.5）**：`postCommitReview` + `postIssueReview` 两个 `default` 方法——默认抛 `UnsupportedOperationException`（不可得显式拒绝，§4 第 5 条），平台不支持 push/夜间形态时不得静默丢发布；接入走 §6 checklist |
| 账本/处置取数端口 | `CommentSource`（05§2.1，v1 基线新增，ADR-0005） | 稳定 | `cra-git-github` 默认实现（适配 adapter 方法）；**M4c/M4d 账本读侧**（commit comment / issue）；**M4e 第一半 `cra-git-gitlab`** 同步实现 PR/commit/issue 三类账本（ADR-0011） | 两方法：`fetchBotComments` + `fetchDispositionSignals`（common POJO）；**载体账本扩展（05§1.3）**：`fetchCommitBotComments` / `fetchIssueBotComments` / `listBotIssues` 三个 `default` 方法——前两者默认显式拒绝（静默空账本会导致重复发布），`listBotIssues` 默认空列表（不可得语义）；消费方 = store 的 GitHub 账本实现与 eval report；**新平台接入须同步提供本端口实现**（§6 checklist 并入） |
| 本地仓库上下文 | `LocalRepoContext`（05§2.1） | 稳定 | jgit blame（CI checkout / 本地模式，05§2.1） | `blame` + `localRepoRoot()`；`localRepoRoot()` 为空是**合法形态**——pre-existing 检测降级为不标注 + Summary 声明，不假装有能力（05§2.1）；**M4c：`firstParentChain`**（default 空列表 = 不可得，push 基线解析据此要求显式 `--base`） |
| 评审账本 | `ReviewLedger` SPI（02§5、05§1.5） | 稳定 | 三形态：GitHub 评论账本（CI）/ 本地 SQLite（pre-PR）/ PG（服务形态，M4b 已落地），按部署形态选择（02§5）；**M4c/M4d 键控扩展**：`ReviewKey`（repo + 形态 + (pr\|commit)）| 读已发布指纹 + 写 finding 级 / DONE 标记 + 按 commit 查 DONE；标记规范 05§1.3 **平台无关，原样移植**；键控方法以 default 加入（05§1.3 v1.4），无 PR 载体粒度 = 载体评论级 |
| 报告签名信封 | `ReportSignature`（cra-common，04§2.4、ADR-0008/0009） | 演进（格式约定） | `cra-server` 签发（`/api/v1/reports/attest`，需 `X-CRA-Repo`），`cra-eval verify --signature --repo` 验签 | 行式信封 `cra-report-hmac-sha256-v2`（key_id/report_sha256/repo/commit/tier/issued_at/run_id/signature）；HMAC-SHA256 覆盖版本行至 run_id 行（不含 signature 行）；repo 绑定 owner/name；v1 信封不再接受，格式变更【强制】bump 版本并写 ADR |
| 评审角色 | `SubAgent<I,O>` 统一抽象（03§1） | 演进 | BugHunter 四视角 + Security / Test / Convention / Perf / CrossChecker（03§3） | 接入走 §5 checklist（8 步：role / schema / 预算 / 触发 / 分型 / 对抗集 / 里程碑 / grep 传播） |
| Prompt 模板 | classpath 资源 + 版本记录（04§4） | 演进 | `cra-agents/src/main/resources/prompts/` 目录树（04§4） | 新模板落对应子目录，mustache 占位符 + 引用 shared 三件套 + UNTRUSTED 定界（04§3.3）；`prompt_template_versions` 与 run 关联（05§1.2）；改动必过评测回归（06§1.2、04§2.4） |
| 产品事件源 | `EventHistorySource`（05§1.6、ADR-0012） | 演进 | `JsonlEventHistorySource`：本地文件 / `file:` URI / 对象存储 `http(s)` 预签名 URL（JSONL，不含云 SDK）；Jira/Confluence/服务台等未接 | 两方法：`id()`（水位键）+ `read(since)`（返回 `ReadResult`：事件 + 行级失败 + 行数）；不可得语义 = 抛 `IOException`（采集器有上限重试，耗尽后 `executed=false` + 声明）；接入走 §6 checklist 的"端点核查/增量语义/失败声明"三项 |
| 事件检索/存储 | `EventHistoryQuery` / `EventHistoryStore`（`cra-common`，05§1.6、ADR-0012） | 稳定 | `PostgresEventHistoryStore`（M4f-full；`product_event` + 水位表）；`EventHistoryQuery.empty()` 供 CLI/无数据部署 | 读：`search(EventQuery)` + `find(repo,eventId)`；写：`upsertAll`（幂等键 `(repo,event_id)`）+ `checkpoint/saveCheckpoint`；不可得语义 = 运行时异常 → CrossChecker 降级 `available=false` + 覆盖声明（不得当作"已检查无矛盾"）；接入走 §6 checklist |
| LLM 模型路由 | `application.yml` 三档配置（05§5） | 演进（配置级） | standard / strong / light + fallback-chain + per-model RPM（05§5） | **无代码改动**：配置增条目即可；换模型家族属"模型改动"，触发 L2 评测回归（04§2.4） |
| 评测数据集格式 | 06§3 fixture 规格 | 演进 | bad 30 + clean 20 + 对抗 16（含 context 6；M1 基线为 bad 12 + clean 8 + 对抗 10），badcase 持续回流（06§3） | 每用例 = `{repo_snapshot.tgz, diff.patch, expected_findings.json, meta.json}`，加载器 / 断言器（位置 ±5 行 + 类别 + 断言关键词）兼容（06§3）；格式变更【强制】bump 版本字段并保证旧集可加载——06§1.2 的聚合口径与门禁趋势依赖历史数据集可比 |
| ContextAssembler 切片策略 | L0–L5 分层与裁剪（04§1） | **内部，暂不开放** | cra-context（02§3） | 不开放原因：切片启发式与 04§2 预算算术、Summary 覆盖声明强耦合（04§1 裁剪优先级）——外部定制切片会绕过成本核算与覆盖声明 |
| 编排 pipeline 步骤 | Orchestrator ①–⑦（02§1） | **内部，暂不开放** | cra-core（02§3） | 不开放原因：步骤顺序承载幂等 / 收敛 / 降级语义（02§4）——可插拔步骤抽象会诱导绕过账本闸与预算闸 |

读表规则【强制】：任何"接新东西"的开发工作开始前，先在本表登记新条目或确认命中既有条目；稳定性等级定义见 §3；角色接入程序见 §5，平台接入程序见 §6。

## 3. 稳定性等级定义

| 等级 | 承诺 | 变更程序 |
|---|---|---|
| **稳定** | 签名即契约 | 签名变更须 ADR + 迁移说明【强制】 |
| **演进** | 会变，但变更可追踪 | 变更走 07 头部三条腿核查（数字 / 端点 / 跨文档传播）+ 相关验收条款同步【强制】；prompt 模板类变更另加评测回归（06§1.2） |
| **内部** | 勿依赖 | 变更不通知、不写迁移说明；依赖内部接缝的代码属实现细节，坏了自理 |

三点澄清，不搞 semver——三级 + 各文档头部版本块已覆盖本项目全部场景，版本号管理本身是 imagined 需求（§8.1）：

1. **ADR 的载体（v1.0 → v1.1 修订：与 10§6 统一）**：本节曾定义"不建 ADR 目录、决策记录落在文档版本块"——10§6 已升级该机制为 **docs/adr/ 目录 + 轻量模板**（跨模块接口变更 / 技术选型 / 规范例外 / 破坏性 SPI 变更四类必须写）；文档版本块与 01§8 修订行**保留**为变更轨迹（ADR 记"为什么"，版本块记"改了哪"），二者并行。本节后续所有 "ADR" 一词均指 10§6 定义的目录机制【强制】；
2. **配置级不是第四级**：是演进扩展点的一种变更形态——只碰配置文件（如 05§5 增模型条目），不触碰代码与签名；
3. **稳定 ≠ 永不变**：是"变更必须付出 ADR + 迁移的显式成本"，逼作者先想清楚再动签名。

赋值规则【强制】（登记新扩展点时按此定级，且须在登记行写明定级理由）：

- SPI 接口（有多个实现方 / 多个部署形态依赖）→ 默认**稳定**；
- 内容资源（prompt 模板、评测数据集、配置条目）→ 默认**演进**；它们的"签名"是格式约定而非 Java 签名，演进程序 = 三条腿 + 评测回归；
- 只有一个实现、且与全局机制强耦合的接缝 → 默认**内部**；内部升演进须补最小实现集 checklist（§1 第 2 条）。

## 4. SPI 演进规则【强制】

本节全部条款为强制——SPI 是三形态部署与多平台适配的地基（02§5）：

1. 接口**只加 `default` 方法**，不删、不改已有签名；`GitPlatformAdapter` / `LocalRepoContext` / `ReviewLedger` 同一规则；
2. 破坏性变更（删改签名、语义变化）须 ADR + **全部实现方同步迁移清单**——宁可带 default 方法过渡两个里程碑，不做"一处改三处漏两处"；
3. SPI 入参与返回类型只允许 cra-common 纯 POJO（02§3 依赖规则"所有模块 → cra-common"），禁止平台特异类型出接口；
4. **实现模块不得依赖其他实现模块**：`cra-git-github` 不得 import 未来的 `cra-git-gitlab`，账本三实现同理（02§3）——横向依赖一旦出现，"换形态 / 换平台"就从配置退化成改代码；
5. 新 SPI 方法若非全平台 / 全形态可实现，必须定义**"不可得"语义**（对照先例：`localRepoRoot()` 为空 → pre-existing 降级声明，05§2.1），禁止抛异常假装全平台支持。

正反例对照（扩展 `GitPlatformAdapter` 时）：

- ❌ 反例：加 `List<MergeRequest> fetchGitlabMergeRequests(...)`——平台特异方法进通用 SPI，违第 3 条；GitLab 语义应由新实现类以既有签名承载，装不下说明该走 §4 破坏性变更程序或另立 SPI；
- ✅ 正例：加 `default Optional<BlameResult> remoteBlame(...) { return Optional.empty(); }` 并在 Javadoc 声明"默认不可得，调用方走本地 blame 或降级声明"——同时满足第 1 条（default 过渡）与第 5 条（不可得语义）。

## 5. 新增评审角色接入 checklist

照 07 头部三条腿展开。前车之鉴：四轮评审 34 项缺陷中 6 项属第四轮，其中 4 项是跨文档传播断裂（01§8）——"造了发动机没接变速箱"。8 步如下：

- [ ] 1.【强制】定义 `AgentRole` 与输出 category：**新 category 必须同步 05§1.1 枚举**。先例：`context` 漏进枚举导致 CrossChecker 全部输出被 schema 校验拒收、M4f-lite 验收不可能通过（01§8 四轮缺陷①——同类病第三犯）；
- [ ] 2.【强制】brief 模板落 `prompts/reviewer/<role>.md`（classpath 资源，04§4 目录），引用 shared 三件套（output-contract / evidence-rules / untrusted-delimiter，04§4）；模板即代码，改动走 PR + 评测回归（04§4）；
- [ ] 3.【强制】预算行进 03§6 速查表 + 04§2.2 补增量算术。先例：CrossChecker 无预算行无算术，违反 07§6.4 自立的纪律（01§8 四轮缺陷③）；且其增量 ≈22k（预算上限）/ ≈18.6k（实测输入）已把 Standard 推到 70% 降档线附近（04§2.2，2026-10-27 口径）——新角色必须核算对 run 级 500k 预算的挤占（02§4.4）；
- [ ] 4.【强制】触发条件进 03§3 角色表，并核对深度档位联动（02§4.3：quick / standard / deep 哪档触发、实例数）与 05§5 配置项（新角色默认关闭，先例：`crosschecker.enabled: false`）；
- [ ] 5.【强制】判定 verifier / judge brief 是否需按 category 分型组装（03§4.2）：新角色的证据类型若与代码类不同而不分型 → 验证层必然 INSUFFICIENT → 拒杀（context 类教训，01§8 四轮缺陷②）；
- [ ] 6.【建议】评估对抗集是否需新类别用例（先例：06§1.2 第 4 类 context 用例）——新角色的召回应有门禁度量归属，不靠单一 fixture 自证（03§3.1 局限声明的教训）；
- [ ] 7.【强制】07 里程碑挂任务 + 验收标准（含 L3 全量口径与对抗集关联，写法对照 M4f-lite 验收，07§5）；
- [ ] 8.【强制】grep 全文档核查传播（07 头部第三条腿）：schema（05§1）→ 算术与速查表（04§2、03§6）→ 验证与上下文模型（03§4、04§1 L5）→ 评测与验收（06、07）→ workflow 与配置（05§4-5）——**全部接上才算接完**。

**传播地图示例**（第 8 步做完长什么样——以 `category=context` 为例，修复位置即 01§8 四轮缺陷①②④的落点）：

```
grep "context" 应命中并核对：
  05§1.1  枚举含 context（+ 默认 important 档）
  03§3 / 03§3.1  角色表 + 详设（触发、输入、局限声明）
  03§4.2  verifier/judge 分型组装（context 类注入 cited commit diff 节选）
  04§1 L5  外部证据层（预取内联，≤1k/commit）
  03§6 / 04§2.2  预算行 + 增量算术
  06§1.2  对抗集第 4 类 + L3 门禁口径
  07§5    M4f-lite 任务与验收
  05§5    配置开关
```

**完成定义（DoD）【强制】**：上表 8 步全勾 + grep 传播清单贴进 PR 描述 + 评测回归报告（04§2.4 分级）附上——三缺一不合 PR。

## 6. 新 Git 平台接入 checklist

- [ ] 1.【强制】最小实现集四方法（签名出处 05§2.1）：`fetchDiff` / `readFile` / `postReview` / `fetchBotComments`；`listFiles`（上下文组装）与 `fetchDispositionSignals`（处置周报，06§1.1）按需；blame **不在 adapter**——走 `LocalRepoContext`，无本地仓库时 pre-existing 降级声明（05§2.1）；
- [ ] 2.【强制】账本指纹格式**原样移植**：05§1.3 是平台无关的 markdown 约定（HTML 注释 `cra:` 标记、finding 级 + run 级 DONE 两类标记、五条解析规则、发布侧双向防护），新平台不得自造格式——幂等 / 收敛 / 处置三机制全押在账本上（02§5）；
- [ ] 3.【强制】端点行为核查（07 头部第二条腿）：为 GitHub 钉死过的每条平台事实，新平台逐一重查——冲突 PR 是否触发（GitHub：不触发，02§5 边界表）、review body 可否编辑（GitHub：不可，故 Summary 走 issue comment，05§2.2）、thread resolve 是否 REST 可得（GraphQL-only 即弃，05§2.3）、锚定非法时的 422 语义（05§2.2）；结论按 02§5 触发边界表格式**显式声明而非假装支持**；
- [ ] 4.【强制】draft / 并发语义映射：draft 过滤与转正触发（05§4）、concurrency group + cancel-in-progress——被掐断的 run 无 DONE 标记，新平台的"部分发布中等价物"必须能被账本识别为**不完整**并走指纹级补发，否则复现"其余 5 条永久静默丢失"（02§4.1）；
- [ ] 5.【强制】处置信号可得性核对（06§1.1 信号表：reply 标签 / reaction / 合并事件回填），不可得的信号在周报口径中声明"不采"；
- [ ] 6.【强制】录制回放单测（Git / LLM 调用不进单测，07§6.3）+ `--dry-run` 全程零写断言（05§6）；
- [ ] 7.【建议】新模块 `cra-git-<platform>`，不依赖 `cra-git-github`（§4 第 4 条依赖规则）。

**完成定义（DoD）【强制】**：录制回放单测全绿 + dry-run 零写断言过 + 平台边界表成文（进部署文档）+ fixture 仓库实跑一次端到端回帖与幂等重放（对照 M0 验收 1/3 的口径，07）。

**边界表落点【强制】**：第 3/4/5 步核查出的平台事实，按 02§5 触发边界表格式落三处——部署文档（主要载体）、README 限制声明（02§5 对冲突 PR 的做法：显式声明而非假装支持）、本文 §2 对应行的"当前实现"列（一句话指回部署文档）；三处缺一即重蹈"限制只存在于某人脑子里"。

## 7. M4 可选池与扩展点关系

| M4 项（07§5） | 关系 | 说明 |
|---|---|---|
| M4a 风险分级建议 | 编排内部 + Prompt 模板（演进） | 聚合与 Summary 逻辑属编排内部（§2 暂不开放）；建议输出绑定 commit_sha，复用账本 DONE / 指纹的 commit 绑定语义（07§5） |
| M4b webhook 服务化 | 启用既有扩展点 | ReviewLedger PG 形态（02§5 三形态表）已落地（M4b）；cra-server 只是新壳——core 全部逻辑与形态无关（02§5），CLI 与 server 同构；报告签名信封为新增演进级格式约定（§2，ADR-0008） |
| M4c push 模式 | 已登记并实现（§2 载体扩展） | 触发层 push 事件 + commit-comment 发布载体：`postCommitReview`（default，不可得显式拒绝）+ `ReviewKey(repo, commit)` 键控 + `LocalRepoContext.firstParentChain` 基线解析（05§1.3/§2.4） |
| M4d 夜间存量评审 | 已登记并实现（§2 载体扩展） | 产出 issue 而非 PR comment（07§5、06§4）：`postIssueReview` + `fetchIssueBotComments`/`listBotIssues`；评审流水线与账本复用，仅触发与发布形态变化（05§2.5） |
| M4e GitLab / Gerrit 适配 | 启用既有扩展点 | **GitLab 第一半已落地（2026-10-27）**：`cra-git-gitlab` 走 §6 checklist 1–7 步，ADR-0011 + `docs/deployment-gitlab.md` 边界表；Gerrit 未做。同项的"语义索引 / 跨文件上下文"属 ContextAssembler 内部（§2）；CLI/server 平台选择与真实写操作留第二半 |
| M4f-lite CrossChecker | 启用既有扩展点 + 纯配置 | 新角色走 §5 checklist；数据前提 = T1.4 REVIEW.md 新段落解析（07§5）；开关 = `crosschecker.enabled`（05§5），默认关闭不动 Standard 基线算术（04§2.2） |
| M4f-full 事件史管道 | 已登记并实现（§2 产品事件源 / 事件检索存储两行） | 产品事件史是独立数据面（依赖 M4b 定时任务，07§5）：JSONL 源 → 采集器 → PG → `EventHistoryQuery` → CrossChecker/验证层引用；ADR-0012。Jira/Confluence/服务台等连接器留作 `EventHistorySource` 实现，不在第一版 |

读法【强制】：标"需新增扩展点"的项动工前必须先修订 §2 总表登记（§1 第 1 条）——M4c/M4d 已于 2026-10-26 登记并实现（载体方法入 §2 Git 平台适配 / 账本取数端口两行）；M4f-full 已于 2026-10-27 登记并实现（§2 产品事件源 / 事件检索存储两行，ADR-0012）；"启用既有"与"纯配置"项不得顺手改内部接缝——改了即触发 §3 演进变更程序。

## 8. 反模式

1. ❌ **为 imagined 需求加抽象层**
   "将来也许支持 X"不构成建扩展点的理由——M4e GitLab 排在可选池，正因为它只在真接入时才有端点事实可核查（07 头部第二条腿）；过早抽象 = 提前支付维护成本且大概率猜错方向。
2. ❌ **SPI 方法返回平台特异类型**
   平台类型一旦出接口，"三形态 / 多平台"退化为改代码（§4 第 3 条）；类型必须停在 cra-common 纯 POJO。
3. ❌ **绕过账本直接写评论**
   幂等（DONE 闸）、收敛（指纹去重）、处置采集（reply / reaction 锚定）全部依赖账本为唯一事实源（02§5、05§1.3）——直写评论 = 三机制同时旁路。
4. ❌ **绕过 SubAgent 抽象直连 LlmGateway 写"临时 agent"**
   预算硬限、工具白名单、UNTRUSTED 定界、trace 留痕四道闸全在抽象内（03§1、07§6.5）——直连即四闸全失。
5. ❌ **prompt 以代码内字符串散落**
   模板必须 classpath 资源 + 版本化，否则 `prompt_template_versions` 无法与 run 关联、线上问题无法定位到版本（04§4、06§1.2）。
6. ❌ **新角色 / 类别 / 段落只改一处就提 PR**
   四轮 6 项缺陷中 4 项是跨文档传播断裂（01§8）——§5 第 8 步的 grep 不是可选项，是最后防线。

---

## 9. 变更记录

| 版本 | 日期 | 内容 |
|---|---|---|
| v1.0 | 2026-10-25 | 首版：7 类开放扩展点 + 2 处内部接缝登记；三级稳定性；SPI 演进规则；角色 / 平台接入 checklist（各含 DoD 与传播地图）；M4 可选池映射 |
| v1.1 | 2026-10-25 | §3 澄清 1 的 ADR 载体与 10§6 统一为 docs/adr/ 目录机制 |
| v1.2 | 2026-10-26 | M4c/M4d 载体扩展登记（Git 平台适配 / CommentSource / LocalRepoContext / ReviewLedger 四行）；§7 两项转为"已登记并实现" |
| v1.3 | 2026-10-27 | M4e 第一半：Git 平台适配 / CommentSource 两行登记 `cra-git-gitlab`（ADR-0011）；§7 M4e 更新为 GitLab 已落地、Gerrit 未做 |
| v1.4 | 2026-10-27 | M4f-full：新增产品事件源 `EventHistorySource` 与事件检索/存储 `EventHistoryQuery`/`EventHistoryStore` 两行（ADR-0012）；§7 M4f-full 转为已登记并实现；Jira/Confluence 等连接器列为后续实现 |
