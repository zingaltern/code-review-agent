# 05 · 数据模型与集成设计

> 文档版本：v1.1（2026-10-25，响应方案评审缺陷 ①⑥⑦）
> **v1.1 修订要点**：SPI 增加本地 jgit blame（GitHub API 无行级 blame 端点）；账本指纹规范（HTML 注释内嵌）；inline 锚定校验与逐条降级（防整包 422 零回帖）；workflow 收窄为仅 PR 触发并增加 artifact 上传；安全检查单补注入防御项。

---

## 1. 核心数据模型（cra-common）

### 1.1 CandidateFinding / Finding

```json
{
  "id": "f_01hxy…",
  "run_id": "run_01hxy…",
  "category": "bug|security|test|convention|performance",
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
  "anchor_status": "INLINE|SUMMARY_FALLBACK",       // v1.1：锚定校验结果
  "dedup_key": "sha256(file|line_bucket|category|assertion_normalized)",
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

### 1.3 账本指纹规范（GitHub 评论账本的存储格式，v1.1 新增）

每条 bot 评论体末尾内嵌 HTML 注释（渲染不可见，API 可解析）：

```markdown
**[Important] Offset 计算在空列表时越界** — `src/…/Foo.java:87`
…(转义后的 finding 正文)…
<!-- cra:fp=9f2a…c4 | id=f_01hxy… | run=run_01hxy… | commit=abc1234 | v=1 -->
```

账本解析器（cra-git-github）：拉取 bot 用户在该 PR 的全部评论 → 提取 `cra:` 标记 → 得到已发布指纹集、run 历史、处置锚点。此为 CI 形态下幂等与收敛控制的事实源（02§5）。

### 1.4 Trace 格式（NDJSON）

```json
{"ts":"…","run_id":"…","event":"agent_start","agent":"bug-hunter/boundary","brief_path":"…"}
{"ts":"…","event":"agent_output","agent":"…","findings":3,"tokens":{…},"latency_ms":8200}
{"ts":"…","event":"verify","file_batch":"Foo.java","findings":4,"verdicts":{…}}
{"ts":"…","event":"anchor_check","finding_id":"f_…","status":"SUMMARY_FALLBACK"}
{"ts":"…","event":"publish","comments":5,"skipped_duplicate":1,"ledger_writes":5}
```

brief 全文随 trace 存于同 artifact（`briefs/*.md`）。

### 1.5 存储 Schema

CI 形态无本地库（账本=GitHub）；本地/服务形态：`review_run`、`finding`、`verdict`、`published_comment`、`prompt_template_version` 表，`ReviewLedger` SPI 统一抽象（cra-store）。

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
```

**修订说明**：

- **blame 走本地 jgit**：GitHub REST/GraphQL 均无行级 blame 端点（open feature request）。CI workflow 本就 checkout 仓库，本地模式天然有仓库；`localRepoRoot()` 为空（如未来服务形态无 checkout）时 pre-existing 检测**降级为不标注 + Summary 声明**，不假装有该能力；
- v1.0"不再依赖本地 git 进程"的说法**收回**：diff 获取用 compare API（不依赖 fetch-depth），blame 用本地 jgit，两者并存。

### 2.2 发布：锚定校验与逐条降级（v1.1 新增，防整包 422）

1. 提交前逐条校验：finding 的 (file, line) 必须落在该 PR diff 的某个 hunk 内（PR Review API 硬性要求）；pre-existing finding 与窗口边缘 finding 常不满足；
2. 不满足者 `anchor_status=SUMMARY_FALLBACK`：正文进 Summary 列表（带 file:line 普通链接，无 inline 定位）；
3. 提交策略：整包一次 review 请求；收到 422 → 降级为逐条提交，仍失败的按 SUMMARY_FALLBACK；全部结果回写账本。

### 2.3 GitHub 实现要点

认证 `GITHUB_TOKEN`；读 `X-RateLimit-Remaining` 主动节流 + 指数退避（≤3 次）；inline comment 走 PR Review API（`commit_id` 绑定 head sha, event=COMMENT）；bot 身份识别按 token 对应 login（CI 内为 `github-actions[bot]`）。

## 3. CLI 契约（cra-runner-cli）

```bash
# CI 模式（GitHub Actions step 内）
cra review --repo "$GITHUB_REPOSITORY" --pr "$PR_NUMBER" \
  --base "$BASE_SHA" --head "$HEAD_SHA" --depth standard --token "$GITHUB_TOKEN"

# 本地 pre-PR 模式（M2 起；账本=本地 SQLite，默认不回帖）
cra review --local --base main --depth standard --dry-run=true

# 处置采集/周报（本地跑，读 GitHub 账本，无常驻服务）
cra-eval report --repo zingaltern/code-review-agent --since 7d
```

退出码：`0` 正常完成（**有 finding 不是失败**）；`2` 自身故障；`3` `--security-block` 模式下存在 confirmed 安全 finding（供需要阻塞语义的仓库）。

## 4. GitHub Actions 集成（v1.1：仅 PR 触发 + artifact）

```yaml
name: code-review-agent
on:
  pull_request:
    types: [opened, synchronize, reopened]
  # v1.0 曾含 push 触发：postReview 依赖 PR 号与 PR diff，push 直推在本设计下是
  # 未定义行为，已移除；push 场景（commit-comment 模式）排入 M4。
permissions:
  contents: read
  pull-requests: write
concurrency:
  group: review-${{ github.event.pull_request.number }}
  cancel-in-progress: true
jobs:
  review:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4        # 供 jgit blame 与 base 分支 REVIEW.md 读取
        with: { fetch-depth: 0 }         # blame 需要历史
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: 17 }
      - run: curl -fsSL …/cra-cli -o cra && chmod +x cra    # 发行方式 M0 定
      - run: ./cra review …
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
          CRA_LLM_API_KEY: ${{ secrets.CRA_LLM_API_KEY }}
          CRA_LLM_BASE_URL: ${{ vars.CRA_LLM_BASE_URL }}
      - name: persist trace              # v1.1 新增：运行器即焚，trace 必须外传
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
  agents:
    verifier:
      mode: single                  # M2: single | M3: dual-vote
      dual-vote:
        require-distinct-model-family: true   # v1.1：异构硬校验，违例配置拒绝启动
        v1: { model: strong,  template: balanced-review }
        v2: { model: "<异构>", template: refute-oriented }
      important-refuted-spotcheck: 0.20       # 拒真保险丝（03§4.3）
    budget: { run-total-tokens: 400000, degrade-at: [0.6, 0.8] }
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
- [ ] GitHub token 最小权限（contents:read + pull-requests:write）；
- [ ] dry-run 模式全程零写操作（单测断言零写调用）；
- [ ] diff/文件内容只发往配置的 LLM 端点，不落第三方。
