# ADR-0013 GitLab 平台接线（CLI/server 平台选择与 webhook）

- 状态：accepted（2026-10-27，M4e 第二半；真实 GitLab 写操作因无 owner token 仍为 pending）
- 背景：ADR-0011 已交付 `cra-git-gitlab`（`GitPlatformAdapter` + `CommentSource`，不依赖 `cra-git-github`），但 CLI 与 server 仍固定装配 GitHub。11§6 的接入 checklist 要求"新平台能被组合根选择，且平台差异显式声明"；直接照抄 GitHub 装配会把两平台 token/仓库/验签语义混在一起。需要在不改 SPI 签名、不改账本格式、不引入实现模块横向依赖的前提下完成接线。
- 决策：
  1. **平台选择发生在组合根，而不是 SPI 或配置中心**。CLI：`--platform github|gitlab` > `CRA_GIT_PLATFORM` > `github`；server：`CRA_SERVER_PLATFORM=github|gitlab`（默认 `github`，非法/大写值 fail fast）。`cra review` 与 `cra sweep` 共用解析；两个实现模块互不依赖，未选中的平台不构造、不读取其 token。
  2. **GitLab 连接参数沿用 GitLab CI 变量**：`--gitlab-project group/project` > `CRA_GITLAB_PROJECT` > `CI_PROJECT_PATH` > `--repo`（嵌套 group 按最后一个 `/` 拆分 owner/name）；`--pr` > `CI_MERGE_REQUEST_IID`；`--base` > `CI_MERGE_REQUEST_DIFF_BASE_SHA`；`--head` > `CI_COMMIT_SHA`；`--gitlab-base-url` / `CRA_GITLAB_BASE_URL` 接受实例根或 `/api/v4` 根并归一；`CRA_GITLAB_BOT_USERNAME` 作为 `/user` 不可读时的 bot 身份回退。
  3. **GitLab token 只从环境或密钥文件读取**：`CRA_GITLAB_TOKEN` 优先，其次 `CRA_GITLAB_TOKEN_FILE`（内容去空白、错误信息不含内容）。GitLab 模式显式拒绝 `--token`（该参数仅 GitHub），避免跨平台串用密钥；`GITHUB_TOKEN` 在 GitLab 模式不被读取。
  4. **账本键与标记格式不变**：仍为 repo + pr/commit（05§1.3），平台只替换取数端点；`StoreAssembly.createGitHubLedger`（历史命名）继续消费平台无关的 `CommentSource`，因此 GitLab 模式不需要新的账本实现。
  5. **本地 pre-PR 形态保持平台中立**：`--local` 使用 jgit + SQLite，不构造远程 adapter、不要求 GitLab token；`LocalAppConfig` 不引入平台分支（本地形态没有"远程平台"这一事实源）。
  6. **server 按平台注册端点**：GitHub 模式只注册 `/webhooks/github`（`X-Hub-Signature-256` HMAC），GitLab 模式只注册 `/webhooks/gitlab`（`X-Gitlab-Token` secret token，SHA-256 摘要后常数时间比较）；非当前平台端点不存在（404），避免"端点存在但用错平台 adapter 入队"。`CRA_SERVER_WEBHOOK_SECRET` 复用为平台对应的 webhook 密钥。
  7. **GitLab MR 事件显式映射**：`open`/`reopen`/带新提交的 `update`（`oldrev` 非空）→ 评审（归一化 `opened`/`reopened`/`synchronize`）；`update` 无 `oldrev` → `metadata_update`；`merge`/`close` → `merged`/`closed`；审批类 → `approval_event`；draft（`draft`/`work_in_progress`/标题前缀）→ `draft`；其他 → `unsupported_event`/`unsupported_action`/`unsupported_object_kind`。忽略一律 200 + `reason`（GitLab 对非 2xx 可能重投），不静默假装支持。
  8. **delivery 幂等**：优先 `X-Gitlab-Event-UUID`（GitLab 16.1+），缺失时用 `SHA-256(event + "\n" + raw body)` 前 40 位十六进制。同一 payload 重投命中同一 `webhook_delivery`，不二次入队；成功后重复投递 200 duplicate，队列满 503 并释放占位。
  9. **失败语义与安全**：验签在 JSON 解析之前；缺 `X-Gitlab-Event`/非法 JSON/缺 `diff_refs.base_sha`、`head_sha`、`project.path_with_namespace`、`iid` → 400；token 不写日志/仓库；错误信息只含路径/事件名，不含 payload 与密钥。
  10. **门禁影响**：本变更不改 SPI 签名、账本标记格式、门禁阈值、expected/数据集，也不改 `LocalAppConfig`/`LlmConfigFactory`/`cra-core`/`cra-llm`/prompts；按 04§2.4 不触发 L2 报告要求（PR CI 的 L1 仍照常跑）。
- 理由：平台选择是部署事实，放在两个组合根最直观；实现模块互不依赖（11§4 第 4 条）由 ArchUnit R4 继续守护，CLI/server 对 `git-gl` 的依赖已在 10§2 矩阵中预登记。复用 `CRA_SERVER_WEBHOOK_SECRET` 而不是新增平台专用 env，减少部署面；用 `X-Gitlab-Token` 的常数时间比较符合 GitLab 的共享密钥模型（它不是 HMAC）。忽略事件返回 200 + reason 既满足 GitLab 的重投语义，也满足 11§6"显式声明而非假装支持"。
- 后果：
  - `cra-runner-cli` / `cra-server` 新增 `cra-git-gitlab` 依赖（10§2 已允许）；两个平台实现仍互不依赖；
  - CLI 平台选择 16 例、平台接线 1 例（WireMock 端到端到 DONE 闸）、server 平台/解析/验签 26 例、GitLab webhook HTTP+PG 集成 5 例；
  - 真实 GitLab **读**路径已对 `gitlab-org/cli` 公共项目验证（compare/tree/raw file）；**写操作 pending**（无 owner token），清单见 `docs/deployment-gitlab.md` §12；
  - draft MR 转正不会自动触发（GitLab 无 `ready_for_review` 事件；移除前缀通常只是 metadata update），需要在下次 push 或手动运行时评审——已写入部署限制；
  - GitLab 端点的 `X-Gitlab-Token` 与 GitHub HMAC 共用 `CRA_SERVER_WEBHOOK_SECRET`，部署文档必须按平台解释其含义；
  - `ServerConfig` 新增平台字段；`CraConfig` 追加 `platform` + `GitLabSettings` 组件（保留全部旧构造器重载，测试与 eval 侧无破坏性改动）。
- 替代方案：
  - **两个端点都注册、在控制器内按平台拒绝**：端点存在会诱导误配 webhook，且错平台事件可能入队；拒绝。
  - **在 `cra-common` 新增平台枚举**：只是一层配置字符串，新增跨模块公开类型需独立 ADR；CLI/server 各自校验足够。
  - **GitLab 也用 HMAC**：GitLab webhook 的 Secret token 是直传共享密钥，不是 HMAC；强行 HMAC 会让真实 webhook 全部 401。
  - **改 `LocalAppConfig` 支持远程平台选择**：本地形态没有远程 adapter/`CommentSource`，加分支只会制造"平台已选但未使用"的假象；拒绝。
