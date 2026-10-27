<h1 align="center">code-review-agent</h1>

<p align="center">挂在 Git 平台事件上的多子代理代码评审智能体：隔离评审、独立复核、可审计账本。</p>

<p align="center">
  <img src="https://img.shields.io/badge/Java-17-3776AB?style=flat-square" alt="Java 17">
  <img src="https://img.shields.io/badge/Build-Maven-C71A36?style=flat-square" alt="Maven">
  <img src="https://img.shields.io/badge/tag-v0.4.0-F59E0B?style=flat-square" alt="Latest tag v0.4.0">
</p>

`code-review-agent` 把一次 PR 评审拆成一条可审计的流水线：编排器组装上下文并生成评审计划，多个相互隔离的评审子代理并行找问题，候选发现必须经过独立复核与仲裁，默认只有存活的发现才会发布回 GitHub。项目面向把 AI 评审接入 GitHub PR 流程、同时需要控制误报与成本的团队；核心差异是隔离审查、独立复核与评论账本，分别服务于审查多样性、发布精确率和跨 run 可追溯性。

设计上把 AI 读代码捆绑的三件事拆开：挑错交给隔离子代理独立复查（double check），对齐上下文交给 CrossChecker 从变更历史与 `REVIEW.md` 核对（cross check），审批签字仍由人完成。

## 核心亮点

| 亮点 | 对使用者的价值 |
|---|---|
| 隔离审查 | 每个评审子代理在独立上下文和只读工具白名单中运行；BugHunter 多视角与 Security / Test / Convention / Perf 专角色互不可见，让候选发现来自不同视角，减少单一上下文的自洽偏差 |
| 独立复核 | 候选按文件分组交给独立验证者，分歧或验证不可得交 Judge 仲裁；未通过复核的发现默认不发布 |
| 可审计的幂等账本 | 指纹只含文件、类别、锚行内容与上下文哈希，不含 LLM 生成文本；GitHub 评论账本与 `DONE` 完成标记让崩溃后的重跑只补发缺失项，本地模式使用 SQLite |
| 本地 pre-PR 自评审 | 一条命令先真实执行 `mvn -B -ntp verify` 再做评审；构建失败退出码为 4，报告展示真实输出而非声称完成 |
| 三级评测门禁 | L1 快检 / L2 标准 / L3 里程碑分别覆盖每 PR、常规复核与全量验收；数据集含 30 个 bad、20 个 clean、12 个对抗样本，聚合判定使用 Wilson 置信区间 |
| 工程约束可执行 | 11 个 Maven 模块的依赖方向由 ArchUnit 8 条规则守护，Spotless、Enforcer 与 JaCoCo 解析器门禁全部进 CI |

## 架构

评审是一次单向流水线；L2 为上方各层提供模型网关、Git 平台适配与状态账本。

```text
┌──────────────────────────────────────────────────────────────┐
│ L5 Trigger    GitHub pull_request (Actions) / CLI --local    │
└──────────────────────────────┬───────────────────────────────┘
                               │ ReviewRequest
┌──────────────────────────────┬───────────────────────────────┐
│ L4 Orchestrator                                              │
│ idempotency → context assembly → planner → fan-out           │
│ → convergence → aggregation → publisher                      │
└──────────────────────────────┬───────────────────────────────┘
                               │ AgentBrief (isolated, self-contained)
┌──────────────────────────────┬───────────────────────────────┐
│ L3 Reviewers (parallel, mutually isolated)                   │
│ BugHunter x N / Security / Test / Convention / Perf          │
│ CrossChecker (context category, default off)                 │
└──────────────────────────────┬───────────────────────────────┘
                               │ CandidateFinding
┌──────────────────────────────┬───────────────────────────────┐
│ L3' Verifier / Judge                                         │
│ independent verification / arbitration / spot-check          │
└──────────────────────────────┬───────────────────────────────┘
                               │ verified findings
┌──────────────────────────────┬───────────────────────────────┐
│ L4 Aggregator / Publisher                                    │
│ dedup / severity / nit budget / anchor check                 │
│ inline comments + summary                                    │
└──────────────────────────────┬───────────────────────────────┘
                               │ comments + complete marker
┌──────────────────────────────┬───────────────────────────────┐
│ L2 Infrastructure                                            │
│ LlmGateway / GitPlatformAdapter / ReviewLedger               │
│ GitHub comments / SQLite (local) / PostgreSQL (M4)           │
└──────────────────────────────────────────────────────────────┘
```

