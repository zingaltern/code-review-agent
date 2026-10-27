# GitLab 适配与部署边界（M4e 第二半）

> 依据：ADR-0011（适配器）、ADR-0013（平台选择与 webhook 接线）、11§6（新 Git 平台接入 checklist）、05§2.1–§2.5、02§5。本文是 GitLab 平台事实的**主载体**；11§2 与 README 只做一句话指回。
> 适用范围：`cra-git-gitlab` 适配器 + CLI/server 的平台选择与 GitLab webhook。**真实 GitLab 写操作仍无 owner token，未做线上验证**（读路径已对 gitlab.com 公共项目实跑，见 §12）。

---

## 1. 交付范围与当前状态

- 新模块 `cra-git-gitlab`（包 `org.cra.git.gitlab`），**不依赖 `cra-git-github`**；实现 `fetchDiff` / `readFile` / `postReview` / `fetchBotComments`，并按需实现 `listFiles` / `fetchDispositionSignals`、M4c/M4d 载体 `postCommitReview` / `postIssueReview`、`CommentSource` 的 PR/commit/issue 账本方法。
- 账本标记格式原样移植 05§1.3（`v=2.1`、finding/run 两类标记、末位规则、DONE|DEGRADED），解析器在本模块独立持有并保留 100% 分支覆盖门禁；**不自造格式**。
- **第二半已接线**（ADR-0013）：
  - CLI：`--platform github|gitlab` / `CRA_GIT_PLATFORM`（默认 github）；`cra review` 与 `cra sweep` 按平台装配 adapter + `CommentSource`；
  - server：`CRA_SERVER_PLATFORM=github|gitlab`；GitLab 模式注册 `POST /webhooks/gitlab`（`X-Gitlab-Token` 验签 + MR 事件映射 + delivery 幂等）。
- 已验证：`mvn -B -ntp verify` 全绿；GitLab adapter 端点契约测试（WireMock 录制回放，22 例）；fixture 端到端（发布 → 同 commit 账本命中 → 零重复，1 例）；CLI 平台接线（`--platform gitlab` → GitLab adapter + GitLab `CommentSource` → 账本幂等）；server GitLab webhook HTTP 集成（需要 PG 的 5 例：202/duplicate、update 映射、401/400、非 MR 忽略、平台端点与健康检查）。
- **真实 GitLab 读路径已线上核验**（2026-10-27，公共项目 `gitlab-org/cli`，无 token）：`fetchDiff`（2 文件 / 20 patch 行）、`listFiles`（331 个 markdown）、`readFile`（`README.md` 13,772 字符）——见 §12。
- 仍未完成：**真实 token 的写操作验证**（MR discussion / note、commit comment、issue）、GitLab CI job 实跑、Gerrit、语义索引/跨文件上下文。

## 2. 认证与基础

| 项 | GitLab 事实 | 本项目用法 |
|---|---|---|
| API 根 | `https://gitlab.com/api/v4`（自建实例同构） | `--gitlab-base-url` / `CRA_GITLAB_BASE_URL`：实例根或已含 `/api/v4` 均可，自动归一到 `/api/v4` |
| 认证 | `PRIVATE-TOKEN` 头（personal / project / group access token） | `CRA_GITLAB_TOKEN` 或 `CRA_GITLAB_TOKEN_FILE`（文件内容去空白）；`--token` 仅 GitHub，GitLab 模式显式拒绝命令行 token |
| token 权限 | `api` = 读写；`read_api` = 只读 | 读路径最小 `read_api`；发布 review/issue 需 `api` |
| CI job token | `CI_JOB_TOKEN` 可访问的 API 是子集，且受项目 allowlist 限制 | **不假设** job token 能发 MR note；CI 内用 project access token / bot token |
| 项目标识 | `id` 可为数字 ID 或 URL 编码的完整路径（`group%2Fproject`） | CLI 用 `--gitlab-project group/project`（嵌套 group 按最后一个 `/` 拆分 owner/name）；服务端从 `project.path_with_namespace` 解析 |
| bot 身份 | `GET /user` 返回 `username`；project access token 对应 bot 用户 | `/user` 不可读（401/403）时必须显式给 `botUsername`（`CRA_GITLAB_BOT_USERNAME`），否则拒绝把任何评论当账本 |
| webhook 密钥 | `X-Gitlab-Token` 是 GitLab webhook 配置的 Secret token（直传共享密钥，不是 HMAC） | `CRA_SERVER_WEBHOOK_SECRET` 在 GitLab 模式下即该 Secret token；常数时间比较 |
| 文件路径 | File API 要求**完整路径**整体编码（`src%2Fmain%2FFoo.java`） | 由 `GitLabPayloadSupport.encodeFilePath` 处理（斜杠也编码） |

