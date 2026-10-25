# 10 · 架构规范

> 文档版本：v1.0（2026-10-25，开发前规范包）
> 前置阅读：`02-architecture-design.md` §1/§3、`03-multi-agent-design.md` §1、`05-data-and-integration.md` §2
> 定位：**02 是架构设计（是什么），本文是架构的"守则与守护"（怎么保住它）**——模块依赖规则、包结构、DI 约定、ArchUnit 守护清单、ADR 与豁免流程。目标是个人项目的一句话版本：**让依赖方向永远不烂**，不是企业级治理。代码怎么写归 09，测试怎么跑归 08，扩展点登记归 11；模块怎么依赖、谁来守护，归本文。

---

## 1. 平衡原则

- 规则少而全部可自动执行：每条【强制】都落在 ArchUnit / maven-enforcer 的红灯里，写不进测试的偏好只配【建议】；
- 例外有出口：违规不搞审批流，走 freeze 登记 + issue 链接 + 30 天复审（§7），到期即红；
- 单一事实源：依赖矩阵（§2）既是文档也是 ArchUnit 规则的生成源，规则与矩阵不双维护；
- 守护本身轻量：cra-archtest 只依赖不产出，CI 增量时长预算 <30s（08§4 的 10min 总盘子里忽略不计）。

## 2. 模块依赖矩阵【强制】

行 = 依赖方，列 = 被依赖方；✅ = 允许（Maven compile 依赖），❌ = 禁止，`-` = 自身。依据与扩写自 02§3 依赖方向。`cra-common` 不设行：它不依赖任何模块（矩阵列全 ❌，被所有人依赖的底座）。`cra-server` 为 M4 模块，其行自 M4 起生效。

| 依 \ 被 | common | llm | git | git-gh | context | agents | store | core | cli | eval | server |
|---|---|---|---|---|---|---|---|---|---|---|---|
| **cra-llm** | ✅ | - | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| **cra-git** | ✅ | ❌ | - | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| **cra-git-github** | ✅ | ❌ | ✅ | - | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| **cra-context** | ✅ | ❌ | ❌ | ❌ | - | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| **cra-agents** | ✅ | ❌ | ❌ | ❌ | ❌ | - | ❌ | ❌ | ❌ | ❌ | ❌ |
| **cra-store** | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | - | ❌ | ❌ | ❌ | ❌ |
| **cra-core** | ✅ | ✅ | ✅ | ❌ | ✅ | ✅ | ✅ | - | ❌ | ❌ | ❌ |
| **cra-runner-cli** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | - | ❌ | ❌ |
| **cra-eval** | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ | ❌ | - | ❌ |
| **cra-server**（M4） | ✅ | ❌ | ❌ | ✅ | ❌ | ❌ | ✅ | ✅ | ❌ | ❌ | - |

每行一句 ❌ 理由（✅ 的依据已在 02§3）：

- **cra-llm**：网关只面向 OpenAI 兼容端点，不知道 Git、不知道评审业务（01§5.1 选型原则）——网关一旦知道 Git，"模型无关"（01§4.1 G4）开始漏气；
- **cra-git**：SPI 模块不绑实现（git-github ❌）——接口稳定性（11§3）先于实现存在；
- **cra-git-github**：平台实现自洽，不依赖 llm/context 等其他基建；未来 `cra-git-<platform>` 实现之间同样互不依赖（11§4 第 4 条）；
- **cra-context**：上下文组装只消费 cra-common 的 diff/文件数据，不发起平台调用——切片由编排器预取后内联（03§2.1），assembler 自己调 API 会绕过预算核算（04§1）；
- **cra-agents**：子代理是**纯定义**（角色模板/工具白名单/输出 schema，02§3），LLM 循环与调度属 cra-core 驱动——定义独立于执行，才能独立演进、独立测试（03§1：所有子代理共享同一执行模型，差异仅在定义五件套）；
- **cra-store**：账本只面向 cra-common 纯 POJO（11§4 第 3 条），不感知平台 API；
- **cra-core**：不依赖 git-github——编排与平台实现解耦，换平台/换形态不改 core（02§5"core 全部逻辑与形态无关"）；不依赖 cli/eval/server——上游不知道壳和评测；
- **cra-runner-cli**：组合根例外（见下），但 ❌ eval/server 两条仍是铁律——入口不依赖评测框架，两个壳互不知道彼此；
- **cra-eval**：评测框架经命令行黑盒驱动被测对象（05§3），只直连 common（数据模型）与 store（账本读取，02§5 回灌）；若 M3 评测落地确需进程内驱动 core，先修订 02§3 与本表并写 ADR（§6）；
- **cra-server**（M4）：第二组合根，同 cli 约束（02§5：CLI 与 server 只是两种壳）。

