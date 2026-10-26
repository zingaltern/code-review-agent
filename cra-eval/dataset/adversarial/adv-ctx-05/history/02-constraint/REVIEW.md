# Review Policy: stats-api

## Product context
- 统计/报表接口的金额字段直接面向对外 JSON 输出，口径由财务统一维护。
- 序列化层对金额字段有固定渲染管线，依赖 BigDecimal 类型与两位小数标度。

## Known incidents
- 2026-08-05 对账差额事故（INC-2176）：统计接口金额用 double 承载，二进制浮点累计误差导致与账务系统差额 0.01–0.03 元。
- 2026-08-12 修复（commit 5d94b17）：对外金额字段统一 BigDecimal（scale=2），MoneyFormat 提供唯一格式化出口。

## Sniff tests
- 对外金额字段必须 BigDecimal、scale=2，禁止 double；序列化固定两位小数。
