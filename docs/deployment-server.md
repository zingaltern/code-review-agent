# cra-server 部署形态（M4b 服务化）

> 依据：02§5（部署形态与状态归属）、07§5 M4b/M4e/M4f-full、ADR-0008/0012/0013。本文是服务化形态的**操作手册**：怎么起、配什么、怎么检查、怎么停机、有哪些边界。
> 适用读者：部署本服务的 owner / 运维。评审逻辑本身见 02/03/04，不在本文重复；GitLab 平台事实见 `deployment-gitlab.md`。
> **M4f-full**：事件史采集（默认关闭）与 CrossChecker 检索/引用见 §1、§3、§5.1、§7。

---

## 1. 形态总览

```
GitHub ──webhook(HMAC)──┐
GitLab ──webhook(token)─┤
                        ├─▶ cra-server
                        │      ├─ WebhookController / GitLabWebhookController（按平台注册）
                        │      │     └─ 验签 → PR/MR 事件子集解析
                        │      ├─ WebhookIngestService（delivery 幂等 claim → 有界队列）
                        │      ├─ ReviewQueue（有界 + 背压 + 固定 worker）
                        │      │     └─ ReviewJobHandler → CoreReviewExecutor
                        │      │            └─ cra-core ReviewPipeline（与 CLI 同一实现）
                        │      ├─ PostgreSQL 账本（cra-store PostgresLedger，05§1.5 五表）
                        │      ├─ 事件史（M4f-full，ADR-0012）
                        │      │     ├─ EventHistoryCollectionJob（cron，默认关闭）
                        │      │     ├─ IncrementalEventHistoryCollector ← EventHistorySource（JSONL 本地/HTTP(S)）
                        │      │     ├─ product_event / event_collection_checkpoint（server.sql）
                        │      │     └─ EventHistoryQuery → CrossChecker 材料 + event 引用预取
                        │      └─ AttestationController（/api/v1/reports/attest，HMAC 报告签名）
```

- **第二组合根**：cra-server 与 cra-runner-cli 只是两种壳（02§5），评审逻辑完全复用 core；**平台由 `CRA_SERVER_PLATFORM` 在装配期决定**（M4e 第二半，ADR-0013），只注册当前平台的 webhook 端点；
- **账本**：PostgreSQL，按仓库创建 `ReviewLedger` 实例（delivery 表与签发表为 server 自有）；账本键仍为 repo + pr/commit，与平台无关；
- **队列**：进程内有界队列（不引入外部 broker，理由见 ADR-0008）；满时 HTTP 503 → 平台按同一 delivery/event 重投；
- **幂等**：delivery id（PG 唯一键）挡重复投递（GitLab 用 `X-Gitlab-Event-UUID`，缺失时用 payload 摘要）；core 的 commit 闸 + 指纹账本挡同 head 重跑（02§4.1）；
- **签名报告**：服务端对 L2 报告签发 HMAC 信封，`cra-eval verify --signature` 校验（04§2.4）。
- **事件史（M4f-full，ADR-0012）**：默认关闭。开启后按 cron 从 JSONL 来源增量采集工单/事故复盘到 `product_event`；CrossChecker（`CRA_CROSSCHECKER_ENABLED=true`）按变更路径/commit/关键词检索并注入 brief，验证层按 `event <id>` 预取事件条目。采集失败（来源/入库重试耗尽）如实记录 `executed=false`，不阻断评审。

## 2. 快速启动（docker-compose）

```bash
cd docker
cp .env.example .env          # 覆盖全部密钥；填入 CRA_LLM_* 与平台凭证（GitHub 或 GitLab）
docker compose up --build -d
docker compose ps             # 等 postgres 与 server 都 healthy
curl -fsS http://127.0.0.1:8080/readyz
```

- compose 文件：[`docker/docker-compose.yml`](../docker/docker-compose.yml)；镜像：[`docker/Dockerfile`](../docker/Dockerfile)；
- 默认端口：server `8080`，PostgreSQL 宿主机 `55432`（仅调试；生产不要暴露）；
- 卷：`cra-pgdata`（PG 数据）、`cra-runs`（trace/brief 落盘）；
- 启动顺序：PG healthy → server；server 启动时执行手写幂等 DDL（五表 + delivery/attestation/schema_migration），失败即退出（fail fast）。

