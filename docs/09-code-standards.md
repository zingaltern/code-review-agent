# 09 · 代码编写规范

> 文档版本：v1.3（2026-11-01，§6 加注：单次 daemon 读取线程豁免见 ADR-0018——pre-PR 实跑子进程输出读取线程非"线程池/调度器"）
> 文档版本：v1.2（2026-10-31，ADR-0017：新增 `*CommandRunner` 后缀——CLI 子命令执行器；`*Command` 回归跨层边界 DTO）
> 文档版本：v1.1（2026-10-27，M4e 第一半：包名示例补 cra-git-gitlab → org.cra.git.gitlab）
> 文档版本：v1.0（2026-10-25，开发前规范包）
> 前置阅读：`02-architecture-design.md` §3/§4、`03-multi-agent-design.md` §1、`05-data-and-integration.md` §1
> 定位：**Java 代码的唯一编写规范**——格式、命名、异常、日志、并发、依赖、注释。分支/提交/PR 规范以 `00§3–5` 为准（本文不重复）；模块边界与分层以 02 为准，本文只管"代码怎么写"。适用对象：人类 owner 与全部 AI 开发 agent。
> 目标：多个 AI agent 写出的代码收敛为"一个作者"，review 成本最低。

---

## 1. 平衡原则

- **格式交给工具**（§2）：空格、换行、import 顺序人不审、不讨论；
- **命名靠约定**（§3）：后缀语义表对齐既有文档类型，禁止现场发明新叫法；
- **争议写法默认化**：每类写法选业界争议最少的默认，个人偏好不给表达机会；
- 每条规则带级别：**【强制】** = 工具或流程自动可查，**【建议】** = 评审提醒，**【可选】** = 自便；
- 强制即自动化——全部强制项落在 `mvn verify`（00§4 自检）或 PR 检查内，机制对照：

| 检查机制 | 覆盖 |
|---|---|
| Spotless `spotless:check`（绑定 verify） | 格式与 import（§2） |
| 规则自检测试：JUnit 源码文本扫描，**cra-archtest 持有**（v1 基线修正：原放 cra-common 在单模块构建/IDE 下会扫描 0 文件、空集断言、绿灯空转）——Maven 属性传递各模块源码根 + **最小文件数断言（目标模块 <N 源文件即红）** + 仅根构建有效 | System.out、吞异常、parallelStream、import 通配符、SPI Javadoc（§4/5/6/8） |
| maven-enforcer | 依赖红线（§7） |
| PR 描述检查（00§5） | 新依赖用途说明（§7） |

## 2. 格式化工具链

**【强制】Spotless + google-java-format**（Maven 插件，root pom 声明、全模块继承，`spotless:check` 绑定 verify 阶段；`spotless:apply` 本地一键修复）。

- 引擎选型：**google-java-format（GOOGLE style：2 空格缩进、100 列）**，不用 palantir-java-format。理由一句话：Google Style 的官方参考实现对 Java 17 新语法（record/文本块）支持最完整（palantir 在 Maven Spotless 下对文本块格式化有历史缺陷，而本项目 prompt/JSON 模板大量用文本块），且是 AI 训练语料中存量最大的 Java 风格——多 agent 默认输出与其重合度最高，重排 diff 与 review 成本最小。
- **import 顺序**【强制】：由引擎编码——static import 单块在最前，其余按 ASCII 排序单块；配 `removeUnusedImports`；**禁止 import 通配符 `*`**（规则自检测试查）。
- **不引入 Checkstyle**【强制】：一个格式工具足够；风格类规则已由引擎编码，语义类禁令归规则自检测试（§1 机制表）——双工具意味着两套配置要同步维护，收益为零。
- 引擎与插件版本在 M0 落地时钉最新稳定版进 root pom，升级走单独 `chore:` PR。

```xml
<plugin>
  <groupId>com.diffplug.spotless</groupId>
  <artifactId>spotless-maven-plugin</artifactId>
  <configuration>
    <java>
      <googleJavaFormat><style>GOOGLE</style></googleJavaFormat>
      <removeUnusedImports/>
    </java>
  </configuration>
  <executions><execution><goals><goal>check</goal></goals></execution></executions>
</plugin>
```

