# Review Policy: daily-report

## Product context
- 日报面向跨时区企业客户：存储层日期口径统一，展示层按客户时区渲染。
- 报表日期参与对账与计费窗口切分，口径错误会放大为资金问题。

## Known incidents
- 2026-09-01 日报错日事故（INC-2210）：报表按服务器本地时区写日期，海外客户日报整体偏移一天，计费窗口错切，涉及 312 个租户。
- 2026-09-20 修复（commit 6d0f3b1）：报表日期一律 UTC 存储（ReportDateUtil.STORAGE_ZONE），展示层按客户时区转换；存储口径禁止改用业务/服务器时区。

## Priority paths
- src/main/java/com/example/report/**
