# ADR-0010 服务端签名 L2 报告（设计稿）

- 状态：提议（2026-10-27，owner 决策 2C：长期采用服务端签名报告）
- 关联：ADR-0008（M4b 报告签名）、ADR-0009（签名 v2 repo 绑定）、04§2.4、06§1.2
- 影响面：`cra-server` attest 端点/审计表、`cra-eval verify`、`.github/workflows/ci.yml` 的 `eval-l2` job、部署文档
- 本 ADR 只做设计；**不写生产代码、不改 CI**，待 owner 批准方案后再排期。

## 1. 问题

当前 L2 报告是“本地/CI 跑评测 → 提交 `eval/l2/report.json` + `.sha256` → CI 校验哈希 + 门禁 + commit 绑定”。
这是荣誉制：哈希只能防篡改，不能证明报告由谁生成；而且 **squash 合并会把报告绑定的 commit 压掉**，
main 上的旧报告对后续 PR 不再有效，改核心代码的 PR 必须重跑 L2（2026-10-27 已实际踩中）。

M4b 已有服务端 HMAC 签名（ADR-0008/0009），但它当前只签 **review_run 中 DONE|DEGRADED 的评审报告**；
L2 评测报告不是一次 review run，服务端没有“该 commit 的 L2 评测已完成”的记录。直接把现有
`/api/v1/reports/attest` 接到 L2 会卡在 409，因此需要区分“评审报告签名”和“评测报告签名”。

## 2. 目标与非目标

**目标**
- CI 能验证 L2 报告由持有服务端密钥者签发，且绑定 `repo + commit + tier=l2 + 报告摘要`。
- squash 合并后不再因“报告 commit 不在 PR 历史”假红；报告与“被测代码提交”的绑定语义仍可审计。
- 不改变 L2 的评测内容、阈值、样本集和判定口径（06§1.2 不动）。

**非目标**
- 不证明报告由服务端**执行**（那需要托管评测，成本与架构都更大）。
- 不替代 L3 全量门禁；L3 仍是里程碑手动评测。
- 不解决“报告内容是否真实反映代码质量”——只解决“报告是否被服务端签发、绑定是否正确”。

## 3. 方案选项

### 方案 A：新增“评测报告”签名类型（推荐）
- 签名信封升 v3（或新增 scheme `cra-eval-report-hmac-sha256-v1`），在 v2 的
  `repo/commit/tier/report_sha256/issued_at/run_id` 基础上增加 `kind:eval`。
- 新端点 `POST /api/v1/eval-reports/attest`：
  - 请求：报告 JSON 字节 + `X-CRA-Repo: owner/name` + Bearer token；
  - 服务端校验报告基本形状（JSON、tier=l2、gate 字段存在）、repo/commit 非空、报告 sha256；
  - 签发并落 `eval_report_attestation(repo, commit, tier, report_sha256, key_id, issued_at, run_id, signature)`。
  - 策略开关 `CRA_SERVER_ATTEST_EVAL_REQUIRE_REVIEW`：
    - `false`（推荐首版）：不要求该 commit 有 review_run，允许 CI 在评审前跑 L2；
    - `true`：要求该 repo/commit 已有 DONE|DEGRADED（更严，但可能阻塞“先 L2 后评审”的流程）。
- CI 的 `eval-l2` job：
  - 过渡期：hash-only 仍可用，但输出 warning；
  - 强制期：必须有 `eval/l2/report.json.sig`，`cra-eval verify --signature --repo "$GITHUB_REPOSITORY" --tier l2 --expect-commit <报告 commit>`；
  - 报告 commit 的祖先检查保留；squash 后仍要求报告绑定“被测代码提交”，但允许报告提交在 PR head 之后仅 `eval/l2/` 变更（现有规则）。
- 优点：语义清晰、与评审签名隔离；服务端可审计“哪个 repo/commit 的 L2 报告被签发”。
- 缺点：新端点/表/密钥用途；CI 需要持有签名密钥或调用服务端（二选一）。

### 方案 B：复用现有评审签名端点，扩展 tier=l2
- 让 `/api/v1/reports/attest` 接受 `tier=l2` 且不要求 review_run。
- 优点：实现最小；缺点：把“评审完成签名”和“评测报告签名”混在同一张审计表/同一策略，
  未来很难区分；不推荐。

### 方案 C：服务端托管执行 L2
- CI 把 L2 交给服务端跑，服务端直接产出并签名报告。
- 优点：最强（执行+签名同源）；缺点：服务端要接 LLM/数据集/快照，成本和运维大；
  与当前“L2 本地跑、CI 只校验”的成本纪律冲突。列为长期演进，不作为本次方案。

**推荐：A**；首版 `REQUIRE_REVIEW=false`，强制签名阶段可配置。

## 4. 报告与信封格式（方案 A）

```text
cra-eval-report-hmac-sha256-v1
key_id:<key id>
report_sha256:<sha256>
repo:<owner/name>
kind:eval
commit:<被测代码提交>
tier:l2
issued_at:<ISO-8601>
run_id:<attestation id>
signature:<hmac>
```

HMAC 覆盖版本行至 `run_id` 行；`kind` 固定 `eval`，防止把评审报告信封当评测报告复用。
`commit` 必须是报告 JSON 的 `commit` 字段（服务端比对），`tier` 必须是 `l2`。

## 5. CI 流程

1. 本地/CI 产报告：`cra-eval run --set l2 --k 2 --depth quick --tier l2 --commit <被测 head>`。
2. 调 `POST /api/v1/eval-reports/attest`（Bearer + `X-CRA-Repo`）→ 得到 `.sig`。
3. 报告 + `.sig` 作为 PR 最后一个提交（仅 `eval/l2/` 变更）。
4. `eval-l2` job：
   - 验签（repo/commit/tier/报告摘要）；
   - 报告 commit 是 PR head 祖先；其后仅 `eval/l2/`；
   - 门禁绿。
5. 强制期后，无 `.sig` 的 L2 报告直接红（fork PR 无 secrets 时按现有规则跳过/降级并声明）。

## 6. 密钥、权限与失败模式

- 签名密钥只存服务端与 CI secret；轮换用 `key_id` 区分，旧签名在有效期后失效。
- 每仓库独立 Bearer token（或未来按租户隔离密钥）——避免 A 仓库 token 为 B 仓库签报告。
- 失败模式：服务端不可用 → CI 明确红/降级并声明，不静默用 hash-only 冒充签名；
  报告被篡改 → 退出码 5；repo/commit/tier 不匹配 → 退出码 5。
- 重放：`run_id` + `issued_at` + 审计表；同一报告重复签发允许（幂等），但审计可查。

## 7. 迁移与工期

- 阶段 1（1–2 天）：新增端点/表/信封 + 单测/集成测试 + `cra-eval verify` 支持 `kind=eval`；
  CI 先“有签名则验、无签名 warning”。
- 阶段 2（0.5 天）：CI 强制签名；文档/部署检查单更新；回滚开关。
- 阶段 3（可选）：服务端托管执行 L2（另写 ADR）。
- 依赖：M4b 服务已部署、CI 可访问服务端、签名密钥/ token 配置；不影响 4A 的 repo 绑定 v2。

## 8. 验收标准（设计阶段）

- owner 确认方案 A/B/C 与 `REQUIRE_REVIEW` 首版取值；
- 明确 CI 强制签名的时间点与失败降级策略；
- 明确密钥/ token 的归属与轮换责任；
- 再开实现任务（预估 1–2 天，含测试与文档）。
