# Owner 裁决记录（2026-10-27）

> 本文件记录 M0–M4 收口阶段 owner 对历史遗留问题的逐项裁决与落地状态。
> 只记录决策与指向；设计细节见对应 ADR/审计/PR。

| # | 议题 | 裁决 | 状态 / 落点 |
|---|---|---|---|
| 1 | 官方 L3 bad 召回 71/90（Wilson 下界 0.6937 < 0.70） | **A：接受为已知红，不调阈值** | 已记录于 docs/07「M4 收口记录」；不自动重跑；后续由 L3 稳定性实验决定是否定向改进 |
| 2 | L2 报告的 squash 绑定失效 | **C：长期采用服务端签名报告** | 设计稿 ADR-0010（PR #11）；实现待 owner 确认方案 A 与强制签名时点；过渡期每个改核心代码的 PR 重跑 L2 |
| 3 | M4b/M4c/M4d 真实环境未验证 | **A：做真实 GitHub 端到端验收** | 验收结果 PR #10（open，CI 绿）；见 docs/deployment-server.md §9 |
| 4 | M4b 报告签发未按 repo 限定 | **A：按 repo 限定** | 已实现并合并：报告签名 v2 + `X-CRA-Repo` + `(repo, commit)` 查询（PR #14 / ADR-0009） |
| 5 | 评测门禁代码改动不触发 L2 | **A：纳入 L2 触发范围** | 已实现并合并（PR #9）：`VerifyCommand/ReportHasher/GateEvaluator/GatePolicy/AggregateEngine` |
| 6 | 文档与实现不一致 | **A：做文档一致性清理** | PR #8（open，CI 绿）；本文件不与其争用 README/docs/07 |
| 7 | M4e / M4f-full 未完成 | 按提示词继续开发 | M4e GitLab 适配 PR #13（open）；M4f-full 未在运行列表，待启动（需先定数据源） |
| 8 | CrossChecker 默认关闭 | **B：保持默认关闭** | `CRA_CROSSCHECKER_ENABLED=false`；开启约 +20k token/次；按仓库/路径按需开启 |
| 9 | L3 翻转 case 的 expected 是否过严 | **B：审计，只修正确有错误者** | 审计报告 docs/audits/l3-flip-audit-2026-10-27.md（PR #12）：A=1（Mockito-3 锚点）、B=4（验证层误拒）、C/D=3（无候选/类别口径）；本审计未改数据/阈值 |
| 10 | L2/L3 模型与温度不统一 | **A：标准化** | 暂定 opencode-go `deepseek-v4.1-flash` + temperature 0.2（与官方 L3 一致）；本地 `deepseek-chat` L2 报告标记为非标准；暂不重跑历史报告；待 L3 稳定性实验结果最终确认 |
| 11 | 是否立即打 v0.5.0 | 等真实环境验收 + L3 决策后再打 | 当前不打 tag；真实验收 PR #10、L3 稳定性实验仍在进行 |
| 12 | M4a 签字建议是否强制 | **保持“只建议”** | `agentApprover()` 恒 false；不接入 branch protection；如需强制走平台策略 |
| 13 | M4c/M4d 分支过滤/cron | 真实环境验收时确认后再调 | 当前 push 仅 `main`、nightly 18:00 UTC；见 PR #10 验收记录与部署文档 |

## 关联链接

- 决策 5A：PR #9（`ci.yml` L2 触发正则）
- 决策 2C：ADR-0010（`docs/adr/0010-server-signed-l2-reports.md`）
- 决策 4A：ADR-0009（`docs/adr/0009-repo-scoped-report-attestation.md`）、PR #14
- 决策 9B：`docs/audits/l3-flip-audit-2026-10-27.md`
- 决策 3A/13：PR #10、`docs/deployment-server.md` §9
- 决策 6：PR #8（文档一致性）
- 决策 10：L3 稳定性实验进行中；实验结论出来后更新本条
