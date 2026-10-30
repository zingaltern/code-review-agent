# 历史 PR 评审发现清查与处置（2026-10-30）

> 背景：2026-10-25 至 10-29 期间合并的 PR 由项目自身流水线（`review` job）评审，部分 Summary
> 记录了已确认发现（inline 载体未启用，发现详情在 Summary 内）。本清查逐个拉取 bot 评审产物、
> 对照当前 main（`e9329b7`）复核，给出逐条判定与处置。**判定口径**：FIXED = 后续提交已修复；
> STILL_PRESENT = 当前代码/文档仍有该问题；OBSOLETE = 重构或口径变更使发现不再适用；
> FALSE_POSITIVE = 评审结论本身不成立（含忽视既有兜底/设计）。

## 一、数据来源与方法

- 已合并 PR 共 24 个（#1–#23、#35、#39）；逐个拉取 issue comments 的 `<!-- cra: -->` 标记与
  pull review comments，提取 `status/findings` 与发现详情。
- **有已确认发现的 PR 共 6 个**：#1（6 条）、#2（3 条）、#6（4 条）、#8（6 条）、
  #13（8+2 条）、#14（10 条），去重后 **27 条独立发现**（#8/#14 的 attestation 类重复项合并；
  PR #1 的 1 与 6 同源）。其余 PR 最新 run 均 findings=0。
- 复核方式：按"内容"定位当前 main 代码（行号已漂移）、读实现与测试、`git log -S` 追溯后续提交。

## 二、逐条判定与处置

### PR #1（M3 评测门禁）

| # | 发现 | 判定 | 处置 |
|---|---|---|---|
| 1 | `--sha256-out` 仅在 `--json` 非空时生成、无提示 | STILL_PRESENT | **修复**：解析期校验，单独给 `--sha256-out` 即报错（评审原文"父目录未校验"半句不成立——`ReportHasher.writeSha256File` 会 `createDirectories`） |
| 2 | RunStatsReader 多 trace 取字典序最后而非最新，且读失败被静默掩盖 | STILL_PRESENT | **修复**：目录名按时间戳零填充，字典序即时间序——保留"取最新"并修正失败语义：最新 trace 存在但解析失败 → 结果不可用，不得回退旧值 |
| 3 | `lastRunEnd` 字段缺失静默记 0 仍 `available=true` 进均值分母 | STILL_PRESENT | **修复**：`run_end` 存在但 `tokens_in/tokens_out/latency_ms` 缺失 → `available=false` |
| 4 | CaseObservation 八参兼容构造器静默丢 RunStats | STILL_PRESENT（收窄） | **修复**：调用方迁移到九参构造器后删除八参兼容构造器 |
| 5 | EvalCliArgs 拼写错误参数被静默吞值 | STILL_PRESENT | **修复**：各子命令 parser 拒绝未知 `--flag` 与非预期位置参数（含测试） |
| 6 | `sha256Out` 无提示（与 #1 同源） | STILL_PRESENT | 与 #1 同一修复 |

### PR #2（god class 治理）

| # | 发现 | 判定 | 处置 |
|---|---|---|---|
| 7 | `ReviewRoutingPolicy.Availability` 重命名后无回归测试 | STILL_PRESENT（true 路径有行为断言，false 分支零覆盖） | **修复**：新增 `ReviewRoutingPolicyCrossCheckTest`（4 例）锁定无数据/sniff-only 模式不匹配/quick/未装配四条跳过原因 |
| 8 | L2 门禁用报告内 commit 作 `--expect-commit`，可放行陈旧报告 | FIXED | `2192f65`（PR #2 自身）已加祖先绑定 + "报告 commit 之后仅 `eval/l2/` 变更" 校验（ci.yml）；伪造层面由 ADR-0010 签名路径兜底，`ReportHasher` 已声明荣誉制局限 |
| 9 | docs/02 顶部版本行并列 | OBSOLETE | 多行版本头（新行前置、旧行保留的 changelog 式）已成全仓 docs 惯例 |

### PR #6（M4a 签字建议）

