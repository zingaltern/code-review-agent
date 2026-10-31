# cra-git / cra-git-github / cra-git-gitlab 代码审计（2026-10-31）

> 范围：`cra-git`、`cra-git-github`、`cra-git-gitlab` 的 `src/main` 与 `src/test`（基线 `main @ f67a80e`）。
> 方法：逐文件静态审阅（docs/09 代码规范、Sonar/Alibaba 视角、docs/10 架构规范）→ 子代理逐条二次核查（确认/驳回/需上下文）→ 仅修复确认项 → 子代理对修复 diff 做回归复核 → `mvn verify` 全绿。
> 分支：`fix/git-audit`（独立 worktree `cra-wt-git-audit`，未触碰其他并发 worktree 与 `main`）。

## 1. 模块功能画像

| 模块 | 功能 |
|---|---|
| `cra-git` | Git 平台 SPI（`GitPlatformAdapter`）与本地仓库能力：jgit blame / 首父链 / 近期变更史 / 被引 commit patch（`LocalRepoContext`），本地 pre-PR 适配器（`LocalGitAdapter`：resolve/HEAD/remote 身份、本地 diff/读文件/列树、no-op 发布），确定性语义索引（`SymbolIndex`：jgit head 树 + Java 词法/结构抽取，ADR-0014）。 |
| `cra-git-github` | GitHub REST v3 实现：compare diff、contents raw、tree、PR review inline 发布与 422 逐条降级、Summary issue comment 发布/编辑、commit comment 与 issue 载体、bot 评论账本（inline+issue+commit+issue 载体）、PR/issue 列表、处置信号（reply 标签 + reaction）、bot 身份回退（`github-actions[bot]`）。 |
| `cra-git-gitlab` | GitLab REST v4 实现（ADR-0011）：compare diff（拒绝 compare_timeout 部分结果）、repository files/tree、MR discussions inline 锚定（base/head/start 三元组，历史 version 回退）、MR note Summary、commit comment（合成稳定 id）、issue 载体、MR/commit/issue 账本、处置信号（discussion reply + award emoji）。 |

## 2. 已确认并修复（按主题）

| # | 问题 | 影响 | 修复 |
|---|---|---|---|
| 1 | `JgitLocalRepoContext` 仅接受 `.git` 目录 | git worktree / submodule（`.git` 为文件）下 blame、commit patch、变更史、首父链全部降级 | 目录或文件均视为仓库（与 `LocalJgitAdapter`/`JgitSymbolIndex` 对齐），补 worktree 用例 |
| 2 | `JgitSymbolIndex.readBlob` 宽松 UTF-8 解码 | 非法字节以 U+FFFD 伪文本进入索引，违背 ADR-0016 严格解码 | 改用 `Texts.strictUtf8`，失败按二进制跳过并记 notes |
| 3 | GitHub `encodePath` 用表单编码 | 含空格路径被编成 `+`，`readFile` 404（GitLab 侧已用 `%20`） | 路径段编码统一 `%20`，补空格/加号用例 |
| 4 | GitHub `getPaged`、`listBotIssues`、`listPullRequests` 与 GitLab `listBotIssues`、`listPullRequests` 触顶静默返回部分结果 | 处置信号 / 夜间 PR/issue 列表可能不完整，违反“截断不静默” | 统一第 101 页探测：恰好 10k 且探页为空 = 正常；探页有数据 = 显式失败 |
| 5 | GitHub 逐条 422 降级捕获所有 `CraIntegrationException` | 逐条发布遇 401/403/5xx 被误记为 `SUMMARY_FALLBACK`，run 不失败 | 仅 422 降级，其余上抛（与 GitLab 既有语义一致） |
| 6 | 状态码判定依赖异常 message 子串 | 文案变更即破坏 422 降级 / `/user` 403 回退 | 新增内部 `GitHubApiException` / `GitLabApiException`（status + path 结构化），message 格式不变 |
| 7 | `Retry-After` 无上限（最长约 31 年） | CI/服务线程可被外部响应头长时间挂起 | 单次退避封顶 60s（两平台 transport） |
| 8 | GitHub `created_at` 直接 `Instant.parse` | 畸形时间抛 `DateTimeParseException`（未包装） | 改用容错 `DispositionSupport.instantOrEpoch` |
| 9 | GitHub `listPullRequests(repo, null)` NPE；缺 `updated_at` 条目处理不一致 | 无窗口调用崩溃 / 窗口截断 | `since == null` 支持；缺 `updated_at` 仅跳过该条、不截断后续页；`updatedAt` 回退 `Instant.EPOCH` |
| 10 | GitHub `reviewId` 缺失/`null` 时写入空串 | `Optional.of("")` 语义错误 | 空或 null → `Optional.empty()` |
| 11 | `cachedLogin` / `cachedUsername` 非 volatile；GitHub 空 login 被缓存 | server 并发重复 `/user`；空 login 使账本过滤全灭 | `volatile` + 空 login 显式失败 |
| 12 | GitLab `fetchIssueBotComments` 每个 issue 解析两次 | 双倍解析开销 | `GitLabLedgerSupport.parseFiltered` 单次解析完成过滤与映射 |
| 13 | `JavaSourceScanner` 把 module-info 上下文关键字当保留字 | 方法名 `open/to/with/uses` 等不被抽取 | 移除模块级关键字；补方法名用例 |
| 14 | enum 常量带实参/类体不抽取（类 javadoc 声称支持） | 符号索引漏常量 | `A(1)` / `A { … }` 均按字段抽取 |
| 15 | `LocalJgitAdapter` 远端 URL 未剥离尾斜杠/query | `…/r.git/?ref=main` 推导身份失败 | 先剥 query/fragment/尾斜杠再解析；补用例 |
| 16 | `LocalJgitAdapter.listFiles` 递归模式下 `isSubtree()` 死分支 | 无效判断 | 删除 |
| 17 | GitHub/GitLab adapter 688/745 行（docs/10 §8 建议线 500） | 上帝类，且 docs/10 的“已降至 500 行以下”与基线不符 | 账本/处置/列表读侧拆为 `GitHubCommentReader`（410 行）/`GitLabCommentReader`（420 行），adapter 350/391 行，接口语义不变 |
| 18 | package-info / SPI javadoc 过期（称账本标记解析、锚定校验仍在 internal；`LocalRepoContext` 写“M0 仅有接口”） | 过期注释误导（09§8） | 同步为当前职责与 worktree 语义 |
| 19 | 测试卫生：空 `@AfterEach`、测试内无意义 `{}` 块 | 噪声 | 删除/清理 |