## 3. 命名约定

包名【强制】：`org.cra.<module>`（`cra-common → org.cra.common`、`cra-git-github → org.cra.git.github`、`cra-git-gitlab → org.cra.git.gitlab`（M4e，ADR-0011）、`cra-runner-cli → org.cra.runner.cli`、`cra-archtest → org.cra.archtest`（v1 基线补登，第五轮缺陷②））；跨模块共享类型只允许放 `org.cra.common`（02§3 依赖方向）。

类名后缀语义表【强制】——新类型取名先查此表，与既有类型对齐：

| 后缀 | 语义 | 既有对齐 |
|---|---|---|
| `*Agent` | 子代理**执行器**：LLM 循环 + 结构化输出的 SubAgent 实现 | ReviewerAgent / VerifierAgent / JudgeAgent（03§1） |
| `*Reviewer` / `*Verifier` | 评审/验证**角色**：AgentRole 值、prompt 模板名、角色配置类；执行器仍叫 `*Agent` | SecurityReviewer、ConventionReviewer（03§2/§3）；角色标识符用 kebab-case（`bug-hunter/dataflow`，05§1.1） |
| `*Gateway` | 外部服务统一出口：路由/限流/重试/降级/核算的**唯一**接触面 | LlmGateway（02§3） |
| `*Adapter` | 外部平台 SPI 及其实现 | GitPlatformAdapter（05§2）、cra-git-github / cra-git-gitlab 内实现类 |
| `*Ledger` | 账本抽象与各形态实现 | ReviewLedger（02§5） |
| `*Assembler` | 上下文组装 | ContextAssembler（02§3） |
| `*Planner` | 评审计划生成（产出 `ReviewPlan`） | Planner（02§3） |
| `*Guard` | 流程护栏（收敛/预算/抽样） | ConvergenceGuard（02§3） |
| `*Command` / `*Result` | 跨层边界 DTO | PostReviewCommand、DiffResult、ReviewRequest/ReviewResult（05§2、02§5） |
| `*CommandRunner` | CLI 子命令执行器：参数解析产物 → run → 退出码（仅 cra-runner-cli 内，package-private；ADR-0017） | ReviewCommandRunner、SweepCommandRunner、LocalPrePrCommandRunner |
| `*Exception` | 项目异常层次 | §4 |

方法与字段从简，按 Google Java Style 即可（驼峰、常量 `UPPER_SNAKE`、boolean 字段不带 `is` 前缀），不逐条抄。枚举值与 trace `event` 名用小写 snake_case（`agent_start`，05§1.4）。

## 4. 异常与错误处理

**基类与层次**【强制】：项目异常一律 unchecked，继承统一基类，方法签名不声明 checked 异常：

```java
/** 项目异常基类：仅承载"终止性故障"，可预期业务分支不用异常（见 §4 规则）。 */
public abstract class CraException extends RuntimeException { … }
```

- `CraConfigException`：配置非法、拒绝启动（如 dual-vote 异构校验失败，05§5）→ CLI 退出码 2（05§3）；
- `CraIntegrationException`：外部系统（Git API / 存储）边界故障，在边界内转换后上抛；
- `CraLedgerException`：账本读写/解析失败 → 按 05§1.3 兜底分层降级，不允许炸穿 run。

**何时抛 / 何时禁**：

- **允许抛**：① 启动期 fail-fast；② run 级不可恢复故障——沿调用链上抛到 Orchestrator 顶层**统一**捕获 → run 落盘 `FAILED` + trace（02§4.2）；中间层不得半路拦截。
- **【强制】禁用异常表达的状态**（用状态枚举/结果对象，02§4.2）：子代理超时与预算击穿（`BUDGET_EXCEEDED`，02§4.4）、schema 校验失败（`SCHEMA_VIOLATION`，04§6）、验证不可得（`UNVERIFIED`，02§4.2）、run 降级（`DEGRADED`）——这些是可预期业务分支，不是故障。

