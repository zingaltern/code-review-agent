# 05 · 数据模型与集成设计

> 文档版本：v1.0（2026-10-25）
> 前置阅读：`02-architecture-design.md`

---

## 1. 核心数据模型（cra-common）

### 1.1 CandidateFinding / Finding（子代理输出与流转核心）

```json
{
  "id": "f_01hxy…",                  // ULID
  "run_id": "run_01hxy…",
  "category": "bug|security|test|convention|performance",
  "title": "Offset 计算在空列表时越界",
  "description": "当 items 为空时 size()-1 返回 -1，导致 get(-1) 抛 IndexOutOfBoundsException",
  "failure_mode": "触发条件：items 为空列表；错误结果：IndexOutOfBoundsException",   // 触发条件→错误结果，必填
  "file": "src/main/java/…/Foo.java",
  "line": 87,                         // head 版行号
  "side": "RIGHT",                    // inline comment 用
  "evidence": "L85-88: return items.get(items.size()-1) …",
  "suggestion": "Optional<Foo> last(List<Foo> items) { return items.isEmpty() ? empty() : of(get(size-1)); }",
  "severity_proposed": "important|nit",
  "pre_existing_suspect": false,
  "confidence": 0.8,                  // 发现者自评，仅供排序参考，不参与晋升
  "origin": { "agent_role": "bug-hunter/dataflow", "model": "…" },
  "verification": {
    "verdicts": [
      { "verifier": "v1", "verdict": "CONFIRMED", "rationale": "…", "evidence": "…" },
      { "verifier": "v2", "verdict": "CONFIRMED", "rationale": "…", "evidence": "…" }
    ],
    "judge": null,
    "status": "CONFIRMED|REJECTED|DISPUTED|UNVERIFIED"
  },
  "final_severity": "important|nit|pre-existing",
  "dedup_key": "sha256(file|line_bucket|category|assertion_normalized)",
  "github_comment_id": 123456789,     // 发布后回填，幂等去重用
  "human_disposition": null            // fixed|wrong|intentional|ignored（反馈闭环回填）
}
```

设计要点：

- `confidence` **不参与**晋升判定（自评分不可靠），晋升只看 verifier 判定；
- `dedup_key` 是收敛控制（03§5）的指纹基础；
- `human_disposition` 由反馈采集脚本/人工标注回填（见 06）。

### 1.2 ReviewRun（一次评审的账本）

```json
{
  "id": "run_01hxy…",
  "repo": "zingaltern/code-review-agent",
  "pr_number": 42, "commit_sha": "…", "base_sha": "…",
  "trigger": "ci|cli|webhook", "depth": "quick|standard|deep",
  "status": "SUCCESS|DEGRADED|FAILED|SKIPPED",
  "plan": { "chunks": […], "agents": [{role, model, budget}…] },
  "stats": {
    "findings_candidate": 12, "findings_confirmed": 5, "rejected": 6, "disputed_judged": 1,
    "nit_published": 2, "pre_existing": 2,
    "tokens_in": 182000, "tokens_out": 21000, "cost_usd": 0.41,
    "latency_ms": 154000, "degradations": ["perf-reviewer-timeout"]
  },
  "trace_path": "runs/…/trace.ndjson",
  "created_at": "…"
}
```

### 1.3 Trace 格式（NDJSON，每行一个事件）

```json
{"ts":"…","run_id":"…","event":"agent_start","agent":"bug-hunter/boundary","brief_path":"…/bh1-brief.md"}
{"ts":"…","event":"agent_output","agent":"…","findings":3,"tokens":{…},"latency_ms":8200}
{"ts":"…","event":"verify","finding_id":"f_…","verdicts":["CONFIRMED","CONFIRMED"],"status":"CONFIRMED"}
{"ts":"…","event":"publish","comments":5,"skipped_duplicate":1}
```

brief 全文单独落盘（`runs/{run_id}/briefs/*.md`），trace 只存路径引用——trace 可读，brief 可复现。

### 1.4 存储Schema（SQLite，cra-store 负责 Flyway 式迁移）

表：`review_run`、`finding`、`verdict`、`published_comment`、`prompt_template_version`（见 06：prompt 版本与 run 关联，回归定位用）。

## 2. GitPlatformAdapter SPI（cra-git）

