package org.cra.eval.internal;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.cra.common.git.CommentLedger;
import org.cra.common.git.DispositionSignal;
import org.cra.common.git.PullRequestRef;
import org.cra.common.git.RepoRef;

/**
 * 周报取数中间模型（T3.4）：把"账本 + 处置信号 + PR diff 事实"和"trace run 事实"分开承载， 聚合器（{@link
 * WeeklyReportBuilder}）只依赖本模型——离线回放与真实 GitHub 取数走同一聚合路径。
 */
final class WindowData {

  private WindowData() {}

  /** 文件行锚点。 */
  record LineKey(String file, int line) {}

  /**
   * 后续 commit 对锚定文件的影响（fixed 自动初判依据）。
   *
   * @param touchedFiles 整文件级变更（删除/重命名——行号映射不可靠，按"涉及"处理）
   * @param touchedLines 具体旧侧行号（compare 的 LEFT 侧 = 评论锚定 commit）
   */
  record TouchFacts(Set<String> touchedFiles, Set<LineKey> touchedLines) {

    static final TouchFacts EMPTY = new TouchFacts(Set.of(), Set.of());

    TouchFacts {
      touchedFiles = Set.copyOf(touchedFiles == null ? Set.of() : touchedFiles);
      touchedLines = Set.copyOf(touchedLines == null ? Set.of() : touchedLines);
    }

    /**
     * 锚定 (file, line) 是否被后续变更涉及。
     *
     * @param file 文件（评论锚定 commit 的路径）
     * @param line 行号
     * @return true 若涉及
     */
    boolean touches(String file, int line) {
      return touchedFiles.contains(file) || touchedLines.contains(new LineKey(file, line));
    }
  }

  /**
   * 单个 PR 的取数结果。
   *
   * @param pull PR 元数据
   * @param ledger bot 评论账本（inline + Summary，05§2.3）
   * @param signals 处置信号（reply 标签 + reaction）
   * @param touchesByCommit 评论 commit → 后续变更事实（仅合并 PR 计算；缺失 = 不可判定）
   */
  record PrWindow(
      PullRequestRef pull,
      CommentLedger ledger,
      List<DispositionSignal> signals,
      Map<String, TouchFacts> touchesByCommit) {

    PrWindow {
      signals = List.copyOf(signals == null ? List.of() : signals);
      touchesByCommit = Map.copyOf(touchesByCommit == null ? Map.of() : touchesByCommit);
    }
  }

  /**
   * Judge 复核过的 REFUTED 样本（拒真评估池条目）。
   *
   * @param findingId finding 标识（跨 run 去重键）
   * @param overturned Judge 判定 CONFIRMED（= 原 REFUTED 被推翻）
   */
  record RefusalSample(String findingId, boolean overturned) {}

  /**
   * 单个 run 的 trace 事实（05§1.4 事件子集）。
   *
   * @param runId run 标识
   * @param repo 仓库（run_start 缺失时为 null）
   * @param pr PR 编号（缺失为 0）
   * @param startedAt run 开始时间
   * @param status run 终态
   * @param dryRun 是否 dry-run（不进漏斗与零发布率）
   * @param candidates 候选数（promotion 事件去重）
   * @param confirmed 确认数（PUBLISH + Judge CONFIRMED）
   * @param published 发布数（run_end）
   * @param suppressedByTruncation 截断丢弃数（run_end）
   * @param refutedTotal REFUTED 判定总数
   * @param judgeUnavailable 抽查命中但 Judge 不可得数
   * @param judgedRefusals Judge 可读的 REFUTED 复核样本
   * @param promotionData 是否存在 promotion 事件（false = 漏斗不可得）
   */
  record RunFacts(
      String runId,
      String repo,
      int pr,
      Instant startedAt,
      String status,
      boolean dryRun,
      int candidates,
      int confirmed,
      int published,
      int suppressedByTruncation,
      int refutedTotal,
      int judgeUnavailable,
      List<RefusalSample> judgedRefusals,
      boolean promotionData) {

    RunFacts {
      judgedRefusals = List.copyOf(judgedRefusals == null ? List.of() : judgedRefusals);
    }
  }

  /**
   * 固化 badcase 的来源事实（T3.5；用于"事后推翻 REFUTED"的拒真池补入）。
   *
   * @param label missed|false-positive|relabeled
   * @param runId 来源 run
   * @param findingId 来源 finding
   * @param verifierVerdict 当时验证者判定（可空：brief/trace 未记录）
   * @param addedAt 固化时间（ISO）
   */
  record BadcaseFacts(
      String label, String runId, String findingId, String verifierVerdict, String addedAt) {}

  /**
   * 报告窗口（聚合输入）。
   *
   * @param repo 仓库
   * @param source 账本数据源（github|ledger-fixture）
   * @param prs PR 取数结果
   * @param runs trace run 事实
   * @param badcases 已固化 badcase
   * @param notes 取数阶段的口径/不可得声明
   */
  record Window(
      RepoRef repo,
      String source,
      List<PrWindow> prs,
      List<RunFacts> runs,
      List<BadcaseFacts> badcases,
      List<String> notes) {

    Window {
      prs = List.copyOf(prs == null ? List.of() : prs);
      runs = List.copyOf(runs == null ? List.of() : runs);
      badcases = List.copyOf(badcases == null ? List.of() : badcases);
      notes = List.copyOf(notes == null ? List.of() : notes);
    }
  }
}
