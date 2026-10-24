# 06 · 评测体系与反馈闭环

> 文档版本：v1.0（2026-10-25）
> 设计公理三：不可度量则不可改进。本模块与多代理机制同等重要。

---

## 1. 三层度量体系

| 层 | 问题 | 数据源 | 频率 |
|---|---|---|---|
| L1 在线噪声度量 | 发布的 finding 人类怎么看？ | GitHub comment 处置信号 | 每周 |
| L2 离线评测集回归 | 改动 prompt/模型后质量变了吗？ | 本地 fixture 数据集 | 每次改动（CI 门禁） |
| L3 运行健康度 | 系统自身稳吗、贵吗？ | run stats + trace | 持续 |

### 1.1 L1 在线噪声度量（照搬 Anthropic 的 comment 分类法）

每条已发布 finding 的人工处置被归为四类，通过两路信号自动/半自动采集：

| 处置 | 判定信号（按优先级） |
|---|---|
| **fixed** | finding 涉及行在后续 commit 被修改且模式匹配 suggestion（自动初判） |
| **wrong** | 回复含 `:wrong:` / `not-a-bug` 标签；或作者 reply 反驳且 reviewer 复核（人工标注） |
| **intentional** | 回复 `:intentional:` / resolve without change（自动） |
| **ignored** | 7 天无任何反应（自动） |

核心指标：

```
发布精确率 = (fixed + intentional) / 全部已发布
误报率     = wrong / 全部已发布
```

目标（01§4.4）：精确率 ≥80%、误报率 ≤15%。周报由 `cra-eval report` 从 ReviewStore + GitHub 拉取生成。

### 1.2 L2 离线评测集（回归门禁）

**数据集构成**（`cra-eval/src/main/resources/datasets/`）：

1. **bad-PR 集**：历史真实造成 bug 的 PR（从自己仓库 revert/fix commit 反查 + 专家标注预期 finding），每条 = `{diff, 相关文件, expected_findings[{file,line,category,assertion关键词}]}`；
2. **clean-PR 集**：确认无问题的 PR，用于测纯误报率（Anthropic 对已知干净 PR 跑评审的实践）；
3. **对抗集**：构造的经典陷阱（注入式幻觉诱导——finding 描述得头头是道但代码里根本不存在的模式），专测 verifier 的证伪能力。

**门禁流程**：

```
改 prompt/模型/编排 → cra-eval run --dataset all
  ├─ bad-PR 检出率（recall proxy）不得下降超过 2pp
  ├─ clean-PR 误报数不得上升
  └─ 对抗集：幻觉 finding 必须 100% 被 verifier 拒绝
不达标 → 禁止合并（CI 强制）
```

`prompt_template_version` 与每个 run 关联：线上出问题能定位到是哪版 prompt 的锅。

### 1.3 L3 运行健康度

每次 run 的 stats 聚合（延迟 p95、成本均值、降级率、schema 违规率、verifier 分歧率），异常阈值告警（如分歧率突增 = 模型或 prompt 退化的前兆）。

## 2. 反馈闭环（漏报怎么办）

Anthropic 的复盘纪律：**漏报时问"模型当时缺什么上下文"**，然后补到对应层：

```
线上漏报/误报 badcase
  → 复盘 trace：该子代理当时看到了什么 brief
  → 归因：
     上下文缺失   → ContextAssembler 补切片规则（代码改动）
     规则缺失     → REVIEW.md 增补约定（仓库 owner 动作）
     视角缺失     → 新增 BugHunter 视角模板（prompt 改动）
     模型能力     → 调整模型路由档位
     重复出现     → 在源头修：lint 规则 / CI 检查，而不是评审反复提
  → badcase 固化为评测集新条目 → 回归门禁防再犯
```

`cra-eval badcase add <run_id> <finding_id> --label missed|false-positive` 一条命令完成固化。

## 3. 数据飞轮的冷启动

M0-M1 没有历史数据时的顺序：

1. 用公开的 bug-fix commit 集（如 SWE-bench Lite 抽样、本项目历史 fix commit）做 bad-PR 集 v0（≥30 条）；
2. clean-PR v0 用本仓库早期 chore PR（≥20 条）；
3. 自举：本项目每个 PR 都跑自己，处置结果每周回流数据集；
4. 对抗集 v0 手工构造 10 条典型幻觉模式（引用不存在的行为、行号张冠李戴、忽略调用方已有防护、把 pre-existing 当新引入）。

## 4. 周期性任务（M3 后由 cron/server 承接）

- **夜间存量评审**（nightly legacy review）：对重点目录跑"只找 important 级"的评审，产出 issue 而非 PR comment（Anthropic nightly agents 实践）；
- **每周噪声报表**：分仓库/分角色/分视角的精确率，定位哪个视角在放噪声；
- **月度模型路由审计**：成本-质量曲线，决定档位调整。
