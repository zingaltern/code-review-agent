package org.cra.eval.internal;

import java.time.Instant;
import org.cra.common.git.RepoRef;

/** 周报账本取数端口（cra-eval 内部）：真实 GitHub（经 CommentSource 构造器注入，ADR-0005）与离线回放 （测试/脱敏样例）实现同一契约。 */
interface ReportWindowSource {

  /**
   * 拉取窗口内的账本与处置事实。
   *
   * @param repo 仓库
   * @param since 窗口起点（含）
   * @return 取数结果（runs/badcases 由编排方另行拼装）
   */
  WindowData.Window load(RepoRef repo, Instant since);

  /**
   * 数据源标签（报告渲染用）。
   *
   * @return github | ledger-fixture
   */
  String label();
}