新增回归用例：worktree `.git` 文件、非法 UTF-8 跳过、远端 URL 变体、enum 常量两种形态、上下文关键字方法名、空格路径、处置分页溢出、畸形时间、`since=null`、逐条 401 上抛、空 review id、退避封顶、GitHub/GitLab 列表恰好 10k + 探页。

## 3. 二次核查驳回项（不计为缺陷）

- **全限定名与 import 混用**：docs/09 未禁止，Spotless 也不改写；仅在本次触达文件内顺手统一，不作规范违规。
- **GitHub transport 仅认 200/201**：本模块端点成功码均为 200/201，无 204/202；与 GitLab 的 2xx 区间只是风格差异。
- **`textOrNull` 多次 `asText()`**：最多 2 次且为 O(1)，无可测收益。
- **GitLab commit comment 合成 id**：ADR-0011 决策 7 明确设计，非缺陷。
- **`catch (IOException | RuntimeException)` best-effort**：接口契约（不可得不炸 run）与消费方声明均要求，保留；但 `RuntimeException` 偏宽，登记为后续可改为“带失败态结果对象”。

## 4. 未修复项（需 owner/独立任务决策）

| 项 | 原因 / 建议 |
|---|---|
| `SymbolIndexOptions` 的 `*` 语义（含 `/`）与 `PathGlobs` 不一致，`JgitSymbolIndex` 自持第四份 glob | 属 cra-common 公开契约语义，按 10§6 改语义需 ADR；当前生产调用恒传空模式，无现网影响。建议 owner 决定：统一到 `PathGlobs`（改契约）或保留并在 ADR-0016 中登记例外。 |
| `JavaSourceScanner` 仍约 700 行 | docs/10 §8 为【建议】线；该类是单一抽取算法（词法/头部/成员三段），暂不拆分；如需强制可另起 refactor 拆 lexer/member parser。 |
| GitLab `listBotIssues` / `listPullRequests` 分页骨架仍同构（已收敛到 reader） | 纯重复代码，非缺陷；可与分页器重构一并处理。 |
| GitLab `fetchDispositionSignals` 两遍遍历 discussions | 可合并单遍，零语义变化；为控制本次 diff 范围未改。 |
| GitLab `fetchDiff` 对缺 `new_path`/`old_path` 的 diff 条目仅 WARN 跳过 | 与 ADR-0011 决策 11“拒绝部分 diff”理念冲突；改为失败会改变线上行为，需 owner 决定。 |
| GitHub 5xx 是否重试 | docs/05 §2.3 只写 429/403 退避，docs/08 另有 “429/5xx ≤3” 口径；先统一文档再改代码。 |
| 文档事实纠正 | `docs/10 §8` 目标注记与基线（688 行）不符（本次拆分后为 350，可更新）；`docs/deployment-gitlab.md` 仍称解析器在本模块；`cra-git-gitlab/pom.xml` description 同上；`docs/05 §2.2` “仍失败的”与“非 422 上抛”有歧义。因文档冻结与并发文档线，未在本次代码分支修改。 |

## 5. 验证证据

- `mvn -B -ntp -pl cra-git,cra-git-github,cra-git-gitlab -am test`：cra-git 32、cra-git-github 33、cra-git-gitlab 29，全部 0 失败。
- `mvn -B -ntp verify`（全仓）：BUILD SUCCESS，3:48；cra-archtest 1443 用例（R1–R8 + 源码扫描）全绿；Spotless 全模块通过。
- 修复 diff 已由独立子代理只读复核：结论“可合入”，其 6 条建议中 4 条已在 `fix(git): 复核跟进…` 提交处理（缺 `updated_at` 不截断、空 review id、enum 类体常量、补 10k 边界用例），其余 2 条为文档措辞/测试补充，登记于 §4。
