# 05 · 数据模型与集成设计

> 文档版本：v1.8（2026-10-28，M4e 池内项"语义索引/跨文件上下文"第一版：§5 新增 `semantic_context.*` 配置项与环境变量（ADR-0014；默认关闭））
> 文档版本：v1.7（2026-10-27，M4e 第二半 + M4f-full：CLI/server 平台选择与 GitLab webhook（ADR-0013）；§1.6 产品事件史 JSONL/PG/检索/引用（ADR-0012）；调试/契约不变）
> 文档版本：v1.5（2026-10-27，M4e 第一半：GitLab 适配器端点差异/账本映射/待验证项落 ADR-0011 与 docs/deployment-gitlab.md；SPI 签名不变）
> 文档版本：v1.3.3（2026-10-26，M4b：PostgreSQL 五表 + 手写幂等 DDL；`cra-eval verify` 增签名参数）
> 文档版本：v1.3.1（2026-10-25，响应第四轮评审缺陷 ①③④⑥）
> **v1.1**：SPI 增加本地 jgit blame；账本指纹规范；锚定校验与逐条降级；安全检查单扩充。
> **v1.2**：指纹 v2 + 解析器规则 + 发布侧转义剥离；replies 双端点；workflow draft 过滤与 cli 分发。
> **v1.2.1**：run 完成标记（status=DONE）；Summary 载体改 issue comment；滑窗容错（02§4.1）。
> **v1.3.1**：§1.1 Finding 枚举补 **`context`**（v1.3 新增 CrossChecker 的输出类别未传播到 schema，其全部输出会被 schema 校验拒收——四轮里第三次同类病）；§4 补回滚职责注（修复 01§3.4 悬空引用）；§5 补 CrossChecker 配置项。
> **v1.3.2**（M2 T2.9–T2.11 实现撞墙定点修订，2026-10-25）：§3 本地 CLI 实参补齐（--exec/--ledger-db/三档路由参数）、退出码新增 **4**（本地实跑失败，验收 8 的可判定信号）、本地解析语义（repo/head/token/LLM 可省、pr=0）；§1.4 补 `template_versions` 事件；§1.5 登记 `prompt_template_version` 落地。
> **v1.4**（M4c push 直推 + M4d 夜间存量定点修订，2026-10-26）：§1.3 账本键控扩展（`ReviewKey` = repo + 形态 + (pr|commit)；无 PR 载体粒度 = 载体评论级；**标记格式不变**）；§1.4 trace 增 `mode`/`carrier` 字段；§2.1 SPI 增 commit/issue 载体 default 方法与 `LocalRepoContext.firstParentChain`；§2.4/§2.5 载体语义；§2.3 端点清单补 commit comment 与 issues 列表/创建；§3 CLI 增 `--push` / `sweep`；§4 触发边界表补 push 与夜间定时的平台事实；§6 token 权限补 push 形态 `contents: write`。

---

## 1. 核心数据模型（cra-common）

### 1.1 CandidateFinding / Finding

```json
{
  "id": "f_01hxy…",
  "run_id": "run_01hxy…",
  "category": "bug|security|test|convention|performance|context",   // v1.3.1 增 context（CrossChecker，03§3.1）；context 类默认 important 档（业务/产品矛盾不产 nit）
  "title": "Offset 计算在空列表时越界",
  "description": "当 items 为空时 size()-1 返回 -1，导致 get(-1) 抛 IndexOutOfBoundsException",
  "failure_mode": "触发条件：items 为空列表；错误结果：IndexOutOfBoundsException",
  "file": "src/main/java/…/Foo.java",
  "line": 87,
  "side": "RIGHT",
  "evidence": "L85-88: return items.get(items.size()-1) …",
  "suggestion": "Optional<Foo> last(List<Foo> items) { … }",
  "severity_proposed": "important|nit",
  "pre_existing_suspect": false,
  "confidence": 0.8,
  "origin": { "agent_role": "bug-hunter/dataflow", "model": "…" },
  "verification": {
    "mode": "single|M2 | dual-vote|M3",
    "verdicts": [
      { "verifier": "v1", "template": "balanced-review", "model": "…",
        "verdict": "CONFIRMED", "rationale": "…(含独立定位的行号引用)", "anti_anchor_check": "PASS" }
    ],
    "judge": null,
    "status": "CONFIRMED|REJECTED|UNVERIFIED"
  },
  "final_severity": "important|nit|pre-existing",
  "anchor_status": "INLINE|SUMMARY_FALLBACK",       // 锚定校验结果
  "dedup_key": "sha256(file | category | anchor_line_content_hash | anchor_context_hash)",  // 指纹 v2，见 02§4.1——不含 LLM 文本与绝对行号
  "github_comment_id": 123456789,
  "human_disposition": null                           // fixed|wrong|intentional|ignored
}
```

要点：`confidence` 不参与晋升；`anti_anchor_check` 记录反锚定校验结果（04§4.2）；`anchor_status` 支撑发布降级（§2）。

### 1.2 ReviewRun

```json
{
  "id": "run_01hxy…",
  "repo": "zingaltern/code-review-agent",
  "pr_number": 42, "commit_sha": "…", "base_sha": "…",
  "trigger": "ci|local|webhook", "depth": "quick|standard|deep",
  "status": "SUCCESS|DEGRADED|FAILED|SKIPPED",
  "plan": { "chunks": […], "agents": [{role, model, budget}…] },
  "stats": {
    "findings_candidate": 12, "confirmed": 5, "rejected": 6, "unverified": 1,
    "nit_published": 2, "pre_existing": 2, "anchor_fallback": 1,
    "coverage": { "files_changed": 10, "files_reviewed": 10, "note": "全量" },
    "tokens_in": 243000, "tokens_out": 20500, "cost_usd": 0.39,
    "latency_ms": 252000, "degradations": ["verifier-retry-once"]
  },
  "trace_ref": "artifact://runs/2026/10/25/run_01hxy…",
  "prompt_template_versions": { "bug-hunter/dataflow": "…@abc123" },
  "created_at": "…"
}
```

### 1.3 账本指纹规范（v1.2.1：DONE 完成标记）

**两类标记，同套解析规则**：

**finding 级标记**（每条 inline bot 评论末尾）：

```markdown
**[Important] Offset 计算在空列表时越界** — `src/…/Foo.java:87`
…(转义后的 finding 正文)…
<!-- cra:v=2.1 | fp=<anchor_line_content_hash|anchor_context_hash 截断十六进制>
     | file=src/…/Foo.java | cat=bug | id=f_01hxy… | run=run_01hxy…
     | commit=abc1234 | line=87 -->
```

**run 完成标记**（每次 run 收尾发布的 Summary issue comment 末尾，v1.2.1 新增——commit 级幂等闸**只认它**）：

```markdown
…(Summary 正文：概述/分级列表/覆盖声明/截断声明)…
<!-- cra:v=2.1 | run=run_01hxy… | commit=abc1234 | status=DONE | findings=5 | suppressed=0 -->
```

