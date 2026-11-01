# ADR-0018 pre-PR 实跑子进程输出读取线程（裸 Thread）豁免

- 状态：accepted（2026-11-01，规约审计 D2 项处置：BuildVerifyRunner 裸 Thread 定性为 P2 规范债后补豁免声明）
- 背景：09§6【强制】"子代理执行模型 = 共享固定线程池……各组件禁止自建线程池/调度器"。pre-PR 实跑（07 T2.10，`BuildVerifyRunner`）须逐行实时读取子进程 stdout：OS 管道缓冲区写满后子进程因背压阻塞，若在主线程先 `waitFor` 再读会互相等待（死锁形态），无法满足"真实输出逐行实时回显 + 全量落盘 + 超时强杀"契约。现行实现（`cra-runner-cli` `BuildVerifyRunner.startReader`）：单次 run 一个 `new Thread(…, "cra-pre-pr-reader")` 读取线程——daemon、`join(5s)`（超时强杀后 `join(2s)`）收尾、非池化、非无界创建、生命周期不超出单次 run。严格按字面，09§6 只禁"自建线程**池/调度器**"，裸 Thread 不在其列；但该形态处于 archtest 守护盲区，每轮审计都需重新解释"这不是池"，解释成本持续发生。
- 决策：
  1. 显式声明：`BuildVerifyRunner` 的单次子进程输出读取线程**不构成线程池/调度器**，不在 09§6"禁止自建线程池/调度器"的适用范围内；本 ADR 免除逐次解释成本（与 ADR-0007 同模式：规则字面与实现形态的边界冲突，显式豁免优于解释漂移）。
  2. 豁免不变式（超界即回 09§6 普通纪律或重新走 ADR）：每 run 至多 1 个读取线程；daemon + `join` 收尾；线程生命周期不超出单次 run；不做任务队列、不复用、不池化。
  3. 09§6 该条尾部加注"（单次 daemon 读取线程豁免见 ADR-0018）"；代码零改动，行为零变化。
- 理由：管道背压是 POSIX 管道的事实行为，独立读线程是满足"实时回显 + 超时强杀"两契约的最小机制；且读取线程**不并入 cra-core 共享池**——池饥饿/排队会延迟或饿死实跑回显（回显是 pre-PR 的第一公民，07 T2.10），cra-runner-cli 组合根本也不依赖 cra-core 的池。
- 替代方案与否决理由：
  1. `ExecutorService` 提交、用后 shutdown——对单次、有 join 语义的读取任务是纯开销（多一层池生命周期管理，"join 该任务收尾"语义需额外拼装），复杂度更高、收益为零；
  2. 主线程同步读、不用 `waitFor`——无法同时得到超时强杀与实时回显（`waitFor` 前必须先排干管道，超时机制失效）；
  3. 虚拟线程（Java 21）——项目基线 Java 17（09§7，`maven.compiler.release=17`），不可用。
- 后果：豁免范围仅此一处实现点（`BuildVerifyRunner.startReader`，线程名 `cra-pre-pr-reader`）；若将来引入裸 Thread 的 archtest 守护，须按决策 2 的不变式放行该形态；出现第二个裸 Thread 读取点或需要复用/池化时，本豁免失效并复审。
