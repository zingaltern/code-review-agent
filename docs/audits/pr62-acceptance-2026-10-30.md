# PR #62 验收记录（2026-10-30）

- **分支**：`fix/source-audit`（worktree `cra-wt-source-accept`），审计基线 head `cc8d146`，对照 `origin/main` `d2c6bee`。
- **范围**：本记录只覆盖评测指标/门禁口径、`cra-eval` 边界与文档；core/llm/cli/git-github 的并行修改由另一 agent 负责，不在本记录内重复裁决。
- **纪律**：只改 `cra-eval`（含测试）与 `docs/`；未 push、未跑 eval、未改任何阈值。

## 一、三份审计结论摘要

### 1. 评测指标与门禁口径审计

- **未获批准、必须回退（2 项）**：
  1. `AggregateEngine` 把幻觉拒绝率分母从 `completed` 改为 `observations`。全部观测完成时数值不变；部分失败时拒绝率单向下降，可使 L1 幻觉门禁由绿翻红；全失败时 `GateEvaluator` 的“无完成观测——不可判定”分支失效，退化为 `0/N = 0.000`。06§1.2 未对该分母做定点修订。
  2. `AggregateEngine` 把 `NegativeInvariantJudge` 的判定面从 fallback 拼接串扩展到 review 引导语 + 完整 Summary 正文。PR 载体下 Summary 的 Important/Nit 列表复述 inline finding 标题，同一目标行为文本会在 `inline_title` 与 `summary_body` 各记一次，`invariant_violations` 由 1 变 2；并放大审计已记录的 adv-inj-03“标题引述 skip-all”假红类（`docs/audits/pr-review-findings-audit-2026-10-30.md:100-107`，标题引述词白名单属 owner 未决口径）。
- **批准保留（2 项）**：
  1. `detected` / `bugOnlyDetected` 加 `obs.completed()` 门：失败 run 的 partial 命中只保留为 `matched` 证据，不计入检出分子；与 06§1.2“失败 run 数值含 0 贡献”一致。
  2. `GateEvaluator` 零发布 `observed` 由 `z.ratio()` 改为 `z.sampleRatio()`：仅修正 JSON 判定值显示，pass 判定在 `0e59391` 起已是样本级，红绿不变。
- **配套缺口**：上述行为均无测试；`EvalReport`/`ReportWriter`/`GatePolicy` 仍有观测级零发布与“检出=≥1 expected 命中”的旧文案。本次已补 3 个口径测试、未跑集合声明测试，并补 docs/06 一行。

### 2. 源码与边界审计（PR #62 首轮/第二轮评审）

- 首轮 8 条发现（`566f3e1`）：`DiffResult.totalPatchLines` 只计增删内容行、`TarReader` PAX/padded 边界、GitLab 分页边界与参数校验、stub 400 响应不吞异常、L1 误红回退等。
- 第二轮（`cc8d146`）：PAX padded 重算、GitLab 分页多探一页避免恰好 10k 条误触顶、补边界测试。
- 本次验收继续补齐 `cra-eval` 侧两项：rename from/to 的 C 风格引号解码（与 `diff --git` 头一致）及非 ASCII rename 用例；`FixtureMetaTest` 恢复被删除的 adversarialSubtype 用例（context/injection/hallucination/refusal/未知/null）。

### 3. 测试与文档审计

- 测试缺口集中在“口径变更无回归测试”：失败 run 检出分子、拒绝率分母、零发布 `observed`、报告未跑集合声明、非 ASCII rename。
- 文档缺口：06§1.2 未写“检出仅计完成观测”；04§2.2 未写 diff 行数口径；05 未声明 compare API 截断与 `BuildVerifyRunner` 环境变量剥离；PR #62 缺验收记录（本文件）。

## 二、本 PR 裁决

### 保留（协调者批准）

| 项 | 内容 | 依据 |
|---|---|---|
| 检出完成门 | `detected = obs.completed() && matched >= 1`；`bugOnlyDetected` 同门 | 06§1.2 失败 run 0 贡献 |
| 零发布 observed | `GateCheck.observed = z.sampleRatio()` | `0e59391` owner 裁决样本级；仅显示对齐 |

### 回退（未获批准的口径变更）

| 项 | 回退为 | 说明 |
|---|---|---|
| 幻觉拒绝率分母 | `proportion(rejected, completed)` | 恢复“无完成观测→不可判定”分支；失败 run 退出分母 |
| 注入扫描面 | `judge(inline texts, "", summaryOf(obs))` | 恢复 main 语义：inline 文本 + fallback 文本；零容忍判定本身不变 |

### Deferred 四项裁定

| # | 事项 | 裁定 | 落点 |
|---|---|---|---|
| 1 | GitHub compare API >300 文件/250 commit 截断未声明 | **接受 defer**，记入已知限制与 backlog | `docs/05-data-and-integration.md` §4 |
| 2 | `DiffRenderer` 对 `patch == null` 直接猜二进制 | **defer**（本 PR 不修；二进制/空 patch 混淆留待独立任务） | 本记录 |
| 3 | `VerifyCommand` 重判未持久化 crosschecker 开关（恒传 false） | **defer**（跨档重判需报告携带开关，独立任务） | 本记录 |
| 4 | `GateEvaluator` 缺集 SKIPPED | **现行为正确**，但报告必须声明未跑集合 | 本 PR 已实现：`blocked_note` 增“未跑集合：…” + 测试 |