### 2.1 GitHub webhook 配置

| 项 | 值 |
|---|---|
| Payload URL | `https://<your-host>/webhooks/github` |
| Content type | `application/json` |
| Secret | 与 `CRA_SERVER_WEBHOOK_SECRET` 一致 |
| Events | Pull requests（只处理 `opened` / `reopened` / `synchronize` / `ready_for_review`；draft 忽略） |

服务端只接受 `X-Hub-Signature-256`（HMAC-SHA256），验签在 JSON 解析之前；失败 401，不泄露细节。

### 2.2 GitLab webhook 配置（M4e 第二半，ADR-0013）

设置 `CRA_SERVER_PLATFORM=gitlab` 后只注册 GitLab 端点（GitHub 端点返回 404）；默认 `github` 只注册 GitHub 端点。

| 项 | 值 |
|---|---|
| Payload URL | `https://<your-host>/webhooks/gitlab` |
| Secret token | 与 `CRA_SERVER_WEBHOOK_SECRET` 一致（`X-Gitlab-Token` 直传校验，不是 HMAC） |
| Trigger | Merge request events（push/issue/note 等事件 200 ignored，带显式 `reason`） |
| 评审动作 | `open` / `reopen` / 带新提交的 `update`（`oldrev` 非空）；draft、merge、close、审批、仅元数据更新显式忽略 |

GitLab 的完整事件映射、状态码、幂等键与待验证项见 [deployment-gitlab.md](deployment-gitlab.md) §8/§9。

## 3. 环境变量表

密钥只从环境变量读（05§6）；下表“必填”项缺失时服务拒绝启动（fail closed）。