| # | 发现 | 判定 | 处置 |
|---|---|---|---|
| 10 | flagOnly 未识别 `+++`/`---` 之外补丁头、单字符判定误判纯删除 | FALSE_POSITIVE | `@@` 已在跳过清单；`diff --git`/`index` 行不以 `+`/`-` 开头被过滤；"删除行计入变更"是 javadoc 明示语义 |
| 11 | SignoffSections 空规则段 + stale 靠正文子串 | FALSE_POSITIVE（前半不可达）+ 已有覆盖 | 空规则段不可达（所有 advisory 路径至少 1 条规则）；stale 字面量已有守卫测试（`SignoffSectionsTest` 用 `staleNotice()` 输出喂 `hasStaleNotice()` 自检） |
| 12 | FLAG_LINE 可能误命中 `abc enabled: true` / 漏判 | FALSE_POSITIVE | 该形态不命中（`enabled` 前为空格，第二分支锚定 `^`）；漏判方向 fail-safe（升 MEDIUM，只升不降） |
| 13 | docs-only LOW 与 MEDIUM 规则并存（门禁失败时） | 接受 | 最终 tier 取 max=MEDIUM（分级正确）；规则列表保留两条命中记录属可解释性设计。改主代码会触发全量 L2，收益不抵成本——记录此取舍 |

### PR #8 + PR #14（报告签名 v2 / 服务端 attestation，重复项合并）

| # | 发现 | 判定 | 处置 |
|---|---|---|---|
| 14 | `requireKnownCommit=false` 时 repo 缺失不被拒、签发空 repo v2 信封 | FALSE_POSITIVE（危害不成立） | `ReportSignature.sign` 走 `requireRepo` 规范构造器，空 repo 抛 `IllegalArgumentException`——**签不出来**（fail closed），只是以 500 而非 4xx 呈现。**修复体验面**：repo 空/格式非法在服务入口校验 → 409（与空 repo 同语义） |
| 15 | `X-CRA-Repo` 由客户端提供可冒充任意 repo | OBSOLETE | ADR-0009 §信任边界明文：头由调用方提供，服务端只校验与 `review_run` 匹配，不替代每仓独立 token；端点受 Bearer 保护 |
| 16 | 信封/审计的 repo/commit 未与请求头交叉核对 | FALSE_POSITIVE | 同一代码路径同一来源（header repo + 报告 JSON commit）同时喂 sign 与 store.record，构造上不可能互相矛盾；报告 JSON 无 repo 字段 |
| 17 | `X-CRA-Repo` 非法格式行为无测试、null/blank 被下游抹平 | STILL_PRESENT（部分：缺头 409 已有测试） | **修复**：格式非法 → 409（同空 repo 语义）+ 集成测试用例 |
| 18 | parse 8→9 行缺 v1 拒绝与行数负向测试 | FIXED | `ReportSignatureTest` 已有 `parseRejectsWrongScheme`（v1 命名）与 `parseRejectsMissingField`（行数）；v1 不兼容是 ADR-0009 明文决策 |
| 19 | `requireRepo` 格式校验无测试 | STILL_PRESENT | **修复**：补 `owner`/`/name`/`owner/`/`a/b/c`/`owner//name`/空串负向测试 |
| 20 | canonicalText 注释"六个字段行"与实际七个不符 | STILL_PRESENT | **修复**：两处注释按实际行数改正 |
| 21 | PostgresKnownCommitChecker 按 (repo,commit) 查询无跨仓测试 | STILL_PRESENT | **修复**：PG 集成测试补跨仓隔离用例（同 commit 不同 repo → not known） |
| 22 | `server.sql` 的 ADD COLUMN 迁移与默认值无测试 | 接受 | 新建库走 `CREATE TABLE` 路径（列与默认值内建）；legacy 迁移路径测试价值低，记录 |
| 23 | deployment-server.md 披露明文签名密钥值 | STILL_PRESENT | **修复（最高优先）**：删除明文，改为"一次性密钥、验收后弃用、明文不落仓库"声明 |
| 24 | 文档披露 scratch 仓库全名/webhook id/PAT 等基建细节 | 接受 | 私有仓库验收证据链；文档已声明"tunnel 已失效、保留作链接证据"的取舍 |
| 25 | `verify --repo` 无 `--signature` 时被静默接受 | STILL_PRESENT | **修复**：解析期报错（对照 `--signing-key-file` 先例） |

### PR #13（GitLab 适配器）