`status` 取值 `DONE | DEGRADED`，**两者均为完成标记**（02§4.1 v1 基线修正：降级完成也是完整发布——部分角色超时但 run 正常收尾并发布了全部应发内容）。

无任何完成标记的 run（发布中途崩溃、被 cancel-in-progress 掐断）视为**不完整**：重跑时不全量跳过，走指纹级补发（02§4.1）——修复"发布 3/8 条后崩溃的 run 与完整发布在账本中不可区分，余下 5 条永久静默"的缺陷。

指纹键构成与锚行选取规则、±5 行滑窗容错匹配：见 02§4.1（**不含 LLM 生成文本与绝对行号**）。

**解析器规则（防污染，两类标记同规则）**：

1. **仅认末位**：只解析评论 body 的**最后一行**之后（body 末尾）且**位于任何代码围栏之外**的 `cra:` 标记；正文中间、代码块内的同类标记一律忽略；
2. **严格格式校验**：`v=` 必须为已知版本、字段集合与顺序合法、哈希为规整十六进制；`status=` 仅允许 DONE/DEGRADED；任一不满足 → 忽略该标记 + trace 记 `LEDGER_SUSPECT`（同一 PR 出现 ≥3 次可疑标记时 Summary 警告，疑似注入尝试）；
3. **发布侧双向防护**：finding 正文转义时将 `<!--` 替换为 `&lt;!--`（正文永远不可能再产生未转义 HTML 注释）；标记行由发布器在全部正文之后追加；
4. **一致性校验**：标记内 `commit` 须与该评论所属 PR 的 run 上下文一致（run 上下文由同 PR 的 DONE 标记链提供，不依赖运行器侧记录）；标记 `file` 与评论正文 file:line 不一致 → 忽略并告警；
5. **兜底分层**（对应 02§4.1）：解析不到任何有效标记 → 退回 **commit 级幂等闸**（有 DONE 才跳过），仅指纹级去重失效；**永不**退到"空账本全量重发"。

**键控扩展（v1.4，M4c/M4d）：有 PR 形态 vs 无 PR 形态**

标记格式**不变**（`v=2.1`、字段顺序、末位规则全部照旧）——只扩展"这份标记属于谁"的键：

| 形态 | 账本键 `ReviewKey` | 载体 | 键的落地方式 |
|---|---|---|---|
| PR（M0–M3） | `repo + pr + commit` | inline review + Summary issue comment | 评论端点按 PR 作用域（05§2.2） |
| push 直推（M4c） | `repo + commit`（pr=0） | 单条 commit comment | `GET /commits/{sha}/comments` 端点本身按 commit 作用域（05§2.4） |
| 夜间存量（M4d） | `repo + commit`（被评审对象是历史 PR） | 单条 issue | 列出 bot 的 issue，按标记内 `commit=` 过滤（05§2.5） |

约定【强制】：

1. **无 PR 形态的账本粒度 = 载体评论级**：一条 commit comment / 一条 issue 只承载一个末位 run 完成标记（finding 详情在正文里是**渲染**，不生成 finding 级标记——否则同一评论内多个标记全部命中"仅认末位"规则被判 LEDGER_SUSPECT）。因此无 PR 形态**不做指纹级补发**：载体是单条原子发布，不存在"发布 3/8 条后崩溃"的半截状态；发布失败 = 无标记 = 整条重发（02§4.1 兜底分层）。
2. **键的实现面**：`ReviewLedger` 只加 default 键控方法（`findCompletion(ReviewKey)` 等，11§4 第 1 条），PR 评论账本、commit 账本、issue 账本各自按载体取数；SQLite 形态按 `pr_number` 落库（无 PR 形态 pr=0，本地模式不使用）。
3. **夜间 issue 的 PR 归属**：issue 载体不带 PR 号字段，按 head sha 过滤即完成键控（同一 commit 全局唯一）；issue 标题/正文携带 PR 号仅供人读，不参与幂等判定。
4. **同一 PR 不同 head**：夜间形态每次评审的键随 head 变化，互不抑制（每个 commit 一次完整发布）；PR 形态的跨 commit 指纹去重语义不变（02§4.1）。

### 1.4 Trace 格式（NDJSON）

> **v1.3.2**（2026-10-25，M2 隔离运行时事件先行定点修订，07§6.5）：注册 `tool_call` / `tool_result` / `agent_budget_exceeded` 事件与 `agent_output.termination` 字段；`dedup.match` 增 `in-run-exact` 取值（M2 跨代理精确去重合并，03§4.5 汇聚侧，T2.8 的验证侧分组复用同一键）。
> **M4a 补登（2026-10-26）**：`signoff_advice`（签字建议：决策类型、风险等级、建议人数、commit 绑定、命中规则；`agent_approver` 恒 false——agent 不是 approver）。

```json
{"ts":"…","run_id":"…","event":"run_start","repo":"…","pr":42,"depth":"standard","dry_run":false}
{"ts":"…","run_id":"…","event":"gate_check","gate":"commit","hit":false}
{"ts":"…","run_id":"…","event":"agent_start","agent":"bug-hunter/boundary","brief_path":"…"}
{"ts":"…","event":"agent_output","agent":"…","findings":3,"findings_brief":["bug src/Foo.java:L4 …"],"termination":"completed","tokens":{…},"latency_ms":8200}
{"ts":"…","event":"tool_call","agent":"…","tool":"read_file","turn":2,"allowed":true}
{"ts":"…","event":"tool_result","agent":"…","tool":"read_file","chars":1200}
{"ts":"…","event":"agent_budget_exceeded","agent":"…","limit":"turns|time|input-tokens|output-tokens"}
{"ts":"…","event":"convergence","round":2,"breached":false,"review_base":"abc1234|pr-base"}
{"ts":"…","event":"merge","finding_id":"f_…","merged_from":["f_…"]}
{"ts":"…","event":"merge_summary","clusters":5,"merged_away":2}
{"ts":"…","event":"anchor_check","finding_id":"f_…","status":"SUMMARY_FALLBACK"}
{"ts":"…","event":"dedup","finding_id":"f_…","match":"exact|sliding-window|possible_duplicate|in-run-exact|none"}
{"ts":"…","event":"ledger_parse","valid":11,"suspect":0,"fallback":"none|commit-gate"}
{"ts":"…","event":"plan","effective_depth":"standard","chunks":4,"skipped":2}
{"ts":"…","event":"signoff_advice","decision":"ADVISORY_ONLY|DISABLED","applicable":true,"tier":"low|medium|high|none","recommended_signers":2,"commit":"abc1234…","stale":false,"agent_approver":false,"rules":["high-risk-path","business-logic"],"rules_version":1}
{"ts":"…","event":"template_versions","versions":{"bug-hunter/dataflow":"dataflow@1a2b3c04","security-reviewer":"security@5e6f7a80"}}
{"ts":"…","event":"dedup","finding_id":"f_…","match":"exact|sliding-window|possible_duplicate|in-run-exact|none"}
{"ts":"…","event":"blame_check","note":"…"}
{"ts":"…","event":"verify","file_batch":"Foo.java","findings":4,"verdicts":{"confirmed":3,"refuted":1,"insufficient":0,"unavailable":0}}
{"ts":"…","event":"verification","finding_id":"f_…","verdict":"CONFIRMED|REFUTED|INSUFFICIENT_EVIDENCE","rationale":"…（≤400 字）","followup":false}
{"ts":"…","event":"test_index","available":true,"files":42}
{"ts":"…","event":"verify_retry","file_batch":"Foo.java","reason":"…"}
{"ts":"…","event":"verify_followup","finding_id":"f_…","file":"src/…","status":"provided|unreadable"}
{"ts":"…","event":"anti_anchor_check","finding_id":"f_…","status":"PASS|FAIL","overlap":0.0,"refs":2}
{"ts":"…","event":"judge_start","agent":"judge","finding_id":"f_…","brief_path":"…","reason":"…"}
{"ts":"…","event":"judge","finding_id":"f_…","verdict":"CONFIRMED|REJECTED|unavailable","rationale":"…"}
{"ts":"…","event":"promotion","finding_id":"f_…","verifier_verdict":"CONFIRMED|REFUTED|INSUFFICIENT_EVIDENCE|UNAVAILABLE","decision":"publish|judge|reject|unverified","reason":"…","spotcheck_fuse":false}
{"ts":"…","event":"aggregate","candidates":12,"ordered":5,"nit_dropped":2,"suppressed_by_truncation":0}
{"ts":"…","event":"summary_supersede","comment_id":123,"status":"patched|skipped|unavailable|failed"}
{"ts":"…","event":"publish","comments":5,"skipped_duplicate":1,"suppressed_by_truncation":0,"done_marker":true}
```

