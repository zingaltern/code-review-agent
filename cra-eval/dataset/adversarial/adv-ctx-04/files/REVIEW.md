# Review Policy: export-svc

## Product context
- 报表导出必须异步：提交后立即返回任务号，执行在会话外，完成后经站内信与邮件通知返回结果。
- 企业客户会话有 30 分钟硬上限，任何在会话内同步等待长任务完成的实现都会被强制中断。

## Known incidents
- 2026-09-06 企业导出超时事故（INC-2201）：同步导出占住会话等待，企业长会话 30 分钟上限触发强制中断，用户丢失全部未保存的筛选条件。
- 2026-09-20 修复（commit 8c2e91a）：导出改异步——ExportService 只提交任务即返回；禁止在会话内同步等待导出结果。

## Priority paths
- src/main/java/com/example/export/**
