# ADR-0009 GitLab 平台适配（M4e 第一半：新实现模块，不改 SPI 签名）

- 状态：accepted（2026-10-27，M4e 第一半实现随本 ADR 落地；Gerrit 与语义索引不在本 ADR 范围）
- 背景：11§2 已把 Git 平台适配登记为稳定扩展点，05§2.1 的 `GitPlatformAdapter` 与 `CommentSource` 只约束 common POJO。GitLab 与 GitHub 在 diff 端点、评论载体、锚定参数、分页与限流上均不同；照抄 GitHub 实现会产出"看起来能跑、锚定全错"的适配器。本 ADR 先钉死平台事实与差异，再落 `cra-git-gitlab`（11§6 checklist 第 3 步）。
- 决策：
  1. **新模块 `cra-git-gitlab`（包 `org.cra.git.gitlab`），不依赖 `cra-git-github`**。实现 `GitPlatformAdapter` 四方法 + `listFiles` / `fetchDispositionSignals`，以及 `CommentSource` 的 PR / commit / issue 三类账本方法（11§4 第 4 条：实现模块互不依赖）。组合根可按部署选择装配；本半未改 CLI/server 的平台选择。
  2. **账本标记格式原样移植 05§1.3**：`v=2.1`、finding 级与 run 级两类标记、末位规则、转义规则、DONE|DEGRADED 语义全部不变；解析器以本模块自有包复制一份（不跨实现模块 import），并保留 08§7 的 100% 分支覆盖门禁。键控沿用 05§1.3 v1.4：PR 形态 `(repo, mr iid, commit)`，commit/issue 形态 `(repo, commit)`。
  3. **认证与项目标识**：`PRIVATE-TOKEN` 头；API 根默认 `https://gitlab.com/api/v4`；项目路径 URL 编码为 `group%2Fproject`；bot 身份取 `GET /user` 的 `username`，CI job token 不可读 `/user` 时回退到显式 `botUsername`（缺省则抛出，绝不把人类评论当账本）。
  4. **端点映射与差异**（均为 GitLab REST v4 文档核查结果）：

     | 用途 | GitHub（既有） | GitLab（本实现） |
     |---|---|---|
     | 变更集 | `GET /repos/{o}/{r}/compare/{base}...{head}` | `GET /projects/:id/repository/compare?from={base}&to={head}`；`compare_timeout=true` 视为失败（部分 diff 不静默评审） |
     | 文件内容 | `GET /contents/{path}?ref=`（raw） | `GET /repository/files/{url-encoded-full-path}/raw?ref=` |
     | 文件树 | `GET /git/trees/{sha}?recursive=1` | `GET /repository/tree?ref={sha}&recursive=true`（offset 分页） |
     | inline 发布 | `POST /pulls/{n}/reviews`（整包，422 逐条降级） | `POST /merge_requests/{iid}/discussions`（逐条建线程，无整包 review 对象） |
     | Summary 载体 | `POST /issues/{n}/comments` | `POST /merge_requests/{iid}/notes` |
     | Summary 编辑 | `PATCH /issues/comments/{id}` | `PUT /merge_requests/{iid}/notes/{id}` |
     | 账本拉取 | `/pulls/{n}/comments` + `/issues/{n}/comments` | `GET /merge_requests/{iid}/discussions`（`position` 非空 = inline，空 = issue 级 note） |
     | push 载体 | `POST /commits/{sha}/comments` | `POST /repository/commits/{sha}/comments`（字段 `note`） |
     | commit 账本 | `GET /commits/{sha}/comments` | `GET /repository/commits/{sha}/comments` |
     | issue 载体 | `POST /issues` | `POST /issues`（`description` + 逗号分隔 `labels`） |
     | issue 账本/扫描 | `/issues?state=all&sort=updated`（需排除 PR 条目） | `GET /issues?state=all&order_by=updated_at&sort=desc&author_username=`（issues 端点本身不含 MR） |
     | MR/PR 列表 | `/pulls?state=all&sort=updated` | `GET /merge_requests?state=all&order_by=updated_at&sort=desc` |
     | reaction | `/issues/comments/{id}/reactions` | `GET /merge_requests/{iid}/notes/{note_id}/award_emoji`（名称 `thumbsup`/`thumbsdown`） |
     | bot 身份 | `/user` → `login` | `/user` → `username` |

  5. **锚定参数差异（关键）**：GitLab 新建 diff 线程要求 `position` 携带 `base_sha`、`head_sha`、`start_sha` 三个 SHA，且 `position_type=text`、`old_path`/`new_path`，新增行用 `new_line`、删除行用 `old_line`。实现优先用 MR 的 `diff_refs`；被评审 head 不是当前 MR head 时，从 `GET /merge_requests/:iid/versions` 中找 `head_commit_sha == cmd.commitId()` 的版本取三元组；找不到再退回当前 `diff_refs` 并记 WARN。GitLab 没有"整包 review"，故逐条建线程；锚定非法（400/422）逐条降级为 `SUMMARY_FALLBACK`，其余状态上抛（与 GitHub 的非 422 语义对齐）。
  6. **分页与限流**：offset `page`/`per_page`（默认 20，最大 100），优先读 `X-Next-Page`；429 与带 `Retry-After` 的 403 退避 ≤3 次；`RateLimit-Remaining < 20` 仅告警不硬睡。GitLab.com 的 `RateLimit-*` 头全响应返回，应用级限流可能不在头里，因此 429 仍可能突然出现（已按退避处理）。
  7. **commit comment 无 id（平台事实）**：GitLab 的 commit comment 响应没有评论 id（只有 `author.id`）。为满足 `CommentLedger` 的正整数 id 与账本键控，适配器生成**稳定的本地合成 id**：`sha256(sha + "\n" + note)` 取前 15 位十六进制转正整数；发布载体 id 用同一函数由 `(sha, body)` 计算，因此 `postCommitReview` 能返回可供核心判定"完整发布"的 id，重放拉取也能得到同一 id。合成 id 只用于本地映射（不可用于编辑/删除评论），不是 GitLab 平台标识。
  8. **issue 载体差异**：GitLab issue 的 `labels` 是逗号分隔字符串；文档明确缺失标签会**自动创建**（05§2.5 的"标签不存在时平台忽略"是 GitHub 语义，此处按 GitLab 实际行为声明）。issue 列表端点不返回 MR，无需 GitHub 的 `pull_request` 条目排除；`iid` 作为载体/账本 id。
  9. **触发边界（GitLab CI，显式声明而非假装支持）**：
     - MR pipeline 运行**源分支内容**（官方文档原文），`CI_MERGE_REQUEST_DIFF_BASE_SHA` = MR diff 的 base SHA，作为 CLI 的 `--base`；`CI_COMMIT_SHA` 为 head。
     - 冲突 MR：GitLab 文档未声明冲突会阻断 MR pipeline（MR pipeline 只依赖源分支内容）；与 GitHub"冲突不触发"不同，但此条为文档推导，**待真实 GitLab 实例确认**，写入部署文档的限制表。
     - draft MR：官方文档说明 draft MR 照常运行同样的 pipeline；本项目在 CI 层按标题前缀 `Draft:`/`[Draft]`/`(Draft)` 过滤（与 05§4 的 draft 过滤同目的），移除前缀后的 pipeline 是否自动重跑取决于项目规则，部署文档要求以 `workflow:rules` 显式覆盖。
     - fork MR：默认在 fork（源）项目创建并运行，使用 fork 的 CI 变量；父项目受保护资源不可用——需要评审父项目时按官方"父项目触发"模式配置，本组件不假设 secrets 可见。
     - 并发：`resource_group` 串行同一 MR/ref，`interruptible: false` + `auto_cancel.on_new_commit: conservative`（默认）避免把已发布一半的 run 掐断；被掐断的 run 无 DONE，重跑整条重发（与 05§2.4 一致）。
  10. **处置信号可得性**：reply 标签从 discussion 线程映射（bot 首条 note = 被回复父评论，后续人类 note = reply）；reaction 从 bot Summary note 的 `award_emoji` 采 `thumbsup`/`thumbsdown`；GitLab 的 discussion `resolved` 在 REST **可得**，但 `DispositionSignal` 模型只有 reply_tag/reaction 两类，本轮**不采**（不新增 common 类型；如将来采，按 11§4 加 default/新 kind 并写 ADR）。`resolve` 与合并事件回填不在本适配器。
  11. **失败与降级**：Summary note 发布失败 → `summaryCommentId` 为空（无 DONE，重跑走指纹级补发）；inline 发布非锚定错误 → 上抛由 run 级失败处置；`compare_timeout` → 上抛（不允许部分 diff 伪装完整评审）；文件超限/二进制 → `patch=null` / `binary=true`（沿用 common 语义）。
