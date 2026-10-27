# ADR-0014 语义索引与 L2 跨文件上下文（第一版）

- 状态：accepted（2026-10-28，M4e 池内项第一版随本 ADR 落地；默认关闭）
- 关联：04§1（L0–L5 分层，L2 行）、04§2.2（预算算术）、03§3.1（CrossChecker 上下游路径集）、03§2.1（切片由编排器预取内联）、11§2/§4（扩展点登记与 SPI 演进）、07§5 M4e、ADR-0011/0013（M4e 的另一半：GitLab）
- 影响面：`cra-common`（`org.cra.common.semantic` 快照 POJO）、`cra-git`（`SymbolIndex` SPI + jgit 实现）、`cra-core`（切片组装/派发/验证 brief/CrossChecker 路径集）、`cra-runner-cli` 与 `cra-server`（配置与组合根）、`docs/04`、`docs/07`、`docs/11`、`README.md`

## 1. 问题

评审 brief 的上下文只有 diff（L0）与 hunk 窗口/条件全文（L1）。这导致两类系统性漏报：

1. **改了定义但看不到调用方**：方法/字段语义变了，受影响的其他文件不在 brief 里，reviewer 无法判断调用点是否仍然成立；
2. **改了被多处引用的工具类/模型类**：reviewer 不知道影响面（谁 import、谁调用、调用点长什么样），只能对单文件做局部判断。

docs/04 的 L2 行自 M1 起是"未实现项（M4 前不做索引）"；03§3.1 的 CrossChecker 也把"上下游路径集"退化为"本 PR 变更文件集"，并如实声明 context 召回无结构性保证。

约束（来自任务与既有设计纪律）：

1. **确定性**：同输入同输出（可重放），不是向量/LLM/embedding；
2. **零外部依赖**：不引入索引服务、守护进程、持久化存储；索引在评审期间构建于内存/临时目录；
3. **不动门禁**：不修改阈值、expected/数据集与发布语义；
4. **默认关闭**：`CRA_SEMANTIC_CONTEXT_ENABLED=false`；关闭时 reviewer brief 与开关引入前逐字节一致；
5. **第一版 Java-only**：其他文件跳过并写覆盖声明；
6. **有界**：按路径排序、固定扫描上限、跳过二进制/超长文件与 `target/`、`.git/`、`node_modules/` 等目录，超限必须声明（不静默丢弃）；
7. **不炸 run**：关闭/无本地仓库/构建失败/超限 → 降级为"不可得"+ 覆盖声明（11§4 第 5 条）。

## 2. 决策

### 2.1 第一版是确定性符号索引，不是向量/LLM 召回

索引由 head 侧源码的**词法/结构抽取**得到：类型/方法/字段定义（含所属类型与行号）、符号引用位置、import 关系、文件级引用计数。 语义上它回答的是"符号名在哪里定义、在哪里出现"，不是"这段代码在语义上依赖什么"。

这样选的理由：评审要的是**可定位的证据**（file:line + 行内容），而确定性索引可以保证同 head + 同选项 → 快照逐字段相等（可重放、可断言）， 并且能进单测与 CI；向量召回引入 embedding 服务、索引持久化与非确定性（模型版本变化即结果漂移），与"证据必须可重放"的验收口径冲突。

### 2.2 不引入 JavaParser：零依赖的词法/结构扫描器

实现是 `cra-git` 内的 `JavaSourceScanner`（约 600 行，含词法器与小状态机）：

- 词法：剥离注释与字符串/字符/文本块内容（行号保持不变），产出 identifier/punct/number 记号；
- 结构：括号深度 + 类型体栈，识别 `class|interface|enum|record` 类型定义、类型体内的成员签名（方法/构造器/字段/enum 常量）、`package`/`import` 段；
- 引用：类型体内的 identifier 出现位置（关键字/字面量除外），只对"索引内已定义的名字"统计并保留位置（见 2.3）。

