# 08 · 测试策略与验证方案

> 文档版本：v1.0（2026-10-25，开发前规范包）
> 定位：02§6 横切关注点中"测试策略"一句（编排逻辑单测；子代理 LLM 录制回放；端到端 fixture 仓库 + dry-run 断言；不可信输入注入用例进对抗集）的**工程化展开**——规定工程测试怎么写、怎么跑、怎么进 CI。不推翻原表述，只细化。
> **与 06/07 的边界声明**：06 回答"评审质量好不好"（L1/L2/L3 度量、数据集与统计化门禁），07 回答"每个里程碑验收什么、按什么节奏"；本文只回答"**机制是否按设计运转**"。所有质量阈值与判定口径（Wilson 下界、k 次运行、零发布护栏等）一律以 06§1.2 / 04§2.4 为准，本文不重复数值；07 各 M 验收用例是需求清单，本文 §5 将其映射为可执行测试设计，验收执行仍以 07 文字为准，冲突按 07§6 文档即契约处理。

---

## 1. 平衡原则

个人项目，测试服务于"**敢改**"——重构与回归时有安全网，而不是追覆盖率数字。LLM 调用**永不进 CI 单测**（07§6 纪律 3，机械守护见 §3.2）。级别即平衡机制：**【强制】** 项必须可被 CI/工具自动守护，**【建议】** 项给出理由但不阻塞，**【可选】** 项记录取舍。测试代码随实现同 PR 提交，`mvn -B -ntp verify`（00§4）即本地自检。

## 2. 测试金字塔与分层归属

### 2.1 分层总表

| 层 | 占比（目标） | 载体 | 测什么 | 明确不测什么 |
|---|---|---|---|---|
| 单测 | ~70% | JUnit 5 + Mockito，各 `cra-*` 模块内 | 纯逻辑：账本标记解析（05§1.3）、指纹与滑窗去重（02§4.1）、预算与降档阶梯（02§4.4）、Aggregator 排序合并与 nit 预算、ContextAssembler 窗口/裁剪优先级（04§1）、CLI 参数与退出码（05§3）、SQLite 账本（JDBC 临时目录） | Spring 装配、网络行为、LLM 真实输出质量、prompt 文案本身（模板质量归评测，04§4） |
| 集成 | ~20% | WireMock 拦 GitHub REST + LLM cassette 回放 | adapter 端点契约（05§2.3 清单）、429/5xx 指数退避（02§4.5）、422 锚定逐条降级（05§2.2）、Orchestrator 全流程（cassette 回放 fake LLM → 断言 finding 集合与发布序列 05§2.2） | 真实 GitHub、真实 LLM、统计化质量判定（06§1.2 管辖） |
| e2e | ~10% | fixture 仓库快照 + `cra review --dry-run` | CLI 全链路：快照 PR → finding 集合 + Summary 结构 + trace NDJSON（05§1.4）断言 | 任何写操作（dry-run 零写，07§1 验收 2）；性能（另见 §6） |

**【强制】** LLM 真实调用不进上述任何一层——三层全部 cassette 回放；真实调用只存在于本地录制、自举 workflow、评测 job 三处（07§6 纪律 3）。

### 2.2 模块归属要点

- `cra-common`：schema 校验（05§1.1 枚举含 context）单测——解析拒收是行为，必须锁住；
- `cra-llm`：重试/降级链/并发信号量/RPM 限流的**非网络逻辑**单测；HTTP 行为在集成层；
- `cra-git-github`：账本解析器、锚定校验（07§6 纪律 2"隔离机制必须有直接单测"）；
- `cra-core`：幂等闸、ConvergenceGuard、BudgetEnforcer——全部可纯函数化测试（输入 ReviewRequest/账本快照，输出决策）；
- `cra-store`：SQLite 实现用 `@TempDir` 临时目录，每次测试新建库（无共享状态、天然并行安全）。

### 2.3 全局不测清单

