# ADR-0005 账本与处置采集的取数端口 CommentSource（cra-common）

- 状态：accepted（补录 v1 基线第五轮裁决，2026-10-25）
- 背景：cra-store 只依赖 common，但 GitHub 评论账本的原始数据（bot 评论/处置信号）只能经 `GitPlatformAdapter.fetchBotComments/fetchDispositionSignals`（cra-git-github 实现）获取，10§2 矩阵下无合法路径；`cra-eval report` 周报同样断供（第五轮缺陷①）。
- 决策：cra-common 定义 `CommentSource` 端口（返回 common POJO）；store 的 GitHub 账本实现**构造器注入**该端口；cra-git-github 提供默认实现（适配既有 adapter 方法）；组合根（cra-runner-cli / eval 的 Main）装配；矩阵放开 `cra-eval→git(+git-gh) ✅`（仅 api、仅开发侧）。
- 理由：替代方案各有结构性缺陷——store 私建 GitHub 客户端（双维护）、store→git 直接依赖（账本抽象开始知道平台）、core 内桥接（编排器沾平台 IO）。
- 后果：每新增平台须同步提供 CommentSource 实现（11§2 已登记）。