**不选 JavaParser 的理由**：新增第三方依赖需要说明许可证/体积/维护面（任务约束），而本索引只需要"定义与引用位置"，不需要 AST、类型绑定或泛型求值； 词法方案的偏差是可枚举的（见 §5 局限），且不引入版本升级与供应链成本。若未来要精确到重载/类型绑定，再单独立项换实现——端口形状（`SymbolIndex`）不变。

### 2.3 端口与快照形状（11§4）

`cra-git/api/SymbolIndex`：

```java
SymbolIndexSnapshot build(String headSha, SymbolIndexOptions options);
```

- 快照（`cra-common` 纯 POJO）：`available/headSha/reason/filesScanned/filesSkipped/truncated/notes` +
  `definitions`（类型/方法/字段 + owner + 行 + 行摘录）、`references`（符号名 → 出现次数 + 位置（file,line,行摘录））、`imports`（含仓库内解析目标）、`fileStats`（定义数/出边/入边）；
- 选项：`maxFiles`（默认 2000）、`maxFileBytes`（默认 256 KiB）、`include/exclude`（`*` 通配，整串匹配路径）、`overLimitPolicy`（`TRUNCATE` 截断并声明 / `UNAVAILABLE` 整体不可得）；
- 位置上限：每符号 ≤25 处、≤10 文件；每文件每名 ≤100 行——**上限只影响证据条数，不影响出现次数计数**。

**只对"已定义符号名"统计引用**：未定义标识符（局部变量、JDK/第三方 API）不入引用表，避免把 grep 噪声当跨文件证据。 代价是"引用了外部库"不进入索引——这本来也不该出现在 L2 切片里（不在本仓，reviewer 读了也没用）。

### 2.4 变更符号抽取（两级优先）

`cra-core` 从 diff 新增行推导"这次改动碰了哪些符号"：

- **Tier A（定义变更，优先）**：新增行里出现的、且定义就在本 PR 变更文件内的名字；以及新增行所在的**最内层定义**（方法体改动而签名未变时仍归属到方法符号）；
- **Tier B（引用变更）**：新增行里出现的、定义在仓库其他文件的名字，且全仓出现 ≥2 次（一次出现多为局部变量/参数）；
- 排除：Java 关键字/字面量、JDK 常见类型（`String`/`List`/`Optional` 等）、长度 <3 的标识符；Tier B 另排除高频通用名（`get/size/toString/equals/...`）；
- 排序：tier → 出现次数降序 → 名字升序；上限 8 个符号/brief。

### 2.5 切片组装与注入面

`CrossFileSliceAssembler` 对每个符号渲染：定义行 + 跨文件引用位置（file:line + 行摘录）+ import 关联（谁 import 了本 PR 变更的类型）。 

- **变更文件不重复注入**：本 PR 文件已在 L0/L1 里，切片只补"看不到的文件"（引用位置与 import 段都跳过变更文件）；
- **证据自足**：行摘录随快照内联，切片组装不发起任何平台/文件读取（纯函数、6ms 级）；
- **注入面**：04§1 的按角色默认注入集 = `bug-hunter/dataflow`、`security-reviewer`、`verifier`；其余角色（boundary/concurrency/resource/perf/convention/test/cross-checker）不注入；
- **预算**：单 brief ≤4k token（`SemanticContextOptions.sliceTokens`，可配；下限 500/上限 12000）。切片 token **从该代理的 L1 预算中扣除**（`ReviewAgentDispatcher.buildContextInput`、`VerificationBatchAssembler.buildBatch`），不额外扩大单代理输入硬限；超预算按符号顺序截断，并在 brief 末尾写 HTML 注释形式的**截断声明**（含未注入符号数与引用数）；
- **覆盖声明**：索引范围（文件数/定义数/跳过数）、Java-only、截断事实进 Summary 覆盖行（`SemanticContext.coverageNotes()`），与 02§4/04§1 的"裁剪必须可见"一致。

### 2.6 CrossChecker 路径集增强（03§3.1 输入 b）