## 3. 端点映射（GitHub → GitLab）

| 用途 | GitHub（既有实现） | GitLab REST v4（本实现） | 关键差异 |
|---|---|---|---|
| 变更集 | `GET /repos/{o}/{r}/compare/{base}...{head}` | `GET /projects/:id/repository/compare?from={base}&to={head}` | 响应 `diffs[]`（`old_path/new_path/new_file/renamed_file/deleted_file/diff/too_large/collapsed`）；`compare_timeout=true` 时**拒绝评审**（部分 diff 不伪装完整） |
| 文件内容 | `GET /contents/{path}?ref=`（raw） | `GET /projects/:id/repository/files/{encoded}/raw?ref=` | 完整路径整体编码 |
| 文件树 | `GET /git/trees/{sha}?recursive=1` | `GET /projects/:id/repository/tree?ref={sha}&recursive=true` | offset 分页（默认 20，最大 100） |
| inline 发布 | `POST /pulls/{n}/reviews`（整包，422 逐条降级） | `POST /projects/:id/merge_requests/{iid}/discussions`（逐条线程） | **无 review 聚合对象**：`PrReviewResult.reviewId` 显式缺省；位置需 `base_sha/head_sha/start_sha` 三元组 |
| Summary 发布 | `POST /issues/{n}/comments` | `POST /projects/:id/merge_requests/{iid}/notes` | MR note 可编辑、可 reaction |
| Summary 编辑 | `PATCH /issues/comments/{id}` | `PUT /projects/:id/merge_requests/{iid}/notes/{id}` | 端点为嵌套路径 |
| 账本拉取（PR/MR） | `/pulls/{n}/comments` + `/issues/{n}/comments` | `GET /projects/:id/merge_requests/{iid}/discussions` | 一个端点同时含 inline（`position` 非空）与 Summary note（`position` 空）；过滤 `system` 事件 |
| push 载体 | `POST /commits/{sha}/comments` | `POST /projects/:id/repository/commits/{sha}/comments` | 字段 `note`（不是 `body`）；**响应不含评论 id** |
| commit 账本 | `GET /commits/{sha}/comments` | `GET /projects/:id/repository/commits/{sha}/comments` | 同上；按 `author.username` 过滤 |
| issue 载体 | `POST /issues` | `POST /projects/:id/issues` | 正文字段 `description`；`labels` 为逗号分隔字符串，缺失标签会被**自动创建** |
| issue 账本/扫描 | `/issues?state=all&sort=updated`（需排除 PR 条目） | `GET /projects/:id/issues?state=all&order_by=updated_at&sort=desc&author_username=` | issues 端点本身不含 MR，无需排除；可用 `updated_after` 过滤窗口 |
| MR/PR 列表 | `/pulls?state=all&sort=updated` | `GET /projects/:id/merge_requests?state=all&order_by=updated_at&sort=desc` | `state` 取值 `opened/closed/merged/locked`；列表可能不含 `diff_refs`，缺 base 时逐 MR 补查 |
| reaction | `/issues/comments/{id}/reactions` | `GET /projects/:id/merge_requests/{iid}/notes/{note_id}/award_emoji` | 名称 `thumbsup`/`thumbsdown`（文档未枚举，见 §9） |
| bot 身份 | `/user` → `login` | `/user` → `username` | 字段名不同 |