```java
public interface GitPlatformAdapter {
    DiffResult fetchDiff(RepoRef repo, String baseSha, String headSha);   // 结构化 hunk 列表
    FileContent readFile(RepoRef repo, String sha, String path);
    List<String> listFiles(RepoRef repo, String sha, String pathGlob);
    blame(RepoRef repo, String sha, String path, int line);              // pre-existing 复核
    PrReviewResult postReview(RepoRef repo, int prNumber, ReviewPayload payload); // inline comments + summary
    CommentThread fetchExistingComments(RepoRef repo, int prNumber);     // 幂等与处置采集
}
```

GitHub 实现要点（cra-git-github）：

- 认证：`GITHUB_TOKEN`（CI 内置）或 App token（服务化阶段）；
- 限流：读 `X-RateLimit-Remaining` 主动节流 + 指数退避重试（最多 3 次）；
- inline comment 用 PR Review API（`POST /repos/{o}/{r}/pulls/{n}/reviews`，`commit_id` 绑定 head sha，event=COMMENT）；一次 review 提交全部 comments，避免多条零散通知；
- diff 获取用 compare API（`GET /repos/{o}/{r}/compare/{base}...{head}`），不依赖 CI checkout 与本地 git 进程（消除原项目 fetch-depth=2 的脆弱性）。

## 3. CLI 契约（cra-runner-cli）

```bash
# CI 模式（GitHub Actions step 内）
cra review \
  --repo "$GITHUB_REPOSITORY" --pr "$PR_NUMBER" \
  --base "$BASE_SHA" --head "$HEAD_SHA" \
  --depth standard \
  --token "$GITHUB_TOKEN" --dry-run=false

# 本地模式（pre-PR 自评审，M3）
cra review --local --base main --depth quick --dry-run=true
```

退出码：`0` 正常完成（无论有无 finding——**发现不是失败**）；`2` 自身故障（配置/网络/预算击穿）；`3` 安全 finding 阻塞模式命中（可选 `--security-block`，供需要阻塞语义的仓库）。

## 4. GitHub Actions 集成（新仓库自举 workflow）

`.github/workflows/review.yml`：

```yaml
name: code-review-agent
on:
  pull_request:
    types: [opened, synchronize, reopened]
  # push 到 master 的直接评审（延续原项目场景，用 compare 最近两个 commit）
  push:
    branches: [main]
permissions:
  contents: read
  pull-requests: write
concurrency:
  group: review-${{ github.event.pull_request.number || github.sha }}
  cancel-in-progress: true          # 连续 push 时取消旧评审，省成本
jobs:
  review:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4   # 仅供 CLI 读取 REVIEW.md（或改用 API 读取，M2 决定）
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: 17 }
      - run: curl -fsSL …/cra-cli …   # 发行方式 M0 定：fat jar 缓存 or docker
      - run: cra review …
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
          CRA_LLM_API_KEY: ${{ secrets.CRA_LLM_API_KEY }}
          CRA_LLM_BASE_URL: ${{ vars.CRA_LLM_BASE_URL }}
```

要点：`concurrency` 取消旧 run 是成本控制第一道闸；`synchronize` 触发 + diff-only 复审（03§5）配合，增量评审成本远低于全量。

## 5. 部署级配置（application.yml 骨架）

```yaml
cra:
  llm:
    models:
      standard: { provider: openai-compatible, model: "…", base-url: "${CRA_LLM_BASE_URL}" }
      strong:    { provider: openai-compatible, model: "…" }
      light:     { provider: openai-compatible, model: "…" }
    fallback-chain: [standard, light]
  agents:                        # 覆盖 03§6 默认值
    verifier: { count: 2 }
    budget: { run-total-tokens: 400000 }
  review:
    default-depth: standard
    large-diff-lines: 2000
    hard-cap-lines: 8000
    nit-budget-default: 3
    publish-unverified: never    # never | labeled（UNVERIFIED 标签发布）
```

## 6. 安全与合规检查单（开发 agent 实现时逐条对照）

- [ ] 密钥只从环境变量读取，trace/日志脱敏（`sk-`、`ghp_` 前缀正则掩码）；
- [ ] 子代理无网络、无 shell、无仓库外路径访问（工具实现层硬编码限制，不靠 prompt 约束）；
- [ ] diff/文件内容只发往配置的 LLM 端点，不落第三方；
- [ ] GitHub token 最小权限（contents:read + pull-requests:write）；
- [ ] dry-run 模式全程不产生写操作（单测断言零写调用）。