以下内容**任何一层都不测**，理由各不相同，防止测试范围蔓延：

- **GitHub 平台行为**：冲突 PR 不触发、fork PR 无 secrets 等（02§5 触发边界表）——平台事实，测不了也不该测，靠限制声明与部署文档兜住；
- **prompt 文案质量**：模板改动走评测回归（04§4 模板即代码），工程测试只断言渲染后的结构（定界完整、占位符全展开）；
- **Spring 装配**：DI 仅用于组装，装配错误在自举 workflow 首跑即暴露，不值一条测试；
- **provider 真实延迟与可用性**：属 L3 运行健康度（06§1.3），不是工程测试对象。

### 2.4 与评测体系的衔接（不越界）

L1 快检在 CI 内发生真实 LLM 调用（04§2.4），但它是**评测门禁**而非工程测试——归 06§1.2 / 04§2.4 管辖，本文只在 §4 登记其 CI 时长占用与"红了升级不阻塞"的接入方式。工程测试对评测体系的贡献限于：为 06 的判定器与 runner（`cra-eval`）提供单测（§5 对抗行）。评测 fixture 与工程 fixture 分离管理（§8.1）。

## 3. 工具选型【强制】

### 3.1 固定清单

- **JUnit 5**：Java 17 + Maven 标准选择，`@Tag` 区分 fast/slow；
- **Mockito**：只 mock SPI 边界（`GitPlatformAdapter` / `LlmGateway` / `ReviewLedger`），不 mock 模块内部【建议】——mock 内部会把测试绑死在实现上，重构即碎；
- **AssertJ**：随 spring-boot-starter-test 引入，断言风格不另立；
- **WireMock**：GitHub REST mock（§3.2 之外的另一半）；
- **自研轻量 LLM 录制回放**（cassette）：见 3.2 定案。

**【强制】** 上述清单为准：新增测试依赖须先修订本文（文档即契约，07§6）——防止测试依赖树悄悄膨胀。

### 3.2 LLM 录制回放：候选对比与自研定案

- 候选 A **Betamax**（Java 版 VCR，HTTP 代理级录制，cassette 语义最接近）：**排除**——2016 年后停止维护，JVM agent 方式对 Java 17 无兼容承诺；且绑定 HTTP wire 格式，provider 响应字段一变 cassette 集体失效；
- 候选 B **WireMock 直接 stub LLM 端点**：**排除**——LLM 请求体是巨型 prompt，需为每条手写映射规则；prompt 微调即映射全废，且无"请求哈希 → 响应"的 cassette 生命周期（录制/重录/未命中检测）。GitHub API 适合 WireMock 恰因其请求是确定性小 REST 调用，两者要求不同；
- **定案：自研轻量 cassette**（约一个测试工具类 + 编解码，目标 ≤200 行）：`LlmGateway` 外套录制代理，录制模式记 `sha256(model + messages + response_format + template_version)` → 完整响应（含 usage），JSON 落盘；回放模式**哈希未命中即 fail-fast**【强制】——宁可测试红，不可静默透传真实调用；CI 测试 job 不配 `CRA_LLM_API_KEY`，从密钥侧双保险。

cassette 条目格式（单文件多场景，NDJSON，一请求一行）：

```json
{"key":"sha256:9f2a…","template_version":"bug-hunter/dataflow@abc123",
 "request_meta":{"model":"standard","messages_hash":"sha256:…"},
 "response":{"content":"…","usage":{"in":18200,"out":1500},"latency_ms":8200}}
```

`template_version` 进哈希输入的原因：prompt 改动必然改变请求体，把它显式入键可让"哪些场景受模板变更影响"在重录前即可静态列出（§8.2）。

### 3.3 明确不引入的