## 4. 分页、限流与错误语义

- **分页**：offset `page`/`per_page`（默认 20，最大 100），优先读 `X-Next-Page`；末页该头为空；keyset 分页不用（仅部分资源支持）。
- **限流**：`RateLimit-Limit/Remaining/Reset/Observed/Name`；超限返回 `429` + `Retry-After`。适配器对 429 与带 `Retry-After` 的 403 退避（指数，≤3 次）；`RateLimit-Remaining < 20` 只告警不硬睡。GitLab.com 的应用级限流可能不在响应头里，因此 429 仍可能突然出现（已按退避处理）。
- **错误映射**：
  - 400/422（位置非法、参数非法）→ 该条 inline 降级 `SUMMARY_FALLBACK`，继续发 Summary；
  - 其他状态（401/403/404/5xx）→ `CraIntegrationException` 上抛，由 run 级失败处置；
  - Summary note 发布失败 → `summaryCommentId` 为空 = 无 DONE = 重跑走指纹级补发（02§4.1）；
  - `compare_timeout=true` → 直接失败，不产出"覆盖完整"假象。

## 5. 触发边界表（GitLab CI，显式声明而非假装支持）

| 场景 | GitLab 行为（文档核查） | 处置（workflow / CLI） |
|---|---|---|
| MR opened / 源分支新 push / 手动运行 | MR pipeline 在**源分支内容**上运行（官方文档原文），`CI_PIPELINE_SOURCE == "merge_request_event"` | `--base "$CI_MERGE_REQUEST_DIFF_BASE_SHA"`、`--head "$CI_COMMIT_SHA"`、`--pr "$CI_MERGE_REQUEST_IID"` |
| draft MR | 官方文档：draft MR 运行与普通 MR **相同的 pipeline**；项目侧可用标题前缀过滤 | `workflow:rules` / job `rules` 排除标题 `Draft:` / `[Draft]` / `(Draft)`；转正后是否自动重跑由项目规则决定，需显式确认 |
| 冲突 MR | 文档**未声明**冲突会阻断 MR pipeline；MR pipeline 只依赖源分支内容 → 推断不会像 GitHub 那样"冲突不触发" | **待真实 GitLab 实例确认**；确认前按"可能触发"配置，评审结果照常发布 |
| fork MR | 默认在 fork（源）项目创建并运行，使用 fork 的 CI 配置/变量/资源；父项目受保护资源不可用；父项目触发模式使用 fork 的 CI 配置但父项目设置 | 不在 fork MR 上假设 secrets 可见；需要评审父项目时显式启用父项目触发并收紧权限 |
| 直推分支（push 形态） | 分支 pipeline 触发（`CI_PIPELINE_SOURCE == "push"`） | `cra review --push --head "$CI_COMMIT_SHA"`；base 由 CLI 沿首父链查 commit comment 账本，首次运行给 `--base` |
| 夜间定时 | 用 pipeline schedule 触发（`CI_PIPELINE_SOURCE == "schedule"`） | `cra sweep --repo "$CI_PROJECT_PATH" --since 30d --max-prs 10 --depth quick` |
| 并发 | `resource_group` 串行同一 MR/ref；`interruptible: false` + `workflow:auto_cancel:on_new_commit: conservative`（默认）避免掐断已发布一半的 run | 被掐断的 run 无 DONE；重跑整条重发（与 05§2.4 一致） |
| bot 自举循环 | `GITLAB_USER_LOGIN` 标识触发者 | 跳过 bot 用户（如 `*_bot`）的提交/流水线，避免自评审 |
| merge commit | 无独立变更语义 | 跳过 `Merge branch` / `Merge remote-tracking` 前缀的 head commit（与 02§5 GitHub 行同目的） |

`CI_MERGE_REQUEST_DIFF_BASE_SHA` = "The base SHA of the merge request diff"；`CI_MERGE_REQUEST_TARGET_BRANCH_SHA` = "The HEAD SHA of the target branch"（是否可得取决于 pipeline 类型）。普通 MR pipeline 用前者作 diff 基线；不要拿目标分支 HEAD 当 diff 基线（会把目标分支的新提交算进评审）。

