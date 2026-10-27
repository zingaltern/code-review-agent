package org.cra.runner.cli;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.cra.common.exception.CraException;
import org.cra.common.git.PostIssueCommand;
import org.cra.common.git.PullRequestRef;
import org.cra.common.git.RepoRef;
import org.cra.common.run.ReviewRequest;
import org.cra.common.run.ReviewResult;
import org.cra.common.run.RunStatus;
import org.cra.core.api.Orchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * `cra sweep` 夜间存量评审（M4d，05§2.5/§3）。
 *
 * <ol>
 *   <li>扫描范围：{@code CommentSource.listPullRequests(repo, since)}（按 updated 倒序）取前 {@code --max-prs}
 *       个；
 *   <li>逐 PR 复用完整评审流水线（diff 语义与 PR 形态一致），发布形态 = ISSUE：每条被评审 PR 一个 issue （载体 + 账本粒度，正文含 Summary 与全部
 *       finding 详情 + DONE 标记，05§1.3）；
 *   <li>幂等：第二次夜间跑对同一 head commit 命中载体账本 DONE 闸 → SKIPPED，不再产 issue；全部 SKIPPED 时 不产汇总 issue（05§2.5）；
 *   <li>汇总 issue：本次实际评审 ≥1 个 PR 时发布一条扫描汇总（标题含日期，同日已存在则不重复发布）； 汇总只承载范围/预算/截断声明，不承载账本标记（05§2.5）。
 * </ol>
 *
 * <p>退出码：0 = 正常（含无 PR、全跳过）；2 = 自身故障（配置/平台/任一 PR 失败/汇总发布失败；逐 PR issue 不回滚）。
 */
final class SweepCommand {

  private static final Logger LOG = LoggerFactory.getLogger(SweepCommand.class);

  /** 汇总 issue 标签（平台无该标签时忽略，不影响幂等——幂等靠标题+正文首行）。 */
  private static final List<String> NIGHTLY_LABELS = List.of("cra-nightly");

  /**
   * 执行夜间存量评审。
   *
   * @param parsed 解析后的参数
   * @return 进程退出码
   */
  int execute(SweepArgs.Parsed parsed) {
    CraConfig config = parsed.config();
    Instant since = Instant.now().minus(SweepScope.parseSince(parsed.since()));
    try {
      PlatformComponents components = PlatformComponents.create(config);
      RepoRef repo = new RepoRef(config.repoOwner(), config.repoName());
      List<PullRequestRef> allPulls = components.commentSource().listPullRequests(repo, since);
      List<PullRequestRef> pulls = allPulls.stream().limit(parsed.maxPrs()).toList();
      int truncated = Math.max(0, allPulls.size() - pulls.size());
      LOG.info(
          "cra sweep start: repo={}/{} since={} window={} selected={} truncated={} max-prs={} dry-run={}",
          repo.owner(),
          repo.repo(),
          parsed.since(),
          allPulls.size(),
          pulls.size(),
          truncated,
          parsed.maxPrs(),
          config.dryRun());
      if (pulls.isEmpty()) {
        LOG.info("cra sweep end: 窗口内无可评审 PR（窗口={}），不产 issue", parsed.since());
        return 0;
      }
      List<PrRun> runs = runAll(config, pulls);
      int failed = (int) runs.stream().filter(r -> r.failed()).count();
      int reviewed = (int) runs.stream().filter(r -> !r.failed() && r.reviewed()).count();
      boolean rollupOk = true;
      if (!config.dryRun() && reviewed > 0) {
        rollupOk = publishRollup(components, repo, parsed, allPulls.size(), runs, reviewed, failed);
      }
      LOG.info(
          "cra sweep end: reviewed={} skipped={} failed={} rollup={}",
          reviewed,
          runs.stream().filter(r -> !r.failed() && !r.reviewed()).count(),
          failed,
          rollupOk ? "OK" : "FAILED");
      return exitCodeFor(failed, rollupOk);
    } catch (CraException e) {
      LOG.error("cra sweep config/bootstrap failure: {}", e.getMessage());
      return 2;
    } catch (RuntimeException e) {
      LOG.error("cra sweep unexpected failure", e);
      return 2;
    }
  }

