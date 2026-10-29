# Owner 裁决记录（2026-10-27）

> 本文件记录 M0–M4 收口阶段 owner 对历史遗留问题的逐项裁决与落地状态。
> 只记录决策与指向；设计细节见对应 ADR/审计/PR。

| # | 议题 | 裁决 | 状态 / 落点 |
|---|---|---|---|
| 1 | 官方 L3 bad 召回 71/90（Wilson 下界 0.6937 < 0.70） | **A：接受为已知红，不调阈值** | 已记录于 docs/07「M4 收口记录」；不自动重跑；后续由 L3 稳定性实验决定是否定向改进 |
| 2 | L2 报告的 squash 绑定失效 | **C：长期采用服务端签名报告** | 设计稿 ADR-0010（PR #11）；实现待 owner 确认方案 A 与强制签名时点；过渡期每个改核心代码的 PR 重跑 L2 |
| 3 | M4b/M4c/M4d 真实环境未验证 | **A：做真实 GitHub 端到端验收** | **已完成**：PR #10 合并 `55dc873`；证据见 docs/deployment-server.md §9 |
| 4 | M4b 报告签发未按 repo 限定 | **A：按 repo 限定** | 已实现并合并：报告签名 v2 + `X-CRA-Repo` + `(repo, commit)` 查询（PR #14 / ADR-0009） |
| 5 | 评测门禁代码改动不触发 L2 | **A：纳入 L2 触发范围** | 已实现并合并（PR #9）：`VerifyCommand/ReportHasher/GateEvaluator/GatePolicy/AggregateEngine` |
| 6 | 文档与实现不一致 | **A：做文档一致性清理** | **已完成**：PR #8 合并 `b17c580`（含 4A/5A 口径与 README 限制） |
| 7 | M4e / M4f-full 未完成 | 按提示词继续开发 | **M4e GitLab 两半已合并**：PR #13 `f7fa5f9`（适配器，ADR-0011）+ PR #17 `c364323`（CLI/server 平台选择与 GitLab webhook，ADR-0013）；真实 GitLab 写操作仍待 owner token；**M4f-full 已合并**：PR #18 `b418c0b`（事件史 JSONL→PG→CrossChecker，ADR-0012，默认关闭；Jira/Confluence 等外部源未接）；Gerrit 未做；**语义索引/跨文件上下文第一版已合并**：PR #22 `477f6bd`（ADR-0014，默认关闭；L2 56/60=0.933） |
| 8 | CrossChecker 默认关闭 | **B：保持默认关闭** | `CRA_CROSSCHECKER_ENABLED=false`；开启约 +20k token/次；按仓库/路径按需开启 |
| 9 | L3 翻转 case 的 expected 是否过严 | **B：审计，只修正确有错误者** | 审计报告 docs/audits/l3-flip-audit-2026-10-27.md（PR #12）：A=1（Mockito-3 锚点）、B=4（验证层误拒）、C/D=3（无候选/类别口径）；本审计未改数据/阈值 |
| 10 | L2/L3 模型与温度不统一 | **A：标准化** | opencode-go `deepseek-v4.1-flash` + **temperature 0.2 维持**；本地 `deepseek-chat` L2 报告标记非标准。**稳定性实验完成（2026-10-27）**：8 个翻转 case × k=3，0.0 与 0.2 聚合检出同为 19/24；0.0 多 2 次预算击穿、0.2 零发布观测更少（3/24 vs 5/24）；维持 0.2，详见 `docs/audits/l3-stability-2026-10-27.md` |
| 11 | 是否立即打 v0.5.0 | 等真实环境验收 + L3 决策后再打 | **条件已满足**：真实验收 PR #10、L3 裁决 1A、稳定性实验完成；**v0.5.0 本轮打**（tag 说明列出已知红与待办） |
| 12 | M4a 签字建议是否强制 | **保持“只建议”** | `agentApprover()` 恒 false；不接入 branch protection；如需强制走平台策略 |
| 13 | M4c/M4d 分支过滤/cron | 真实环境验收时确认后再调 | 真实验收已完成（PR #10 `55dc873`）；当前 push 仅 `main`、nightly 18:00 UTC；见部署文档 §9 |

## 关联链接

- 决策 5A：PR #9（`ci.yml` L2 触发正则）
- 决策 2C：ADR-0010（`docs/adr/0010-server-signed-l2-reports.md`）
- 决策 4A：ADR-0009（`docs/adr/0009-repo-scoped-report-attestation.md`）、PR #14
- 决策 9B：`docs/audits/l3-flip-audit-2026-10-27.md`；**验证层误拒子类已定向修复**（ADR-0015 + PR #21，见文末「验收状态更新（2026-10-28 验证层反证纪律）」）
- 决策 3A/13：PR #10、`docs/deployment-server.md` §9
- 决策 6：PR #8（文档一致性）
- 决策 10：L3 稳定性实验已完成，`docs/audits/l3-stability-2026-10-27.md`；维持 temperature 0.2

## 验收状态更新（2026-10-27 收尾）

- **PR #10（真实验收）**：合并 `55dc873`；scratch 仓库 run/commit comment/issue 证据已独立核验（run 40488612581/40488832763/40489412861/40489683682；comments 206848589/206848762；issues #5/#6/#7）。
- **PR #8（文档一致性）**：合并 `b17c580`；已修正 4A 签名 v2、5A L2 触发、真实 GitHub 验收、单租户限制、L3 owner 裁决 A 等过期口径。
- **PR #13（M4e GitLab 第一半）**：合并 `f7fa5f9`；ADR 由 0009 重号为 0011，模块测试 58 例、verify（含 PG）与 eval-l1 绿；CLI/server 接线与真实 GitLab 写操作 pending。
- **L3 稳定性实验**：运行方报告已完成，但仓库分支、PR、eval run、artifact 与记忆均未留下可验收落点；**本记录不将其计为已验收**，需提交报告/分支/run id 后再独立验收。

