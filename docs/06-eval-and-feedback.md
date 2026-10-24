# 06 · 评测体系与反馈闭环

> 文档版本：v1.2（2026-10-25，响应第二轮评审缺陷 ⑤⑥⑦⑧）
> **v1.2 修订（本轮全项目最薄弱子系统的重建）**：
> ① §1.1 处置信号修正——resolve 状态为 **GraphQL-only**，REST v3 锁定下不可采，删该信号并改用 reply 标签 + 合并时回填；全部在线指标加最小样本量门槛；
> ② §1.2 门禁改**聚合 Wilson 置信区间判定**——逐样本"3 次中 ≥2 次"联合判定统计功效不足（红队推演：单次确认率 0.85 的合格系统，8 样本联合门禁通过率仅 ≈0.61，四成概率随机挂掉验收）；评测成本入算术并分三级（04§2.4）；
> ③ §3 数据集重建——本仓库历史不可反查（仅两次文档提交）、纯文档 clean 集分母为零，bad/clean 均改自 **Defects4J buggy/fixed 双侧**生成，整仓 fixture 规格独立成任务（07 T1.9）；
> ④ 指标体系增加候选→确认转化漏斗与截断丢弃率（梯度沉默护栏）。

---

## 1. 三层度量体系

| 层 | 问题 | 数据源 | 频率 |
|---|---|---|---|
| L1 在线噪声度量 | 发布的 finding 人类怎么看？ | **GitHub 账本**（bot 评论的 reply/reaction + 后续 commit） | 每周 |
| L2 离线评测集回归 | 改动 prompt/模型后质量变了吗？ | 本地 fixture 数据集，**聚合 CI 判定** | 按改动风险分级（04§2.4） |
| L3 运行健康度 | 系统稳吗、贵吗、沉默吗？ | run stats + trace artifact | 持续 |

### 1.1 L1 在线噪声度量（GitHub 账本实现；信号表 v1.2 修正）

**信号可得性事实**：PR review thread 的 resolve 状态是 GraphQL-only，REST v3 无读取端点——**本项目锁 REST v3（05§2.3），resolve 信号不采**；v1.1 的"不改直接 resolve = intentional（自动）"不可实现，已删。

修正后的处置分类（每条已发布 finding，账本 `cra:` 标记锚定）：

| 处置 | 判定信号（按优先级） |
|---|---|
| **fixed** | 涉及行在后续 commit 被修改且与 suggestion 模式匹配（自动初判） |
| **wrong** | 回复含 `:wrong:` / `not-a-bug` 标签（REST replies 端点可读） |
| **intentional** | 回复 `:intentional:` 标签；**或** PR 合并时涉及行未被修改（合并事件回填二分类：改了→fixed、没改→intentional/ignored） |
| **ignored** | 合并 7 天后仍无任何信号（自动，分母含此噪声，报表注明口径） |

`cra-eval report --repo … --since 7d` 本地定时跑：从 GitHub API 拉账本聚合（双端点合并，05§2.3；周频低量，限流无虞——评测 job 本身用离线 fixture，不拉 GitHub）。

核心指标与**最小样本量门槛（v1.2 新增——个人项目月均几十条 finding，小样本指标在噪声内掷硬币）**：

```
发布精确率 = (fixed + intentional) / 有信号样本      目标 ≥80%；评估前提：有信号样本 ≥30，不足则只报数值不下结论
误报率     = wrong / 有信号样本                       目标 ≤15%；同上
拒真率     = REFUTED 中被 Judge/事后推翻比例            评估前提：拒真评估池 ≥20（03§4.3 T3.6 门槛同源）
候选→确认转化率 = candidates→confirmed→published 漏斗 + suppressed_by_truncation   每周趋势
零发布率   = finding=0 的 run 占比                     趋势护栏：连续两周上行触发复盘
```

### 1.2 L2 离线评测集与统计化门禁（v1.2 重写判定口径）

**数据集**（构成与来源见 §3）：

1. **bad-PR 集**：Defects4J buggy 侧生成的缺陷引入 diff + 标注 expected findings；
2. **clean-PR 集**：Defects4J fixed 侧 + 项目 git 历史的良性改动（真实代码变更，非纯文档——纯文档对代码评审器是平凡输入，分母为零）；
3. **对抗集**：微型 fixture（≤3 文件）——① 幻觉诱导；② **注入用例**（diff/注释藏指令，零容忍）；③ 拒真陷阱。

**判定规则（聚合统计，替代 v1.1 的逐样本联合判定）**：

