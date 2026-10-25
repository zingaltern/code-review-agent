# Review Policy: report

## Product context
- 财务报表的 totals 必须与明细表可对账；舍入口径由财务口径评审确定。
- 退款报表与销售报表共用同一对账规则。

## Known incidents
- 2026-07-19 对账差 1 分事故（INC-1932）：明细逐行四舍五入、汇总先求和后四舍五入，两表口径不一致，月度对账失败。
- 2026-07-25 修复（commit 5b81e77）：报表 totals 统一采用先求和后舍入（HALF_UP，两位小数）。

## Sniff tests
- 导出报表的 totals 口径必须与明细表一致。
