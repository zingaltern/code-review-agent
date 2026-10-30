# ADR-0016 账本解析器与 glob/strictUtf8/处置辅助下沉 cra-common（单份共享）

- 状态：accepted（2026-10-31；**部分取代 ADR-0011 决策 2**——其中"解析器以本模块自有包复制一份（不跨实现模块 import）"的条款由本 ADR 取代，其余端点/锚定/键控决策不变）
- 背景：ADR-0011 曾以"格式冻结 + 复制成本可控"选择让 `cra-git-gitlab` 复制一份 `LedgerMarkerParser`（替代方案第 2 条明言"提到 cra-common 需独立 ADR"）。事实检验：2026-10-30 单日内该解析器经历两轮修复（围栏计数按连续反引号串、CRLF/CR 行尾归一），每轮都要在 github/gitlab 两份逐字节相同的实现上双写——双写漂移风险已被证明真实，"成本可控"不成立。同类复制还有三处：glob→regex（github/gitlab PayloadSupport 与 jgit 适配器三份同义实现，仅 `?` 语义有差异）、strictUtf8（三份逐字节相同）、DispositionSupport（两份逐字节相同）。
- 决策（四项下沉 cra-common，均为纯函数、无平台 IO）：
  1. `org.cra.common.git.LedgerMarkerParser`（含 `MarkerParseResult`/`ParseOutcome`/`SuspectReason`）——紧邻其产出的 `LedgerFindingMarker`/`LedgerRunMarker`；08§7 的 100% 分支覆盖门禁（`check-ledger-parser`）由两模块 pom 合并为 cra-common 一份（include 改 `org/cra/common/git/LedgerMarkerParser*`）；
  2. `org.cra.common.context.PathGlobs.compile/matches` 为 glob 唯一实现：`**/` 零目录可选、裸 `**` 跨段、`*` 单段、`?` 单段通配 `[^/]`（采纳 jgit 的更严语义；现网调用点仅 `**/*.java`、`**/test/**/*.java` 与 REVIEW.md skip/priority，无 `?` 字面量，行为不变），三个适配器私有 globToRegex 删除；
  3. `org.cra.common.text.Texts.strictUtf8`：malformed/unmappable 一律 REPORT，失败由调用方降级 binary 语义（不静默替换）；
  4. `org.cra.common.git.DispositionSupport`：reply 标签/摘录/时间字段容错单份（cra-common 由此新增唯一编译依赖 jackson-databind，01§5.2 白名单内）。
- 理由：格式被 05§1.3 冻结意味着语义漂移即事故（幂等/收敛/处置全押在账本上，02§5），单份实现 + 单份门禁是唯一能消除双写漂移的形态；11§4 第 4 条"实现模块互不依赖"不受影响——两实现都向下依赖 common 是矩阵既有方向。10§6 的"跨模块接口变更成本"由此以一篇 ADR 一次性支付。
- 范围之外：分页读取器（`getPagedArray` 等端点遍历逻辑）与 `reachedOlder` 判定保留在各自实现模块——分页语义属平台差异（offset vs link header），非逐字节同文；后续若第三平台需要再议。
- 后果：cra-git-github/gitlab 的 `internal/ledger` 包删除，调用点改 import；三处 glob 用例与两份 LedgerMarkerParser 测试合并为 cra-common 单份；docs/10 §3、docs/11 §2/§4、docs/08 §3 对应条目同步（"独立复制"→"下沉 common，ADR-0016"）。
- 替代方案：维持双写并加"复制件逐字节相同"的守护测试（治标——每轮修复仍需人工双写，守护只能事后报红）；抽 `cra-git` 内部共享（违反"SPI 模块不绑实现"的分层直觉，且 cra-context/core 无法复用 glob/strictUtf8）。