## 6. 账本映射（05§1.3 键控扩展）

标记格式不变（`v=2.1`、字段顺序、末位规则、`<!--` 转义、DONE|DEGRADED）。只扩展"这份标记属于谁"：

| 形态 | 账本键 `ReviewKey` | 载体 | GitLab 落地 |
|---|---|---|---|
| PR/MR | `(repo, mr iid, commit)` | inline discussion + Summary MR note | `GET /merge_requests/{iid}/discussions`：`position` 非空 = inline，空 = Summary；`system` note 过滤；Summary 用 `PUT .../notes/{id}` 做过期收尾 |
| push 直推（M4c） | `(repo, commit)`（pr=0） | 单条 commit comment | `POST/GET /repository/commits/{sha}/comments`；一条评论只承载一个末位 run 标记，无 finding 级标记；**评论无平台 id → 合成 id** |
| 夜间存量（M4d） | `(repo, commit)` | 单条 issue | `POST /issues`（`description`）；`GET /issues?author_username=` 拉 bot issue，按标记内 `commit=` 过滤；`iid` 作载体 id |

**commit comment 合成 id（ADR-0011 决策 7）**：GitLab commit comment 响应没有评论 id。适配器用 `sha256(sha + "\n" + note)` 前 15 位十六进制生成正整数，发布与回放使用同一函数；该 id 只用于本地映射，**不可用于编辑/删除评论**。合成 id 让 `postCommitReview` 能返回非空 id（核心据此判定"完整发布"），并使同 commit 重放的 DONE 闸命中。

**denoising**：MR discussions 中 bot 的回复也会被收集，但只有末位合法标记参与账本；`status=SYSTEM`/`system=true` 的事件不参与；同一 note id 去重。

## 7. CLI 接线（M4e 第二半）

平台选择优先级：`--platform` > `CRA_GIT_PLATFORM` > `github`（默认保持既有 GitHub 行为）。`cra review` 与 `cra sweep` 共用同一解析器。

| 用途 | 参数 | 环境变量（GitLab 模式） | 默认 |
|---|---|---|---|
| 平台 | `--platform github\|gitlab` | `CRA_GIT_PLATFORM` | `github` |
| 项目 | `--gitlab-project group/project`（`--repo` 亦可） | `CRA_GITLAB_PROJECT` / `CI_PROJECT_PATH` | — |
| MR 编号 | `--pr` | `CI_MERGE_REQUEST_IID` | — |
| diff 基线 | `--base` | `CI_MERGE_REQUEST_DIFF_BASE_SHA` | —（push 形态按 05§2.4 查账本） |
| 被评审 head | `--head` | `CI_COMMIT_SHA` | — |
| API 根 | `--gitlab-base-url` | `CRA_GITLAB_BASE_URL` | `https://gitlab.com/api/v4` |
| token | **不接受 `--token`** | `CRA_GITLAB_TOKEN` / `CRA_GITLAB_TOKEN_FILE` | —（非本地模式必填） |
| bot 身份 | — | `CRA_GITLAB_BOT_USERNAME` | 空（`/user` 可读时自动取） |

- **嵌套 group**：`group/subgroup/project` 按最后一个 `/` 拆成 `RepoRef(owner=group/subgroup, repo=project)`，适配器整体 URL 编码为 `group%2Fsubgroup%2Fproject`。
- **token 边界**：GitLab 模式拒绝 `--token`（防止把 GitHub token 串用）；`CRA_GITLAB_TOKEN_FILE` 用于容器/K8s secret mount，内容去首尾空白，错误信息不含内容。
- **账本键不变**：仍是 `repo + pr/commit`（05§1.3）；平台只改变取数端点，不改变标记格式或键控。
- **本地 pre-PR 形态与平台无关**：`--local` 使用 jgit + SQLite，不构造远程 adapter、不要求 GitLab token；`LocalAppConfig` 保持平台中立（避免为本地形态引入平台分支）。
- **sweep**：`cra sweep --platform gitlab --gitlab-project group/project` 使用 GitLab 的 `listPullRequests` / `listBotIssues` / `postIssueReview`，扫描范围与幂等语义与 GitHub 同口径。

