# ADR-0006 M1 流水线线 api 扩展与 jgit 首装

- 状态：accepted（2026-10-25，M1 T1.3–T1.7 实现期）
- 背景：07 T1.3–T1.7 要求覆盖声明/截断计数进 stats/Summary（05§1.2 coverage）、L1 上下文进 reviewer brief（04§1）、
  jgit blame 复核 pre-existing（05§2）。这需要扩展三个模块的 api 公开类型签名并首次引入 jgit 依赖。
- 决策：
  1. `ReviewStats`（cra-common）增 `preExisting`、`suppressedByTruncation`、`coverage` 三成员（补齐 05§1.2 的 M1 子集）；
  2. `GeneralReviewInput`（cra-agents）增 `windowContext`（L1 槽位，UNTRUSTED 定界）；
  3. `CoreAssembly.createOrchestrator`（cra-core）增 `PipelineOptions` 与 `LocalRepoContext` 两参（05§5 配置运行时形态 + blame 上下文；
     ContextAssembler/ReviewPolicyParser 由 core 内部经 `ContextAssembly` 创建，不进组合根）；
  4. jgit（01§5.2 白名单内）首装于 cra-git 模块 pom（版本 7.2.0.202503040940-r 直接钉在模块级，root pom 未动），
     仅 internal 的 `JgitLocalRepoContext` 使用；新增 cra-git api 装配工厂 `GitAssembly`。
- 理由：全部为"实现补齐文档既有契约"，无新决策面；jgit 属白名单依赖，版本钉模块级避免动 root pom（协调者边界）。
- 后果：调用方（组合根）需按新签名装配；`TemplateLoader.load` 因 chunk 并行渲染补 synchronized（M0 串行调用的隐藏缺陷，M1 修复）。
- 替代方案：不动 ReviewStats、把覆盖声明放 trace only——拒绝：验收 4 明确要求 Summary 如实声明（02§4.4 梯度沉默护栏）。
