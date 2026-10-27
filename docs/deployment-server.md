# cra-server 部署形态（M4b 服务化）

> 依据：02§5（部署形态与状态归属）、07§5 M4b、ADR-0008。本文是服务化形态的**操作手册**：怎么起、配什么、怎么检查、怎么停机、有哪些边界。
> 适用读者：部署本服务的 owner / 运维。评审逻辑本身见 02/03/04，不在本文重复。

---

## 1. 形态总览

```
GitHub ──webhook(HMAC)──▶ cra-server
                            ├─ WebhookController（验签 → pull_request 子集解析）
                            ├─ WebhookIngestService（delivery 幂等 claim → 有界队列）
                            ├─ ReviewQueue（有界 + 背压 + 固定 worker）
                            │     └─ ReviewJobHandler → CoreReviewExecutor
                            │            └─ cra-core ReviewPipeline（与 CLI 同一实现）
                            ├─ PostgreSQL 账本（cra-store PostgresLedger，05§1.5 五表）
                            └─ AttestationController（/api/v1/reports/attest，HMAC 报告签名）
```

- **第二组合根**：cra-server 与 cra-runner-cli 只是两种壳（02§5），评审逻辑完全复用 core；
- **账本**：PostgreSQL，按仓库创建 `ReviewLedger` 实例（delivery 表与签发表为 server 自有）；
- **队列**：进程内有界队列（不引入外部 broker，理由见 ADR-0008）；满时 HTTP 503 → GitHub 按同一 delivery 重投；
- **幂等**：delivery id（PG 唯一键）挡重复投递；core 的 commit 闸 + 指纹账本挡同 head 重跑（02§4.1）；
- **签名报告**：服务端对 L2 报告签发 HMAC 信封，`cra-eval verify --signature` 校验（04§2.4）。

## 2. 快速启动（docker-compose）

```bash
cd docker
cp .env.example .env          # 覆盖全部密钥；填入 CRA_LLM_* 与 CRA_GITHUB_TOKEN
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

## 3. 环境变量表

密钥只从环境变量读（05§6）；下表“必填”项缺失时服务拒绝启动（fail closed）。

| 变量 | 必填 | 默认 | 说明 |
|---|---|---|---|
| `CRA_SERVER_PORT` | 否 | `8080` | HTTP 端口（compose 内固定 8080） |
| `CRA_SERVER_DB_URL` | 否 | `jdbc:postgresql://localhost:5432/cra` | PG JDBC URL（compose 注入） |
| `CRA_SERVER_DB_USER` / `CRA_SERVER_DB_PASSWORD` | 否 | `cra` | PG 凭据（compose 注入；生产必须覆盖密码） |
| `CRA_SERVER_DB_POOL` | 否 | `10` | Hikari 连接池上限 |
| `CRA_SERVER_WEBHOOK_SECRET` | 是 | — | GitHub webhook HMAC 密钥 |
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
| `CRA_SERVER_RUN_TOTAL_TOKENS` | 否 | `500000` | run 级预算（02§4.4） |
| `CRA_GITHUB_API_BASE` | 否 | `https://api.github.com` | GitHub API 根（测试/stub 可注入） |
| `CRA_GITHUB_TOKEN` | 否* | — | GitHub token（*生产建议必填：rate limit 与回帖权限） |
| `CRA_LLM_API_KEY` / `CRA_LLM_BASE_URL` / `CRA_LLM_MODEL` | 是 | — | OpenAI 兼容端点（缺失拒绝启动） |
| `CRA_LLM_MODEL_STRONG` / `CRA_LLM_MODEL_LIGHT` | 否 | 继承 standard | 三档路由 |
| `CRA_LLM_TEMPERATURE` | 否 | 空（不传） | 建议 `0.2`（05§5） |
| `CRA_LLM_FALLBACK_CHAIN` | 否 | `standard,light` | 降级链 |
| `CRA_LLM_RPM` | 否 | 文档默认 | 形如 `standard=60,strong=30,light=60` |
| `CRA_CROSSCHECKER_ENABLED` | 否 | `false` | M4f-lite CrossChecker |
| `CRA_CROSSCHECKER_MODES` | 否 | `assumption,sniff` | 模式开关 |
| `CRA_CROSSCHECKER_HISTORY_WEEKS` | 否 | `8` | 变更史回溯周数 |
| `CRA_SERVER_EVENT_HISTORY_ENABLED` | 否 | `false` | M4f-full 事件史采集骨架开关 |
| `CRA_SERVER_EVENT_HISTORY_CRON` | 否 | `0 0 3 * * *` | 采集 cron（Spring 六段式） |