**边界转换**【强制】：

- LlmGateway 是唯一接触 LLM 客户端异常的地方：内部完成退避重试（429/5xx ≤3 次，02§4.5），耗尽后**返回降级结果对象**（携带 fallback 链状态，02§4.2），底层 IOException/HTTP 异常绝不抛穿编排层；
- GitPlatformAdapter 同理：限流/422 在实现内处理（05§2.2 逐条降级），处理不了的上抛 `CraException` 子类；
- 外部库异常类型（`IOException`、`JsonProcessingException`、`GitAPIException`）禁止出现在 cra-common 与 cra-core 的公开签名中。

- **【强制】禁 catch 后仅 log 吞掉**：每个 catch 必须三选一——转 `CraException` 上抛 / 降级并写入状态字段与 trace / 有上限的重试。检查：规则自检测试 + 评审。
- **【强制】禁异常做流程控制**：不得用异常代替可空返回、状态枚举或循环退出条件。

## 5. 日志规范

结构化 JSON 日志（01§5.2、02§6 既定；编码器用 logstash-logback-encoder，为此唯一引入的日志依赖）。公共字段【强制】：

| 字段 | 语义 |
|---|---|
| `run_id` | run 标识，贯穿全链路（02§6） |
| `agent` | 子代理/组件名，与 AgentRole 一致（`bug-hunter/dataflow`，05§1.1） |
| `event` | 事件名，对齐 trace 事件集（`agent_start`/`verify`/`publish`…，05§1.4） |
| `latency_ms` | 该事件耗时 |
| `tokens_in` / `tokens_out` | token 用量（对齐 05§1.2 run stats） |

级别语义表【强制】：

| 级别 | 语义 | 例 |
|---|---|---|
| ERROR | 需人介入的组件故障 | run 落盘 FAILED（02§4.2）、账本不可读写 |
| WARN | 降级发生 | fallback 链触发、SUMMARY_FALLBACK、BUDGET_EXCEEDED 杀代理 |
| INFO | run 级里程碑 | run 开始/结束、publish 完成、深度降档 |
| DEBUG | 代理内部细节（brief 构成、重试明细） | CI 默认 INFO，仅本地复现开 DEBUG |

- **【强制】禁 `System.out` / `printf` / `printStackTrace`**：一律走 SLF4J；检查：规则自检测试。**例外（ADR-0007）**：`cra-runner-cli` 展示层的 stdout 即用户界面——pre-PR 实跑真实输出回显与报告打印（07 T2.10 契约）豁免 `System.out/err` 禁令；`printStackTrace` 仍全局禁，豁免仅限该模块，其余模块不变。
- 【建议】不可信内容（diff/注释/finding 文本，04§3.3）不整段进日志，只记摘要与引用；密钥按 05§6 正则掩码。

## 6. 不可变与并发

- **【强制】cra-common 数据模型优先 record 或全不可变类**（CandidateFinding、Verdict、ReviewRun 等，05§1）：无 setter；集合字段用 `List.copyOf` 防御性拷贝；生命周期变迁（candidate→verified→published）**不回写实体**——用 with 式复制或独立的 `Map<FindingId, FindingStatus>` 状态表（03§4.5 的 `f.status = …` 是伪代码示意，落地按本条）。
- 共享可变状态【强制】只允许存在于编排器与网关内部：LlmGateway 的并发信号量与 per-model RPM 计数（02§4.5）、Orchestrator 的 run 级预算账目（02§4.4），且必须私有字段 + 构造器注入，不外泄引用。
- 子代理执行模型【强制】= 共享**固定**线程池（大小即 LLM 并发 8，02§4.5）+ 带超时 Future（`orTimeout`/`get(timeout)`），池在 cra-core 单例创建；各组件禁止自建线程池/调度器（单次 daemon 读取线程豁免见 ADR-0018）。
- **【强制】业务路径禁 `parallelStream`**：公共 ForkJoinPool 与 LLM 信号量互不感知，破坏并发上限与延迟可预测性；检查：规则自检测试。
- 【建议】锁范围最小化：优先不可变快照与 `ConcurrentHashMap.compute`；`synchronized` 块内禁做 I/O 与 LLM 调用。

