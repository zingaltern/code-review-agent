# ADR-0012 产品事件史数据模型与引用格式（M4f-full）

- 状态：accepted（2026-10-27，M4f-full 第一版实现随本 ADR 落地）
- 关联：03§3.1（CrossChecker 模式 A）、03§4.2（context 类分型组装）、04§1 L5（外部证据层）、05§1.6/§5、07§5 M4f-full、11§2、ADR-0008（PG 服务形态）
- 影响面：`cra-common` 事件模型/端口与 `ContextCitation`；`cra-core` CrossChecker 材料与验证层引用解析；`cra-agents` cross-checker 模板；`cra-server` 事件源/采集器/PG 存储/调度/配置；`docs/deployment-server.md`

## 1. 问题

M4f-lite 的 CrossChecker 只能看到两类外部证据：git 变更史（jgit）与 REVIEW.md 的 Product context / Known incidents / Sniff tests。
会话超时案例（01§3.4）的根因往往记录在**工单/事故复盘**里——这些内容不在仓库、不在 diff，也不一定被写进 REVIEW.md。
M4f-full 要给模式 A 补上"已文档化的产品事件史"这一数据面，同时**不得假装覆盖 tribal knowledge**（口头约定、未记录的事故仍然不可见）。

约束：

1. 第一版不接 Jira / Confluence / 服务台 API（端点、认证、增量语义各不相同；没有真实凭据无法验收）；
2. 数据必须可幂等重放、可增量、失败可声明；默认关闭；
3. CrossChecker 产出的 context finding 必须能引用具体事件，验证层要能按引用预取事件原文，否则重演"验证层对代码外证据失明 → 必然 INSUFFICIENT → 拒杀"（03§4.2 的教训）；
4. 事件摘要来自外部系统，属不可信输入；`raw_json` 只入库，不直接进 brief。

## 2. 决策

### 2.1 数据源：JSONL（本地文件 / 对象存储 HTTP(S)）

- 第一版输入是 owner 维护的 JSONL：每行一个 JSON 对象。位置支持本地路径、`file:` URI、`http(s):` URL（S3/GCS 预签名 URL 直接可用，不引入云 SDK）。
- 读取端口 `EventHistorySource`（`cra-server`，11§2 登记为演进扩展点）：`id()` + `read(since)`。Jira/Confluence/服务台连接器是后续实现，不改编排层。
- 刻意不先做"自动抓取"：工单与复盘的**结构化字段**因团队而异，第一版把"结构化"这一步交给 owner 的导出脚本，采集器只负责窗口、去重、重试与入库。

### 2.2 事件 schema 与幂等键

必填/可选字段（`ProductEvent`，05§1.6 表 `product_event`）：

| 字段 | 必填 | 说明 |
|---|---|---|
| `event_id` | 是 | 源系统内唯一标识（`INC-42` / `TICKET-7` / UUID）；字符集 `[A-Za-z0-9][A-Za-z0-9._:#/-]{0,199}` |
| `repo` | 是 | `owner/name`，统一小写存储与查询 |
| `service` / `tenant` | 否 | 服务与租户（多租户事故常只影响特定租户） |
| `occurred_at` | 是 | ISO-8601（支持偏移，如 `+08:00`）；窗口与水位依据 |
| `type` | 是 | `ticket｜incident｜postmortem｜change｜other`（小写；postmortem 计入事故复盘统计） |
| `summary` | 是 | 事件摘要；**brief 只注入这一段**（≤240 字符），`raw_json` 不进 brief |
| `source_url` | 否 | 源系统链接（人工核对） |
| `commit_refs` | 否 | 关联 commit（小写十六进制 4–64 位；短 sha 与完整 sha 前缀互配） |
| `paths` | 否 | 事件影响的仓库相对路径或目录前缀（**本 ADR 的扩展字段**，用于按变更路径检索） |
| `raw_json` | 否 | 源记录原始 JSON（紧凑序列化；只入库，不注入 brief） |
| `collected_at` | 是 | 采集时间（源缺省时由采集器写入） |

**幂等键 = `(repo, event_id)`**：同键重复采集按覆盖语义更新内容，绝不产生第二行。`paths` 是可选扩展而非必填 schema——事件源不声明路径时，只能靠 commit 引用或关键词命中，检索说明如实反映。