| 变量 | 必填 | 默认 | 说明 |
|---|---|---|---|
| `CRA_SERVER_PORT` | 否 | `8080` | HTTP 端口（compose 内固定 8080） |
| `CRA_SERVER_DB_URL` | 否 | `jdbc:postgresql://localhost:5432/cra` | PG JDBC URL（compose 注入） |
| `CRA_SERVER_DB_USER` / `CRA_SERVER_DB_PASSWORD` | 否 | `cra` | PG 凭据（compose 注入；生产必须覆盖密码） |
| `CRA_SERVER_DB_POOL` | 否 | `10` | Hikari 连接池上限 |
| `CRA_SERVER_WEBHOOK_SECRET` | 是 | — | webhook 密钥：GitHub = HMAC 密钥；GitLab = webhook Secret token（`X-Gitlab-Token`） |
| `CRA_SERVER_PLATFORM` | 否 | `github` | 平台选择（`github`\|`gitlab`，小写；M4e 第二半，ADR-0013） |
| `CRA_GITLAB_BASE_URL` | 否 | `https://gitlab.com/api/v4` | GitLab API 根（实例根或已含 `/api/v4` 均可，自动归一） |
| `CRA_GITLAB_TOKEN` | gitlab 模式必填 | — | GitLab access token（`api` scope；PRIVATE-TOKEN） |
| `CRA_GITLAB_TOKEN_FILE` | 否 | — | token 文件路径（容器/K8s secret mount；与 `CRA_GITLAB_TOKEN` 二选一，前者优先） |
| `CRA_GITLAB_BOT_USERNAME` | 否 | — | `/user` 不可读时的 bot 身份回退（CI job token 场景必填） |
| `CRA_SERVER_API_TOKEN` | 是 | — | `/api/v1/**` Bearer token |
| `CRA_SERVER_SIGNING_KEY` | 是 | — | 报告签名 HMAC 密钥（CI 侧同名 `CRA_REPORT_SIGNING_KEY`） |
| `CRA_SERVER_SIGNING_KEY_ID` | 否 | `cra-server-v1` | 密钥标识（轮换用，进签名信封） |
| `CRA_SERVER_ATTEST_REQUIRE_KNOWN_COMMIT` | 否 | `true` | 只对服务端已完成评审的 commit 签发 |
| `CRA_SERVER_QUEUE_CAPACITY` | 否 | `100` | 有界队列容量；满 → 503 背压 |
| `CRA_SERVER_WORKERS` | 否 | `2` | 异步评审 worker 数 |
| `CRA_SERVER_DRAIN_TIMEOUT_SECONDS` | 否 | `30` | 停机排水等待上限 |
| `CRA_SERVER_SHUTDOWN_GRACE` | 否 | `30s` | Spring graceful shutdown 等待（需 < `stop_grace_period`） |
| `CRA_SERVER_REVIEW_DEPTH` | 否 | `standard` | 评审档位（quick\|standard\|deep） |
| `CRA_SERVER_DRY_RUN` | 否 | `false` | 全程零写（调试） |
| `CRA_SERVER_RUN_ROOT` | 否 | `.cra/server-runs` | trace/brief 落盘根 |
| `CRA_SERVER_WORKSPACE` | 否 | `.` | 本地仓库根；无 checkout 时 pre-existing 检测降级声明（05§2.1） |
| `CRA_SERVER_RUN_TOTAL_TOKENS` | 否 | `800000` | run 级预算（02§4.4） |
| `CRA_REVIEW_LARGE_DIFF_LINES` | 否 | `4000` | 大 diff 降档线（05§5 review.*；与 CLI/sweep 共用同一组键） |
| `CRA_REVIEW_HARD_CAP_LINES` | 否 | `16000` | 硬上限（须大于 `CRA_REVIEW_LARGE_DIFF_LINES`） |
| `CRA_REVIEW_NIT_BUDGET` | 否 | `3` | nit 预算默认（0 = 禁止 nit） |
| `CRA_REVIEW_DEGRADE_AT` | 否 | `0.70,0.85` | run 预算降档阈值（`0<first<second<=1`） |
| `CRA_REVIEW_SPOT_CHECK_PERCENT` | 否 | `20` | important/security 级 REFUTED 抽查保险丝（0..100） |
| `CRA_REVIEW_PUBLISH_UNVERIFIED` | 否 | `never` | UNVERIFIED 发布策略（`never`\|`labeled`） |
| `CRA_REVIEW_TESTS` | 否 | `false` | 测试文件是否进入评审范围（02§4.4） |
| `CRA_GITHUB_API_BASE` | 否 | `https://api.github.com` | GitHub API 根（测试/stub 可注入） |
| `CRA_GITHUB_TOKEN` | 否* | — | GitHub token（*生产建议必填：rate limit 与回帖权限） |
| `CRA_LLM_API_KEY` / `CRA_LLM_BASE_URL` / `CRA_LLM_MODEL` | 是 | — | OpenAI 兼容端点（缺失拒绝启动） |
| `CRA_LLM_MODEL_STRONG` / `CRA_LLM_MODEL_LIGHT` | 否 | 继承 standard | 三档路由 |
| `CRA_LLM_TEMPERATURE` | 否 | 空（不传） | 建议 `0.2`（05§5） |
| `CRA_LLM_FALLBACK_CHAIN` | 否 | `standard,light` | 降级链 |
| `CRA_LLM_RPM` | 否 | 文档默认 | 形如 `standard=60,strong=30,light=60` |
| `CRA_LLM_MAX_CONCURRENCY` | 否 | `8` | LLM 并发信号量上限（1..64；有效并发 = min(值, cra-core 线程池宽度)） |
| `CRA_CROSSCHECKER_ENABLED` | 否 | `false` | M4f-lite CrossChecker |
| `CRA_CROSSCHECKER_MODES` | 否 | `assumption,sniff` | 模式开关 |
| `CRA_CROSSCHECKER_HISTORY_WEEKS` | 否 | `8` | 变更史回溯周数 |
| `CRA_SERVER_EVENT_HISTORY_ENABLED` | 否 | `false` | 事件史采集开关（M4f-full，ADR-0012）；开启时 `SOURCE` 必填（fail closed） |
| `CRA_SERVER_EVENT_HISTORY_CRON` | 否 | `0 0 3 * * *` | 采集 cron（Spring 六段式；组装期 `CronExpression` 校验） |
| `CRA_SERVER_EVENT_HISTORY_SOURCE` | 开启时必填 | — | JSONL 来源：本地路径 / `file:` URI / `http(s)` 预签名 URL |
| `CRA_SERVER_EVENT_HISTORY_LOOKBACK_DAYS` | 否 | `7` | 单次采集窗口（天）；应 ≥ cron 周期 |
| `CRA_SERVER_EVENT_HISTORY_OVERLAP_HOURS` | 否 | `24` | 与上次水位的重叠（迟到事件；幂等键防重复） |
| `CRA_SERVER_EVENT_HISTORY_MAX_ATTEMPTS` | 否 | `3` | 读取/入库最大尝试次数（含首次） |
| `CRA_SERVER_EVENT_HISTORY_RETRY_BACKOFF_MS` | 否 | `500` | 第 n 次重试等待 n × 基数（毫秒） |
| `CRA_SERVER_EVENT_HISTORY_MAX_BYTES` | 否 | `67108864` | 单次读取上限（64MB；超出按读取失败） |
| `CRA_SERVER_EVENT_HISTORY_MAX_EVENTS` | 否 | `5000` | 单次入库上限（超出按发生时间升序截断，水位随已入库部分） |