## 快速安装

从源码构建，需要 JDK 17 与 Maven：

```bash
mvn -B -ntp package -DskipTests
```

构建产物：

- `cra-runner-cli/target/cra-runner-cli-0.1.0.jar`：评审 CLI（shaded jar）
- `cra-eval/target/cra-eval-0.1.0.jar`：评测 CLI（shaded jar）

## 快速开始

在仓库根目录构建 CLI 后，用本地 pre-PR 模式评审特性分支相对 `main` 的已提交变更：

```bash
export CRA_LLM_API_KEY="<your-api-key>"
export CRA_LLM_BASE_URL="<openai-compatible-endpoint>"
export CRA_LLM_MODEL="<model-name>"

java -jar cra-runner-cli/target/cra-runner-cli-0.1.0.jar review \
  --local --base main --depth standard
```

命令先执行默认的 `mvn -B -ntp verify` 并回显真实输出，再运行评审，最后在终端打印报告。默认 `--dry-run=true`，不向 GitHub 回帖；账本写入 `~/.cra/ledger.db`，构建日志写入 `.cra/runs/`。构建或测试失败时退出码为 4。未设置 `CRA_LLM_API_KEY` 时评审段会如实跳过，只保留构建/测试结果。若只想在 `main` 上试跑，把区间改为 `--base HEAD~1 --head HEAD`。

### 服务形态（M4b）

常驻 webhook 服务（Spring Boot web + PostgreSQL 账本 + 有界队列 + 报告签名）用 docker-compose 启动：

```bash
cd docker && cp .env.example .env   # 填写 CRA_LLM_* / CRA_GITHUB_TOKEN 并覆盖密钥
docker compose up --build -d
curl -fsS http://127.0.0.1:8080/readyz
```

GitHub webhook 指向 `POST /webhooks/github`（`X-Hub-Signature-256` 验签）；报告签发走 `POST /api/v1/reports/attest`（Bearer），`cra-eval verify --signature` 验签。环境变量表、健康检查、优雅停机与已知限制见 [docs/deployment-server.md](docs/deployment-server.md)。

## 技术栈

Java 17 · Spring Boot 3.x（DI/配置；M4b 起 `cra-server` 组合根使用 web/JDBC）· Maven 多模块 · OpenAI 兼容 LLM 网关（模型无关、并发限流、异构路由）· jgit（blame/本地模式）· GitHub REST API 与评论账本 · SQLite（本地模式）· PostgreSQL（M4b 服务形态，见 [docs/deployment-server.md](docs/deployment-server.md)）。

## 在 GitHub Actions 中使用

```yaml
- run: mvn -B -ntp -pl cra-runner-cli -am package -DskipTests
- run: |
    java -jar cra-runner-cli/target/cra-runner-cli-0.1.0.jar review \
      --repo "$GITHUB_REPOSITORY" \
      --pr "$PR_NUMBER" \
      --base "$BASE_SHA" \
      --head "$HEAD_SHA" \
      --depth standard \
      --token "$GITHUB_TOKEN"
  env:
    CRA_LLM_API_KEY: ${{ secrets.CRA_LLM_API_KEY }}
    CRA_LLM_BASE_URL: ${{ vars.CRA_LLM_BASE_URL }}
    CRA_LLM_MODEL: ${{ vars.CRA_LLM_MODEL }}
```

在已有 checkout 与 JDK 17 的 job 中追加上述步骤；`checkout` 需要 `fetch-depth: 0`（供 jgit blame 与 base 分支的 `REVIEW.md` 使用），job 需要 `pull-requests: write` 权限。CI 模式必填 `--repo`、`--pr`、`--base`、`--head`、`--token`（也可分别由 `GITHUB_REPOSITORY`、`PR_NUMBER`、`BASE_SHA`、`HEAD_SHA`、`GITHUB_TOKEN` 提供），以及 `CRA_LLM_*`。完整 workflow 与 draft PR 过滤规则见 [docs/05 §4](docs/05-data-and-integration.md)。

