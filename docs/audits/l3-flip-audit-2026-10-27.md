# L3 翻转 case 断言审计（2026-10-27，owner 决策 9B）

- 输入：上一轮官方 L3（run 40342221542，78/90）与 M4 后官方 L3（run 40416120356，71/90），
  以及两轮 `cases/**/trace.ndjson`。
- 对象：bad-Closure-1/3、bad-Gson-5/6/7、bad-JacksonCore-8、bad-Mockito-3/9（外加 Mockito-10 回补）。
- 方法：逐 case 对照 `expected_findings.json`（file/line/category/keywords）与实际 published
  findings + verifier/judge verdict；分类：
  - **A 断言本身有错**：行号/类别/关键词与真实 finding 语义不符，且可直接修正；
  - **B 验证层方差/误拒**：候选正确但被 REFUTED/INSUFFICIENT；
  - **C 无候选/真 miss**：没有产生可用候选；
  - **D 类别口径**：评审把同一缺陷写成 `test` 类，expected 只有 `bug` 条目（不是断言错，属口径选择）。
- 约束：本审计不改阈值/门禁/数据集；所有修改建议等 owner 批准。

## 逐 case 结果

| case | 旧 → 新 | 变化 | 分类 | 证据 | 建议 |
|---|---|---|---|---|---|
| bad-Closure-1 | 3/3 → 2/3 | -1 | **C** | 新 run2 published=0，trace 无 verification 事件（没有候选） | 不改断言；属发现层方差 |
| bad-Closure-3 | 2/3 → 1/3 | -1 | **B** | 新 run0 `REFUTED×2 + INSUFFICIENT`，run2 `INSUFFICIENT×2`；新 run1 仅 test L367 命中 | 不改断言；验证层问题 |
| bad-Gson-5 | 3/3 → 2/3 | -1 | **C/D** | 新 run1 只发布 test L209（“缺少测试”）+ test L116，无 bug L212 候选 | bug 断言无错；若 owner 认可 test OR 口径，可加 L209 test（类似 M3 校准），但不在本次自动改 |
| bad-Gson-6 | 3/3 → 2/3 | -1 | **C/D** | 新 run1 只发布 test L67（null 路径无测试），无 bug L68 候选 | 同上；不自动改 |
| bad-Gson-7 | 3/3 → 2/3 | -1 | **B** | 新 run1 published=0、`REFUTED×2`；run0/2 仍命中 | 不改断言；验证层问题 |
| bad-JacksonCore-8 | 1/3 → 0/3 | -1 | **B** | 新 3 个 run 全部 `REFUTED`；verifier 认为调用方不会把 null 当 char[] 用 | 不改断言；这是典型验证层误拒（真实 D4J bug） |
| bad-Mockito-3 | 3/3 → 2/3 | -1 | **A** | 新 run0 发布 bug L132，expected line=126，Δ=6 > ±5；旧 run bug 在 L129（Δ3） | **建议 expected line 126→129/130**（覆盖 L129/L132，仍锚同一方法），属锚点容差修正 |
| bad-Mockito-9 | 2/3 → 1/3 | -1 | **B** | 新 run0 `INSUFFICIENT`、run1 `REFUTED`；run2 bug L35 命中 | 不改断言；验证层问题 |
| bad-Mockito-10 | 2/3 → 3/3 | +1 | — | 新 run0/1/2 全命中（test/bug 混合） | 无 |

净变化：8 个丢失观测，1 个回补（Mockito-10），净 -7（78→71）。

## 结论

1. **A 类只有 1 处**：bad-Mockito-3 的 expected line 126 与真实锚点 L132 相差 6，超出 ±5；
   把期望行改为 129（或 130）可同时覆盖 L129 与 L132，语义不变。修正后本轮理论值
   **72/90 = 0.800，Wilson 下界 0.7059 ≥ 0.70**（刚过线）。
2. **B 类 4 处**（Closure-3、Gson-7、JacksonCore-8、Mockito-9）：候选正确但被验证层
   REFUTED/INSUFFICIENT；不改 expected，应该由 L3 稳定性实验（temperature/verifier 上下文）
   处理。JacksonCore-8 是最明确的误拒样本（真实 D4J #182）。
3. **C/D 类 3 处**（Closure-1、Gson-5、Gson-6）：Closure-1 是无候选；Gson-5/6 是评审把
   同一缺陷写成 `test` 类。后者若加 test OR 条目可多命中，但这是**口径选择**（M3 有先例），
   不是断言错误；本审计不自动改。
4. **不建议只为让本轮变绿而改 Mockito-3 锚点**：虽然 72/90 会过线，但它是单观测收益，
   且会与“不按运行结果放宽”的纪律混淆。建议与 L3 稳定性实验一起决定：
   - 若确认 L132 是合理的稳定锚点，做一次有证据的锚点修正 + 重跑 L3；
   - 否则接受 71/90 已知红（owner 决策 1A），把精力放在 B 类验证层。
5. 所有 expected 关键词均为中文行为词，实际 finding 也以中文为主；本轮未见英文关键词
   系统性漏配。**不改任何关键词/类别/阈值。**

## 复现

```bash
# 报告
# 旧：run 40342221542 artifact report-all-k3.json
# 新：run 40416120356 artifact report-all-k3.json
# trace：两轮 artifact 内 cases/<case>/run-<k>/run_*/trace.ndjson
# expected：cra-eval/dataset/bad/<case>/expected_findings.json
```

审计脚本/人工核对口径见本文件表格；未修改仓库数据。