路径语义（`EventPathMatcher`）：文件路径精确匹配；目录前缀按路径段边界匹配（`src/billing` 命中 `src/billing/Invoice.java`，不命中 `src/billing2/...`）；`/**`、`/*`、尾部 `/` 在采集时规范化为目录前缀。不支持任意 glob，文档显式声明。

### 2.3 存储：PostgreSQL（复用 M4b DataSource）

`server.sql` 增两张表（手写幂等 DDL，`ServerSchemaMigrator` 版本 `server-0002`）：

- `product_event`：主键 `(repo, event_id)`；`repo + occurred_at DESC` 索引（时间窗口）；`commit_refs` / `paths` 为 JSONB 数组 + GIN 索引；
- `event_collection_checkpoint(source_id, watermark, updated_at)`：按事件源保存水位，只前进不后退。

`PostgresEventHistoryStore` 实现 `EventHistoryStore`：批量 upsert（`ON CONFLICT (repo, event_id) DO UPDATE`）、水位读写、按 repo/路径/commit/关键词/时间检索。检索实现把路径/commit 过滤下推 SQL；关键词对 `summary` 与 `raw_json` 做大小写不敏感子串匹配（`%`/`_` 转义）。

### 2.4 采集语义（`IncrementalEventHistoryCollector`）

1. **窗口**：`effectiveSince = max(请求窗口起点, 上次水位 − overlapHours)`；overlap 吸收迟到事件，幂等键保证重叠不产生重复；
2. **去重**：批内同键以后出现者为准；跨批由存储唯一键兜底；
3. **单次上限**：事件按发生时间升序入库，超过 `maxEventsPerRun` 截断，水位只推进到已入库部分，剩余下次继续；
4. **重试**：来源读取与入库各自有上限重试（`maxAttempts`，退避 `n × backoff`），耗尽后 `executed=false` + note 声明，**不假装成功**；
5. **水位推进**：仅当入库成功且**无行级解析失败**时推进；解析失败不推进（失败行下次重试），note 与 `failed` 计数如实记录；入库失败明确声明"可能已部分写入，幂等键保证重跑无重复"；
6. **默认关闭**：`CRA_SERVER_EVENT_HISTORY_ENABLED=false`；开启时 `CRA_SERVER_EVENT_HISTORY_SOURCE` 必填（fail closed），cron 由 `CronExpression` 组装期校验。

### 2.5 查询端口与 CrossChecker 接入

- `EventHistoryQuery`（`cra-common`，11§2）：`search(EventQuery)` + `find(repo,eventId)`。`EventQuery` 的过滤是"条件间 AND、同项内 OR"。
- `CrossCheckEventAssembler`（core）：查询面 = 本 PR 变更路径 + PR/变更史 commit + 由路径与 commit message 派生的关键词；窗口与变更史同源（`historyWeeks`，默认 8 周）；最多 20 条。
- 材料预算（03§6）：CrossChecker 输入仍 ≤16k = diff 精简视图 ~8k + **变更史与事件史合计 ≤4k** + 产品段 ≤2k。事件优先占 ≤2k，剩余留给 commit；超预算截断进覆盖声明。
- 事件摘要进入 `{{untrusted_history}}` 槽（与 commit message 同为不可信输入，UNTRUSTED 定界）；`raw_json` 永不注入。
- 无数据/未装配/检索失败：`available=false` + 覆盖声明（"事件史不可得：模式 A 仅用变更史与产品段"），**不当作"已检查且无矛盾"**；每次查询都声明 tribal knowledge 硬边界。

### 2.6 引用格式：`event <event_id>`

`ContextCitation` 增第四类机器可解析引用（与 commit / REVIEW.md 条目并列）：

```
矛盾来源：
- event INC-42 — 2026-08-14 会话超时事故（企业长会话窗口 8h）
```

- 只在「矛盾来源」块内解析（避免正文 "event loop" 误命中）；行首或列表项均可；id 字符集与 `ProductEvent.EVENT_ID_PATTERN` 一致；
- CrossChecker prompt 规定 event id 必须来自简报中的真实条目，不得编造；
- 验证层 `CitedEvidenceResolver` 按 `(repo, event_id)` 预取事件，注入 verifier/judge 的 context 类 brief：id / type / occurred_at / service / tenant / source_url / 摘要 / commit_refs / paths，每批 ≤5 条、≤1.5k token；未解析的引用退化为 finding 自带证据原文，不静默失明；
- 事件引用不改变晋升规则：context finding 仍走既有验证/仲裁管线。