  private List<PrRun> runAll(CraConfig config, List<PullRequestRef> pulls) {
    List<PrRun> runs = new ArrayList<>();
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.registerBean(CraConfig.class, () -> config);
      context.register(AppConfig.class);
      context.refresh();
      Orchestrator orchestrator = context.getBean(Orchestrator.class);
      for (PullRequestRef pr : pulls) {
        if (pr.headSha() == null || pr.headSha().isBlank()) {
          runs.add(PrRun.skipped(pr, "平台未返回 head sha"));
          continue;
        }
        if (pr.baseSha() == null || pr.baseSha().isBlank()) {
          // 旧数据源/平台不提供 base：不猜 diff 基线，如实跳过（05§2.5）
          runs.add(PrRun.skipped(pr, "平台未返回 base sha"));
          continue;
        }
        ReviewRequest request =
            new ReviewRequest(
                config.repoOwner(),
                config.repoName(),
                pr.number(),
                pr.baseSha(),
                pr.headSha(),
                ReviewCommand.toDepth(config.depth()),
                config.dryRun(),
                false,
                org.cra.common.run.ReviewMode.ISSUE);
        try {
          ReviewResult result = orchestrator.run(request);
          runs.add(PrRun.of(pr, result));
          LOG.info(
              "cra sweep pr #{} head={} -> {} published={}",
              pr.number(),
              pr.headSha(),
              result.status(),
              result.stats().findingsPublished());
        } catch (RuntimeException e) {
          // 单 PR 故障隔离：不中断整轮夜间评审，如实进汇总并以非零退出码收尾
          runs.add(PrRun.failed(pr, String.valueOf(e.getMessage())));
          LOG.error("cra sweep pr #{} failed: {}", pr.number(), e.getMessage());
        }
      }
    }
    return runs;
  }

  private boolean publishRollup(
      PlatformComponents components,
      RepoRef repo,
      SweepArgs.Parsed parsed,
      int windowTotal,
      List<PrRun> runs,
      int reviewed,
      int failed) {
    String date = LocalDate.now(ZoneOffset.UTC).toString();
    String title = "[cra-nightly] 汇总 " + date + "：评审 " + reviewed + " 个 PR";
    String firstLine = "## 夜间存量评审 " + date;
    boolean exists =
        components.commentSource().listBotIssues(repo, null).stream()
            .anyMatch(c -> c.body().startsWith(firstLine));
    if (exists) {
      LOG.info("cra sweep rollup 已存在（{}），不重复发布", title);
      return true;
    }
    StringBuilder body = new StringBuilder();
    body.append(firstLine).append("\n\n");
    body.append(scopeDeclaration(parsed.since(), windowTotal, runs.size(), parsed.maxPrs()))
        .append("每条 finding 的详情见对应 PR 的夜间评审 issue。\n\n");
    body.append("| PR | head | 结果 | 发布 | 候选 | 确认 | tokens(in/out) |\n");
    body.append("|---|---|---|---|---|---|---|\n");
    long tokensIn = 0;
    long tokensOut = 0;
    int suppressed = 0;
    List<String> degradations = new ArrayList<>();
    for (PrRun run : runs) {
      tokensIn += run.tokensIn();
      tokensOut += run.tokensOut();
      suppressed += run.suppressed();
      degradations.addAll(run.degradations());
      body.append("| #")
          .append(run.pr().number())
          .append(" | `")
          .append(shortSha(run.pr().headSha()))
          .append("` | ")
          .append(run.outcomeLabel())
          .append(" | ")
          .append(run.published())
          .append(" | ")
          .append(run.candidates())
          .append(" | ")
          .append(run.confirmed())
          .append(" | ")
          .append(run.tokensIn())
          .append("/")
          .append(run.tokensOut())
          .append(" |\n");
    }
    body.append("\n合计：评审 ")
        .append(reviewed)
        .append(" / 跳过 ")
        .append(runs.stream().filter(r -> !r.failed() && !r.reviewed()).count())
        .append(" / 失败 ")
        .append(failed)
        .append("；tokens ")
        .append(tokensIn)
        .append("/")
        .append(tokensOut)
        .append("\n\n");
    body.append("预算与截断声明：每 PR 预算 run-total-tokens=500k；截断抑制 ")
        .append(suppressed)
        .append(" 条；降级事件 ")
        .append(degradations.isEmpty() ? "无" : String.join("、", degradations))
        .append("\n\n");
    body.append("幂等：同一 head commit 已有 DONE 标记的 PR 在后续夜间跑中跳过（05§1.3）。\n");
    try {
      components
          .adapter()
          .postIssueReview(
              new PostIssueCommand(
                  repo.owner(), repo.repo(), title, body.toString(), NIGHTLY_LABELS));
      LOG.info("cra sweep rollup published: {}", title);
      return true;
    } catch (RuntimeException e) {
      // 汇总发布失败不回滚已发布的逐 PR issue（账本已完整）；如实记录并由退出码 2 收尾
      LOG.error("cra sweep rollup publish failed: {}", e.getMessage());
      return false;
    }
  }

  /** 退出码：任一 PR 失败或汇总发布失败 = 2，否则 0。 */
  static int exitCodeFor(int failed, boolean rollupPublished) {
    return failed > 0 || !rollupPublished ? 2 : 0;
  }

  /** 范围/截断声明：窗口总数、本次评审数、被 --max-prs 截断数。 */
  static String scopeDeclaration(String since, int windowTotal, int selected, int maxPrs) {
    int truncated = Math.max(0, windowTotal - selected);
    return "扫描窗口：since="
        + since
        + "，窗口内 "
        + windowTotal
        + " 个 PR；本次评审前 "
        + selected
        + " 个（--max-prs 上限 "
        + maxPrs
        + "），截断 "
        + truncated
        + " 个。";
  }

  private static String shortSha(String sha) {
    return sha == null || sha.length() <= 7 ? String.valueOf(sha) : sha.substring(0, 7);
  }

  /** 单 PR 运行记录（结果 / 跳过 / 失败三态，汇总 issue 的数据面）。 */
  private record PrRun(
      PullRequestRef pr, ReviewResult result, String skipReason, String failureReason) {

    static PrRun of(PullRequestRef pr, ReviewResult result) {
      return new PrRun(pr, result, null, null);
    }

    static PrRun skipped(PullRequestRef pr, String reason) {
      return new PrRun(pr, null, reason, null);
    }

    static PrRun failed(PullRequestRef pr, String reason) {
      return new PrRun(pr, null, null, reason);
    }

    boolean failed() {
      return failureReason != null || (result != null && result.status() == RunStatus.FAILED);
    }

    boolean reviewed() {
      return result != null
          && (result.status() == RunStatus.SUCCESS || result.status() == RunStatus.DEGRADED);
    }

    String outcomeLabel() {
      if (failureReason != null) {
        return "失败：" + failureReason;
      }
      if (skipReason != null) {
        return "跳过：" + skipReason;
      }
      return result.status() == RunStatus.SKIPPED ? "已评审（幂等跳过）" : result.status().name();
    }

    long tokensIn() {
      return result == null ? 0 : result.stats().tokensIn();
    }

    long tokensOut() {
      return result == null ? 0 : result.stats().tokensOut();
    }

    int suppressed() {
      return result == null ? 0 : result.stats().suppressedByTruncation();
    }

    List<String> degradations() {
      return result == null ? List.of() : result.stats().degradations();
    }

    int published() {
      return result == null ? 0 : result.stats().findingsPublished();
    }

    int candidates() {
      return result == null ? 0 : result.stats().findingsCandidate();
    }

    int confirmed() {
      return result == null ? 0 : result.stats().confirmed();
    }
  }
}