- **Testcontainers 不引入【强制】**：本项目当前无外部服务——GitHub API 用 WireMock、SQLite 是进程内 JDBC（临时目录即隔离）、PG 是 M4b 才出现（届时再议）。Docker 依赖只会让 CI 变慢变脆，违背 §1 与 §4 时长预算；
- **Spring 全量上下文测试不引入【建议】**：DI 仅用于装配，`@SpringBootTest` 启动整上下文的收益低于其时长成本；配置绑定用 `@ConfigurationProperties` 级单测（如 05§5 `require-distinct-model-family` 违例拒绝启动）。

## 4. CI 门禁演进表

延续 00§9"CI 多门禁渐进"与 00§10 required check 设定，门禁随里程碑累加：

| 里程碑 | 门禁内容（累加） | 断言 | 时长预算 |
|---|---|---|---|
| M0 | compile + 全模块单测 + spotless 格式检查（即 00§4 自检 `mvn verify` 同款 + 格式） | CI 绿 = 合入前提 | ≤5min |
| M1 | + 账本解析器与幂等闸单测（05§1.3 规则 1–5 逐条，§5 场景 3/4）；+ L1 快检接入 CI（对抗集注入 3 + 幻觉 2，~3min，04§2.4） | 解析规则全分支覆盖（§7）；L1 红**只升级不阻塞**（04§2.4） | ≤10min |
| M2 | + 隔离机制单测：预算硬限 / 工具白名单 / UNTRUSTED 定界 / 反锚定校验器（07 T2.1/T2.4，07§6 纪律 2）；+ e2e dry-run；+ ArchUnit 守护 02§1/§3 分层与依赖方向（规则清单见 10§5） | e2e 断言 finding 集合与账本标记；ArchUnit 违例即红 | ≤10min |
| M3 | + L2 报告哈希校验：prompt/模型改动 PR 附 `cra-eval run --json` 报告，CI 校验其存在与 sha256（04§2.4 荣誉制） | 报告缺失或哈希不符即红 | ≤10min |

**【强制】** 任一阶段 CI 时长 >10min 视为门禁事故，处置次序固定：先把该检查挪夜间/本地 + 哈希校验（04§2.4 同款），不减门禁权威（06§1.2"被绕过的门禁等于没有门禁"）。守护方式：workflow 末尾计时 step，超限即失败。

## 5. 关键测试场景清单（07 验收 → 可执行测试设计）