| # | 发现 | 判定 | 处置 |
|---|---|---|---|
| 26 | postCommitReview 无条件 `Optional.of`、未校验 body | FALSE_POSITIVE | 合成 comment id 是 ADR-0011 决策 7 的文档化设计（发布/回放共用同一函数）；body 空校验在命令构造器；失败路径由 transport 抛异常 |
| 27 | listPullRequests `updated==null` 即 reachedOlder 可能漏 MR | FALSE_POSITIVE | 查询 `order_by=updated_at&sort=desc`，后续页只会更旧；GitHub 侧同口径；该分支纯防御 |
| 28 | `globToRegex` 的 `**/` 不匹配零级目录 | STILL_PRESENT | **修复（双侧同步）**：`**/` 匹配零或多级目录；补零目录与双形态测试 |
| 29 | LedgerMarkerParser "LEADING 标志位错位" | FALSE_POSITIVE | 代码中不存在该机制；字段严格校验使中间标记不可能被误接受为有效标记 |
| 30 | fenceCount 对连续反引号重复计数、忽略缩进围栏 | STILL_PRESENT | **修复（双侧同步）**：≥3 连续反引号计一个定界符；仅行首（≤3 空格）构成围栏；补围栏/行内/既有用例 |
| 31 | excerpt 对 null body NPE | STILL_PRESENT（潜在） | **修复**：null 守卫（对照 replyTag）+ 测试 |
| 32 | `mr.path("sha")` 为空致每次全量回退 | FALSE_POSITIVE | 已有 `fetchMergeRequest` 取 `diff_refs.head_sha` 的兜底，仍缺则 warn+skip |
| 33 | nextPage `Integer.parseInt` 非数字抛 NFE | STILL_PRESENT（潜在） | **修复**：非数字页号按无下一页处理 |
| 34 | URLEncoder 空格/斜杠/字符集问题 | FALSE_POSITIVE | 实现已 `+`→`%20`、显式 UTF-8；`/`→`%2F` 是 GitLab 路径端点的要求 |
| 35 | retryAfterMs 负数/超大值无校验 | FALSE_POSITIVE | `\d{1,9}` 即校验（负数/非数字不匹配即走指数退避）；超大值限 9 位无溢出 |

## 三、统计与修复批次

- **判定分布**（27 条独立项 = 35 个明细行，含同源/重复合并与双注记）：STILL_PRESENT 17、
  FIXED 2、OBSOLETE 2、FALSE_POSITIVE 11、接受 3（行 11 与 14 带双注记：FP 判定 + 已有覆盖/
  体验面修复）。
- **修复批次**：PR #40（`7cc6160`，cra-eval 5 项）、PR #41（`3d73e78`，双平台 4 项 + 一致性补充 2 项）、
  PR #42（`dd82e61`，attest/签名/文档 6 项）、PR #43（`b897301`，cra-core 测试 1 项；本审计文档随收口 PR 入库）。
- 修复均含测试；未修改任何门禁阈值、数据集 expected、提示词。

## 四、过程教训（建议固化为收口动作）

1. **self-review 发现无人跟进**：6 个 PR 共 27 条已确认发现，仅 2 条被后续提交顺带修复——
   根因是发现只落在 PR Summary 评论里，合并即失联，没有进入任何待办。
   **建议**：里程碑收口时增加一步"汇总本期 PR 的 bot 发现并逐条判定"（本文件即首次执行样例）；
   或让 nightly sweep 对含未处置发现的已合并 PR 产出跟踪 issue。
2. **误报率约三分之一**（9/27）：集中在忽视既有兜底（fail-closed、兜底查询、分页不变量）与
   设计决策（ADR 明示），提示评审侧可加强"先查兜底与 ADR 再报"的提示词约束——但本轮
   **不改提示词**（避免与正在进行的 L3 重跑搅动基线）。
3. 高价值发现集中在两类：**测试缺口**（4 条）与**文档卫生**（明文密钥 1 条）——前者说明
   "有实现无测试"是最常见的真实缺口，后者提醒验收记录须先脱敏再入档。
4. **L1 哨兵在本批 6 个 PR 中命中 4 个（40–45），全部同提交重跑即绿**：
   - adv-inj-03 的"报告注入"措辞边界：正确检出载荷后，若 finding 标题引用 `skip-all/suppress`
     字面词，负向不变量（扫描 inline 标题+聚合文本）判违例（PR #41/#43 命中）；且纯测试改动
     （运行时与 main 完全一致）的 PR 出现同一违例，可作对照证明与代码变更无关。
   - adv-hal-01 的幻觉拒绝 k=1 采样方差（PR #44/#45 命中 1/2 < 0.80），为 M4e 起记录在案的
     已知抖动（04§2.4）。
   处置：全部按既有实践重跑（4/4 绿），**未改阈值/扫描面**；标题引述词白名单是否调整、k=1
   哨兵是否升 k≥2 属口径决策，留 owner。

## 五、第二轮：修复 PR 自身的评审跟进（2026-10-30 晚）

四个修复 PR 合并前各自被流水线评审，产生 9 条新发现（#40 4 条、#41 3 条、#42 2 条；
#43–#46 findings=0）。逐条处置如下，修复落点 PR #47。

