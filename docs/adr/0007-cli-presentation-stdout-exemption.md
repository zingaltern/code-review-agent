# ADR-0007 CLI 展示层豁免 System.out 禁令

- 状态：accepted（2026-10-25，M2 T2.10 实现期，红驱动定点修订）
- 背景：09§5【强制】"禁 `System.out`，一律走 SLF4J"（守护：cra-archtest 源码扫描）与 07 T2.10/验收 8 正面冲突——
  pre-PR 本地模式的契约是**向用户展示真实输出**（构建/测试输出实时回显 + 退出码 + 失败摘录置顶报告），
  "真实输出"必须是子进程 stdout 原样，而 SLF4J/logback 输出会附加 JSON 日志前缀与级别，破坏该契约。
- 决策：
  1. 09§5 的 System.out 禁令语义收窄为**日志路径**；CLI 的**展示层**（stdout 即用户界面）豁免。
  2. 豁免范围 = `cra-runner-cli` 模块（唯一 CLI 壳；组合根之外无展示职责），实现点仅 `BuildVerifyRunner`（实跑回显）
     与 `LocalPrePrCommand`（报告输出）两处；`System.err` 与 `printStackTrace` 禁令维持全局。
  3. 守护调整：`SourceScanRulesTest` 的 System.out/err 检查对 `cra-runner-cli` 跳过，其余模块与两条禁止项不变。
- 理由：契约优先级（07 T2.10 验收 8 可判定性依赖 stdout）+ 规则本意（日志统一 SLF4J），非放宽工程质量。
- 复审条件：若 CLI 出现以 stdout 做诊断日志的用法（违反展示层语义），回滚本 ADR 并恢复全模块禁令。
- 修订（2026-11-01，规约审计 P0——err 豁免经查无使用，收回）：实态核查 `grep -rn "System.err" cra-runner-cli/src/main` 零命中——决策 2 的两处展示点从未使用 `System.err`；期间 09§5 文本与 `SourceScanRulesTest` 把豁免从 `System.out` 漂移成 `System.out/err`（文档层漂移，无代码对应，与本 ADR 决策 2 原文"`System.err` 与 `printStackTrace` 禁令维持全局"相悖）。据此：收回 System.err 豁免——09§5 恢复 `System.err` 全局禁，`SourceScanRulesTest` 的 System.err 检查恢复全模块（含 `cra-runner-cli`）；System.out 豁免不变，仍仅 `BuildVerifyRunner`（实跑回显）与 `LocalPrePrCommandRunner`（报告输出；类名随 ADR-0017 由 `LocalPrePrCommand` 更名）两处展示点。
