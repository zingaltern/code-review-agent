# AGENTS.md — 本仓库开发 agent 行为约束

任何 agent 在本仓库执行 git 写操作（commit / push / branch / merge）之前，必须先读完
[docs/00-branching-and-workflow.md](docs/00-branching-and-workflow.md)——它是分支、提交、PR、推送的唯一规范。以下是摘要，冲突时以 docs/00 为准。

## 硬规则（违反即事故）

- 唯一长期分支 `main`；一切改动走 `<type>/<slug>` 短生命周期分支 + PR（squash merge），type ∈ `feat|fix|docs|refactor|test|chore|perf|exp`；
- **禁止直推 main**；禁止 force push / rebase 已推送分支（`exp/*` 除外）；
- **严禁向 `zingaltern/openai-code-review`（本仓库前身）推送任何内容**；除 origin 外不向任何远端推送；
- 未经 owner 明确指示：不增删/改名 remote、不改 GitHub 设置与分支保护、不删非自己创建的分支；
- 分支名小写英文 kebab-case（建议 `m<N>-` 前缀），不用中文；提交走 Conventional Commits（英文 type + 中文主题），原子小步；
- 自检（构建 + 测试）通过才能开 PR；PR 描述用三行模板（改了什么 / 为什么 / 怎么验证的），验证证据粘贴真实输出，**禁止编造**；
- 合并后立即删除分支（本地 + 远端）；分支与任务不符时删掉重建，不将错就错；`feat`/`fix` 超 1 周未合并主动报告。

## 工作流（六步）

main 切分支 → 小步提交 → 需要时 `merge origin/main` → 自检 → `push` + PR → squash 合并后删分支。完整命令序列见 docs/00 §4。

## 项目上下文

- 设计契约：docs/01–07；工程纪律（文档即契约、测试先行、LLM 调用不进单测等）见 docs/07 §6，与分支规范并行生效；
- 当前状态：设计 **v1.3** 已完成三轮评审修订与视频设计指导整合（2026-10-25，24 项缺陷闭环 + 三功能框架/CrossChecker 两模式/签字规矩，对照表见 docs/01 §8），**可进入 M0**；里程碑顺序与验收标准见 docs/07（注意 07 头部"数字与端点纪律"）；
- 技术栈：Java 17 · Spring Boot 3.x（仅 DI/配置）· Maven 多模块（`cra-*`）· OpenAI 兼容协议 LLM 网关。