开启且索引可用时，CrossChecker 变更史检索的路径集从"本 PR 变更文件集"扩为"变更文件 + 定义/引用/import 关联文件"（上限 60 条，确定性截断）。 声明文本随来源切换（"语义索引关联文件（定义/引用/import；ADR-0014）"），**不改变 CrossChecker 的判定逻辑与预算**（16k 输入 / 4k 输出不变）。 这缓解（但不消除）03§3.1 声明的"上下游路径集是启发式"局限。

### 2.7 配置与默认关闭

| 形态 | 开关 | 上限 |
|---|---|---|
| CLI | `--semantic-context` / `CRA_SEMANTIC_CONTEXT_ENABLED` | `CRA_SEMANTIC_CONTEXT_MAX_FILES`、`_MAX_FILE_BYTES`、`_SLICE_TOKENS`（同名 flag 可覆盖） |
| server | `CRA_SEMANTIC_CONTEXT_ENABLED`（`ServerConfig.from`） | 同上 |

关闭时：不构建索引、不发 `semantic_context` trace 事件、不写任何覆盖声明、`slicesText` 为空（模板渲染占位"（无切片）"）—— 与开关引入前逐字节一致（`SemanticContextPipelineTest.disabledKeepsBriefIdenticalToPreSwitchBehaviour` 断言）。开启后仅 standard/deep 生效；quick 不触发并声明原因（02§4.3 档位联动）。

## 3. 理由

- **确定性优先**：评审的价值在证据可定位；L2 切片的价值是"把 reviewer 看不到的文件摆到面前并给出行号"，这不需要语义相似度；
- **零依赖、零服务**：第一版让整条链（索引 → 抽取 → 切片 → brief → 预算 → 声明）在同一进程内可测可重放；引入 JavaParser/向量库/索引服务会把"数据面正确性"变成"外部系统可用性"问题；
- **默认关闭**：L2/L3 评测口径与基线算术是已冻结的验收面（04§2.2、06§1.2）；新数据面在未证明收益前不改变基线。关闭时的字节级一致是可用测试证明的硬约束，而不是口头承诺；
- **从 L1 预算扣除而不是加预算**：单代理输入硬限是隔离机制的一部分（03§2.3）；切片挤占 L1 是显式取舍——L1 不够时会走既有的窗口收窄/丢弃阶梯并声明，不会静默越限；
- **两级优先 + 噪声排除**：切片的信噪比取决于"选对符号"；定义变更优先保证主场景（改方法 → 看调用方），出现次数门槛与 STOPLIST 抑制局部变量/通用方法名噪声。

## 4. 后果

- **预算影响（04§2.2 需同步）**：单 brief ≤4k；Standard 中型 PR 的注入面是 dataflow 4 chunk + security 4 chunk（+ 验证批次数），最坏情况 8×4k=32k，但这些 token 从 L1 预算中扣除——**只有当 L1 已被压到 1k 下限时才会抬高总输入**。实测（本仓库 486 个 Java 文件）：索引构建 0.56s（冷）/0.16s（热），单符号切片 352 token / 6 处引用 / 1 个关联文件；8 符号上限对应的切片量级 ≈1–3k token，远低于 4k 上限；
- **索引构建耗时**：486 文件 0.56s；2000 文件上限外推仍在秒级（两趟扫描、每趟只读 blob）。构建失败/超限不影响 run（不可得 + 声明）；
- **L2 回归**：本 ADR 涉及 core 编排、验证 brief 组装（`VerificationBatchAssembler`/`VerificationWave`）与 reviewer brief 组装（`ReviewAgentDispatcher`），按 04§2.4 属"prompt/编排逻辑改动" → 需要 L2 报告（报告绑定最终提交）。**默认关闭**意味着 L2 的观测口径与基线一致（关闭路径有 e2e 断言）；
- **评测边界（重要）**：CLI/server 的评测组合根默认不开启语义上下文（`CRA_SEMANTIC_CONTEXT_ENABLED=false`），因此 L2/L3 不度量"L2 切片对检出率的增益"；本 PR 的验收是单测 + 真实 git fixture 的 e2e（brief 内可见调用方文件/行）+ 关闭态一致性断言。收益度量（在评测集上开/关对比）是后续独立任务，需要新的数据集口径与预算，不在本 ADR 范围；
- **不改变门禁**：不改阈值、expected/数据集与发布语义；`context` 类 finding 的晋升规则不变；
- **可观测性**：`semantic_context` trace 事件（available/files_scanned/definitions/references/imports/truncated/slice_tokens）、`agent_start.slices_tokens`、Summary 覆盖行。

