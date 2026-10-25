# 05 · 数据模型与集成设计

> 文档版本：v1.3.1（2026-10-25，响应第四轮评审缺陷 ①③④⑥）
> **v1.1**：SPI 增加本地 jgit blame；账本指纹规范；锚定校验与逐条降级；安全检查单扩充。
> **v1.2**：指纹 v2 + 解析器规则 + 发布侧转义剥离；replies 双端点；workflow draft 过滤与 cli 分发。
> **v1.2.1**：run 完成标记（status=DONE）；Summary 载体改 issue comment；滑窗容错（02§4.1）。
> **v1.3.1**：§1.1 Finding 枚举补 **`context`**（v1.3 新增 CrossChecker 的输出类别未传播到 schema，其全部输出会被 schema 校验拒收——四轮里第三次同类病）；§4 补回滚职责注（修复 01§3.4 悬空引用）；§5 补 CrossChecker 配置项。
> **v1.3.2**（M2 T2.9–T2.11 实现撞墙定点修订，2026-10-25）：§3 本地 CLI 实参补齐（--exec/--ledger-db/三档路由参数）、退出码新增 **4**（本地实跑失败，验收 8 的可判定信号）、本地解析语义（repo/head/token/LLM 可省、pr=0）；§1.4 补 `template_versions` 事件；§1.5 登记 `prompt_template_version` 落地。

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

### 1.4 Trace 格式（NDJSON）

> **v1.3.2**（2026-10-25，M2 隔离运行时事件先行定点修订，07§6.5）：注册 `tool_call` / `tool_result` / `agent_budget_exceeded` 事件与 `agent_output.termination` 字段；`dedup.match` 增 `in-run-exact` 取值（M2 跨代理精确去重合并，03§4.5 汇聚侧，T2.8 的验证侧分组复用同一键）。