## 4. 端点与状态码

| 端点 | 鉴权 | 说明 |
|---|---|---|
| `POST /webhooks/github` | HMAC-SHA256 | 202 已入队；200 重复 delivery / 事件忽略；400 缺头/非法 payload；401 验签失败；503 队列满（带 `Retry-After: 30`） |
| `POST /api/v1/reports/attest` | Bearer | 原始报告 JSON → `text/plain` 签名信封；400 非法报告；409 commit 未经服务端评审；401 未授权 |
| `GET /healthz` | 无 | 进程 + 队列统计（capacity/queued/inFlight/accepted/completed/failed/rejected） |
| `GET /readyz` | 无 | PG 可达才 200；不可达 503（docker healthcheck 用） |

**背压语义**：队列满时先释放 delivery 占位再回 503——GitHub 重投同一 delivery 时可再次 claim，不会因为一次背压永久丢失；成功 delivery 的重复投递在 claim 阶段被拒（200 duplicate），不会二次评审；**失败 delivery 的重投允许重试**（`FAILED → RECEIVED` 原子重置，仅一个重投能成功），LLM 暂不可得等可恢复故障不必人工换 delivery id。

## 5. 健康检查与优雅停机

- **健康检查**：compose 用 `/readyz`（PG 可达）；`/healthz` 额外暴露队列水位，便于观察背压；
- **优雅停机**：`SIGTERM` → Spring `server.shutdown=graceful` 停止接收新请求 → `ReviewQueue.stop()` 停止接收新任务，在 `CRA_SERVER_DRAIN_TIMEOUT_SECONDS` 内等待队列清空与 in-flight 归零 → 超时才 `shutdownNow`；`docker stop_grace_period` 必须大于 Spring grace + 排水时间（compose 默认 `90s`，Spring grace 30s + 排水 30s 留余量）；
- 停机日志包含 `drained/queued/inFlight/accepted/completed/failed/rejected`，可据此判断是否有任务被丢弃。

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
| fork PR | webhook 可到，但 GitHub token 权限受限；回帖可能失败 | 02§5 |
| 服务端无 checkout | `LocalRepoContext.localRepoRoot()` 为空：pre-existing 检测降级为不标注 + Summary 声明 | 05§2.1 |
| 多仓库 | 每个仓库一个 PG 账本实例（按 owner/name 键控），orchestrator 按仓库缓存；仓库数增长 = 常驻线程池增长，需按部署规模评估 | ADR-0008 |
| 多副本 | delivery 幂等由 PG 唯一键兜底，但同一仓库的 orchestrator 缓存/队列是进程内的；同 head 重跑由 core commit 闸兜底 | ADR-0008 |
| 签发 commit 判定范围 | v2 起按 `(repo, commit)` 查 `review_run`；`X-CRA-Repo: owner/name` 必填（`CRA_SERVER_ATTEST_REQUIRE_KNOWN_COMMIT=true` 时）。v1 全局签发路径已移除，旧签名需重签 | ADR-0009、05§1.5 |
| 过期 Summary 收尾 | PG 账本不落评论原文（与 SQLite 同语义）：`ReviewLedger.comment()` 为空 → 新 run 不 PATCH 旧 Summary 的 `[已被 run_xxx 取代]` 前缀；旧 Summary 仍可见 | 05§1.5、05§2.2 与本地形态一致 |
| 事件史采集 | M4b 只有接口与调度骨架，采集器为 Noop，日志明确写“未实现” | 07§5 M4b、M4f-full |

## 8. 运维检查单

- [ ] 三个密钥均已替换为强随机值，且 `CRA_REPORT_SIGNING_KEY`（CI secret）与 `CRA_SERVER_SIGNING_KEY` 一致；
- [ ] `CRA_GITHUB_TOKEN` 具备读取 PR diff 与发布 review/issue comment 的权限；
- [ ] `CRA_LLM_*` 端点可达，`CRA_LLM_TEMPERATURE` 已设置（建议 0.2）；
- [ ] `stop_grace_period` > Spring grace + 队列排水；
- [ ] `/healthz` 的 `rejected`/`queued` 有监控告警（持续背压 = 容量或 LLM 限流问题）；
- [ ] PG 备份覆盖 `review_run`/`finding`/`webhook_delivery`（账本是幂等与收敛的事实源）。