## 三、本次修复清单

- `cra-eval/src/main/java/org/cra/eval/internal/AggregateEngine.java`
  - 恢复 `summaryOf(obs)` 与 `judge(..., "", summaryOf(obs))`；
  - 拒绝率分母回退 `completed`；
  - `blocked_note` 增“未跑集合：bad/clean/adversarial（缺集门禁项 SKIPPED——仅判定本次所跑集合）”声明；
  - 保留 `detected`/`bugOnlyDetected` 完成门。
- `cra-eval/src/main/java/org/cra/eval/internal/CaseObservation.java`、`EvalRunner.java`
  - 撤回未获批准的 `reviewBody`/`summaryBody` 捕获与传递（回到 main 形态）。
- `cra-eval/src/main/java/org/cra/eval/internal/DiffSplitter.java`
  - `rename from`/`rename to` 行按 C 风格引号解码（八进制 UTF-8 字节），与 `diff --git` 头一致。
- `cra-eval/src/test/java/...`
  - `AggregateEngineTest`：失败 run 带 expected 命中不计检出；全部完成时 detected/拒绝率与基线一致；未跑集合声明。
  - `GateEvaluatorTest`：零发布 `GateCheck.observed == sampleRatio`（M2 4/36→0/12、红侧 2/12）。
  - `DiffSplitterTest`：非 ASCII rename（`caf\303\251.txt`）解码用例。
  - `FixtureMetaTest`：恢复 adversarialSubtype 四个用例，保留 path traversal 边界用例。
- `docs/`
  - `06-eval-and-feedback.md` §1.2：检出仅计完成观测（失败 run 0 贡献）；拒绝率仍以完成观测为分母。
  - `04-context-and-prompt-design.md` §2.2：diff 行数口径（增删内容行；300/2000 阈值按此口径）。
  - `05-data-and-integration.md` §4：compare API 截断、`BuildVerifyRunner` 敏感环境变量剥离两条已知限制。
  - 本验收记录。

## 四、验证

- `mvn -q -pl cra-eval spotless:check`：通过（本 PR 全部 Java 修改文件）。
- `mvn -B -ntp verify`：**BUILD SUCCESS**（20:32，含 spotless）；`cra-eval` 144 tests、`cra-archtest` 1415 tests 全绿。
- 提交前根级 `mvn -q spotless:check` 复检时，并行 agent 正在修改的 `cra-llm/.../LlmGateway.java` 报格式违规并阻断该命令；该文件不在本 PR 修改范围，本 agent 未触碰。
- 未执行 eval（按协调者纪律）；未 push。

## 四、并行修复（core/llm/cli/git-github，提交 `f3eb1f5`）

三份审计中的高风险/可观测性问题，已由并行修复落地并由协调者独立复核：

| 项 | 审计结论 | 修复 |
|---|---|---|
| H1/M3 共享池 + 360s 超时 | 排队时间计入超时；360s 短于网关最坏重试链 → 服务端并发下假超时 | `TaskHandle` 记录实际开始时间，区分 `agent-queue-timeout` / `agent-timeout`（验证波同构）；`TimeoutBudgets` 按 `max(360s, requestTimeout×(maxRetries+1)+60s)` 推导，无配置回退 720s；验证波取 2× |
| M4 构建日志不实 | “完整输出仍见日志文件”实际写的是被截断的内存缓冲 | 边读边写完整日志文件；内存只保留展示窗口；日志不可用时不再声称完整 |
| M5 trace 静默降级 | 写失败只 WARN、永久关闭写入面；interrupt 可触发 | 恢复首次失败即抛；`ClosedByInterruptException` 清中断重试一次，重试失败才抛 |
| 评审 #5/#15 batches | 成功批双计且无测试 | 每批恰计一次 + 成功/超时/失败测试 |
| RPM 配额（低） | 排队超时仍消耗 RPM 配额与注释不符 | 先取并发许可后取 RPM 槽；排队超时不消耗配额 |
| M8 GitHub 分页 | GitHub 满页即抛、GitLab 探页，语义不一致 | GitHub 对齐 GitLab：探第 101 页，空则恰好 10k 正常返回，非空抛错 |
| #17 PAX 目录/换行 | 目录条目非零 size 可致流错位；path 换行用例缺失 | 目录 `size>0` 明确拒绝；补 LF/CR 拒绝与 PAX path 覆盖目录用例 |

**协调者独立验证**：`mvn -q spotless:check` 通过；`mvn -B -ntp verify` 全绿（archtest 1415）；回退点复核（拒绝率分母 `completed`、`judge(inline, "", summaryOf)`、`detected` 完成门保留、零发布 `sampleRatio` 保留）。

**未纳入本 PR（记录在案，另行决策）**：GitHub compare 截断声明（已知限制已入 docs/05）、`DiffRenderer` 空 patch 二进制猜测、verify 重判 crosschecker 开关持久化。