## 4. 端点与状态码

| 端点 | 鉴权 | 说明 |
|---|---|---|
| `POST /webhooks/github` | HMAC-SHA256 | 202 已入队；200 重复 delivery / 事件忽略；400 缺头/非法 payload；401 验签失败；503 队列满（带 `Retry-After: 30`）。仅 `CRA_SERVER_PLATFORM=github`（或未设置）时注册 |
| `POST /webhooks/gitlab` | `X-Gitlab-Token` | 语义同上；200 ignored 带 `reason`（`unsupported_event` / `draft` / `merged` / `metadata_update` 等）。仅 `CRA_SERVER_PLATFORM=gitlab` 时注册 |
| `POST /api/v1/reports/attest` | Bearer | 原始报告 JSON → `text/plain` 签名信封；400 非法报告；409 repo 缺失/非 owner/name 形式或 commit 未经服务端评审；401 未授权 |
| `GET /healthz` | 无 | 进程 + 队列统计（capacity/queued/inFlight/accepted/completed/failed/rejected）+ `platform` |
| `GET /readyz` | 无 | PG 可达才 200；不可达 503（docker healthcheck 用） |

**背压语义**：队列满时先释放 delivery 占位再回 503——GitHub 重投同一 delivery 时可再次 claim，不会因为一次背压永久丢失；成功 delivery 的重复投递在 claim 阶段被拒（200 duplicate），不会二次评审；**失败 delivery 的重投允许重试**（`FAILED → RECEIVED` 原子重置，仅一个重投能成功），LLM 暂不可得等可恢复故障不必人工换 delivery id。

## 5. 健康检查与优雅停机

- **健康检查**：compose 用 `/readyz`（PG 可达）；`/healthz` 额外暴露队列水位，便于观察背压；
- **优雅停机**：`SIGTERM` → Spring `server.shutdown=graceful` 停止接收新请求 → `ReviewQueue.stop()` 停止接收新任务，在 `CRA_SERVER_DRAIN_TIMEOUT_SECONDS` 内等待队列清空与 in-flight 归零 → 超时才 `shutdownNow`；`docker stop_grace_period` 必须大于 Spring grace + 排水时间（compose 默认 `90s`，Spring grace 30s + 排水 30s 留余量）；
- 停机日志包含 `drained/queued/inFlight/accepted/completed/failed/rejected`，可据此判断是否有任务被丢弃。

## 5.1 事件史采集（M4f-full，默认关闭）

1. 准备 JSONL（每行一个对象；字段见 05§1.6/ADR-0012）：

   ```json
   {"event_id":"INC-42","repo":"owner/repo","service":"billing","tenant":"acme","occurred_at":"2026-08-14T03:00:00Z","type":"incident","summary":"企业长会话窗口改为 8h 后，30 分钟会话续期假设失效","source_url":"https://tracker/INC-42","commit_refs":["abc1234"],"paths":["src/billing/**"]}
   ```