**v1.2 补登（M2 T2.4–T2.8 实现面）**：`convergence`（轮次与 diff-only 基点，03§5）、`merge`/`merge_summary`（run 内精确去重，03§4.5）、`verify`（三值判定计数，03§4.2）、`verify_retry`（验证不可得缩上下文重试，02§4.2）、`verify_followup`（INSUFFICIENT 受控补查，03§4.2）、`anti_anchor_check`（反锚定质控信号 PASS/FAIL，不阻断晋升）、`judge_start`/`judge`（仲裁与抽查复核，03§4.4）、`promotion`（晋升决定与抽查保险丝命中，03§4.3）。

**v1.4 字段扩展（M4c/M4d）**：`run_start` 增 `mode`（`pull_request|commit|issue`）；`publish` 增 `mode` 与 `carrier`（PR 形态无 `carrier`；无 PR 形态为 `commit_comment|issue`，`comments` 为该载体条数 0/1）。事件名不变，旧消费方按缺省字段兼容。

brief 全文随 trace 存于同 artifact（`briefs/*.md`）。

### 1.5 存储 Schema

CI 形态无本地库（账本=GitHub）；本地/服务形态：`review_run`、`finding`、`verdict`、`published_comment`、`prompt_template_version` 表，`ReviewLedger` SPI 统一抽象（cra-store）。已落地：M1 建 review_run/finding；M2 T2.11 建 `prompt_template_version`（repo/pr_number/run_id/template_name/template_version，随 run 落库，05§1.2 prompt_template_versions 的持久化形态）；verdict/published_comment 随验证层/处置采集数据面落地。

**M4b 服务形态（2026-10-26，ADR-0008）**：`cra-store` 新增 `PostgresLedger`（`StoreAssembly.createPostgresLedger`），五表全部建表（`db/postgres/ledger.sql`，手写幂等 DDL）；读写语义与 SQLite 形态一致——完成标记只认 DONE|DEGRADED、commit 匹配大小写不敏感、同 finding_id 重登记幂等（`ON CONFLICT ... DO UPDATE`）。`verdict`/`published_comment` 当前只有表与迁移，读取方随验证层/处置采集数据面接入（不建无读取方的投机 SPI 方法）。server 自有表 `webhook_delivery`（delivery 幂等键）、`report_attestation`（签发审计）、`schema_migration` 由 `db/postgres/server.sql` 创建。

### 1.6 产品事件史（M4f-full，ADR-0012）

服务端自有数据面，默认关闭；第一版数据源 = owner 维护的 JSONL（本地文件 / `file:` URI / 对象存储 `http(s)` 预签名 URL），Jira/Confluence 等连接器是后续 `EventHistorySource` 实现。

**JSONL 行格式**（每行一个对象；未知字段忽略）：