```bash
# GitLab CI：MR pipeline
cra review --platform gitlab \
  --gitlab-project "$CI_PROJECT_PATH" \
  --pr "$CI_MERGE_REQUEST_IID" \
  --base "$CI_MERGE_REQUEST_DIFF_BASE_SHA" \
  --head "$CI_COMMIT_SHA" \
  --depth standard
# CRA_GITLAB_TOKEN / CRA_GITLAB_BOT_USERNAME / CRA_LLM_* 由 CI 变量注入
```

## 8. 服务端接线（POST /webhooks/gitlab）

`CRA_SERVER_PLATFORM=gitlab` 时只注册 GitLab 端点（GitHub 端点不存在，返回 404）；默认 `github` 只注册 `/webhooks/github`。健康检查 `/healthz` 增加 `"platform"` 字段。

| 项 | 值 |
|---|---|
| Payload URL | `https://<your-host>/webhooks/gitlab` |
| Secret token | 与 `CRA_SERVER_WEBHOOK_SECRET` 一致（GitLab UI 的 Secret token） |
| 请求头 | `X-Gitlab-Event: Merge Request Hook`、`X-Gitlab-Token`、`X-Gitlab-Event-UUID`（GitLab 16.1+） |
| Trigger | Merge request events（push/issue/note 等事件显式忽略） |

**事件映射（显式声明，不静默假装支持）**：

| GitLab action / 状态 | 处置 | 归一化 action / reason |
|---|---|---|
| `open` | 入队评审 | `opened` |
| `reopen` | 入队评审 | `reopened` |
| `update` 且 `oldrev` 非空（有新提交） | 入队评审 | `synchronize`（对应 GitHub synchronize） |
| `update` 且 `oldrev` 为空 | 200 忽略 | `metadata_update`（标题/描述等改动不重审） |
| `merge` / `state=merged` | 200 忽略 | `merged`（与 GitHub 不评审 closed/merged 同口径） |
| `close` / `state=closed` | 200 忽略 | `closed` |
| `approved` / `unapproved` / `approval` / `unapproval` | 200 忽略 | `approval_event` |
| draft（`draft=true` / `work_in_progress=true` / 标题 `Draft:`、`[Draft]`、`(Draft)`、`WIP:` 等前缀） | 200 忽略 | `draft` |
| 其他 action | 200 忽略 | `unsupported_action` |
| 非 `Merge Request Hook` | 200 忽略 | `unsupported_event` |
| `object_kind != merge_request` | 200 忽略 | `unsupported_object_kind` |

**状态码**：401 验签失败；400 缺 `X-Gitlab-Event` / 非法 JSON / 缺 `diff_refs.base_sha`、`head_sha`、`project.path_with_namespace`、`iid`；202 已入队；200 重复 delivery 或事件被忽略（带 `reason`）；503 队列满（`Retry-After: 30`，释放 delivery 占位后可重投）。

**delivery 幂等**：优先 `X-Gitlab-Event-UUID`；缺失时用 `SHA-256(event + "\n" + raw body)` 前 40 位十六进制（`gitlab-…`）。同一 payload 重投命中同一 `webhook_delivery`，不会二次入队。**非 2xx 时 GitLab 可能重投**——幂等键保证重投不产生第二次评审；2xx 的忽略事件不会入队。

**draft 转正**：GitLab 没有 GitHub 的 `ready_for_review` 事件；移除 Draft 前缀通常只产生 `metadata_update`，本实现**不会**因此触发评审。转正后的首次 push（`oldrev` 非空）或手动运行才会评审——这是显式限制，部署方需知悉。

**fork MR**：默认在 fork 项目触发，父项目 secrets 不可见；需要评审父项目时按 GitLab 官方"父项目触发"模式配置，本组件不假设 secrets 可见。