**组合根例外【强制】**：cra-runner-cli 是唯一允许依赖图上一切的位置（除 eval/server），且**仅限各模块的 api 包类型**（§3/R3 守护）——装配需要触达各模块 api 包的装配工厂，这是"所有模块 → 按需向下"的唯一豁免点。M4 起 cra-server 成为第二组合根，届时先修订 §5 R6 与本注并写 ADR。

## 3. 包结构模板

包名已由 09§3 定死：`org.cra.<module>`。每个模块内部固定二分【强制】：

```
org.cra.<module>/
├── api/        # 对外接口与 DTO——其他模块只允许 import 此包（R3 守护）
│   └── <Module>Assembly.java   # 纯 Java 装配工厂：创建本模块 internal 实现（§4）
└── internal/   # 实现——跨模块不可见，可自由分子包；同模块测试可访问
```

各模块具体包建议（职责与 02§3 一一对应）：

| 模块 | api | internal / 说明 |
|---|---|---|
| cra-common | 免二分：整个模块即公共契约（05§1 数据模型 + SPI 入参出参 POJO） | 子包按域：`finding` / `run` / `plan` / `brief` / `trace` |
| cra-llm | `LlmGateway` + 降级结果对象（02§4.2） | `routing` / `retry` / `concurrency` / `cost`（02§4.5） |
| cra-git | `GitPlatformAdapter`、`LocalRepoContext`（05§2.1） | jgit blame 实现 |
| cra-git-github | 包名 `org.cra.git.github`（09§3）；api = 装配工厂 | `http`、账本标记解析（05§1.3）、锚定校验（05§2.2） |
| cra-context | `ContextAssembler` | `diff` / `window` / `slice` / `budget`（04§1） |
| cra-agents | `SubAgent`、`AgentRole`、`ToolWhitelist`、`Budget` 定义（03§1） | 角色绑定与模板装载；prompt 资源在 `resources/prompts/`（04§4、11§2） |
| cra-store | `ReviewLedger`（02§5、05§1.5） | `sqlite` / GitHub 评论账本 / PG（M4） |
| cra-core | Orchestrator 入口（输入 `ReviewRequest`、输出 `ReviewResult`，02§5） | `planner` / `fanout` / `verify` / `aggregate` / `publish` / `convergence`（02§1 ①–⑦） |
| cra-runner-cli | 免二分：依赖图终点，无人依赖它 | `Main` + `config`（05§5 绑定）+ `assembly`（装配）+ `command`（参数与退出码，05§3） |
| cra-eval | 数据集格式与报告类型（06§3） | runner 与负向不变量判定器（08§5 场景 6） |
| cra-server（M4） | 建时按同模板 | 动工前先修订本表 |

**组合根唯一【强制】**：M0–M3 全部对象图在 `cra-runner-cli` 的 Main 装配——Spring `@Configuration`/`@Bean` 只允许出现在该模块；其余模块是纯 Java 库。M4 的 cra-server 加入时按 §2 组合根例外扩展。

## 4. DI 与装配约定

