# ADR-0017 CLI 子命令执行器独立后缀 *CommandRunner

- 状态：accepted（2026-10-31）
- 背景：09§3 把 `*Command` / `*Result` 定义为跨层边界 DTO（`PostReviewCommand`、`ReviewRequest/ReviewResult`），但 `cra-runner-cli` 的 `ReviewCommand` / `SweepCommand` / `LocalPrePrCommand` 实际是"参数 → run → 退出码"的子命令执行器。同一后缀两种语义，后缀表失去判别力——新增 CLI 子命令时无从判断该叫 `*Command` 还是别的。
- 决策：09§3 后缀表新增 `*CommandRunner` = CLI 子命令执行器（仅 `cra-runner-cli` 内部、package-private）；三个类改名为 `ReviewCommandRunner` / `SweepCommandRunner` / `LocalPrePrCommandRunner`，`*Command` 回归"跨层边界 DTO"唯一语义。
- 理由：后缀表的全部价值是可判别；重命名是纯机械改动（三个类均 package-private、无跨模块引用、无序列化/反射/字符串依赖），一次性成本最低。保留双语义（备选 B）让后来者继续误判，且 09§3 的"既有对齐"列会同时指向两类不同职责的类型。
- 后果：类名、测试类名与仓内引用同步（约 120 处纯标识符替换）；行为零变化（CLI 参数语义、退出码 0/2/3/4、报告文本、账本键、平台装配结果均不变）。ADR-0007 正文中的 `LocalPrePrCommand` 指同一类，历史决策文本不回改。
- 替代方案：保留 `*Command` 并在 09§3 登记第二语义（零 churn，但后缀继续一符两义）；另择 `*Executor` / `*Subcommand`（与既有 `*Command` 词汇重复度更高，或引入第三套词汇，收益不足）。
