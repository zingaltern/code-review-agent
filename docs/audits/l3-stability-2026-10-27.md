# L3 验证层稳定性实验（2026-10-27，owner 决策 10A 配套）

- 目标：判断 `temperature=0.0` 是否比现行 `0.2` 更稳定/更高召回；不调阈值、不改 expected。
- 对象：M4 后官方 L3 翻转的 8 个 case（Closure-1/3、Gson-5/6/7、JacksonCore-8、Mockito-3/9）。
- 口径：`set=bad` + `cases=8`、k=3、depth=standard、tier=l3；两组仅 temperature 不同。
- 运行：temperature 0 → run `40536199041`；temperature 0.2 → run `40536205522`（main `e89f8c7`）。

## 结果

| 指标 | temp 0.0 | temp 0.2 |
|---|---|---|
| 聚合检出 | **19/24 = 0.792** | **19/24 = 0.792** |
| 零发布观测 | 5/24 = 0.208 | 3/24 = 0.125 |
| 全零样本 | **1/8**（JacksonCore-8） | **1/8**（JacksonCore-8） |
| budget_exceeded | 2（Gson-7） | 0 |

> 注意：这是 8 case 子集，Wilson 下界 0.5953，不可与全量 L3 的 0.70 直接比较。

### 逐 case 检出（detected/3）

| case | temp 0.0 | temp 0.2 | 变化 |
|---|---|---|---|
| bad-Closure-1 | 3/3 | 3/3 | — |
| bad-Closure-3 | 2/3 | 3/3 | +1 |
| bad-Gson-5 | 3/3 | 3/3 | — |
| bad-Gson-6 | 3/3 | 2/3 | -1 |
| bad-Gson-7 | 2/3 | 3/3 | +1 |
| **bad-JacksonCore-8** | **0/3** | **0/3** | —（两次均 3 次 REFUTED） |
| bad-Mockito-3 | 3/3 | 2/3 | -1 |
| bad-Mockito-9 | 3/3 | 3/3 | — |

### 验证层分布（trace）

- **JacksonCore-8：两配置均 3 次 `REFUTED`**——候选被验证层以“调用方不会把 null 当 char[] 用”反证，
  属系统性误拒，不是温度能解决的问题。
- Closure-3：temp0 `REFUTED×4 + CONFIRMED×2 + INSUFFICIENT×1`；temp0.2 `REFUTED×3 + CONFIRMED×4`。
- Gson-7：temp0 `CONFIRMED×2 + REFUTED×4 + INSUFFICIENT×4 + budget×2`；temp0.2 全部 `CONFIRMED×10`。
- Gson-6：temp0 `CONFIRMED×6`；temp0.2 `CONFIRMED×5`。
- Mockito-3：两配置均 `CONFIRMED×13`；temp0.2 少 1 个观测命中（行锚/候选差异）。

## 结论

1. **temperature=0 没有带来净收益**：聚合检出相同（19/24），只是逐 case 互相翻转；0.0 还出现 2 次输出预算击穿，0.2 为 0。
2. **维持标准温度 0.2**（与官方 L3 配置一致）；不因本实验改默认值，也不改阈值/断言。
3. **主要问题在验证层误拒**（JacksonCore-8 两次 0/3、全部 REFUTED）和发现/复核的逐 case 方差，不是采样温度。
   若要继续压低 L3 红项，应做“验证层定向改进”（例如给 bug 类候选更多代码/测试上下文、复核提示词校准），
   属于新的实验，需另立任务并遵守 L2/L3 成本纪律。
4. Mockito-3 的 expected 锚点（9B 审计建议 L126→129/130）仍未改；本实验不支持“为变绿改锚点”。

## 复现

```bash
CASES="bad-Closure-1,bad-Closure-3,bad-Gson-5,bad-Gson-6,bad-Gson-7,bad-JacksonCore-8,bad-Mockito-3,bad-Mockito-9"
# temperature=0 / 0.2 各一次
gh workflow run eval.yml --ref main -f runs=all -f set=bad -f k=3 -f depth=standard \
  -f tier=l3 -f cases="$CASES" -f crosschecker=false -f temperature=0
```

报告 artifact：`eval-reports-40536199041`（0.0）、`eval-reports-40536205522`（0.2）。
两组 workflow 整体结论为 failure 是 `bad_detection`/零发布在 8 case 子集下红（`--fail-on-red` 生效），报告仍完整产出。