## 7. 依赖与语言特性

- **【建议】不引入 Lombok**：record + 手写已覆盖全部需求——AI agent 生成 record 零成本，少一个注解处理器就少一类构建不确定性；同理不引入其他编译期代码生成。
- **【强制】新依赖必须在 PR 说明用途与替代方案**（"为什么不用现有依赖/JDK 标准库"）；红线由 maven-enforcer 自动管：禁 SNAPSHOT、禁重复日志实现、`requireUpperBoundDeps`。既定白名单（01§5.2）：jgit、Jackson、Spring Boot（DI/配置；M4b 起 web/JDBC 仅限 cra-server 组合根，02§7、ADR-0008）、SLF4J/Logback+JSON 编码器；**禁任何编排/重流程框架**（01§5.1）。M4b 新增：PostgreSQL JDBC 驱动（仅 server/host 运行时与 store 测试）与 Testcontainers（仅 `cra-store` 的 PG 契约测试，08§3.3 修订）。
- Java 17 特性用法，各 1 例：

```java
// record + 紧凑构造器校验（cra-common 数据模型，05§1.1）
public record FindingId(String value) {
    public FindingId {
        Objects.requireNonNull(value);
        if (!value.startsWith("f_")) throw new IllegalArgumentException("bad id: " + value);
    }
}
```

```java
// switch 箭头分派 verdict（03§4.3 晋升规则）；不写 default，靠编译器穷尽性检查兜住新增枚举值
FindingStatus next = switch (verdict) {
    case CONFIRMED   -> FindingStatus.CONFIRMED;
    case REFUTED     -> needsSpotCheck(f) ? judge(f) : FindingStatus.REJECTED;
    case INSUFFICIENT, UNAVAILABLE -> judge(f);
};
```

```java
// 文本块写 JSON 模板（schema/NDJSON 行模板，05§1.4），占位用 formatted 注入
static String traceLine(String runId, int findings) {
    return """
        {"run_id":"%s","event":"agent_output","findings":%d}
        """.formatted(runId, findings);
}
```

- **【强制】不启用 `--enable-preview`**：构建可复现优先；switch 的类型模式在 17 尚为 preview，箭头 switch + 枚举穷尽已覆盖本项目分派需求。

## 8. 注释与 Javadoc

- **【强制】公共 SPI 接口与数据模型必写 Javadoc**：SubAgent 及其五个组成类型（03§1）、GitPlatformAdapter / LocalRepoContext（05§2）、ReviewLedger（02§5）、cra-common 全部公开 record 的字段——它们是 02/03/05 的代码契约，AI agent 靠它对齐行为。检查：规则自检测试（上述包 public 成员缺 Javadoc 即 fail）。
- 实现类内部默认**零注释**：写注释只写"为什么"（取舍、坑、不变量），不写"是什么"（复述代码）；能靠命名说清的不写注释。
- 中文 Javadoc/注释【建议】允许且推荐（owner 母语、评审最快）；标识符仍英文。
- 【强制】禁废话注释：`@param x the x`、AI 空转式总结、与代码脱节的过期注释——过期注释比没注释糟，删代码先删注释。

## 9. 反模式清单（每条一行，违反即打回）

1. catch 后仅 log 吞掉（§4）；
2. 用异常做流程控制、代替状态枚举（§4）；
3. `System.out` / `printf` / `printStackTrace` 代替 SLF4J（§5）；
4. 业务路径 `parallelStream`、组件自建线程池（§6）；
5. 数据模型加 setter、构造器泄漏可变集合引用（§6）；
6. 引入 Lombok、编排框架或未走 PR 说明的新依赖（§7、01§5.1）；
7. prompt 与 JSON schema 散落字符串拼接，不走 04§4 模板资源；
8. 正则修 JSON、重试时放宽 schema（04§6）；
9. 硬编码模型名/密钥/预算常量，绕过 05§5 配置；
10. 注释复述"是什么"或过期不更新（§8）。