- 理由：GitLab 的"无 review 聚合对象 + position 三元组 + commit comment 无 id"是三个不可回避的平台差异。把差异收在实现模块内、SPI 零改动，满足 11§4 第 1/3/4 条；账本格式复制而非抽象到 common，避免为两个实现新增跨模块公开类型（10§6 的跨模块接口变更成本）——格式已被 05§1.3 冻结，复制风险由两模块各自的 100% 分支覆盖与同一组契约测试约束。
- 后果：
  - 10§2 矩阵新增 `cra-git-gitlab` 行/列（仅 → common/git），R4 增加"实现模块互不依赖"规则；ArchUnit 类视图与 10§3 包结构同步；
  - 真实 GitLab 写操作（发 review / commit comment / issue）尚无 owner token，未在本机验证；fixture 端到端以 WireMock 录制回放覆盖"发布 → 账本命中 → 同 commit 重放零重复"；
  - 账本标记依赖 note/description 原文保留 `<!-- cra:... -->`；GitLab 的 Markdown 渲染/净化是否影响 API 原文需真实实例确认（fixture 假定保留），列入部署文档待验证项；
  - 合成 commit comment id 与 GitLab 平台无对应关系，编辑/删除 commit comment 的后续能力需要另找端点或改载体；
  - CLI/server 的平台选择与 GitLab CI job 示例留到 M4e 第二半（需 owner token 与真实实例联调）。
- 替代方案：
  - 复用 `cra-git-github` 的 HTTP 层（违反 11§4 第 4 条，且两平台错误语义不同——400 vs 422、position 三元组、无 review 对象）；
  - 把 `LedgerMarkerParser` 提到 `cra-common` 共享（更 DRY，但新增跨模块公开类型，按 10§6 需独立 ADR；格式冻结下复制成本可控）；
  - 用 GitLab Draft Notes 批量发布模拟 GitHub review（多一步 publish，且失败仍逐条，收益不抵复杂度）；
  - 把 GitLab discussion `resolved` 塞进 `DispositionSignal.detail`（污染既有信号语义，且 06§1.1 口径未定义）。