- **构造器注入唯一【强制】**：所有依赖经构造器进入；禁字段注入与 setter 注入（含 `@Autowired` 字段形式）。Spring 对单构造器免注解自动注入，因此 `@Autowired` 注解全项目预期**零出现**（R7 守护）——注解一出现就意味着有人想注入第二个构造器或字段；
- **Spring 仅 DI/配置【强制】**（01§5.1、02§7）：`org.springframework..` 类型不得出现在 cra-runner-cli 之外的主代码（R6 守护）；05§5 部署级配置绑定类归 `cra-runner-cli` 的 config 包——配置属壳的职责（02§5）；
- **模块之间只允许依赖 api 包类型【强制】**（R3 守护）：internal 类型的构造只能被本模块代码与自己的装配工厂触达；
- **装配工厂模式【强制】**：每个模块 api 包提供一个纯 Java 工厂（`<Module>Assembly`），组合根只调工厂、把产物作为构造参数注入 core——Main 从不 import 任何 `internal`；
- 共享可变状态、线程池归属等代码级约束以 09§6 为准（私有字段 + 构造器注入），本文不重复。

## 5. 架构守护【强制】

**守护模块：`cra-archtest`**（02§3 模块清单之外的构建期守护件，登记于本节）。仅 `src/test/java`、无主代码、不被任何模块依赖、不进运行时依赖图；test 依赖全部模块以获得完整类视图。不选 cra-common 的理由：cra-common 是矩阵底座（列全 ❌），为守护测试让它 test 依赖全部模块等于自拆底座——09§1 的"规则自检测试放 cra-common"是**源码文本扫描**（不需要类路径），与 ArchUnit 的**字节码分析**分工并存：cra-common 管写法（System.out、吞异常、通配 import），cra-archtest 管结构（依赖方向）。依赖登记：ArchUnit（test scope，仅 cra-archtest）按 08§3.1 修订程序补进固定清单——08§4 M2 行已预置该门禁，此处不越权代改。

**规则清单（8 条，即 cra-archtest 的全部内容）**：

1. **R1 分层依赖（layeredArchitecture）**：§2 矩阵的机器化复刻。层定义与矩阵一一对应（git-github 的 `org.cra.git.github..` 命中 `org.cra.git..` 前缀，归入 Infra 层）：

```java
layeredArchitecture().consideringAllDependencies()
    .layer("Common").definedBy("org.cra.common..")
    .layer("Infra").definedBy("org.cra.llm..", "org.cra.git..", "org.cra.context..")
    .layer("Store").definedBy("org.cra.store..")
    .layer("Agents").definedBy("org.cra.agents..")
    .layer("Core").definedBy("org.cra.core..")
    .layer("Shell").definedBy("org.cra.runner.cli..")
    .layer("Eval").definedBy("org.cra.eval..")
    .whereLayer("Shell").mayNotBeAccessedByAnyLayer()   // cra-core 不得依赖 cra-runner-cli 由此覆盖
    .whereLayer("Eval").mayNotBeAccessedByAnyLayer()
    .whereLayer("Core").mayOnlyBeAccessedByLayers("Shell")
    .whereLayer("Agents").mayOnlyBeAccessedByLayers("Core", "Shell")
    .whereLayer("Store").mayOnlyBeAccessedByLayers("Core", "Shell", "Eval")
    .whereLayer("Infra").mayOnlyBeAccessedByLayers("Core", "Shell");
```

   矩阵中未被层规则覆盖的 ❌ 单元（如 eval→llm、git-gh→store）由表驱动逐对生成补齐：一条 ❌ = 一条 `noClasses("org.cra.<a>..").should().dependOnClassesThat().resideInAPackage("org.cra.<b>..")`——矩阵是单一事实源，规则循环生成（约 30 行），不做第二份手写清单；