## 验收状态更新（2026-10-27 收尾第二轮）

- **PR #18（M4f-full）**：合并 `b418c0b`；L2 52/60 = 0.867（WL 0.7583）、clean 0.93、零发布 0/30、100/100；事件史默认关闭，只接 JSONL；Jira/Confluence/ServiceNow/Slack/PagerDuty/GitHub Issues 作为事件源均未接。
- **PR #17（M4e 第二半）**：合并 `c364323`；CLI `--platform gitlab` + server `CRA_SERVER_PLATFORM=gitlab` + `/webhooks/gitlab` 接线完成；verify（含 PG）/L1 绿，L2 按规则跳过；真实 GitLab **读**路径已对公共项目验证，**真实写操作仍 pending（无 owner token）**；Gerrit、语义索引未做。
- **完成度口径**：按 11 个功能项，10 项完整（M0–M3、M4a/b/c/d、M4f-lite、M4f-full），M4e 仍为部分完成（GitLab、语义索引第一版完成；Gerrit、真实 GitLab 写操作未做）。

## 验收状态更新（2026-10-27 语义索引）

- **PR #22（M4e 语义索引/跨文件上下文第一版）**：合并 `477f6bd`；`SymbolIndex`（jgit head 树、Java-only、默认关闭）+ L2 跨文件切片（BH-dataflow/Security/Verifier，≤4k，从 L1 预算扣除）；`mvn verify`/L1 绿。
- **L2**：owner 批准后经 `eval.yml` 执行（run 40554850710，head `df7e095`）：bad 56/60 = 0.933（Wilson 下界 0.8407）、clean 0.68、零发布 1/30、100/100 完成；报告绑定 `df7e095`，以最后提交 `6ab6301` 落库。

## 验收状态更新（2026-10-28 验证层反证纪律）

- **PR #21（验证层 REFUTED 反证纪律，ADR-0015）**：合并 `23c20dd`；bug 类 REFUTED 必须携带 `refutation{kind,anchor,quote,reason}`，`RefuteEvidenceGate` 机械校验不通过（缺字段/引述不符/quote 不可逐字定位/过短）→ 降级 INSUFFICIENT_EVIDENCE 交 Judge（trace `refute_evidence_check`）；bug 类 brief 附确定性调用点索引（同目录有界扫描，≤700 token 从 codeContext 预留，不可得如实声明）；judge 提示词增反证审查规则。**未改**阈值（0.70/0.35/0.80）、expected/数据集、CrossChecker 默认值。与 ADR-0014 语义索引并存融合（同文件批次同时计算调用点索引与 L2 切片，预算合并预留）；spotless 融合代码已格式化。
- **有界实验**（8 翻转 case × k=3 × temperature=0.2 × standard）：聚合检出 19/24 → 22/24（WL 0.5953 → 0.7415），JacksonCore-8 0/3 → 2/3，样本级全零 1/8 → 0/8；机制为提示词直接改变判定（改后 0 次机械降级，机械门是保险丝）。详见 docs/07「验证层反证纪律记录（ADR-0015）」。
- **合并 head L2**（run 40594443336，绑定 `a644ac6`，报告最后提交 `ad134ad`）：bad 53/60 = 0.883（Wilson 下界 0.7782）、clean 0.75、零发布样本级 0/30、100/100 完成，门禁绿。合并过程中另有一次 e12bb24 上的 L2（run 40568302478：55/60 = 0.917、WL 0.8193），因 spotless 提交改变报告绑定后按规则在最终 head 重跑，以 `a644ac6` 报告为准。
- **全量 L3**：属里程碑动作，待 owner 批准后执行（PR #21 未自行触发）。


## Owner 裁决补充（2026-10-30）

| # | 事项 | 裁决 | 落点 |
|---|---|---|---|
| 1 | bad-Mockito-3 expected 锚点（9B 审计 A 类唯一一处） | **批准修正**：line 126→129（覆盖实际发布锚点 L129/L132，仍锚同一方法） | `cra-eval/dataset/bad/bad-Mockito-3/expected_findings.json`（note 已回写）；理论值 72/90 = 0.800（WL 0.7059）待 L3 全量重跑验证 |
| 2 | 真实 GitLab 写操作验收（MR discussion/note、commit comment、issue） | **暂缓**（待 owner token，再验时按 `docs/deployment-gitlab.md` §12 清单） | docs/07、README、deployment-gitlab §0/§12 同步 |
| 3 | Gerrit 平台适配 | **不做适配**（Git 平台扩展点保留，如未来重启按 11§6 checklist 另立项） | docs/07、docs/11 §7、deployment-gitlab、README 已写入 |
| 4 | L3 全量重跑（官方口径） | 由 owner 指定的其他会话执行（canonical 参数：`set=all k=3 depth=standard tier=l3 crosschecker=true temperature=0.2`，约 49min）；本轮不含 Mockito-3 修正后的官方数值 | — |

- **完成度口径更新**：Gerrit 移出范围后，M4e 的剩余缺口仅为"真实 GitLab 写操作验收（暂缓）"；11 项功能中 10 项完整、M4e 基本完成（适配器/接线/语义索引均已落地并有测试与读路径实证）。