| # | 来源 | 发现 | 判定 | 处置 |
|---|---|---|---|---|
| 1 | #40 | gate 白名单缺 `--fail-on-red`，可能误拒合法调用 | FALSE_POSITIVE | `--fail-on-red` 是 run/verify 旗标；gate 恒以退出码 5 表示红（GateCommand/05§3 文档化），传它是历史无操作。严格拒绝正是本次新增校验的目的——错误信息会明确列出未知旗标 |
| 2 | #40 | run_end 统计字段非数字类型时 `asInt/asLong` 静默返回 0 | STILL_PRESENT | **修复**：校验改为 `isNumber()`（非数字/缺失/显式 null 一律 available=false）+ 测试 |
| 3 | #40 | 取最新 trace 按父目录名比较，深度 1 的 trace 其 parent 是 runRoot | STILL_PRESENT | **修复**：改按 `runRoot.relativize(trace)` 的第一段目录名排序（深度 1 视为最旧单 run）+ 测试 |
| 4 | #40 | `EvalArgOptions.parse` 会把以 `--` 开头的值吞掉（如 `--tier --l2`），后续旗标丢失且报错误导 | STILL_PRESENT | **修复**：期望值时若下一 token 以 `--` 开头 → 明确报"参数缺值: --tier（下一个 token 看起来是旗标: --l2）" + 测试 |
| 5 | #41 | fenceCount 把"≤3 空格缩进 + ```"算围栏，"与 CommonMark 不符" | FALSE_POSITIVE | CommonMark 恰允许 0–3 空格缩进的围栏；4+ 空格才是缩进代码块（已有负向测试）——行为正确，补 3 空格正向测试锁定 |
| 6 | #41 | fenceCount 未处理 CRLF（`\r\n`），行首缩进检测对 CR 失效 | STILL_PRESENT | **修复**：两份解析器统一先归一 `\r\n`/`\r` → `\n` 再逐行扫描 + CRLF 用例 |
| 7 | #41 | 按 `\n` 拆分遗漏 `\r\n`，4+ 空格缩进的行内反引号串被误判 | 部分（CRLF 见 #6；4+ 空格为 FALSE_POSITIVE） | 4+ 空格不构成围栏在实现与测试中均已正确（#5 说明） |
| 8 | #42 | 明文密钥仍出现在 PR diff/历史中（本 PR 仅删除了文件内值） | 接受（已声明） | 不重写 main 历史；deployment-server.md 补声明"该值历史存在、仅限已废弃 scratch 环境、未用于生产"，配合"一次性密钥"定性 |
| 9 | #42 | 验收证据保留 scratch 仓库名/webhook id 等基建细节 | 接受（已声明） | 与第一轮 #24 同一取舍：私有仓库证据链，文档已声明缓解 |

**修复验证**：cra-eval 118 测试、双平台 LedgerMarkerParserTest 各 44 测试全绿；全量 `mvn verify` 绿。

## 六、第三轮：PR #47 自身的评审跟进（2026-10-30）

PR #47 合并前评审产生 4 条发现，**全部为测试缺口类**（无生产代码缺陷）：

| # | 发现 | 文件类型 | 处置 |
|---|---|---|---|
| 1 | RunStatsReader 非数字校验只测了 `tokens_in`，`tokens_out`/`latency_ms` 路径无测试 | 测试 | **修复**：参数化三字段各一例 |
| 2 | `--` 前缀缺值拒绝只测了首个期望值位置 | 测试 | **修复**：补非首位用例（`--k 1 --tier --l2`） |
| 3 | fenceCount 的 CR-only（`\r`）归一分支无测试 | 生产文件（测试缺口） | **修复**：双平台各补 CR-only 归一用例 |
| 4 | CRLF 回归只断言 marker 存在，未断言围栏内内容被排除 | 测试 | **修复**：双平台各补"CRLF 围栏内标记被排除且计 suspect"用例 |

**策略变更（owner 裁决 #5）**：仓库根新增 `REVIEW.md`，`Skip paths` 排除测试代码——此后测试文件的评审发现不再产生（本轮 4 条即该类），生产代码与文档评审口径不变。解析器实测：`src/test/**`、`**/src/test/**`、`**/*Test.java` 命中；`cra-eval/dataset/**` 与生产文件不受影响。

**统计（三轮累计 56 条发现 = 首轮 27 + 第二轮 9 + 本 4 + 重复合并）**：生产代码实质缺陷 0 条来自最近两轮；测试缺口类 5 条已全部补齐。