2. 配 `CRA_SERVER_EVENT_HISTORY_SOURCE`（本地路径/`file:`/`http(s)` 预签名 URL）与 `CRA_SERVER_EVENT_HISTORY_ENABLED=true`，重启服务；
3. 手动触发一次（或等 cron）后核对：`/healthz` 不暴露采集计数（当前只有进程/队列统计），以服务日志 `event-history 采集结束: executed=… inserted=… note=…` 为准；PG 侧 `SELECT count(*), max(occurred_at) FROM product_event`；
4. 重复跑零重复：同 `(repo,event_id)` 只覆盖不新增；第二次运行日志应为 `inserted=0 updated=N`；
5. 失败处置：来源不可读/入库重试耗尽 → `executed=false` + note；解析失败 → `failed>0` 且**水位不推进**（修好来源后下次重试）；不要手工改水位表来"跳过"失败行；
6. 只让 CrossChecker 用事件史时仍需 `CRA_CROSSCHECKER_ENABLED=true`；事件史无数据/未命中时 CrossChecker 按模式 A 的变更史与 REVIEW.md 产品段继续，覆盖声明写明"事件史不可得/无命中"。

## 6. 报告签名（消除 L2 荣誉制）

服务端签发：

```bash
curl -fsS -X POST http://127.0.0.1:8080/api/v1/reports/attest \
  -H "Authorization: Bearer $CRA_SERVER_API_TOKEN" \
  -H "Content-Type: application/json" \
  --data-binary @eval/l2/report.json > eval/l2/report.json.sig
```

CI/本地校验：

```bash
CRA_REPORT_SIGNING_KEY=... cra-eval verify \
  --report eval/l2/report.json --signature eval/l2/report.json.sig \
  --tier l2 --expect-commit "$REPORT_COMMIT"
```

- 信封格式见 `org.cra.common.attest.ReportSignature`（行式文本，HMAC-SHA256 覆盖报告摘要 + commit/tier/签发时间）；
- `--signing-key-env` 可改读其他环境变量；`--signing-key-file` 用于本地演示；
- **诚实边界**（ADR-0008）：签名证明“持有服务端密钥者签发了该报告摘要”，默认策略还要求该 commit 已被本服务完成评审；它不能证明报告内容由服务端执行生成——服务端托管执行评测是更强的后续形态。

## 7. 已知限制（显式声明，不假装支持）

