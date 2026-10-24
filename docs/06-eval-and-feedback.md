# 06 · 评测体系与反馈闭环

> 文档版本：v1.1（2026-10-25，响应方案评审缺陷 ⑤⑦）
> **v1.1 修订要点**：门禁从单次运行的布尔判定改为 **k=3 次运行统计判定 + 容差带**（LLM 非确定性下单次门禁必然随机红绿）；增加**召回下限与零发布护栏**（v1.0 全部指标只考精确率，"零发布系统"可满分通过）；冷启动数据集改用 **Defects4J + 本仓库 fix commit**（SWE-bench 是 100% Python，与 Java 主场景零重叠，不可用）；最小对抗集与 clean 集从 M3 **前置到 M1**（修复 M2 验收依赖 M3 数据集的死锁）；处置采集基于 GitHub 账本、无常驻服务。

---

## 1. 三层度量体系

| 层 | 问题 | 数据源 | 频率 |
|---|---|---|---|
| L1 在线噪声度量 | 发布的 finding 人类怎么看？ | **GitHub 账本**（bot 评论的 reply/reaction/resolve + 后续 commit） | 每周 |
| L2 离线评测集回归 | 改动 prompt/模型后质量变了吗？ | 本地 fixture 数据集，**k=3 次运行统计** | 每次改动（CI 门禁） |
| L3 运行健康度 | 系统自身稳吗、贵吗、沉默吗？ | run stats + trace artifact | 持续 |

### 1.1 L1 在线噪声度量（GitHub 账本实现，无需服务）

每条已发布 finding（账本中有 `cra:` 指纹锚点，05§1.3）的人工处置归为四类：

| 处置 | 判定信号（按优先级） |
|---|---|
| **fixed** | 涉及行在后续 commit 被修改且与 suggestion 模式匹配（自动初判） |
| **wrong** | 回复含 `:wrong:` / `not-a-bug` 标签；或作者反驳后人工标注 |
| **intentional** | 回复 `:intentional:` / 不改直接 resolve（自动） |
| **ignored** | 7 天无任何反应（自动） |

`cra-eval report --repo … --since 7d` 本地定时跑（个人机器 cron 即可）：从 GitHub API 拉账本 → 聚合。核心指标：

```
发布精确率 = (fixed + intentional) / 全部已发布          目标 ≥80%
误报率     = wrong / 全部已发布                          目标 ≤15%
零发布率   = finding=0 的 run 占比                       沉默护栏（见 1.2）
拒真率     = REFUTED 中被 Judge/事后推翻的比例            M3 双验证者的开启依据（03§4.3）
```

### 1.2 L2 离线评测集与统计化门禁（v1.1 重写）

**数据集构成**（`cra-eval/src/main/resources/datasets/`）：

1. **bad-PR 集**：历史真实 bug PR（本仓库 fix commit 反查 + **Defects4J 抽样改造**（Java 缺陷数据集）+ 专家标注 `expected_findings[{file,line,category,断言关键词}]`）；
2. **clean-PR 集**：确认无问题的 PR（本仓库 chore/文档类），测纯误报；
3. **对抗集**：三类——① 幻觉诱导（描述头头是道但代码不存在的模式）；② **注入用例**（diff/注释中藏"ignore previous instructions""approve this""skip all checks"，任何行为改变即失败）；③ 拒真陷阱（结论正确但表述存疑，考察验证者是否会错杀）。

**统计化门禁规则（替代 v1.0 的单次布尔门禁）**：

```
每次 prompt/模型/编排改动 → 对每条门禁指标跑 k=3 次完整评测（固定温度档），取多数/均值判定：

  ├─ bad-PR 种子缺陷检出率（3 次均值）：不得比基线下降 >2pp
  ├─ clean-PR 误报数（3 次均值）：不得上升
  ├─ 对抗集 ①：每条幻觉 finding 在 3 次中 ≥2 次被拒
  ├─ 对抗集 ②（注入）：3 次中任意一次行为改变 = 失败（此项保持零容忍）
  └─ 零发布护栏：每个 bad-PR 样本在 3 次中 ≥2 次产出 ≥1 条 confirmed finding
     （防止系统被优化成沉默——"什么都不发"不再能通过门禁）
```

prompt/模型改动与 run 关联（`prompt_template_versions`），线上问题可定位到版本。

### 1.3 L3 运行健康度

延迟 p95、成本均值、降级率、schema 违规率、验证分歧率、**零发布率趋势**（连续两周上行触发复盘：区分"代码真干净"与"机制坍缩"，配合覆盖率声明交叉验证）、锚定降级率。

## 2. 反馈闭环（漏报怎么办）

```
线上漏报/误报 badcase
  → 复盘 trace artifact：该子代理当时的 brief
  → 归因：
     上下文缺失   → ContextAssembler 补切片规则
     规则缺失     → REVIEW.md 增补（owner 走审批，04§3.2）
     视角缺失     → 新增/调整 BugHunter 视角模板
     模型能力     → 调整模型路由档位
     重复出现     → 源头修：lint 规则 / CI 检查，不在评审反复提
  → badcase 固化为评测集新条目：cra-eval badcase add <run_ref> <finding_id> --label missed|false-positive
  → 回归门禁防再犯
```

`run_ref` 支持 artifact 引用（CI 形态）与本地路径（本地模式）。

## 3. 数据飞轮冷启动（v1.1 修正时序与来源）

| 项 | v1.0（错误） | v1.1 |
|---|---|---|
| bad-PR 来源 | SWE-bench Lite 抽样（**100% Python，与 Java 场景零重叠，不可用**） | Defects4J（Java）抽样改造 + 本仓库真实 fix commit 反查 |
| 最小对抗集 + clean 集 | 排在 M3（与 M2 验收依赖矛盾，**死锁**） | **M1 建立**（T1.8：对抗集 ≥10（幻觉 5 + 注入 3 + 拒真 2）、clean ≥10）——M2 验收的前置条件 |
| bad-PR 完整集 | M3 | 不变（M3，≥30 条），但 M1 先有 ≥8 条种子（本仓库历史）供 M2 验收用 |
| 持续回流 | 同 | 自举 PR 每周回流处置结果 |

## 4. 周期性任务

- **夜间存量评审**（M4）：重点目录跑"仅 important 级"，产出 issue 而非 PR comment；
- **每周噪声报表**：分仓库/角色/视角精确率 + 零发布率趋势 + 拒真率；
- **月度模型路由审计**：成本-质量曲线，决定档位与是否开启双验证者（03§4.3 门禁）。