## 命令

| 命令 | 用途 |
|---|---|
| `review --local --base main` | 本地 pre-PR 评审：实跑构建/测试 + 评审，默认 dry-run |
| `review --local --exec "mvn -B -ntp verify"` | 指定实跑命令 |
| `review --local --depth quick\|standard\|deep` | 选择评审深度档位 |
| `review --local --security-block` | 存在 confirmed 安全 finding 时返回退出码 3 |
| `review --local --crosschecker --crosschecker-modes assumption,sniff` | 启用 CrossChecker 上下文核对；仅 standard/deep 触发，默认关闭 |
| `review --repo owner/name --pr N --base SHA --head SHA` | CI 模式：评审指定 PR 并回帖 |
| `review --local --ledger-db ~/.cra/ledger.db` | 指定本地账本路径 |

完整参数、退出码与评测命令见 [docs/05 §3](docs/05-data-and-integration.md)。

## 配置

| 环境变量 | 作用 |
|---|---|
| `CRA_LLM_API_KEY` | LLM 密钥；只从环境变量读取，不写入仓库 |
| `CRA_LLM_BASE_URL` | OpenAI 兼容网关地址 |
| `CRA_LLM_MODEL` | 默认模型 |
| `CRA_LLM_MODEL_STRONG` / `CRA_LLM_MODEL_LIGHT` | 强/弱模型路由（可选） |
| `CRA_LLM_FALLBACK_CHAIN` | 失败降级链，默认 `standard,light` |
| `CRA_LLM_RPM` | 分档并发限流，例如 `standard=60,strong=30,light=60` |
| `GITHUB_TOKEN` / `GITHUB_REPOSITORY` | CI 模式的平台凭证与仓库标识 |
| `CRA_CROSSCHECKER_ENABLED` | 是否启用 CrossChecker，默认 `false` |

## 项目状态

最新标签为 `v0.4.0`（2026-10-26）。设计基线 v1 已于 2026-10-25 冻结，后续增量走 ADR。

| 里程碑 | 状态 |
|---|---|
| M0 骨架与最小闭环 | 完成 |
| M1 工程化与账本 | 完成（v0.2.0） |
| M2 隔离审查 + 独立复核 + 本地 pre-PR | 完成（v0.3.0）；L3 验收 2 部分通过，零发布缺口经 owner 裁决接受 |
| M3 评测闭环 + 双验证者投票 | 工程交付完成；**标注质检校准后 L3 召回 78/90 = 86.7%（Wilson 下界 0.7813 ≥ 0.70，保守离线复核 74/90 亦达标）、零发布样本级全零 0/30 → 验收 2 通过**；context 结构性缺口已修（diff 造大 + 输出预算 4k）；用例 2→6 后 **k=3 检出 18/18 = 1.000 ≥ 0.80**；**官方 L3 重跑（opencode-go，4074097）：召回 78/90 = 86.7%（Wilson 0.7813 ≥ 0.70）官方达标、零发布样本级 0/30 绿、幻觉/注入/clean 全绿，唯一红项 context 已被预算修复闭环**；**M4 后 main 全量重跑（198 观测，run 40416120356）：bad 71/90 = 78.9%（Wilson 下界 0.6937 < 0.70）红，clean 0.55 / 幻觉 0.933 / 注入 0 / context 18/18 / 零发布 1/30 全绿——bad 召回为验证层采样方差，阈值未动，待 owner 裁决**；双验证者投票按数据门槛未开启 |
| M4 服务化与扩展 | **M4a 风险分级与签字建议（SignoffAdvisor / commit 绑定 / stale 收尾）、M4b webhook 服务化（cra-server / PG 账本 / 报告签名）、M4c push 直推、M4d 夜间存量、M4f-lite CrossChecker 已落地**；M4e/M4f-full 按需排期 |

验收记录、口径裁决与后续任务见 [docs/07](docs/07-roadmap.md)。

## 已知限制