2. **R2 循环依赖**：`slices().matching("org.cra.(*)..").should().beFreeOfCycles()`——模块切片无环；Maven 侧由 maven-enforcer 与根 pom reactor 顺序双保险（09§7 已有 enforcer）；
3. **R3 api/internal 边界**：对每个含 internal 的模块 M：`noClasses().that().resideOutsideOfPackage("org.cra.<m>..").should().dependOnClassesThat().resideInAPackage("org.cra.<m>.internal..")`——§4"模块间只依赖 api"的机械形态；
4. **R4 SPI 与实现隔离**：`org.cra.git..` 不得依赖 `org.cra.git.github..`（SPI 模块不知实现）；`org.cra.common..` 不得依赖任何其他 `org.cra.<module>..`（底座纯度）；新增平台/账本实现模块时按 11§4 第 4 条同步补"实现互不依赖"规则；
5. **R5 子代理独立性**：`noClasses().that().resideInAPackage("org.cra.agents..").should().dependOnClassesThat().resideInAPackage("org.cra.core..")`——子代理定义独立于编排器（03§1：编排器经自包含 AgentBrief 注入，子代理不感知编排器与其他子代理的存在）；违例即隔离机制（03§2）从类型系统层面失守；
6. **R6 Spring 收敛**：`noClasses().that().resideOutsideOfPackage("org.cra.runner.cli..").should().dependOnClassesThat().resideInAPackage("org.springframework..")`——Spring 仅 DI/配置且仅在组合根（01§5.1、02§7）；jgit/Jackson 等无 Spring 类型的依赖不受影响；cra-server（M4）加入时扩展本规则的允许集合并写 ADR；
7. **R7 注入注解零出现**：`noClasses().should().dependOnClassesThat().belongToAnyOf(Autowired.class)`——字段/setter/多构造器注入都必然引入该注解依赖，一网打尽（§4 构造器注入唯一）；
8. **R8 反射禁令**：`org.cra..` 的类不得依赖 `java.lang.reflect..`——跨层反射是绕过 api/internal 可见性的万能钥匙；确需（如 cra-eval 反射装载）走 §7 豁免。

**CI 接入时点**：cra-archtest 于 M0 建仓时随骨架创建（规则先行，本地 `mvn -B -ntp verify`（00§4）从 M0 即运行它）；作为 **required check 自 M2 起**——与 08§4 CI 门禁演进表 M2 行（"+ ArchUnit 守护 02§1/§3 分层与依赖方向（规则清单见 10 号文档）"）逐字对齐，违例即红。

## 6. ADR 决策记录机制

- **目录 `docs/adr/`，M0 建仓时创建**；文件名 `NNNN-kebab-title.md`，编号自增、永不复用（被取代的 ADR 只改状态，不删除）；
- 模板（一篇 ≤10 行）：标题 / 状态（proposed｜accepted｜superseded by ADR-xxxx）/ 背景 / 决策 / 理由 / 后果 / 替代方案；
- **何时必须写【强制】**：① 跨模块接口变更（api 包公开类型的签名或语义变化）；② 技术选型（新增第三方依赖、更换框架或模型路由形态）；③ 规范例外批准（§7 豁免的授予与续期）；④ 破坏性 SPI 变更（11§4 第 2 条已强制"ADR + 全部实现方迁移清单"，此处收口为唯一程序）；
- **与 11§3 的衔接（本文对既有定义的一处修订，已完成同步）**：11§3 澄清 1 曾定"不建 ADR 目录，决策记录落在文档版本块"。本节将该定义升级：§7 豁免的 issue、11§3 稳定级的"签名变更须 ADR"、SPI 迁移清单都需要**稳定可引用的编号**，版本块的行号不是稳定引用。自本文生效起，ADR 落 `docs/adr/`；文档版本块保留为变更轨迹（01§8 式）——ADR 记"为什么这么定"，版本块记"这一版改了什么"。11§3 已随本批同 PR 修订至 v1.1，与本文对齐。

**附录：历史决策补录**（素材提炼自 01§8 修订记录，每篇 ≤8 行，创建目录时落盘）：