| 场景 | 行为 | 依据 |
|---|---|---|
| 冲突 PR | `pull_request` workflow 不触发（GitHub 平台行为），服务端收不到事件 | 02§5 触发边界表 |
| draft PR | 忽略；`ready_for_review` 时评审 | 02§5、05§4 |
| 同 head 重跑（SKIPPED） | 不写 `review_run`：该表只持久化 DONE/DEGRADED 完成标记；跳过只体现在日志与 `/healthz` 的 `completed` 计数 | 05§1.3、05§1.5、本次验收 |
| 被忽略的 webhook 事件 | 返回 200，但当前实现没有专用日志行（draft/非 PR 均如此）；排障以 GitHub delivery 状态与 HTTP 响应体为准 | 本次验收 |
| fork PR | webhook 可到，但 GitHub token 权限受限；回帖可能失败 | 02§5 |
| 服务端无 checkout | `LocalRepoContext.localRepoRoot()` 为空：pre-existing 检测降级为不标注 + Summary 声明 | 05§2.1 |
| 事件史来源 | 第一版只支持 JSONL（本地/`file:`/`http(s)` 预签名 URL）；**Jira、Confluence、ServiceNow/服务台、Slack/Teams 事故频道、PagerDuty、GitHub Issues 均未接**（后续 `EventHistorySource` 实现） | 11§2、ADR-0012 |
| 事件史覆盖 | 只覆盖已文档化入库的工单/事故复盘；tribal knowledge 不可见（01§3.4）；路径关联依赖事件源声明 `paths`，无结构性召回保证 | ADR-0012 |
| 事件史摘要 | 事件摘要来自外部系统，属不可信输入（brief 内 UNTRUSTED 定界）；`raw_json` 只入库不注入；摘要可能过时/错误 | 04§3.3、ADR-0012 |
| 事件史水位 | 行级解析失败时**不推进水位**（失败行下次重试）；修复来源前该源会持续重读同一窗口 | ADR-0012 |
| 事件史读取上限 | 单次 JSONL 读取 ≤64MB（默认）；超出按读取失败，需拆分对象或调大 `CRA_SERVER_EVENT_HISTORY_MAX_BYTES`；无云 SDK 分页/断点续传 | ADR-0012 |
| 事件史与评测 | CLI 评测组合根未装配 `EventHistoryQuery`；L2/L3 的 context 门禁仍由 git 变更史 + REVIEW.md 驱动，不验证事件史路径 | 04§2.4 |
| 多仓库 | 每个仓库一个 PG 账本实例（按 owner/name 键控），orchestrator 按仓库缓存；仓库数增长 = 常驻线程池增长，需按部署规模评估 | ADR-0008 |
| 多副本 | delivery 幂等由 PG 唯一键兜底，但同一仓库的 orchestrator 缓存/队列是进程内的；同 head 重跑由 core commit 闸兜底 | ADR-0008 |
| 签发 commit 判定范围 | v2 起按 `(repo, commit)` 查 `review_run`；`X-CRA-Repo: owner/name` 必填（无条件，签发入口做非空与形式校验，缺失或非法 → 409）。v1 全局签发路径已移除，旧签名需重签 | ADR-0009、05§1.5 |
| 过期 Summary 收尾 | PG 账本不落评论原文（与 SQLite 同语义）：`ReviewLedger.comment()` 为空 → 新 run 不 PATCH 旧 Summary 的 `[已被 run_xxx 取代]` 前缀；旧 Summary 仍可见 | 05§1.5、05§2.2 与本地形态一致 |
| 事件史采集（默认关闭） | `CRA_SERVER_EVENT_HISTORY_ENABLED=false` 时装配 Noop、采集不执行；开启时按 JSONL 增量采集（M4f-full） | ADR-0012 |

## 8. 运维检查单

- [ ] 三个密钥均已替换为强随机值，且 `CRA_REPORT_SIGNING_KEY`（CI secret）与 `CRA_SERVER_SIGNING_KEY` 一致；
- [ ] `CRA_GITHUB_TOKEN` 具备读取 PR diff 与发布 review/issue comment 的权限；
- [ ] `CRA_LLM_*` 端点可达，`CRA_LLM_TEMPERATURE` 已设置（建议 0.2）；
- [ ] `stop_grace_period` > Spring grace + 队列排水；
- [ ] `/healthz` 的 `rejected`/`queued` 有监控告警（持续背压 = 容量或 LLM 限流问题）；
- [ ] PG 备份覆盖 `review_run`/`finding`/`webhook_delivery`（账本是幂等与收敛的事实源）。

## 9. 真实 GitHub 端到端验收（M4b，2026-10-27）

环境：私有 scratch 仓库 `zingaltern/cra-acceptance-20261027`；`docker compose` 起 PostgreSQL 16 + `cra-server`（运行时镜像复用本机 `d24e221` 构建的 exec jar）；公网入口为 cloudflared 临时 quick tunnel；GitHub 侧为真实 webhook（id `703809632`，仅 `pull_request` 事件）。服务档位 `quick`（降低验收成本；webhook/幂等/队列/账本/签发路径与档位无关）。LLM 为 OpenAI 兼容端点，密钥只经环境变量注入。