```json
{"ts":"…","run_id":"…","event":"run_start","repo":"…","pr":42,"depth":"standard","dry_run":false}
{"ts":"…","run_id":"…","event":"gate_check","gate":"commit","hit":false}
{"ts":"…","run_id":"…","event":"agent_start","agent":"bug-hunter/boundary","brief_path":"…"}
{"ts":"…","event":"agent_output","agent":"…","findings":3,"termination":"completed","tokens":{…},"latency_ms":8200}
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
{"ts":"…","event":"template_versions","versions":{"bug-hunter/dataflow":"dataflow@1a2b3c04","security-reviewer":"security@5e6f7a80"}}
{"ts":"…","event":"dedup","finding_id":"f_…","match":"exact|sliding-window|possible_duplicate|in-run-exact|none"}
{"ts":"…","event":"blame_check","note":"…"}
{"ts":"…","event":"verify","file_batch":"Foo.java","findings":4,"verdicts":{"confirmed":3,"refuted":1,"insufficient":0,"unavailable":0}}
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

brief 全文随 trace 存于同 artifact（`briefs/*.md`）。

### 1.5 存储 Schema

CI 形态无本地库（账本=GitHub）；本地/服务形态：`review_run`、`finding`、`verdict`、`published_comment`、`prompt_template_version` 表，`ReviewLedger` SPI 统一抽象（cra-store）。已落地：M1 建 review_run/finding；M2 T2.11 建 `prompt_template_version`（repo/pr_number/run_id/template_name/template_version，随 run 落库，05§1.2 prompt_template_versions 的持久化形态）；verdict/published_comment 随验证层/处置采集数据面落地。

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
}

public interface LocalRepoContext {                                            // v1.1 新增
    BlameResult blame(String path, int line, String sha);                      // jgit 实现
    Optional<String> localRepoRoot();                                          // CI checkout / 本地模式存在；纯 API 模式为空
}

public interface CommentSource {                                               // v1 基线新增（ADR-0005，10§6）：账本与处置采集的取数端口
    CommentLedger fetchBotComments(RepoRef repo, int prNumber);                // 与 GitPlatformAdapter 同名方法同语义，返回 common POJO
    DispositionSignal[] fetchDispositionSignals(RepoRef repo, int prNumber);   // 06§1.1 周报数据面
}
// 消费方：cra-store 的 GitHub 评论账本实现（构造器注入，只依赖 common 类型）与 cra-eval report；
// 提供方：cra-git-github（适配既有 adapter 方法）；装配：组合根（cra-runner-cli 与 eval 的 Main）。
```

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

### 2.3 GitHub 实现要点（v1.2.1 端点清单修正）

认证 `GITHUB_TOKEN`；读 `X-RateLimit-Remaining` 主动节流 + 指数退避（≤3 次）；inline comment 走 PR Review API（`commit_id` 绑定 head sha, event=COMMENT）；**Summary 走 issue comments 端点**（§2.2）；bot 身份识别按 token 对应 login（CI 内为 `github-actions[bot]`）。

**端点清单（按用途锁定）**：

| 用途 | 端点 | 说明 |
|---|---|---|
| inline 评论发布 | `POST /repos/{o}/{r}/pulls/{n}/reviews` | 只装 inline，body 固定引导语 |
| Summary 发布/收尾编辑 | `POST` / `PATCH /repos/{o}/{r}/issues/{n}/comments/{id}` | issue comment 可编辑（review body 不可） |
| Summary reaction（处置信号） | `POST /repos/{o}/{r}/issues/comments/{id}/reactions` | 👍/👎 快捷处置（06§1.1） |
| 账本拉取 | `GET /pulls/comments` + `GET /issues/{n}/comments` + review replies 线索 | bot 的 inline 与 Summary 分属不同端点，合并去重（v1.2 已定） |

**resolve 状态为 GraphQL-only**：REST v3 锁定下不采、不操作（06§1.1、02§5）。

## 3. CLI 契约（cra-runner-cli）

```bash
# CI 模式（GitHub Actions step 内）
cra review --repo "$GITHUB_REPOSITORY" --pr "$PR_NUMBER" \
  --base "$BASE_SHA" --head "$HEAD_SHA" --depth standard --token "$GITHUB_TOKEN"

# 本地 pre-PR 模式（M2 起；账本=本地 SQLite，默认 dry-run 不回帖）
cra review --local [--base main] [--head REF] [--depth standard] [--dry-run=false] \
  [--exec "mvn -B -ntp verify"] [--ledger-db ~/.cra/ledger.db] \
  [--model-strong M] [--model-light M] [--fallback-chain standard,light] [--rpm standard=60,strong=30,light=60]

# 处置采集/周报（本地跑，读 GitHub 账本，无常驻服务）
cra-eval report --repo zingaltern/code-review-agent --since 7d
```

本地模式解析语义（v1.3.2 实现落地）：`--repo`/`--head`/`--token`/`CRA_LLM_*` 均可省——仓库身份由 remote origin 推导（兜底 `local/<目录名>`）、head 解析为 HEAD、无 `CRA_LLM_API_KEY` 时评审段**如实跳过**（报告声明，不伪装）；`--pr` 省缺为 0（本地无 PR，账本按 `(repo, 0, commit)` 键控）。

退出码：`0` 正常完成（**有 finding 不是失败**）；`2` 自身故障；`3` `--security-block` 模式下存在 confirmed 安全 finding（供需要阻塞语义的仓库）；**`4` 本地 pre-PR 实跑构建/测试失败**（v1.3.2 撞墙定点修订：v1 基线只定义了 0/2/3，而 07 T2.10 验收 8 要求"报告如实展示测试失败输出且退出码非零"——需一个区别于"工具自身故障"的确定性信号。优先级高于评审结论：构建失败时评审照常运行、结果入报告，但退出码恒为 4）。

## 4. GitHub Actions 集成（v1.2：draft 过滤 + 分发方案 + 限制声明）

**已知平台限制（显式声明，见 02§5 触发边界表）**：冲突 PR（mergeable=conflicting）的 `pull_request` workflow **不触发**（GitHub 官方行为）——该场景静默无评审，冲突解决后的 synchronize 会正常触发；fork PR 无 secrets 访问（当前个人仓库无碍，多协作者时引入 App token）。

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
    max-concurrency: 8              # v1.1：LLM 并发信号量
    per-model-rpm: { standard: 60, strong: 30, light: 60 }
  agents:                        # v1.3.1：crosschecker 配置（默认关闭，M4f 启用）
    crosschecker:
      enabled: false             # M4f-lite 起可开
      mode: [assumption, sniff]  # 模式 A/B 开关（03§3.1）
      history-weeks: 8           # 上下游变更史回溯窗口
    verifier:
      mode: single                  # M2: single | M3: dual-vote
      dual-vote:
        require-distinct-model-family: true   # v1.1：异构硬校验，违例配置拒绝启动
        v1: { model: strong,  template: balanced-review }
        v2: { model: "<异构>", template: refute-oriented }
      important-refuted-spotcheck: 0.20       # 拒真保险丝（03§4.3）
    budget: { run-total-tokens: 500000, degrade-at: [0.70, 0.85] }   # v1.2.1：500k + 阈值重校（02§4.4）
  review:
    default-depth: standard
    large-diff-lines: 2000
    hard-cap-lines: 8000
    nit-budget-default: 3
    publish-unverified: never       # never | labeled
  trust:
    review-policy-source: base      # v1.1：固定 base，禁 head（04§3.2）
```

## 6. 安全与合规检查单（v1.1 扩充）

- [ ] 密钥只从环境变量读取，trace/日志脱敏（`sk-`、`ghp_` 前缀正则掩码）；
- [ ] 子代理无网络、无 shell、无仓库外路径（实现层硬编码，不靠 prompt）；
- [ ] **REVIEW.md 只读 base SHA**；PR 含 REVIEW.md 变更时按 base 策略执行并警告（04§3.2）；
- [ ] **不可信内容（diff/注释/commit msg/finding/验证输出）一律 UNTRUSTED 定界**，system prompt 含指令层级声明（04§3.3）；
- [ ] **发布前 markdown 转义**，finding 正文代码块包裹；
- [ ] **注入对抗集在 CI 门禁中运行**，行为改变即失败（06§1.2）；
- [ ] **账本解析只认末位/代码块外/版本合法的标记**；正文 `<!--` 全量转义；解析兜底退 commit 级闸、永不全量重发（05§1.3）；
- [ ] **pre-PR 本地模式实跑构建/测试并展示真实输出**，不以"声称完成"代替（07 T2.10）；
- [ ] 处置采集不依赖 GraphQL-only 的 resolve 状态（05§2.3、06§1.1）；
- [ ] GitHub token 最小权限（contents:read + pull-requests:write）；
- [ ] dry-run 模式全程零写操作（单测断言零写调用）；
- [ ] diff/文件内容只发往配置的 LLM 端点，不落第三方。
