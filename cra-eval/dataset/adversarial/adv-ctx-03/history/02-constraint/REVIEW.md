# Review Policy: catalog-api

## Product context
- 商品/订单列表分页接口由企业前端统一控件渲染，控件按后端默认页大小出码与排版。
- 分页默认值属于对外契约的一部分，调整默认值必须先过 UI 联调评审。

## Known incidents
- 2026-08-21 分页错位事故（INC-2145）：某服务把默认 page size 提到 100，前端控件仍按 20 一屏渲染，列表出现截断与翻页错位，累计工单 47 张。
- 2026-09-20 修复（commit 3a7f5c2）：默认 page size 固定 20，落入 PageRequest.DEFAULT_PAGE_SIZE 并注释约束；未过 UI 联调评审前禁止调整。

## Priority paths
- src/main/java/com/example/pagination/**