```json
{"event_id":"INC-42","repo":"owner/repo","service":"billing","tenant":"acme",
 "occurred_at":"2026-08-14T03:00:00Z","type":"incident",
 "summary":"企业长会话窗口改为 8h 后，30 分钟会话续期假设失效",
 "source_url":"https://tracker/INC-42",
 "commit_refs":["abc1234"],"paths":["src/billing/**"]}
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `event_id` | 是 | 源系统内唯一（`INC-42` / `TICKET-7` / UUID） |
| `repo` | 是 | `owner/name`；统一小写存储与查询 |
| `service` / `tenant` | 否 | 服务与租户 |
| `occurred_at` | 是 | ISO-8601（支持偏移）；增量窗口与水位依据 |
| `type` | 是 | `ticket｜incident｜postmortem｜change｜other` |
| `summary` | 是 | 唯一进 brief 的正文（≤240 字符）；`raw_json` 只入库 |
| `source_url` | 否 | 人工核对链接 |
| `commit_refs` | 否 | 小写十六进制 4–64 位；与查询 commit 前缀互配 |
| `paths` | 否 | 仓库相对文件路径或目录前缀（`/**`、`/*` 规范化为前缀） |
| `raw_json` | 否 | 原始记录（缺省 = 整行对象）；只入库，不注入 brief |
| `collected_at` | 否 | 缺省由采集器写入 |

**PG 表**（`db/postgres/server.sql`，迁移版本 `server-0002`）：

- `product_event(event_id, repo, service, tenant, occurred_at, type, summary, source_url, commit_refs JSONB, paths JSONB, raw_json JSONB, collected_at)`，主键 `(repo, event_id)`；索引 `(repo, occurred_at DESC)` + `commit_refs`/`paths` GIN；
- `event_collection_checkpoint(source_id, watermark, updated_at)`：水位只前进。

**幂等/增量语义**：幂等键 `(repo, event_id)`，同键覆盖不新增行；采集窗口 = `max(请求窗口, 水位 − overlapHours)`；来源读取/入库有上限重试，耗尽后 `executed=false` + note；行级解析失败不推进水位（下次重试）；单次入库有上限，超出按发生时间升序截断，水位只推进到已入库部分。

**检索端口**（`EventHistoryQuery`，11§2）：按 repo / paths / commitRefs / keywords / [since, until] 检索，最多 200 条；CrossChecker 每次取 20 条、与变更史共享 4k 材料预算。**引用格式** `event <event_id>`（`ContextCitation` 唯一解析实现）：验证层按 `(repo, event_id)` 预取事件条目注入 verifier/judge brief。

**诚实边界**：只覆盖已文档化入库的工单/事故复盘；tribal knowledge 不可见（01§3.4）；路径关联依赖事件源声明，召回无结构性保证；事件摘要属不可信输入（UNTRUSTED 定界）；CLI 评测组合根未装配该端口，L2/L3 不验证事件史路径（04§2.4）。

## 2. GitPlatformAdapter SPI（v1.1 修订）

### 2.1 接口定义（v1.1 修订）

```java
public interface GitPlatformAdapter {
    DiffResult fetchDiff(RepoRef repo, String baseSha, String headSha);        // compare API
    FileContent readFile(RepoRef repo, String sha, String path);               // blob API
    List<String> listFiles(RepoRef repo, String sha, String pathGlob);
    PrReviewResult postReview(PostReviewCommand cmd);                          // §2.2 锚定与降级
    CommentLedger fetchBotComments(RepoRef repo, int prNumber);                // 账本（§1.3）
    DispositionSignal[] fetchDispositionSignals(RepoRef repo, int prNumber);   // 处置采集（06§1.1）

    // v1.4（M4c/M4d，SPI 只加 default，11§4 第 1 条）：无 PR 载体的发布；
    // 默认实现抛 UnsupportedOperationException = "不可得"显式拒绝（不得静默丢发布，§2.4/§2.5）
    default Optional<Long> postCommitReview(PostCommitReviewCommand cmd);      // §2.4 commit comment
    default Optional<Long> postIssueReview(PostIssueCommand cmd);              // §2.5 issue
}

public interface LocalRepoContext {                                            // v1.1 新增
    BlameResult blame(String path, int line, String sha);                      // jgit 实现
    Optional<String> localRepoRoot();                                          // CI checkout / 本地模式存在；纯 API 模式为空
    default List<String> firstParentChain(String headSha, int limit);          // v1.4：push 基线解析（§2.4）；不可得=空列表
}

public interface CommentSource {                                               // v1 基线新增（ADR-0005，10§6）：账本与处置采集的取数端口
    default List<PullRequestRef> listPullRequests(RepoRef repo, Instant since); // v1.3.3（T3.4）：窗口 PR 发现；不可得 = 空列表 + 声明
    CommentLedger fetchBotComments(RepoRef repo, int prNumber);                // 与 GitPlatformAdapter 同名方法同语义，返回 common POJO
    DispositionSignal[] fetchDispositionSignals(RepoRef repo, int prNumber);   // 06§1.1 周报数据面（reply 标签 + Summary reaction）
}
// 消费方：cra-store 的 GitHub 评论账本实现（构造器注入，只依赖 common 类型）与 cra-eval report；
// 提供方：cra-git-github（适配既有 adapter 方法）与 cra-git-gitlab（M4e，ADR-0011）；
// 装配：组合根（cra-runner-cli 与 eval 的 Main）。
```

**v1.3.3 实现补充（T3.4，2026-10-26）**：`DispositionSignal` 增 `detail`（wrong|intentional|fixed / +1|-1）、
`actor`、`excerpt` 三个可空分量（保留三参构造，纯加法）；`listPullRequests` 为 default 方法（平台不支持时返回空列表，
消费方声明不采，11§4 第 5 条）。GitHub 实现：PR 列表 `/pulls?state=all&sort=updated`；reply 从
`/pulls/{n}/comments` 全量评论按 `in_reply_to_id` 关联 bot 评论；reaction 从 `/issues/comments/{id}/reactions`。
resolve 仍不采（GraphQL-only）。

**修订说明**：

- **blame 走本地 jgit**：GitHub REST/GraphQL 均无行级 blame 端点（open feature request）。CI workflow 本就 checkout 仓库，本地模式天然有仓库；`localRepoRoot()` 为空（如未来服务形态无 checkout）时 pre-existing 检测**降级为不标注 + Summary 声明**，不假装有该能力；
- v1.0"不再依赖本地 git 进程"的说法**收回**：diff 获取用 compare API（不依赖 fetch-depth），blame 用本地 jgit，两者并存。

### 2.2 发布：载体分离 + 锚定校验与逐条降级（v1.2.1 修正端点）

**载体分离（缺陷 ② 修复，经官方文档钉死）**：`PATCH /repos/{o}/{r}/pulls/comments/{id}` **只能编辑 inline diff 评论**；已提交 PR review 的 body 在 REST v3 下**不可编辑**（update-review 仅对 pending review 生效），GraphQL 的 updatePullRequestReview 才能改——本项目锁 REST v3。因此：

1. **Summary = issue comment**（`POST /repos/{o}/{r}/issues/{n}/comments`）：可编辑（PATCH 同端点，用于 02§5 过期收尾前缀）、有独立 reaction API（处置采集新增 👍/👎 快捷信号，06§1.1）、文末带 run 完成标记（§1.3）；
2. **PR review 只装 inline 评论**：body 为固定引导语（"发现 N 条问题，详见本 PR 的评审 Summary 评论"，不能为空）；
3. 发布顺序：先发 inline review → 再发 Summary issue comment（含 DONE 标记）——DONE 标记存在即"完整发布"证据（02§4.1）。

**锚定校验与逐条降级**（不变）：

1. 提交前逐条校验：finding 的 (file, line) 必须落在该 PR diff 的某个 hunk 内；不满足者 `anchor_status=SUMMARY_FALLBACK`（正文进 Summary 列表）；
2. 提交策略：整包一次 review 请求；收到 422 → 降级为逐条提交，仍失败的按 SUMMARY_FALLBACK；全部结果回写账本。

### 2.4 push 直推载体：commit comment（v1.4，M4c）

**平台事实**：commit comment（`POST /repos/{o}/{r}/commits/{sha}/comments`）只能锚定到 commit，**没有 diff 行级锚点**，因此 push 形态**不发布 inline 评论**。

- **载体**：单条 commit comment，正文 = Summary（概述/分级列表/覆盖声明/截断与预算声明）+ 全部 finding 的**详情块**（描述/失败模式/证据/建议，与 inline 正文同构）+ 末位 run 完成标记（§1.3）；正文 `<!--` 照旧全量转义；
- **锚定降级规则复用**：finding 的 (file,line) 仍过锚定校验并如实进 stats/trace，但发布分派不看 INLINE/SUMMARY_FALLBACK——无行级锚时全部 finding 走"详情进正文"一条路（Summary 中声明"载体无行级锚"）；锚定校验失败的 finding 不会因此丢失；
- **base 语义**：head = 本次 push 的最新 commit；base = **上一完成 commit**——CLI 沿首父链（head 起，`--lookback` 默认 20 步）逐 commit 查 commit comment 账本，取最近带 DONE/DEGRADED 的 commit；首次评审取仓库初版 commit 作基线（根 commit 自身变更无父可对比，不计入）；`--base`/`--baseline` 可显式指定基线；本地仓库不可得时**拒绝启动**并要求显式基线（不猜）；
- **幂等**：同一 head commit 重跑命中 DONE 闸 → 全量跳过，不重复发 commit comment（§1.3 键控）；
- **过期收尾不适用**：commit comment 按 commit 隔离，没有"同 PR 旧 Summary"需要加取代前缀。

### 2.5 夜间存量载体：issue（v1.4，M4d）

**为什么不是 PR comment**：存量评审面向已合并/已关闭的历史 PR，向其评论流回帖没有通知与归属语义；产品形态是"一条可跟踪的 issue"。

- **对象**：窗口内（`--since`，按 updated_at）最近的 `--max-prs` 个 PR（含 open/closed/merged），diff 语义与 PR 形态一致（base.sha..head.sha）；
- **载体**：**每条被评审 PR 一个 issue**，标题 `[cra-nightly] PR #N <sha7> 存量评审`，正文 = Summary + 全部 finding 详情块 + 末位 run 完成标记（§1.3）；标签 `cra-nightly`（标签不存在时平台忽略，幂等不依赖标签）；
- **汇总 issue**：一次夜间实际评审 ≥1 个 PR 时，另发一条 `[cra-nightly] 汇总 <date>`——范围、逐 PR 结果表、tokens、截断与降级声明；**汇总 issue 不承载账本标记**（它不是评审载体）。同日汇总已存在（按正文首行判定）则不重复发布；本次全部 PR 被幂等闸跳过时不发汇总（第二次夜间跑零产物）；
- **范围/截断与失败声明**：汇总 issue 明确窗口内 PR 总数、本次评审数、被 `--max-prs` 截断数；汇总发布失败不回滚已发布的逐 PR issue，但进程以退出码 2 收尾（失败如实声明）；
- **幂等**：DONE 闸按 `repo + head commit` 查 issue 载体账本（§1.3）；同一 head 已被任一夜间 issue 完整发布过 → 跳过；
- **预算/截断声明复用**：逐 PR issue 内为完整 Summary（含覆盖声明、截断抑制数、降级事件），与 PR 形态同源同口径。

### 2.3 GitHub 实现要点（v1.2.1 端点清单修正）

认证 `GITHUB_TOKEN`；读 `X-RateLimit-Remaining` 主动节流 + 指数退避（≤3 次）；inline comment 走 PR Review API（`commit_id` 绑定 head sha, event=COMMENT）；**Summary 走 issue comments 端点**（§2.2）；bot 身份识别按 token 对应 login（CI 内为 `github-actions[bot]`）。

**端点清单（按用途锁定）**：

| 用途 | 端点 | 说明 |
|---|---|---|
| inline 评论发布 | `POST /repos/{o}/{r}/pulls/{n}/reviews` | 只装 inline，body 固定引导语 |
| Summary 发布/收尾编辑 | `POST` / `PATCH /repos/{o}/{r}/issues/{n}/comments/{id}` | issue comment 可编辑（review body 不可） |
| Summary reaction（处置信号） | `POST /repos/{o}/{r}/issues/comments/{id}/reactions` | 👍/👎 快捷处置（06§1.1） |
| 账本拉取 | `GET /pulls/comments` + `GET /issues/{n}/comments` + review replies 线索 | bot 的 inline 与 Summary 分属不同端点，合并去重（v1.2 已定）；人类 reply 含 `in_reply_to_id`（v1.3.3 处置采集） |
| 周报 PR 发现 | `GET /pulls?state=all&sort=updated&direction=desc` | T3.4 `listPullRequests`：按 updated_at 过滤窗口（05§2.1） |
| Summary 满意度 | `GET /issues/comments/{id}/reactions` | T3.4：👍/👎 记 run 满意度，不参与单 finding 分类（06§1.1） |
| push 载体发布 | `POST /repos/{o}/{r}/commits/{sha}/comments` | M4c：commit comment（无行级锚，§2.4） |
| commit 账本拉取 | `GET /repos/{o}/{r}/commits/{sha}/comments` | M4c：键 = repo + commit；只认 bot 自身评论 |
| 夜间 issue 发布 | `POST /repos/{o}/{r}/issues` | M4d：逐 PR issue + 汇总 issue（§2.5） |
| 夜间 issue 账本/汇总判重 | `GET /repos/{o}/{r}/issues?state=all&sort=updated&direction=desc` | M4d：按 bot 登录名过滤；**排除 `pull_request` 条目**（/issues 同时返回 PR）；按标记内 commit 过滤 |
| 夜间扫描范围 | `GET /pulls?state=all&sort=updated&direction=desc` | 复用 T3.4 `listPullRequests`；v1.4 起返回 `base.sha`（diff 基线，§2.5） |

**resolve 状态为 GraphQL-only**：REST v3 锁定下不采、不操作（06§1.1、02§5）。

**GitLab 实现（M4e，ADR-0011/0013）**：端点、认证、分页、限流、触发边界、账本映射与不可得信号的**主载体**是 `docs/deployment-gitlab.md`（11§6 要求的平台边界表落点）。本节 GitHub 端点清单不适用于 GitLab；SPI 签名与账本标记格式不变。M4e 第二半起 CLI/server 可按平台选择 GitLab 实现（`--platform gitlab` / `CRA_SERVER_PLATFORM=gitlab`），账本键仍为 repo + pr/commit。

## 3. CLI 契约（cra-runner-cli）

```bash
# CI 模式（GitHub Actions step 内）
cra review --repo "$GITHUB_REPOSITORY" --pr "$PR_NUMBER" \
  --base "$BASE_SHA" --head "$HEAD_SHA" --depth standard --token "$GITHUB_TOKEN"

# 本地 pre-PR 模式（M2 起；账本=本地 SQLite，默认 dry-run 不回帖）
cra review --local [--base main] [--head REF] [--depth standard] [--dry-run=false] \
  [--exec "mvn -B -ntp verify"] [--ledger-db ~/.cra/ledger.db] \
  [--model-strong M] [--model-light M] [--fallback-chain standard,light] [--rpm standard=60,strong=30,light=60]

# 处置采集/周报（本地跑，读 GitHub 账本，无常驻服务；T3.4）
cra-eval report --repo zingaltern/code-review-agent --since 7d \
  [--json report.json] [--ledger-fixture DIR] [--trace-dir DIR[,DIR...]] \
  [--previous REPORT.json[,REPORT.json...]] [--dataset DIR] [--token TOKEN] [--api-base URL] \
  [--as-of ISO-8601]   # 窗口与"合并满 7 天"判定以该时刻为基准（默认 now；离线回放/测试固定时钟用）

# badcase 固化（T3.5，06§2）
cra-eval badcase add <run_ref> <finding_id> --label missed|false-positive|relabeled \
  --dataset cra-eval/dataset [--to-set bad|clean] [--case-id ID] [--annotator NAME] [--reason TEXT] \
  [--diff-file PATH] [--snapshot-dir DIR] [--artifact-root DIR] [--token TOKEN] [--api-base URL]

# push 直推评审（M4c，05§2.4；无 PR，载体 = commit comment）
cra review --push --repo "$GITHUB_REPOSITORY" --head "$GITHUB_SHA" \
  [--base SHA | --baseline SHA] [--lookback 20] --depth standard --token "$GITHUB_TOKEN"

# 夜间存量评审（M4d，05§2.5；产出 issue，扫描范围可配）
cra sweep --repo owner/name --since 30d --max-prs 10 --depth quick --token "$GITHUB_TOKEN" [--dry-run]

# GitLab CI 模式（M4e 第二半，ADR-0013；token 只认 CRA_GITLAB_TOKEN / _FILE）
cra review --platform gitlab --gitlab-project "$CI_PROJECT_PATH" --pr "$CI_MERGE_REQUEST_IID" \
  --base "$CI_MERGE_REQUEST_DIFF_BASE_SHA" --head "$CI_COMMIT_SHA" --depth standard

# GitLab 夜间存量（issue 载体，语义与 GitHub sweep 同口径）
cra sweep --platform gitlab --gitlab-project group/project --since 30d --max-prs 10
```

**平台选择语义（M4e 第二半）**：`--platform` > `CRA_GIT_PLATFORM` > `github`；GitLab 模式的项目路径取 `--gitlab-project` > `CRA_GITLAB_PROJECT` > `CI_PROJECT_PATH` > `--repo`（嵌套 group 按最后一个 `/` 拆分）；`--token` 在 GitLab 模式被拒绝（防串用），token 只从 `CRA_GITLAB_TOKEN` / `CRA_GITLAB_TOKEN_FILE` 读取；`--gitlab-base-url` 可给实例根或 `/api/v4` 根（自动归一）。`--local` 形态与平台无关（jgit + SQLite，不要求远程 token）。

**push 形态参数语义（v1.4）**：`--push` 与 `--pr`/`--local` 互斥；`--base` 省略时由 CLI 沿首父链查 commit
comment 账本解析"上一完成 commit"（`--lookback` 默认 20；首次 = 仓库初版），`--baseline` 是 `--base` 的同义显式
基线；本地仓库不可得（纯 API 模式）且未给显式基线 → 退出码 2（不猜）。`--dry-run` 全程零写。

**sweep 参数语义（v1.4）**：`--since` 形如 `7d`/`12h`/`2w`（默认 30d，按 PR updated_at 过滤）；`--max-prs`
正整数（默认 10）；深度默认 quick（夜间批量成本纪律，06§4）。退出码：0 = 正常（含无可评审 PR、全幂等跳过、
部分失败已如实进汇总）；2 = 自身故障或存在评审失败（逐 PR 故障隔离，不中断整轮）。

`report` 参数语义：`--since` 支持 `7d`/`12h`/`2w`；token 取 `--token` → `GITHUB_TOKEN` → `GH_TOKEN`；
无 token 且无 `--ledger-fixture` → 退出码 2。`--ledger-fixture` 为离线账本回放（测试/脱敏样例，报告
`data_source=ledger-fixture`）；`--trace-dir` 缺省读本地 `.cra/runs`（存在时）；`--previous` 为历史周报 JSON
（零发布率趋势基线，需本期 + 两期才判定"连续两周上行"）。`badcase add` 的 `run_ref` 支持本地 run 目录 /
`artifact://<rel>`（相对 `--artifact-root`，默认 `.cra/artifact`）/ `file://`；缺 `--diff-file` 或
`--snapshot-dir` 时需要 token 从远端拉；draft 用例须 `annotate confirm` 后才进回归。

本地模式解析语义（v1.3.2 实现落地）：`--repo`/`--head`/`--token`/`CRA_LLM_*` 均可省——仓库身份由 remote origin 推导（兜底 `local/<目录名>`）、head 解析为 HEAD、无 `CRA_LLM_API_KEY` 时评审段**如实跳过**（报告声明，不伪装）；`--pr` 省缺为 0（本地无 PR，账本按 `(repo, 0, commit)` 键控）。

M4a 签字建议输入（05§5 `agents.signoff`）：`--pr-author`（缺省 `CRA_PR_AUTHOR` → `GITHUB_ACTOR`；未知作者不满足低风险 0 人签）、`--tests-status passed|failed|unknown`（缺省 `CRA_TESTS_STATUS`；本地 pre-PR 由实跑退出码覆盖）、`--signoff` / `--signoff=false`（缺省 `CRA_SIGNOFF_ENABLED=true`）、`--trusted-authors a,b`（缺省 `CRA_SIGNOFF_TRUSTED_AUTHORS`）。建议绑定 `--head` commit；新 commit 出现后由账本完成标记比对旧建议并标 stale。

退出码：`0` 正常完成（**有 finding 不是失败**）；`2` 自身故障；`3` `--security-block` 模式下存在 confirmed 安全 finding（供需要阻塞语义的仓库）；**`4` 本地 pre-PR 实跑构建/测试失败**（v1.3.2 撞墙定点修订：v1 基线只定义了 0/2/3，而 07 T2.10 验收 8 要求"报告如实展示测试失败输出且退出码非零"——需一个区别于"工具自身故障"的确定性信号。优先级高于评审结论：构建失败时评审照常运行、结果入报告，但退出码恒为 4）。

### 3.1 cra-eval 评测命令契约（T3.1/T3.3 落地，2026-10-25）

```bash
# 评测运行（本地/CI 通用）。--set l2 = bad 30 + clean 20（60/40 观测；04§2.4 的 L2 口径，
# M1 基线为 bad 12 + clean 8），
# --tier 缺省 auto：k≥3 或 depth=standard → l3，k≥2 → l2，否则 l1。
cra-eval run --dataset cra-eval/dataset --set l2 --k 2 --depth quick --tier l2 \
  --commit "$(git rev-parse HEAD)" --fail-on-red \
  --json eval/l2/report.json --sha256-out eval/l2/report.json.sha256

# 报告校验（CI 的 L2 门禁：哈希 + 门禁结论为绿 + commit 绑定；荣誉制，04§2.4）
# REPORT_COMMIT = 报告内 commit 字段（被测代码提交）；须为 PR head 祖先，其后仅允许 eval/l2/ 变更
cra-eval verify --report eval/l2/report.json --sha256-file eval/l2/report.json.sha256 \
  --tier l2 --expect-commit "$REPORT_COMMIT"

# M4b 服务端签名路径（04§2.4、ADR-0008）：报告摘要由 cra-server 签发 HMAC 信封
# 密钥只从环境变量/文件读（默认 CRA_REPORT_SIGNING_KEY；--signing-key-file 用于本地演示）
cra-eval verify --report eval/l2/report.json --signature eval/l2/report.json.sig \
  --tier l2 --expect-commit "$REPORT_COMMIT"

# 离线重判（阈值/检查项变更后用同一批观测重算门禁；不重跑 LLM）
cra-eval gate --report eval/l2/report.json --tier l2 [--json-out PATH] [--sha256-out PATH]
```

`run` 参数（在 M1/M2 基础上只做加法）：`--tier auto|l1|l2|l3`、`--commit SHA`（缺省取 `GITHUB_SHA`）、`--fail-on-red`（无值开关）、`--sha256-out PATH`、`--crosschecker-enabled`（M4f context 类是否计入门禁）；`--set` 增 `l2` 别名（bad+clean）。`verify` 参数：`--report`（必填）、`--sha256 <hex>` / `--sha256-file <path>` / `--signature <path>`（至少一个）、`--tier`、`--expect-commit`；签名模式下追加 `--signing-key-env`（默认 `CRA_REPORT_SIGNING_KEY`）或 `--signing-key-file`，可选 `--signing-key-id`；门禁结论按**当前**阈值重算（不信任报告内陈旧 gate，阈值下调后旧报告不能凭旧判通过；与存量 gate 不一致时打印差异提示）。`gate` 参数：`--report`（必填）、`--tier`（缺省沿用报告档位）、`--json-out`、`--sha256-out`、`--crosschecker-enabled`。

退出码：`0` 正常完成或 verify/gate 通过；`2` 自身故障（参数/数据集/被测对象启动失败）；**`5` 门禁红或 verify 校验失败**（`--fail-on-red` / `gate` 生效时；CI 的 L1/L2 job 以此判定，04§2.4）。门禁结论同时写入 JSON 报告的 `gate` 字段（`passed` + 逐项 `checks`），文本报告头部输出同一结论。`gate` 重判不修改源报告；`--json-out` 另行落盘重判结果（含新 gate 与哈希），用于阈值变更后的取证（评测重跑范围纪律：只重判、不整轮重跑）。

## 4. GitHub Actions 集成（v1.2：draft 过滤 + 分发方案 + 限制声明）

**已知平台限制（显式声明，见 02§5 触发边界表）**：冲突 PR（mergeable=conflicting）的 `pull_request` workflow **不触发**（GitHub 官方行为）——该场景静默无评审，冲突解决后的 synchronize 会正常触发；fork PR 无 secrets 访问（当前个人仓库无碍，多协作者时引入 App token）。

**M4c/M4d 触发补充（v1.4）**：

| 场景 | 行为 | 处置（workflow / CLI） |
|---|---|---|
| 直推受保护分支 | `push` workflow 触发（`branches: [main]` 为分支过滤；多分支在此追加） | `cra review --push`；base 由 CLI 解析为上一完成 commit（§2.4） |
| merge commit | 无独立变更语义，重复评审合并结果只会产生噪声 | workflow `if` 跳过 `Merge ` / `Merge pull request` 前缀的 head commit |
| bot 自己的提交（自举循环） | 本组件不给自己回帖/反复评审 | workflow `if` 跳过 `github-actions[bot]` 与 actor 以 `[bot]` 结尾的提交 |
| push 并发 | 同一 ref 连续直推 | `concurrency.group` 含 ref，`cancel-in-progress=false`（串行；被掐断的 run 无 DONE，重跑会重发） |
| 夜间定时 | 每日一次批量存量评审（默认 UTC 18:00 / Asia/Shanghai 02:00）+ 支持 `workflow_dispatch` 手动补跑 | 独立 workflow：`contents: read` + `pull-requests: read` + `issues: write`；`cra sweep --since 30d --max-prs 10 --depth quick` |
| 夜间并发 | 同仓库多实例会重复评审同一批 PR | `concurrency.group: review-nightly` 串行；幂等闸兜底（同 head 已有 DONE 则跳过） |

workflow 实现见 `.github/workflows/code-review-agent.yml`（`review` / `review-push` 两个 job）与
`.github/workflows/code-review-agent-nightly.yml`（`sweep`）。下方 M0 示例只示 PR job，push job 的差异是：
`contents: write`（commit comment 权限）、`--push --head "$GITHUB_SHA"`（无 `--pr`/`--base`）、trace artifact
名带 `push-<sha>`。

**回滚兜底职责（v1.3.1 补注，兑现 01§3.4"先兜底再减负"约束的引用）**：快速回滚能力属**部署侧**而非本组件——启用本评审组件削减人工逐行审查的仓库，应同时配置：分支保护 + 按风险分级的人工审批（M4a 建议输出）、合并队列、必要时特性开关；本组件不提供也不假装提供回滚能力。

```yaml
name: code-review-agent
on:
  pull_request:
    types: [opened, synchronize, reopened, ready_for_review]   # v1.2：ready_for_review 补 draft 转正场景
permissions:
  contents: read
  pull-requests: write
concurrency:
  group: review-${{ github.event.pull_request.number }}
  cancel-in-progress: true
jobs:
  review:
    runs-on: ubuntu-latest
    if: github.event.pull_request.draft == false                # v1.2：draft PR 不评审（省成本降噪声）
    steps:
      - uses: actions/checkout@v4        # 供 jgit blame 与 base 分支 REVIEW.md 读取
        with: { fetch-depth: 0 }         # blame 需要历史
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: 17 }
      # cli 分发（v1.2 定案）：M0 自举期 = 仓库内 maven 构建缓存（actions/cache 缓存 ~/.m2 与
      # cra-runner-cli shaded jar，改动少时秒级命中）；M2 起改 GitHub Release 二进制
      # （jlink 最小运行时 zip），workflow 改为 curl 下载固定版本号——可复现、可回滚。
      - name: Build or restore CLI
        run: mvn -B -ntp -pl cra-runner-cli -am package -DskipTests
      - run: java -jar cra-runner-cli/target/cra-runner-cli.jar review …
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
          CRA_LLM_API_KEY: ${{ secrets.CRA_LLM_API_KEY }}
          CRA_LLM_BASE_URL: ${{ vars.CRA_LLM_BASE_URL }}
      - name: persist trace              # 运行器即焚，trace 必须外传
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: cra-trace-${{ github.event.pull_request.number }}-${{ github.event.pull_request.head.sha }}
          path: .cra/runs/
          retention-days: 90
```

## 5. 部署级配置（骨架）

```yaml
cra:
  llm:
    models:
      standard: { provider: openai-compatible, model: "…", base-url: "${CRA_LLM_BASE_URL}" }
      strong:    { provider: openai-compatible, model: "…" }
      light:     { provider: openai-compatible, model: "…" }
    fallback-chain: [standard, light]
    # 采样温度（--llm-temperature / CRA_LLM_TEMPERATURE；空 = 不传沿用 provider 默认）
    # 评审/验证/仲裁为分类判断任务：建议 0.2 降低运行间方差（M3 全部角色共享该值）
    temperature: null
    # opencode-go 适配（v1.3.2）：base-url 指向 opencode.ai 时自动附加 x-opencode-session 头
    # （opencode-go 的路由要求；无需显式配置，网关构建时生成 UUID 会话）
    max-concurrency: 8              # v1.1：LLM 并发信号量
    per-model-rpm: { standard: 60, strong: 30, light: 60 }
  agents:                        # v1.3.1：crosschecker 配置（默认关闭，M4f 启用）；M4a：signoff 建议
    crosschecker:
      enabled: false             # M4f-lite 起可开
      mode: [assumption, sniff]  # 模式 A/B 开关（03§3.1）
      history-weeks: 8           # 上下游变更史/事件史回溯窗口（M4f-full 共用）
    event-history:               # M4f-full（ADR-0012）：默认关闭；开启时 source 必填（fail closed）
      enabled: false
      cron: "0 0 3 * * *"        # Spring 六段式；组装期 CronExpression 校验
      source: ""                 # 本地路径 / file: URI / http(s) 预签名 URL（JSONL）
      lookback-days: 7
      overlap-hours: 24          # 与上次水位的重叠（迟到事件；幂等键防重复）
      max-attempts: 3            # 读取/入库最大尝试次数（含首次）
      retry-backoff-ms: 500      # 第 n 次重试等待 n × 基数
      max-bytes: 67108864        # 单次读取上限（64MB）
      max-events: 5000           # 单次入库上限（超出按发生时间升序截断）
    signoff:                     # M4a（07§5）：只输出建议，不审批——agent 永远不是 approver
      enabled: true              # false = Summary 不出签字段（评审其余行为不变）
      trusted-authors: []        # 低风险 0 人签前提之一（login，大小写不敏感）
      # 路径分级规则取内置默认（core 高危/关键配置/系统提示词/文档/功能开关 glob）；
      # 运行时输入：--pr-author / CRA_PR_AUTHOR / GITHUB_ACTOR；--tests-status / CRA_TESTS_STATUS
      # （passed|failed|unknown；本地 pre-PR 由实跑退出码覆盖）
    verifier:
      mode: single                  # M2: single | M3: dual-vote
      dual-vote:
        require-distinct-model-family: true   # v1.1：异构硬校验，违例配置拒绝启动
        v1: { model: strong,  template: balanced-review }
        v2: { model: "<异构>", template: refute-oriented }
      important-refuted-spotcheck: 0.20       # 拒真保险丝（03§4.3）
    budget: { run-total-tokens: 500000, degrade-at: [0.70, 0.85] }   # v1.2.1：500k + 阈值重校（02§4.4）
    semantic-context:              # M4e（ADR-0014）：L2 跨文件切片；默认关闭
      enabled: false               # CRA_SEMANTIC_CONTEXT_ENABLED / --semantic-context
      max-files: 2000              # 索引扫描 Java 文件数上限（CRA_SEMANTIC_CONTEXT_MAX_FILES）
      max-file-bytes: 262144       # 单文件字节上限（CRA_SEMANTIC_CONTEXT_MAX_FILE_BYTES）
      slice-tokens: 4000           # 单 brief 切片 token 上限，从 L1 预算扣除（CRA_SEMANTIC_CONTEXT_SLICE_TOKENS）
  review:
    default-depth: standard
    large-diff-lines: 2000
    hard-cap-lines: 8000
    nit-budget-default: 3
    publish-unverified: never       # never | labeled
  trust:
    review-policy-source: base      # v1.1：固定 base，禁 head（04§3.2）
```

**平台选择（M4e 第二半，ADR-0013）**：平台不是 `application.yml` 的模型配置，而是组合根启动参数（平台实现模块互不依赖，11§4）：

| 变量 | 作用 | 默认 |
|---|---|---|
| `CRA_GIT_PLATFORM` / `--platform` | CLI 平台（`github`\|`gitlab`） | `github` |
| `CRA_SERVER_PLATFORM` | server 平台（`github`\|`gitlab`，小写） | `github` |
| `CRA_GITLAB_BASE_URL` / `--gitlab-base-url` | GitLab API 根（实例根或 `/api/v4` 根自动归一） | `https://gitlab.com/api/v4` |
| `CRA_GITLAB_TOKEN` / `CRA_GITLAB_TOKEN_FILE` | GitLab token（只从环境/文件；`--token` 仅 GitHub） | — |
| `CRA_GITLAB_PROJECT` / `--gitlab-project` | CLI 的 GitLab 项目（`group/project`，可嵌套 group）；server 从 MR payload 的 `project.path_with_namespace` 取 | `CI_PROJECT_PATH` |
| `CRA_GITLAB_BOT_USERNAME` | `/user` 不可读时的 bot 身份回退 | 空 |
| `CRA_SERVER_WEBHOOK_SECRET` | GitHub HMAC 密钥 / GitLab webhook Secret token | 必填 |
| `CRA_SEMANTIC_CONTEXT_ENABLED` / `--semantic-context` | 语义上下文（L2 跨文件切片，ADR-0014）：开启后 standard/deep 档对 dataflow/security/verifier 注入切片 | `false` |
| `CRA_SEMANTIC_CONTEXT_MAX_FILES` / `--semantic-context-max-files` | 索引扫描 Java 文件数上限（升序截断并声明） | `2000` |
| `CRA_SEMANTIC_CONTEXT_MAX_FILE_BYTES` / `--semantic-context-max-file-bytes` | 单文件字节上限（超限跳过并声明） | `262144` |
| `CRA_SEMANTIC_CONTEXT_SLICE_TOKENS` / `--semantic-context-slice-tokens` | 单 brief 切片 token 上限（500–12000；从 L1 预算扣除） | `4000` |

M4a 的 PR 模板提示项（docs 片段，**不强加**——仓库可自行复制到 `.github/pull_request_template.md`）：

```markdown
## 签字名单（M4a 建议，非强制）
- [ ] 指挥 agent 写代码的人已在签字名单上（agent 的赞成票不计为审批）
- [ ] 本次 approve 针对 commit `<head_sha>`；该 PR 出现新 commit 后须重新核实
- [ ] 签字语义：我核实过这次改动的意图、假设、设计，而非逐行读过
```

## 6. 安全与合规检查单（v1.1 扩充）

- [ ] 密钥只从环境变量/密钥文件读取（`CRA_GITLAB_TOKEN_FILE` 错误信息不含内容）；token 不写入 trace/日志/仓库；
- [ ] 子代理无网络、无 shell、无仓库外路径（实现层硬编码，不靠 prompt）；
- [ ] **REVIEW.md 只读 base SHA**；PR 含 REVIEW.md 变更时按 base 策略执行并警告（04§3.2）；
- [ ] **不可信内容（diff/注释/commit msg/finding/验证输出）一律 UNTRUSTED 定界**，system prompt 含指令层级声明（04§3.3）；
- [ ] **发布前 markdown 转义**，finding 正文代码块包裹；
- [ ] **注入对抗集在 CI 门禁中运行**，行为改变即失败（06§1.2）；
- [ ] **账本解析只认末位/代码块外/版本合法的标记**；正文 `<!--` 全量转义；解析兜底退 commit 级闸、永不全量重发（05§1.3）；
- [ ] **pre-PR 本地模式实跑构建/测试并展示真实输出**，不以"声称完成"代替（07 T2.10）；
- [ ] 处置采集不依赖 GraphQL-only 的 resolve 状态（05§2.3、06§1.1）；
- [ ] GitHub token 最小权限：PR 形态 `contents:read + pull-requests:write`；push 形态另需 `contents:write`（commit comment 载体，§2.4）；夜间形态 `contents:read + pull-requests:read + issues:write`（§2.5）——按 job 分别授予，不整仓库放宽；
- [ ] dry-run 模式全程零写操作（单测断言零写调用）；
- [ ] diff/文件内容只发往配置的 LLM 端点，不落第三方。