## 3. 理由

- **JSONL 而非直连工单系统**：工单/复盘的字段与增量语义因团队而异；第一版先把"结构化入库 + 幂等 + 引用"这条链做通，连接器只需实现 `EventHistorySource`，不触碰采集/检索/引用/验证任何一层。这也是 11§2"宁可内部实现不开放，不可开了不管"的落地方式。
- **PG 而非文件索引**：复用 M4b DataSource 与手写幂等 DDL；本项目的检索面是 repo + 时间 + 路径/commit/关键词的小规模集合，PG 的 JSONB + GIN 足够，且与账本同源可备份。文件索引会引入第二套并发/损坏语义，收益不抵成本。
- **水位 + 重叠 + 幂等键**：三者缺一不可——只有水位会漏迟到事件；只有重叠会产生重复；只有幂等键没有水位会全量重读并倒退推进。
- **引用格式而不是自由文本**：验证层必须能机器预取（03§4.2 的教训：context 类证据不在 brief 里 → INSUFFICIENT → 拒杀）；`event <id>` 与既有 `commit <sha> path <path>` 同构，解析器是唯一权威实现。
- **预算共享 4k**：不扩大 CrossChecker 的 16k 输入预算（不改变 04§2.2 的基线算术），事件与 commit 竞争同一块外部证据预算，超预算可见。

## 4. 后果

- **默认关闭**：不改 M2/M4f-lite 的基线行为与 L2/L3 评测口径；关闭时 CrossChecker 的输入与 M4f-lite 完全一致。
- **L2 回归**：本 ADR 涉及 core 编排、验证层组装与 cross-checker prompt → 按 04§2.4 需 L2 报告（报告绑定最终提交）。
- **评测边界（重要）**：CLI 的评测组合根未装配 `EventHistoryQuery`（事件史是服务端数据面）；因此 L2/L3 的 context 用例仍由 REVIEW.md + git 变更史驱动，**不验证事件史路径**。事件史路径的验收是单测 + PG 集成 + `CrossCheckPipelineTest` 的脚本化 LLM fixture（05§1.6、08§3.1）。
- **数据边界**：只覆盖已文档化入库的工单/事故复盘；tribal knowledge 不可见（01§3.4）。事件源声明的 `paths` 是启发式关联，路径检索无结构性召回保证；摘要可能过时或错误（不可信输入）。
- **运维边界**：JSONL 单次读取有字节上限（默认 64MB），超大对象需拆分或调大上限；对象存储走 HTTP(S)，无云 SDK 的分页/断点续传；水位按事件源标识保存，变更来源位置会重置增量窗口（幂等键保证不重复）；并发采集同一来源时"插入/覆盖"计数可能把插入计为覆盖（为计数预查存在性的竞态），但唯一键保证零重复——计数口径以"总行数 + 水位"为准。
- **未接的外部数据源**：Jira、Confluence、ServiceNow / 服务台、Slack/Teams 事故频道、PagerDuty 事件、GitHub Issues（作为事件源）——均为后续 `EventHistorySource` 实现；本 PR 不假装支持。

## 5. 替代方案

1. **只读 REVIEW.md，让 owner 手工把事故粘进去**（M4f-lite 现状）：成本最低，但工单/复盘无法检索、无法按路径关联，也不覆盖"事故在工单系统里"的常态；
2. **直连 Jira/Confluence API**：需要真实凭据与字段映射，第一版无法验收；且各团队字段不同，适配成本高；
3. **文件索引（每个事件一个文件 / Lucene）**：避免 PG 依赖，但引入第二套并发/损坏/备份语义；本项目已有 PG 服务形态（ADR-0008），复用更诚实；
4. **把事件原文整段注入 brief**：会击穿 16k 预算并放大注入面；本 ADR 只注入摘要，`raw_json` 只入库；
5. **用向量/语义检索替代关键词**：召回可能更好，但需要 embedding 服务与索引，第一版不引入；关键词 + 路径 + commit 的可解释性更符合"证据可定位"的要求。