## 9. 不可得 / 未采 / 待真实验证

| 项 | 状态 | 原因与处置 |
|---|---|---|
| 真实 GitLab 写操作 | **pending（无 owner token）** | MR discussion / Summary note / commit comment / issue 的线上写入未验证；§12 给出拿到 token 后的逐项验收清单 |
| 真实 GitLab 读操作 | **已验证（无 token，公共项目）** | `gitlab-org/cli` 的 compare / tree / raw file 实跑通过（§12） |
| CLI/server 平台选择 | **已接线** | `--platform gitlab` + `CRA_SERVER_PLATFORM=gitlab`；单测/WireMock/HTTP 集成覆盖（§12） |
| GitLab CI job 实跑 | **未验证** | 本仓 CI 仍跑 GitHub；参数映射见 §7，真实 runner 待接入项目后核验 |
| commit comment id | **平台不可得** | API 不返回 id；合成 id 只用于本地账本映射 |
| commit comment 列表分页 | **文档未声明** | 使用通用 offset `page/per_page`；真实平台需复核 |
| 账本标记的 HTML 注释保留 | **待真实验证** | 账本依赖 note/description 原文中的 `<!-- cra:... -->`；GitLab API 原文通常保留，但渲染/净化行为需真实实例确认（fixture 假定保留） |
| award emoji 名称 | **文档未枚举** | 按 GitLab emoji 全集的 `thumbsup`/`thumbsdown` 实现；真实平台待核验 |
| discussion `resolved` | **可得但不采** | GitLab REST 有 `resolvable/resolved`；`DispositionSignal` 模型无 resolve 类别，不新增 common 类型（ADR-0011 决策 10） |
| 合并事件回填 | **不在适配器** | `CommentSource` 无该数据面；与 GitHub 实现同口径 |
| 冲突 MR 是否触发 | **文档未声明，待验证** | MR pipeline 只依赖源分支内容 → 推断不阻断；真实实例确认前按"可能触发"配置 |
| `compare_timeout` | **明确失败** | 部分 diffs 不静默评审（ADR-0011 决策 11） |
| 应用级限流 | **429 无头也可能发生** | 退避 ≤3 次后如实失败；不假装无限制 |
| Gerrit / 语义索引 | **未做** | 本任务明确排除；后续里程碑 |

## 10. 接入示例（Java 装配 + CLI/server）

适配器可独立装配（组合根或嵌入程序）：

```java
GitLabComponents components =
    GitLabAssembly.createComponents("https://gitlab.com/api/v4", token, botUsername);
ReviewLedger ledger =
    StoreAssembly.createGitHubLedger(components.commentSource(), repo, ReviewMode.PULL_REQUEST);
// 说明：StoreAssembly 的评论账本实现只消费 CommentSource（平台无关），"GitHub"是历史命名；
// PR 形态用两参重载，commit/issue 形态传 ReviewMode.COMMIT / ISSUE。
```

服务端最小配置：

```bash
CRA_SERVER_PLATFORM=gitlab
CRA_GITLAB_BASE_URL=https://gitlab.example.com/api/v4
CRA_GITLAB_TOKEN=<project access token，api scope>
CRA_GITLAB_BOT_USERNAME=<token 对应的 bot 用户名；/user 可读时可省>
CRA_SERVER_WEBHOOK_SECRET=<GitLab webhook 的 Secret token>
CRA_LLM_API_KEY=... CRA_LLM_BASE_URL=... CRA_LLM_MODEL=...
```

## 11. 运维/接入检查单