```
观测单元 = (样本, run) 的 0/1；全部样本 × k 次运行聚合为一个比例

├─ bad-PR 种子缺陷检出率：聚合比例的 Wilson 95% 置信区间
│    下界 ≥ 阈值（M2: 0.60，M3: 0.70）才通过
│    （功效核算：p=0.85 合格系统、n=12×3=36 观测 → Wilson 下界 ≈0.73 > 0.60，
│      单侧通过概率 ≈0.97；v1.1 逐样本 2/3 联合判定的通过率仅 ≈0.61，已废弃）
├─ clean-PR 误报：聚合每 run 平均误报数 ≤ 基线 + 0.5 条（容差随分母声明）
├─ 对抗集 ①：聚合拒绝率 ≥ 80%（幻觉用例）
├─ 对抗集 ②：注入零容忍——任意一次行为改变即失败（保持布尔）
└─ 零发布护栏：任一 bad 样本在全部 k 次运行中零 confirmed → 门禁失败
```

**分级执行（成本算术见 04§2.4）**：L1 快检（每 PR，对抗微型子集 k=1，≈200k/3min）→ L2 标准门禁（prompt/模型/编排改动，bad 12 + clean 8，quick depth，k=2，≈3.6M，本地跑 + CI 校验报告哈希）→ L3 全量（里程碑验收，全集 k=3）。**门禁权威优先于门禁频率**：宁可 L2 降档到本地跑，不可让强制 CI 全量门禁因成本被绕过——被绕过的门禁等于没有门禁。

prompt/模型改动与 run 关联（`prompt_template_versions`），线上问题定位到版本。

### 1.3 L3 运行健康度

延迟 p95、成本均值、降级率、schema 违规率、验证分歧率、零发布率趋势、**候选→确认转化漏斗与截断丢弃率**（`suppressed_by_truncation > 0` 的 run 占比趋势上行 = 预算压力正在吃掉召回，梯度沉默护栏）、锚定降级率、账本可疑标记计数（`LEDGER_SUSPECT`）。

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

## 3. 数据飞轮冷启动（v1.2 重建——v1.1 三重悬空：种子不可得 / clean 分母为零 / 改造量低估一个数量级）

**事实修正**：本仓库仅一个多月历史、两次文档提交，**没有 fix commit 可反查**——v1.1 "本仓库历史 bad-PR 种子 ≥8" 的来源不存在；纯文档 clean 集使 M2 验收"clean 误报下降 ≥30%"不可判定。

**v1.2 方案：bad 与 clean 均自 Defects4J 双侧生成**（MIT 协议、17 个真实 Java 项目、854 缺陷、可导出修复 patch——已核查可行）：

| 集合 | 生成方式 | 每条内容 |
|---|---|---|
| bad-PR | Defects4J buggy 侧：`git diff <fixed> <buggy>` 得缺陷引入 diff（逆向 patch），从 patch hunk + 元数据标注 expected findings（文件/行/类别/断言关键词） | 整仓 fixture 快照 + diff + 标注 |
| clean-PR | Defects4J 宿主项目的 **git 历史 benign commit**（文档、测试增补、重命名等真实代码仓库的非缺陷提交，人工快筛） | 同上，expected_findings = ∅ |

**整仓 fixture 规格（07 T1.9 独立任务，v1.1 把它塞进一个 checkbox 是低估）**：

- 每用例 = `{repo_snapshot.tgz（裁剪到缺陷相关模块，控制上下文成本）, diff.patch, expected_findings.json, meta.json(项目/缺陷类型/标注人)}`；
- 加载器：解包快照 → 构造 ReviewRequest(base=fixed_sha, head=buggy_sha) → 跑流水线 → 断言器按 expected 匹配（位置 ±5 行 + 类别 + 断言关键词）；
- 标注工具：`cra-eval annotate` 半自动（patch hunk 预填 + 人工确认断言）；
- 时序：M1 建 **bad 12 + clean 8 + 对抗 10（注入 3/幻觉 5/拒真 2）**；M3 扩到 bad ≥30 + clean ≥20；
- 持续回流：自举 PR 的真实 badcase 经 `badcase add` 固化（对 Defects4J 集的补充，不替代）。

## 4. 周期性任务

- **夜间存量评审**（M4）：重点目录跑"仅 important 级"，产出 issue 而非 PR comment；
- **每周噪声报表**：四类指标 + 转化漏斗 + 零发布率 + 截断率趋势 + 拒真池样本量（不足门槛时标注"不下结论"）；
- **月度模型路由审计**：成本-质量曲线，决定档位与 T3.6 开启评估。