| 验收断言 | 实测结果 | 证据 |
|---|---|---|
| PR `opened` → 202 → 评审 DONE 落 PG | 通过 | delivery `2dfb0da0-d1f0-11f1-87a0-5dc4ccb36e8f`；`run_20261027102140-6f6f0d`，PR #1 head `3f9ae86`，status `DONE`、findings 0 |
| PR `synchronize` → 202 → DONE 落 PG | 通过 | delivery `529ad0f0-d1f0-11f1-91dd-3a9f05e2082f`；`run_20261027102234-513252`，head `faee7cb`，DONE、findings 1 |
| 同 delivery 重投 → duplicate | 通过 | 公网 URL 重放同一 `X-GitHub-Delivery: 2dfb0da0-…` + 合法 HMAC → `200 {"status":"duplicate"}`，未新增 run |
| 新 delivery 同 head → SKIPPED | 通过 | close→reopen 产生 delivery `66108170-d1f0-11f1-92d7-89eb98f49328`（202）；日志 `run_20261027102307-d25ad0 status=SKIPPED`；`review_run` 不落行（见 §7） |
| draft PR → 200 ignored | 通过 | PR #2 delivery `d3d56590-d1f0-11f1-9122-bcb327eca8d6` 返回 200；签名重放返回 `200 {"status":"ignored","event":"pull_request"}` |
| 非 PR 事件 → 200 ignored | 通过 | 签名 `ping` → `200 {"status":"ignored","event":"ping"}` |
| 错签 → 401 | 通过 | `X-Hub-Signature-256=sha256:000…` → 401 `{"status":"unauthorized"}` |
| 非法 payload → 400 | 通过 | 合法 HMAC + `{not-json` → 400 `{"status":"bad_payload"}` |
| 报告签发 → verify 通过 | 通过 | `POST /api/v1/reports/attest` 200；`cra-eval verify --signature` exit 0（scratch 环境一次性密钥，验收后已弃用；明文不落仓库，密钥仅经 env/file 注入；该值历史上曾出现在旧提交与 PR diff 中——不重写历史，仅限已废弃的 scratch 环境，未用于任何生产/长期配置） |
| 篡改报告 → 退出码 5 | 通过 | 仅改 `generated_at`：门禁仍绿，签名校验失败，exit 5 |
| 优雅停机排水 | 通过 | `drained=true queued=0 inFlight=0 accepted=3 completed=3 failed=0 rejected=0` |

PG 账本实测（`review_run`）：`run_20261027102140-6f6f0d` / `3f9ae86` / DONE / 0；`run_20261027102234-513252` / `faee7cb` / DONE / 1。`healthz` 在验收期间 `accepted=3/completed=3`（opened、synchronize、reopened[SKIPPED]），`failed=0`。

**观察与边界（如实记录）**：

1. `review_run` 是完成标记表，只写 DONE/DEGRADED；同 head 重跑的 SKIPPED 不会新增 PG 行。排障需看日志与 `/healthz`，这不是本次验收的代码回归（§7 已登记）。
2. 被忽略的 webhook 事件只返回 200，当前没有专用日志行；GitHub delivery 状态与端点响应体是主要证据。
3. GitHub 的 webhook redelivery API 需要 `admin:repo_hook`；本次使用的 `gh` token 无该 scope，因此“同 delivery 重投”用**同一 GUID + 合法 HMAC 对公网 URL 重放**完成，不是本地模拟。生产若需 UI/API 重投，应授予该 scope 或使用仓库管理员操作。
4. 报告签发验收使用了一份 L2 形状报告并把 `commit` 改为服务端已评审的 head；服务端签发、`verify` 通过、篡改 exit 5。这正好印证 ADR-0008 的诚实边界：签名证明“持有密钥者签发了该摘要”，不证明报告内容由服务端执行生成。
5. 临时 cloudflared tunnel 与个人 PAT 仅用于本次验收；生产应使用稳定 HTTPS 入口、专用 bot 身份/最小权限 token，并轮换 webhook secret。验收评论因此显示为 `zingaltern`（M4b PR 评论）或 `github-actions[bot]`（M4c/M4d workflow）。
6. scratch 仓库为私有临时仓库，验收后保留作链接证据（可随时删除）；其中 webhook 仍指向已失效的临时 tunnel，不影响生产。
7. 本节的 scratch 仓库名/webhook id 等基建细节保留作验收证据链（仓库为私有、tunnel 已失效），属已声明的取舍；密钥类值一律不落仓库（见上表"报告签发"行的注记），仅经 env/file 注入。