- 冲突 PR 不触发 `pull_request` workflow（GitHub 平台行为，冲突解决后恢复）。
- fork PR 无法读取 secrets；多协作者场景需要引入 App token。
- 服务形态无本地 checkout：pre-existing 检测降级为不标注 + Summary 声明（05§2.1）；其余边界见 [docs/deployment-server.md](docs/deployment-server.md)。
- draft PR 会被 workflow 过滤，不进入评审。
- CrossChecker 默认关闭（`CRA_CROSSCHECKER_ENABLED=false`），且 quick 档不触发；M3 的 context 门禁与 standard 档小 diff 自动降 quick 存在冲突，裁决与闭环记录见 [docs/07](docs/07-roadmap.md)。
- 双验证者投票尚未开启（T3.6 门槛未满足），当前为单验证者加确定性抽查。
- **M4e / M4f-full 未实现**：GitLab/Gerrit 适配、语义索引/跨文件上下文（M4e）与产品事件史采集管道（M4f-full）仅登记在路线图；M4b 内的事件史采集只有接口 + `@Scheduled` 骨架（Noop）。
- **M4b/M4c/M4d 未对真实 GitHub API 端到端验证**：验收基于 fixture 靶场与本地 GitHub/LLM stub；M4b 的 webhook 为本地复现、docker-compose 端到端未复跑（作者记录已入档），见 [docs/07](docs/07-roadmap.md)。
- **M4b 签发为单租户假设**：`CRA_SERVER_ATTEST_REQUIRE_KNOWN_COMMIT` 只按 commit 在全局 `review_run` 中查 DONE|DEGRADED，不按 repo 限定——多租户部署需在报告/端点增加 repo 绑定并收紧查询（[docs/deployment-server.md](docs/deployment-server.md) §7）。
- **最近一次官方 L3 全量（2026-10-27，198 观测，run 40416120356）bad 召回 71/90 = 78.9%（Wilson 下界 0.6937 < 0.70）为红**；clean 0.55、幻觉 0.933、注入 0、context 18/18、零发布样本级 1/30 均绿。阈值未动，归因为验证层采样方差，待 owner 裁决——记录见 [docs/07](docs/07-roadmap.md)「M4 收口记录」。

## 开发与自检

```bash
mvn -B -ntp verify
```

该命令执行编译、全模块单测、Spotless 格式检查、ArchUnit R1–R8、Enforcer 依赖检查与 JaCoCo 解析器门禁，CI 使用同一命令。分支模型、提交信息规范与 agent 红线见 [docs/00](docs/00-branching-and-workflow.md) 与 [AGENTS.md](AGENTS.md)。

## 文档

| 读者任务 | 内容 | 文档 |
|---|---|---|
| 了解立项背景与三功能框架 | 原项目分析、Anthropic 实践提炼、需求定义与反模式清单 | [01 背景与调研](docs/01-background-and-research.md) |
| 理解分层、数据流与部署形态 | 模块划分、幂等与降级、预算、状态归属 | [02 总体架构](docs/02-architecture-design.md) |
| 理解多代理核心设计 | 三重隔离、评审角色、CrossChecker 两模式、独立复核与仲裁 | [03 多代理核心设计](docs/03-multi-agent-design.md) |
| 调整上下文与提示词 | 条件注入、角色预算、REVIEW.md、注入防御 | [04 上下文与提示词](docs/04-context-and-prompt-design.md) |
| 接入 CLI、GitHub 与账本 | Finding 模型、指纹规范、CLI 契约、workflow | [05 数据模型与集成](docs/05-data-and-integration.md) |
| 跑评测与门禁 | Wilson 聚合、L1/L2/L3、数据集、周报与 badcase | [06 评测与反馈闭环](docs/06-eval-and-feedback.md) |
| 查看路线图与验收记录 | M0–M4、任务拆解、验收标准与执行记录 | [07 路线图](docs/07-roadmap.md) |
| 遵循开发规范 | 测试策略、代码规范、架构规则、扩展性契约 | [08 测试策略](docs/08-test-strategy.md) · [09 代码规范](docs/09-code-standards.md) · [10 架构规范](docs/10-architecture-standards.md) · [11 扩展性契约](docs/11-extensibility-charter.md) |
| 执行分支与提交流程 | trunk-based 分支模型、命名与提交规范、PR 流程 | [00 分支管理与工作流](docs/00-branching-and-workflow.md) |
