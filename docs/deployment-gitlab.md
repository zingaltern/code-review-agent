# GitLab 适配与部署边界（M4e 第一半）

> 依据：ADR-0011、11§6（新 Git 平台接入 checklist）、05§2.1–§2.5、02§5。本文是 GitLab 平台事实的**主载体**；11§2 与 README 只做一句话指回。
> 适用范围：`cra-git-gitlab` 适配器模块（`GitPlatformAdapter` + `CommentSource`）。**M4e 第一半不改 CLI/server 的平台选择**，真实 GitLab 写操作尚无 owner token，未做线上验证（见 §7）。

---

## 1. 交付范围与当前状态

- 新模块 `cra-git-gitlab`（包 `org.cra.git.gitlab`），**不依赖 `cra-git-github`**；实现 `fetchDiff` / `readFile` / `postReview` / `fetchBotComments`，并按需实现 `listFiles` / `fetchDispositionSignals`、M4c/M4d 载体 `postCommitReview` / `postIssueReview`、`CommentSource` 的 PR/commit/issue 账本方法。
- 账本标记格式原样移植 05§1.3（`v=2.1`、finding/run 两类标记、末位规则、DONE|DEGRADED），解析器在本模块独立持有并保留 100% 分支覆盖门禁；**不自造格式**。
- 已验证：`mvn -B -ntp verify` 全绿；GitLab adapter 端点契约测试（WireMock 录制回放，22 例）；fixture 端到端（发布 → 同 commit 账本命中 → 零重复，1 例）；dry-run 读路径零写断言。
- 未完成（第二半）：CLI/server 的平台选择、GitLab CI job 实跑、真实 token 的写操作验证、Gerrit、语义索引/跨文件上下文。

## 2. 认证与基础

| 项 | GitLab 事实 | 本项目用法 |
|---|---|---|
| API 根 | `https://gitlab.com/api/v4`（自建实例同构） | `GitLabAssembly.createComponents(apiBase, token)`；空值取默认 |
| 认证 | `PRIVATE-TOKEN` 头（personal / project / group access token） | 只从环境变量/密钥管理读取，不写仓库（05§6） |
| token 权限 | `api` = 读写；`read_api` = 只读 | 读路径最小 `read_api`；发布 review/issue 需 `api` |
| CI job token | `CI_JOB_TOKEN` 可访问的 API 是子集，且受项目 allowlist 限制 | **不假设** job token 能发 MR note；CI 内用 project access token / bot token |
| 项目标识 | `id` 可为数字 ID 或 URL 编码的完整路径（`group%2Fproject`） | 适配器统一用 `owner/repo` → `group%2Fproject` |
| bot 身份 | `GET /user` 返回 `username`；project access token 对应 bot 用户 | `/user` 不可读（401/403）时必须显式给 `botUsername`，否则拒绝把任何评论当账本 |
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
| reaction | `/issues/comments/{id}/reactions` | `GET /projects/:id/merge_requests/{iid}/notes/{note_id}/award_emoji` | 名称 `thumbsup`/`thumbsdown`（文档未枚举，见 §7） |
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

## 7. 不可得 / 未采 / 待真实验证

| 项 | 状态 | 原因与处置 |
|---|---|---|
| 真实 GitLab 写操作 | **未验证（pending）** | 需要 owner token 与真实实例；本半只做 WireMock 录制回放 + fixture 端到端 |
| CLI/server 平台选择 | **未接线（第二半）** | 当前 `cra-runner-cli` / `cra-server` 仍固定装配 GitHub；本文只给参数映射，不宣称可直接跑 |
| commit comment id | **平台不可得** | API 不返回 id；合成 id 只用于本地账本映射 |
| commit comment 列表分页 | **文档未声明** | 使用通用 offset `page/per_page`；真实平台需复核 |
| 账本标记的 HTML 注释保留 | **待真实验证** | 账本依赖 note/description 原文中的 `<!-- cra:... -->`；GitLab API 原文通常保留，但渲染/净化行为需真实实例确认（fixture 假定保留） |
| award emoji 名称 | **文档未枚举** | 按 GitLab emoji 全集的 `thumbsup`/`thumbsdown` 实现；真实平台待核验 |
| discussion `resolved` | **可得但不采** | GitLab REST 有 `resolvable/resolved`；`DispositionSignal` 模型无 resolve 类别，不新增 common 类型（ADR-0011 决策 10） |
| 合并事件回填 | **不在适配器** | `CommentSource` 无该数据面；与 GitHub 实现同口径 |
| 冲突 MR 是否触发 | **文档未声明，待验证** | MR pipeline 只依赖源分支内容 → 推断不阻断；真实实例确认前按"可能触发"配置 |
| `compare_timeout` | **明确失败** | 部分 diffs 不静默评审（ADR-0011 决策 11） |
| 应用级限流 | **429 无头也可能发生** | 退避 ≤3 次后如实失败；不假装无限制 |
| Gerrit / 语义索引 | **未做** | 本任务明确排除；M4e 第二半/后续里程碑 |

## 8. 接入示例（Java 装配 + 第二半参数映射）

适配器可独立装配（组合根或嵌入程序）：

```java
GitLabComponents components =
    GitLabAssembly.createComponents("https://gitlab.com/api/v4", token, botUsername);
ReviewLedger ledger =
    StoreAssembly.createGitHubLedger(components.commentSource(), repo, ReviewMode.PULL_REQUEST);
// 说明：StoreAssembly 的评论账本实现只消费 CommentSource（平台无关），"GitHub"是历史命名；
// PR 形态用两参重载，commit/issue 形态传 ReviewMode.COMMIT / ISSUE。
```

第二半接线时的 CI 参数映射（**当前 CLI 尚未支持 `--platform gitlab`，以下仅示意**）：

| GitLab CI 变量 | CLI 参数 | 说明 |
|---|---|---|
| `CI_PROJECT_PATH` | `--repo` | `group/project` |
| `CI_MERGE_REQUEST_IID` | `--pr` | MR 内部编号 |
| `CI_MERGE_REQUEST_DIFF_BASE_SHA` | `--base` | MR diff 基线 |
| `CI_COMMIT_SHA` | `--head` | 被评审 head |
| project access token | `--token` | 需 `api` scope（读+写） |
| bot 用户名 | `CRA_GITLAB_BOT_USERNAME`（待接线） | CI job token 无法读 `/user` 时必填 |

## 9. 运维/接入检查单

- [ ] token scope 按用途最小化：只读 `read_api`，发布需 `api`；token 只从密钥管理注入；
- [ ] `GET /user` 不可读时已配置 bot 用户名，且与评论实际作者一致（否则账本永远为空）；
- [ ] GitLab CI 的 `workflow:rules` 已排除 draft / bot / merge commit，且 `auto_cancel` 不会掐断已发布一半的 run；
- [ ] `resource_group` 与 `interruptible` 已按仓库配置，避免同 MR 并发重复发布；
- [ ] fork MR 场景已确认 secrets/父项目权限边界；
- [ ] 首次 push 形态评审已显式给 `--base`（本地无 checkout 时 CLI 拒绝猜基线，05§2.4）；
- [ ] `--dry-run` 实跑一次并确认零写（WireMock 契约测试已断言读路径只有 GET）；
- [ ] 真实写操作（review/commit comment/issue）在 owner token 到位后按 ADR-0011 待验证清单逐项核验。