- **ADR-0001 状态账本 = GitHub 评论账本，而非本地库**：背景——v1.0 曾让"状态由 Actions 持有"，但运行器用后即焚（01§8 首轮缺陷①）。决策——CI 形态以 bot 自身 PR 评论内嵌指纹与 DONE 标记为唯一事实源（02§5、05§1.3），SQLite/PG 属本地/服务形态，统一走 `ReviewLedger` SPI。替代方案——常驻服务或数据库（M4 前不引入）。后果——解析五规则防污染；跨 run trace 只留 artifact 90 天，接受。
- **ADR-0002 放弃"盲评"，改为独立复核 + 反锚定质控信号**：背景——finding 描述即完整论证，"盲"是装饰（01§8 首轮缺陷③）；引用新颖性硬校验拒真（二轮缺陷④）。决策——verifier 看完整论证但必须独立重建证据链（自定位行号 + 自己的话重述）；新颖性校验降级为 `anti_anchor_check` 质控信号，FAIL 不阻断（03§4.2）。替代方案——剥离论证的真盲评、纯证伪指令（保留给 M3 V2 做差异化）。后果——FAIL 率进周报预警（06§1.3 口径）。
- **ADR-0003 Java 17 + Spring Boot 3.x（仅 DI/配置）+ 自研轻量编排**：背景——01§5.1 决策矩阵。决策——与原项目及团队技能连续；agent 核心循环本就轻量，自研可控可测；Spring 收缩为组合根的 DI 与配置。替代方案——Python + LangGraph/CrewAI（生态丰富但与原项目断裂、pipeline 易失控）、TS + Claude Agent SDK（分发重）。后果——LLM 生态靠 OpenAI 兼容协议补齐（01§4.1 G4）；"禁编排框架"成为依赖红线（09§7）。
- **ADR-0004 双验证者投票降级为 M3 数据门禁增量**：背景——M2 做不到验证者异构，"N 个高相关性投票者等价于 1 个"（01§8 首轮缺陷②⑧；03§2）。决策——M2 单强模型验证者 + Judge 仲裁 + 拒真抽查；M3 双异构投票由数据门禁开启（拒真池样本 ≥20 且推翻率 ≥10%，或处置样本 ≥30 且精确率 <80%，03§4.3）。替代方案——M2 即上双验证者（同构投票空转，虚假独立性）。后果——`require-distinct-model-family` 违例拒绝启动（05§5）；头号特性去留由数据决定。

## 7. 例外与豁免【强制流程】

ArchUnit 违规的唯一合法出口是 **freeze 冻结**，没有"先合了再说"：

1. 对需豁免的规则用 `FreezeArchRule.freeze(rule)` 包装；violation store 配置在 cra-archtest 内（`archunit.properties` 的 `freeze.store.default.path`），文件入 git 跟踪；
2. store 是纯文本：每条被冻结违规手工注记 issue 链接与到期日（`# {issue: <GitHub issue URL>, expires: <YYYY-MM-DD>}` 格式）——cra-archtest 内一条伴生测试解析 store，**无 issue 或无到期日的行直接红**；
3. **30 天复审**：到期日 ≤ 当天且违规未消除 → 伴生测试红；续期最多 1 次，须在 issue 中写明理由；要再续 = 修订本文档对应规则并写 ADR（§6 ③）——文档即契约（07§6），改规则先改文档；
4. store 的创建/更新开关（`allowStoreCreation` / `allowStoreUpdate`）只在本地重构时开启，CI 只读；违规修复后 store 自动收缩，不需要手工删行——**手工删行消不了红**：规则会把它当新违规再红一次，唯一真出口是改代码或登记豁免；
5. 禁止 `@ArchIgnore`、注释规则、改矩阵 ✅/❌ 来"消红"——三者都属于第 3 条的修订程序，跳过即事故（00§8 红线同源精神）。

## 8. 禁止事项

1. 循环依赖（模块级或包级）——CI 红（R2）【强制】；
2. 上帝类：单类型 >500 行（含 prompt 模板资源除外）即拆分——评审提醒，不做门禁【建议】；
3. 跨层反射、`Class.forName` 动态加载绕过 api 边界——R8【强制】；
4. 编排器外 `new` 子代理实现：SubAgent 实现类只能由组合根装配产生；cra-core 只面向 `SubAgent` 接口与 cra-agents 的 api 定义编程（03§1）【强制】；
5. 跨模块 import 他模块 `internal` 包——R3【强制】；
6. SPI 接口模块依赖实现模块、实现模块互相依赖——R4，同 11§4 第 4 条【强制】；
7. 组合根之外出现 Spring 类型、模块内自建 ApplicationContext 或自扫描装配——R6【强制】；
8. 用删 store 行、注释规则、私改矩阵的方式消除架构红灯——§7 第 5 条【强制】。

---

**级别统计**：强制 18 条另加 §7 整节【强制流程】/ 建议 2 条 / 可选 0 条——强制项全部可由 cra-archtest（R1–R8）、maven-enforcer（09§7）或 §7 伴生测试（store 注记完整性、30 天到期）自动守护。
