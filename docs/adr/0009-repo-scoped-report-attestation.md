# ADR-0009 报告签名 v2：repo 绑定的签发与验签

- 状态：已接受（2026-10-27，owner 决策 4A）
- 关联：ADR-0008（M4b 服务化与报告签名）、04§2.4、05§1.5
- 影响面：`cra-common` `ReportSignature`、`cra-server` attest 端点/审计表、`cra-eval verify --signature`、部署文档

## 背景

M4b v1 的签发策略只按 commit 在全局 `review_run` 中查 `DONE|DEGRADED`，不限定仓库。
单租户部署可接受，但同一服务承载多个仓库/团队时，A 仓库完成过评审的 commit 可能被
B 仓库的签发请求复用，形成“跨仓库冒用签名”的风险。

owner 2026-10-27 决策 4A：签发范围必须按 repo 限定。

## 决策

1. **签名信封 v2**：`cra-report-hmac-sha256-v2`，在 v1 字段基础上增加 `repo:<owner/name>`：

   ```text
   cra-report-hmac-sha256-v2
   key_id:<key id>
   report_sha256:<sha256>
   repo:<owner/name>
   commit:<commit>
   tier:<l1|l2|l3>
   issued_at:<ISO-8601>
   run_id:<attestation id>
   signature:<hmac>
   ```

   HMAC 输入 = 版本行至 `run_id` 行（含），`signature` 行不参与；`repo` 必填且校验
   `owner/name` 形式。v1 信封不再接受（格式变更按 11§2 的 bump 版本规则处理）。

2. **服务端签发**：`POST /api/v1/reports/attest` 增加 `X-CRA-Repo: owner/name` 头；
   `CRA_SERVER_ATTEST_REQUIRE_KNOWN_COMMIT=true` 时 repo 与 commit 必须同时存在，
   且 `KnownCommitChecker.exists(repo, commit)` 按 `(repo, commit)` 查 `review_run`。
   `report_attestation` 表增加 `repo` 列（含 `ADD COLUMN IF NOT EXISTS` 与
   `(repo, commit_sha)` 索引）。

3. **CLI 验签**：`cra-eval verify --signature ...` 增加可选 `--repo owner/name`；
   提供时要求与信封 `repo` 一致，不提供时只展示信封 repo（不做期望值比对）。
   服务化 CI 应传 `--repo "$GITHUB_REPOSITORY"`。

4. **兼容/迁移**：旧 v1 签名信封失效，需用 v2 重新签发；哈希荣誉制路径
   （`--sha256/--sha256-file`，无签名）不受影响。历史 `report_attestation` 行
   `repo=''` 保留为历史记录。

## 后果

- 多租户签发不再跨仓库串号；单租户行为不变（请求头传同一 repo 即可）。
- 签名仍只证明“持有服务端密钥者签发了该摘要”，不证明服务端执行了评测
  （ADR-0008 的局限声明仍然有效）。
- `X-CRA-Repo` 由调用方提供；服务端只验证它是否与 `review_run` 匹配，不替代
  每仓库独立 token/权限模型。若未来按租户隔离密钥，应另写 ADR。
- 2026-10-28 修订：repo 的非空与 owner/name 形式校验前移到签发入口（无条件必填，
  非法 → 409，与 `ReportSignature.isValidRepo` 同一规则），不再落到底层信封不变量
  抛 500；信任边界不变——头仍由调用方提供、仅做格式校验。

## 测试与验收

- `ReportSignatureTest`：v2 往返、repo 篡改/缺失拒绝、repo 非法形式（owner//name 等）拒绝。
- `AttestationServiceTest`：repo 缺失/非法 409（含宽松策略）、repo 传入 checker、审计记录含 repo。
- `ServerHttpIntegrationTest`：带 `X-CRA-Repo` 签发 200；缺头 409；头非法形式 409；
  跨 repo 同名 commit 不命中（由 SQL 条件保证）。
- `PostgresKnownCommitCheckerTest`：真实 PG 上 (repo, commit) 命中、跨仓隔离、
  大小写不敏感与空参拒绝。
- `VerifyCommandTest`：`--repo` 匹配通过、不匹配退出码 5。