## 5. 局限（如实声明）

1. **Java-only**：第一版只解析 `.java`；Kotlin/Scala/TS/Python 等跳过并在 notes 声明；
2. **词法启发式而非类型绑定**：不区分重载、不解析泛型边界与注解语义、不建符号到类型的绑定；同名字段/方法在不同类型下合并为同一符号名（可能多报"引用"）；
3. **record 组件与局部变量不抽取**：`record Point(int x, int y)` 的组件、方法内局部变量不入定义表；
4. **匿名类/局部类成员**：匿名类内部方法不记录（局部类按词法位置记录）；
5. **扫描上限**：`maxFiles` 截断按路径升序（确定性），被截断的文件不进索引并在 notes 声明；超长文件（>256 KiB 默认）与二进制跳过；
6. **无向量召回**：切片只覆盖"符号名出现在 diff 新增行"或"新增行落在某定义体内"的关联；跨文件非符号级依赖（配置、SQL、脚本）不可见；
7. **引用位置有上限**：每符号 ≤25 处/≤10 文件（计数仍精确），广泛使用的符号只会展示前 N 处（按 path/line 升序，确定性）；
8. **关闭态无收益**：默认关闭时本数据面不参与评审；是否默认开启取决于后续收益度量；
9. **worktree/子模块**：`.git` 允许是文件（worktree 指针）；但 `LocalRepoContext.localRepoRoot()` 仍按目录判断（既有行为，不在本 ADR 修改）。

## 6. 替代方案

1. **JavaParser / tree-sitter 精确解析**：精度更高（重载、类型绑定、record 组件），但引入第三方依赖（许可证/体积/维护面）与升级成本；任务约束要求先证明必要性。端口形状已定，换实现不需要改消费方；
2. **向量/embedding 检索**：召回可超越符号名，但需要 embedding 服务 + 向量索引，且非确定性（模型版本漂移）与"证据可重放"冲突；索引成本与预算也更高；
3. **持久化索引（SQLite/Lucene/独立服务）**：跨 run 复用可省构建时间（本索引 0.5s 级），但引入第二套一致性/失效/备份语义（key 是 head sha，仓库前进即失效），收益不抵成本；
4. **只用 grep（无索引）**：实现最简单，但每次 brief 都要对全仓 grep（成本随 chunk 数线性放大）、无法做定义/引用/import 的区分、也无法给出"改动符号"的归属判断；
5. **把整仓或全量相关文件塞进 brief**：04§1 已判定为反模式（预算击穿 + 噪声），本 ADR 的切片是"符号级最小证据面"。

## 7. 验收与后续

- 单测：`JavaSourceScannerTest`（抽取/import/引用/容错/确定性）、`JgitSymbolIndexTest`（真实 git fixture：定义/引用/import 解析、上限与截断、不可得、确定性）、`ChangedSymbolsTest`（抽取优先序与噪声排除）、`CrossFileSliceAssemblerTest`（证据内联、变更文件不重复、预算截断声明、确定性）；
- e2e：`SemanticContextPipelineTest`（开启 → dataflow/security/verifier brief 含调用方文件+行；关闭 → 与开关引入前一致；quick → 不触发并声明）；
- 后续建议（不在本 PR）：在评测集上做开/关收益度量（需要新口径与预算）、扩展多语言（Kotlin/TS 优先）、把"索引可用性"接入处置周报、评估是否默认开启。