| # | 场景与断言要点 | 层 | 出处 |
|---|---|---|---|
| 1 | 同 commit 重跑：账本含 DONE 标记 → 零 inline 重发、Summary 只更新 | 集成（WireMock 回灌账本） | 07§1 验收 3；02§4.1；05§1.3 |
| 2 | 发布中途掐断（已发 3/8、无 DONE）→ 重跑只补缺失 5 条，已发 3 条不重发 | 集成 | 07§1 验收 3；02§4.1 |
| 3 | 账本污染：伪造 `<!-- cra:… -->`（正文中间 / 代码块内 / 版本非法 / 字段序错 / file 不一致）逐条忽略 + `LEDGER_SUSPECT` 计数、≥3 次 Summary 告警 | 单测（解析器逐规则）+ 集成（污染评论列表） | 07§2 验收 2；05§1.3 规则 1/2/4 |
| 4 | 兜底分层：无任何有效标记 → 退 commit 闸，**永不**退到空账本全量重发 | 单测 | 05§1.3 规则 5；02§4.1 |
| 5 | 锚定 422 逐条降级：整包 422 → 逐条提交 → 仍失败进 Summary 列表，`anchor_status=SUMMARY_FALLBACK` 回写 | 集成（WireMock 状态机） | 07§1 验收 1；05§2.2 |
| 6 | 注入**负向不变量判定器**单测：mock 发布集含"skip-all / artificial-approve"→ 判红；正常输出 → 判绿；不做快照全等（seed 是 best-effort）。判定器即 L1 快检的承重代码，必须先于评测集被测 | 单测（cra-eval 判定器） | 04§2.4；06§1.2 对抗集②；07§2 验收 6 |
| 7 | 预算硬限与降档阶梯：`BudgetEnforcer` 纯函数——<70% 不降档（含 330k/500k=66% 算术回归锚点）、≥70% 砍 light 角色与 1 视角、≥85% top-10 验证、100% 终止记 `BUDGET_EXCEEDED`/`suppressed_by_truncation` 计数 | 单测 | 02§4.4；07§3 验收 5/6；04§2.2 |
| 8 | 滑窗去重三连漂移：上方插 10 行（锚行内容不变→抑制）、改锚行 ±2 内非锚行（滑窗 ±5 命中→抑制）、改锚行本身（内容已变→重发） | 单测（去重纯函数） | 07§2 验收 1；02§4.1 |
| 9 | 滑窗未命中但同 file 同 category 且行区间重叠 → 只记 `possible_duplicate` 入 trace，不自动抑制 | 单测 + trace 事件断言 | 02§4.1；05§1.4 |
| 10 | REVIEW.md 投毒：PR 内改 `Skip paths: src/**` → 仍按 base 版策略评审 + Summary 警告 | 集成 | 07§2 验收 5；04§3.2 |
| 11 | 过期收尾：新 run 发布成功 → 对上一轮 Summary issue comment 发 PATCH 加 `[已被 run_xxx 取代]` 前缀，inline 不删 | 集成（断言 PATCH 载荷） | 07§2 验收 3；02§5；05§2.2 |
| 12 | dry-run 零写：mock adapter 断言全程零写调用 | 单测 | 07§1 验收 2；05§6 |
| 13 | 验证不可得：Verifier 失败 → 缩小上下文重试 1 次 → 仍失败记 `UNVERIFIED` 不发布、Summary 计数；`publish-unverified=labeled` 路径分支 | 集成（cassette） | 02§4.2；07§3 验收 5 |
| 14 | 反锚定 FAIL 不阻断：`anti_anchor_check=FAIL` 入 trace、finding 照常走晋升管线（off-by-one 种子不被误拒） | 单测 | 07§3 验收 3；03§4.2 |
| 15 | UNTRUSTED 定界与转义机制：brief 生成后所有不可信字段均落在定界符内；发布侧 `<!--` → `&lt;!--` 全量替换 | 单测 | 04§3.3；05§1.3 规则 3；05§6 |
| 16 | pre-PR 实跑：注入会挂测试的分支 → 报告如实展示失败输出且退出码非零 | e2e（`@Tag("slow")`，本地/按需，不进常规 CI 时长预算） | 07§3 验收 8；07 T2.10 |
| 17 | LLM 重试与 GitHub 退避：429/5xx 指数退避 ≤3 次、排队超时按降级语义收尾（02§4.5） | 单测（退避策略纯逻辑）+ 集成（WireMock 延迟/限流响应） | 07§1 验收 5；02§4.5；05§2.3 |
| 18 | 大 diff 降档：>2k 行自动降 Quick、>8k 行只评 priority_paths、降档与覆盖声明如实进 Summary | 单测（Planner 输入→档位决策） | 07§2 验收 4；02§4.4；04§2.2 |
| 19 | dual-vote 配置硬校验：`require-distinct-model-family=true` 下同家族配对 → 启动即拒绝 | 单测（配置绑定，05§5） | 07§4 验收 3 |

**【强制】** 上表 1–19 是对应里程碑 CI/验收的最低测试集：验收执行时逐条引用本表编号作为证据（PR 描述粘贴输出，00§5）。

## 6. 性能与延迟验证