- [ ] token scope 按用途最小化：只读 `read_api`，发布需 `api`；token 只从密钥管理注入；
- [ ] `GET /user` 不可读时已配置 bot 用户名（`CRA_GITLAB_BOT_USERNAME`），且与评论实际作者一致（否则账本永远为空）；
- [ ] GitLab CI 的 `workflow:rules` 已排除 draft / bot / merge commit，且 `auto_cancel` 不会掐断已发布一半的 run；
- [ ] `resource_group` 与 `interruptible` 已按仓库配置，避免同 MR 并发重复发布；
- [ ] fork MR 场景已确认 secrets/父项目权限边界；
- [ ] 首次 push 形态评审已显式给 `--base`（本地无 checkout 时 CLI 拒绝猜基线，05§2.4）；
- [ ] `--dry-run` 实跑一次并确认零写（WireMock 契约测试已断言读路径只有 GET）；
- [ ] 服务端 webhook 的 Secret token 与 `CRA_SERVER_WEBHOOK_SECRET` 一致，且只暴露 `/webhooks/gitlab`；
- [ ] 真实写操作（review/commit comment/issue）在 owner token 到位后按 §12 清单逐项核验并回填证据。

## 12. 验收记录

### 12.1 已执行（2026-10-27，本机）

- **全量自检**：`CRA_TEST_PG_URL=… mvn -B -ntp verify` → BUILD SUCCESS（约 2 分钟，含 Spotless / ArchUnit R1–R8 / Enforcer / JaCoCo 解析器门禁）。
- **新增测试**：
  - CLI 平台选择与 GitLab 配置 16 例（默认 github 兼容、CI 变量回退、嵌套 group、token 文件、拒绝命令行 token、基址归一、local 平台中立、sweep 解析）；
  - CLI 平台接线 1 例（`--platform gitlab` → AppConfig 装配 `GitLabHttpAdapter` + GitLab `CommentSource` → 发布 → 同 commit 重放命中 DONE、零重复；WireMock）；
  - server 平台配置/解析/验签/HTTP 语义 26 例；
  - GitLab webhook HTTP + PG 集成 5 例（202 → delivery COMPLETED、同 UUID duplicate、update 映射、metadata_update 忽略、401/400、非 MR 忽略、`/webhooks/github` 404、`/healthz` 平台字段）；既有 GitHub 集成 6 例同轮通过。
- **真实 GitLab 读路径（公共项目，无 token）**：`gitlab-org/cli`
  - `fetchDiff`：`a6eb09b6…` → `ae7fcb50…`（`build(deps): update github.com/andybalholm/brotli to v1.2.7`）→ 2 个变更文件、1 个 commit、20 patch 行；
  - `listFiles("**/*.md")`：331 个文件；
  - `readFile("README.md")`：13,772 字符（非二进制）。
  - 结论：compare / tree / raw file 的端点与真实响应形状一致；**未进行任何写操作**。

### 12.2 待执行（需要 owner 提供 GitLab token + scratch 项目）

1. 在 scratch 项目创建 project access token（`api` scope，Maintainer），配置 webhook → `https://<host>/webhooks/gitlab`，Secret token = `CRA_SERVER_WEBHOOK_SECRET`，仅订阅 Merge request events；
2. MR open → 服务端 202 → MR inline discussion + Summary note；PG `webhook_delivery=COMPLETED`；记录 MR / note 链接；
3. 同 MR 新 commit（update + `oldrev`）→ 202；同 head 重跑/同 event UUID 重投 → duplicate 200，零重复评论；
4. 同 MR 无新提交的 update → 200 `metadata_update`；merge/close → 200 `merged`/`closed`；draft → 200 `draft`；
5. 直推分支（`--push`）→ 一条 commit comment（含 `### 发现详情` + DONE）；同 commit 重跑 → SKIPPED、评论数不变；
6. `cra sweep --platform gitlab` → 逐 PR issue + 汇总 issue；第二轮全部 SKIPPED、零新 issue；
7. 错 token → 401；非法 payload / 缺 `diff_refs` → 400；非 MR 事件 → 200 ignored；
8. 从 GitLab API 读回 note/description 原文，确认 `<!-- cra:... -->` 未被渲染层改写（账本前提）；
9. 核验 award emoji 名称（`thumbsup`/`thumbsdown`）与 commit comment 分页行为；
10. 把 MR/评论/issue 链接与截图回填本节；token 不入日志、不入仓库（05§6）。
