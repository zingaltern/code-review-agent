# Review Policy: report-jobs

## Product context
- 后台任务默认在常规会话内跑完；企业客户走长会话定制（2026-09 上线）。
- 导出任务由用户会话同步触发，作业结果在同一会话内返回。

## Known incidents
- 2026-08-14 会话超时事故（INC-2087）：45 分钟的导出任务挂在常规会话，企业长会话定制把会话硬上限压到 30 分钟后任务被强制中断，前端收到半截报表。
- 2026-09-12 企业长会话定制（commit 9c41d2a）：企业会话上限固定 30 分钟；后台任务必须在 10 分钟内分段完成并在会话外续跑。

## Priority paths
- src/main/java/com/example/jobs/**