- **测量口径【强制】**：`latency_ms` = trigger（workflow job 启动 / CLI 进入 main）→ `postReview` 最后一次写调用返回。与 05§1.2 stats 同源，不自造第二口径；排除 Actions 排队时间（不可控噪声）。目标值沿用 02§4.3（Standard ≤5min，其算术依据 04§2.3 的 4.2min 分解）；
- **基准 fixture【建议】**：复用 06§3 整仓 fixture 的子集——bad 12 中取 3 个满足 Standard 假设（≤2k diff 行、≤10 文件，02§4.3）的代表样本，不另造性能专用数据；
- **只做记录型基准【强制】**：L3/里程碑验收（04§2.4）顺带记录 p95 趋势进报告；**不建压测管线**——单 PR 串行触发、无并发用户，压测对象不存在。p95 连续两周 >5min → 触发复盘提示（06§1.3 L3 健康度），不做 CI 红门：延迟受 provider 波动主导，红门只会训练人忽略门禁；
- **记录粒度【建议】**：除 run 级 `latency_ms` 外，从 trace NDJSON（05§1.4）聚合 wave 级分解（评审/验证/仲裁/发布），超限时能直接指认段落——与 04§2.3 的算术分解同构，便于对照"哪一段比设计慢"。

## 7. 覆盖率态度

- JaCoCo 全模块采集、报告随 CI artifact 上传【建议】——可观测、不判定；
- `cra-core` / `cra-store` 行覆盖 ≥70%【建议】：编排与状态逻辑改动最频繁，跌破即提醒不阻塞——单人项目里覆盖率数字极易被追成形式，背离 §1"敢改"目标；
- 关键机制类 **100% 分支覆盖【强制】**（JaCoCo `check` 规则按类精确匹配，违例 CI 红）：账本标记解析器（05§1.3）、预算执行器（02§4.4）、工具白名单（07 T2.1、03§2）——实现落地时对号入座锁定类名。理由：这三处任何未测分支都是**静默故障**（幂等失效 / 预算击穿 / 越权工具调用），出错不抛异常只错账；
- 全局覆盖率不设门禁【强制——以白名单形式守护】：JaCoCo check 规则只允许上述一类写死在根 pom，扩充需先修订本文（07§6 文档即契约），禁止给其他模块追加阈值。

## 8. 测试数据管理

### 8.1 存放路径【强制】

| 数据 | 路径 | 说明 |
|---|---|---|
| LLM cassette | `<module>/src/test/resources/cassettes/*.cassette.json` | 随模块走，禁止跨模块引用（依赖方向同 02§3） |
| WireMock stub | `<module>/src/test/resources/wiremock/<endpoint>/` | stub 文件即端点契约文档（05§2.3 清单） |
| e2e fixture 仓库 | `cra-runner-cli/src/test/resources/fixtures/repos/` | 仓库快照 tgz + 场景 diff；生成脚本入库（可重建优于手维护【建议】） |
| 评测整仓 fixture | `cra-eval` 数据目录，按 06§3 规格（repo_snapshot.tgz / diff.patch / expected_findings.json / meta.json） | 与工程 fixture **不混放**：前者度量评审质量（06 管辖），后者断言工程机制（本文管辖） |

### 8.2 录制与更新流程

- **重录触发条件【强制】**（仅三）：prompt 模板版本变更（04§4 模板即代码）；brief 组装逻辑变更导致请求体哈希变；模型路由变更（05§5）；
- 重录在本地 `--record` 模式执行；cassette 变更尽量与实现变更分 PR 提交【建议】（单人项目允许同 PR，但 PR 描述必须写明重录触发原因，保持可审计）；
- **录制侧写盘前强制脱敏【强制】**：`sk-` / `ghp_` 前缀掩码（05§6 规则）；cassette 进 git（体量小、diff 可审）；
- **回放未命中 fail-fast【强制】**：禁止静默穿透到真实 API（§3.2，"LLM 不进单测"的机械守护）；
- cassette 大面积未命中（provider 响应字段漂移）时整体重录【可选】：不做字段级修补——修补过的 cassette 测的是修补代码，不是链路。

---

**级别统计**：强制 16 条 / 建议 9 条 / 可选 2 条——强制项全部可由 CI（JaCoCo check、计时 step、ArchUnit、密钥缺席 + fail-fast）或工具（spotless、WireMock 状态机）自动守护。
