# ADR-0008 M4b webhook 服务化选型与部署形态（PG 账本 + 进程内队列 + 服务端报告签名）

- 状态：accepted（2026-10-26，M4b 实现随本 ADR 落地）
- 背景：02§5 的三种部署形态中，服务形态（PG 账本、常驻 webhook）此前只有设计没有实现；04§2.4 明确 L2 门禁的"本地跑 + CI 校验哈希"是荣誉制（防篡改不防伪造），彻底消除需服务端签发报告。
- 决策：
  1. **第二组合根 = Spring Boot web（`cra-server`）**，与 `cra-runner-cli` 平级；评审逻辑经 `CoreAssembly` 复用 core，server 不复制流水线。R6 的允许集合扩展为 `org.cra.runner.cli..` + `org.cra.server..`。
  2. **队列 = 进程内有界 `ArrayBlockingQueue` + 固定 worker**，满时 HTTP 503（带 `Retry-After`）并释放 delivery 占位；不引入 Kafka/Rabbit/Redis。幂等双闸：PG 唯一键挡同 delivery，core 的 commit 闸 + 指纹账本挡同 head 重跑（02§4.1）。
  3. **PG 账本 = `PostgresLedger`（java.sql + 组合根提供的 `DataSource`）**；05§1.5 五表（`review_run`/`finding`/`verdict`/`published_comment`/`prompt_template_version`）+ server 自有 `webhook_delivery`/`report_attestation`/`schema_migration`。迁移用**手写幂等 DDL**（`IF NOT EXISTS`），不引入 Flyway：当前仅两个版本、全为加法，脚本可审计；表数量增长或需要回滚时再评估 Flyway。
  4. **报告签名 = HMAC-SHA256 行式信封**（`org.cra.common.attest.ReportSignature`，cra-common 纯 JDK），服务端 `/api/v1/reports/attest` 签发并落审计表，`cra-eval verify --signature` 验签。默认 `CRA_SERVER_ATTEST_REQUIRE_KNOWN_COMMIT=true`：只对 `review_run` 里 DONE|DEGRADED 的 commit 签发，签名因此绑定"服务端见过并评审过该提交"。
  5. **PG 测试**：优先 CI service 容器（`CRA_TEST_PG_URL`），本地回退 Testcontainers（`postgres:16-alpine`）；不使用 H2——账本依赖 PG 语义（`ON CONFLICT`、`TIMESTAMPTZ`、`UPPER()`）。据此修订 08§3.1 的"Testcontainers 不引入"。
  6. **M4f-full 事件史采集只留接口与调度骨架**：`EventHistoryCollector` + `@Scheduled` + Noop 实现（日志明确"未实现"），具体采集在 M4f-full。
- 理由：个人项目不引入需要独立运维的 broker/注册中心；进程内队列 + 503 重投足以承载单仓库/少量仓库的 webhook 峰值，且幂等键把"重投"变成安全操作。手写 DDL 与纯 JDK 签名保持依赖面最小（09§7 白名单只新增 Spring Boot web/JDBC 与 PG 驱动）。
- 后果：
  - `cra-server` 行自 M4b 生效（10§2 矩阵），R6/R1/R3 与源码扫描同步扩展；
  - 服务端无 checkout 时 `LocalRepoContext.localRepoRoot()` 为空，pre-existing 检测按 05§2.1 降级声明；
  - orchestrator 按仓库缓存（`ReviewPipeline` 持有固定线程池，逐 job 新建会泄漏线程）；仓库数上限应按部署规模评估；
  - 多副本时 delivery 幂等由 PG 兜底，但队列/orchestrator 缓存是进程内的；
  - **签名局限**：证明"持有服务端密钥者签发了该报告摘要"并绑定已知 commit，不证明报告内容由服务端执行生成；服务端托管执行评测是更强的后续形态（可复用本 ADR 的 `cra-server` 与签名密钥）。
- 替代方案：外部 broker（运维成本与个人项目不匹配）、Flyway（版本少且全加法，收益低于新依赖）、非对称签名/HSM（CI 侧分发公钥更安全，但密钥管理与轮换复杂度超出 M4b 范围；HMAC 已满足"作者无法伪造"的目标）、Actuator（自定义两个健康端点即可覆盖 docker healthcheck，避免额外依赖）。
