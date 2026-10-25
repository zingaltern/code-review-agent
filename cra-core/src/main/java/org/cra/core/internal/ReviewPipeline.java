package org.cra.core.internal;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.stream.Collectors;
import org.cra.agents.api.AgentBrief;
import org.cra.agents.api.GeneralReviewInput;
import org.cra.agents.api.SubAgent;
import org.cra.common.finding.AnchorStatus;
import org.cra.common.finding.CandidateFinding;
import org.cra.common.finding.Severity;
import org.cra.common.finding.Side;
import org.cra.common.git.DiffFile;
import org.cra.common.git.DiffResult;
import org.cra.common.git.FileContent;
import org.cra.common.git.InlineComment;
import org.cra.common.git.LedgerComment;
import org.cra.common.git.LedgerFindingMarker;
import org.cra.common.git.LedgerRunMarker;
import org.cra.common.git.PostReviewCommand;
import org.cra.common.git.PrReviewResult;
import org.cra.common.git.PublishStatus;
import org.cra.common.git.RepoRef;
import org.cra.common.git.ReviewItemResult;
import org.cra.common.run.ReviewRequest;
import org.cra.common.run.ReviewResult;
import org.cra.common.run.ReviewStats;
import org.cra.common.run.RunStatus;
import org.cra.core.api.Orchestrator;
import org.cra.git.api.GitPlatformAdapter;
import org.cra.llm.api.LlmGateway;
import org.cra.store.api.ReviewLedger;

/**
 * M0 线性流水线（07 T0.5）：assemble → review(×1) → publish。
 *
 * <ul>
 *   <li>第一道闸：commit 级幂等，只认账本中该 commit 的 DONE/DEGRADED 完成标记（02§4.1）；
 *   <li>第二道闸：指纹级去重 exact → ±5 滑窗容错 → possible_duplicate 入 trace（02§4.1，{@link
 *       FingerprintDeduper}）——发布中途掐断的 run 无完成标记，重跑按 指纹补发缺失条目、已发布不重发（07§1 验收 3）；
 *   <li>发布顺序：inline review → Summary issue comment（05§2.2）；锚定校验位置非法降级进 Summary（05§2.2）；正文 {@code
 *       <!--} 全量转义、标记行最后追加（05§1.3 规则 3）；
 *   <li>dry-run 全程零写（05§6）；run 级故障统一捕获落盘 FAILED（02§4.2）；trace 事件先行 （07§6.5）。
 * </ul>
 *
 * <p>本类无可事实例状态：一次 run 一个调用栈，run 维度数据全部走参数（09§6 共享可变状态约束）。
 */
public final class ReviewPipeline implements Orchestrator {

  private static final SplittableRandom RANDOM = new SplittableRandom();
  private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
  private static final String MARKER_VERSION = "2.1";

  /** 过期收尾前缀的判定起点（02§5：[已被 run_xxx 取代]；已收尾的评论不再叠加前缀）。 */
  private static final String SUPERSEDED_PREFIX = "[已被 run_";

  /** 可疑账本标记的 Summary 告警阈值（05§1.3 规则 2：同一 PR ≥3 次疑似注入尝试）。 */
  private static final int LEDGER_SUSPECT_THRESHOLD = 3;

  private final GitPlatformAdapter adapter;
  private final ReviewLedger ledger;
  private final AgentRunner agentRunner;
  private final SubAgent<GeneralReviewInput> reviewer;
  private final Path runRoot;

  public ReviewPipeline(
      GitPlatformAdapter adapter,
      ReviewLedger ledger,
      LlmGateway gateway,
      SubAgent<GeneralReviewInput> reviewer,
      Path runRoot) {
    this.adapter = adapter;
    this.ledger = ledger;
    this.agentRunner = new AgentRunner(gateway);
    this.reviewer = reviewer;
    this.runRoot = runRoot;
  }

  @Override
  public ReviewResult run(ReviewRequest request) {
    String runId = newRunId();
    TraceWriter trace = new TraceWriter(runRoot, runId);
    long start = System.currentTimeMillis();
    try {
      trace.emit(
          "run_start",
          Map.of(
              "repo", request.repoOwner() + "/" + request.repoName(),
              "pr", request.prNumber(),
              "base", request.baseSha(),
              "head", request.headSha(),
              "dry_run", request.dryRun()));

      // 第一道闸：commit 级幂等（02§4.1，只认完整发布 DONE|DEGRADED；命中即全量跳过——Summary
      // 保持历史原样，避免 CI 重跑反复 PATCH 旧评论；更新语义如有需要随流水线线统一加）
      Optional<LedgerRunMarker> completion =
          ledger.findCompletion(request.prNumber(), request.headSha());
      trace.emit("gate_check", Map.of("gate", "commit", "hit", completion.isPresent()));
      if (completion.isPresent()) {
        return finish(
            trace,
            runId,
            request,
            RunStatus.SKIPPED,
            new ReviewStats(0, 0, 0, 0, 0, 0, System.currentTimeMillis() - start, List.of(), 0),
            "commit "
                + shortSha(request.headSha())
                + " 已有完成标记（"
                + completion.get().status()
                + "），全量跳过");
      }

      // 第二道闸数据面：已发布指纹（补发缺失、已发布不重发，02§4.1）+ 账本解析分层（05§1.3 规则 5）
      List<LedgerFindingMarker> publishedMarkers = ledger.publishedFindings(request.prNumber());
      List<LedgerRunMarker> completedRuns = ledger.completedRuns(request.prNumber());
      int suspectCount = ledger.suspectMarkerCount(request.prNumber());
      boolean anyValidMarker = !publishedMarkers.isEmpty() || !completedRuns.isEmpty();
      trace.emit(
          "ledger_parse",
          Map.of(
              "valid",
              publishedMarkers.size() + completedRuns.size(),
              "suspect",
              suspectCount,
              "fallback",
              anyValidMarker ? "none" : "commit-gate"));
      // 规则 2（05§1.3）：可疑标记 ≥3 时 Summary 告警疑似注入尝试
      String suspectWarning =
          suspectCount >= LEDGER_SUSPECT_THRESHOLD
              ? "检测到 " + suspectCount + " 个可疑账本标记（LEDGER_SUSPECT），疑似注入尝试"
              : "";

      // assemble（L0：diff + 变更说明；L1 窗口/REVIEW.md 随 T1.3/T1.4 接入）
      DiffResult diff =
          adapter.fetchDiff(
              new RepoRef(request.repoOwner(), request.repoName()),
              request.baseSha(),
              request.headSha());
      GeneralReviewInput input = assembleInput(diff, request);

      // review ×1
      AgentBrief brief = reviewer.buildBrief(input);
      Path briefPath = trace.persistBrief(brief);
      trace.emit(
          "agent_start", Map.of("agent", reviewer.role().id(), "brief_path", briefPath.toString()));
      AgentRunner.AgentRun outcome = agentRunner.execute(brief, runId);
      trace.emit(
          "agent_output",
          Map.of(
              "agent",
              reviewer.role().id(),
              "findings",
              outcome.findings().size(),
              "tokens",
              Map.of("in", outcome.tokensIn(), "out", outcome.tokensOut()),
              "latency_ms",
              outcome.latencyMs()));

      List<String> degradations = new ArrayList<>(outcome.degradations());
      if (outcome.llmUnavailable()) {
        // LLM 不可得：run FAILED，不发布、无 DONE 标记——重跑重试（02§4.2）
        return finish(
            trace,
            runId,
            request,
            RunStatus.FAILED,
            new ReviewStats(
                0,
                0,
                0,
                0,
                outcome.tokensIn(),
                outcome.tokensOut(),
                System.currentTimeMillis() - start,
                List.copyOf(degradations),
                0),
            "LLM 不可得: " + outcome.llmError());
      }
      boolean degraded = false;
      if (!outcome.schemaErrors().isEmpty()) {
        // 候选全部被 schema 校验丢弃：run 降级收尾（仍发布完成标记，02§4.1 降级完成语义）
        degradations.add("schema-violation-all-dropped");
        degraded = true;
      }

      // 去重（指纹 exact → ±5 滑窗 → possible_duplicate，02§4.1）+ 锚定校验（05§2.2）
      List<ScoredFinding> publishable = new ArrayList<>();
      int skippedDuplicate = 0;
      Map<String, Optional<String>> contentCache = new HashMap<>();
      for (CandidateFinding finding : outcome.findings()) {
        List<String> lines =
            contentOf(finding.file(), request.headSha(), request, contentCache)
                .map(text -> List.of(text.split("\n", -1)))
                .orElse(List.of(""));
        FingerprintDeduper.Decision decision =
            FingerprintDeduper.evaluate(
                finding.file(),
                categoryOf(finding),
                finding.line(),
                lines,
                publishedMarkers,
                marker -> oldAnchorHashOf(marker, request, contentCache));
        String matchValue =
            switch (decision.match()) {
              case EXACT -> "exact";
              case SLIDING_WINDOW -> "sliding-window";
              case NONE -> decision.possibleDuplicate() ? "possible_duplicate" : "none";
            };
        trace.emit("dedup", Map.of("finding_id", finding.id(), "match", matchValue));
        if (decision.match() != FingerprintDeduper.Match.NONE) {
          skippedDuplicate++;
          continue;
        }
        AnchorStatus anchor = anchorStatus(diff, finding);
        trace.emit("anchor_check", Map.of("finding_id", finding.id(), "status", anchor.name()));
        publishable.add(new ScoredFinding(finding, decision.fingerprint(), anchor));
      }

      // publish（dry-run 零写）；重试类事件入 stats 但不降级——降级语义 = 未按预期完整发布
      int publishedCount =
          publish(
              request,
              runId,
              trace,
              publishable,
              skippedDuplicate,
              degradations,
              degraded,
              suspectWarning);
      int fallbackCount =
          (int)
              publishable.stream().filter(s -> s.anchor() == AnchorStatus.SUMMARY_FALLBACK).count();

      RunStatus status = degraded ? RunStatus.DEGRADED : RunStatus.SUCCESS;
      ReviewStats stats =
          new ReviewStats(
              outcome.findings().size(),
              publishedCount,
              skippedDuplicate,
              fallbackCount,
              outcome.tokensIn(),
              outcome.tokensOut(),
              System.currentTimeMillis() - start,
              List.copyOf(degradations),
              (int)
                  publishable.stream()
                      .filter(
                          s ->
                              s.finding().category()
                                  == org.cra.common.finding.FindingCategory.SECURITY)
                      .count());
      String summaryLine =
          String.format(
              "run=%s status=%s candidate=%d published=%d dedup=%d fallback=%d tokens=%d/%d",
              runId,
              status,
              stats.findingsCandidate(),
              stats.findingsPublished(),
              stats.skippedDuplicate(),
              stats.anchorFallback(),
              stats.tokensIn(),
              stats.tokensOut());
      return finish(trace, runId, request, status, stats, summaryLine);
    } catch (RuntimeException e) {
      // run 级不可恢复故障统一捕获：落盘 FAILED + trace，不阻塞 PR（02§4.2）
      return finish(
          trace,
          runId,
          request,
          RunStatus.FAILED,
          new ReviewStats(0, 0, 0, 0, 0, 0, System.currentTimeMillis() - start, List.of(), 0),
          "run failed: " + e.getMessage());
    }
  }

  // ---------- 阶段：assemble ----------

  private GeneralReviewInput assembleInput(DiffResult diff, ReviewRequest request) {
    String diffText =
        diff.files().stream()
            .map(
                f ->
                    "--- a/"
                        + f.path()
                        + "\n+++ b/"
                        + f.path()
                        + (f.patch() == null ? "" : "\n" + f.patch()))
            .collect(Collectors.joining("\n\n"));
    String commitMessages =
        diff.commits().stream()
            .map(c -> c.sha() + " " + c.message())
            .collect(Collectors.joining("\n"));
    // M0 不拉 pulls 元数据端点：标题以 PR 号占位（fetchPrMeta 随 M1 上下文组装接入）
    return new GeneralReviewInput("PR #" + request.prNumber(), commitMessages, diffText, "");
  }

  // ---------- 阶段：publish ----------

  private int publish(
      ReviewRequest request,
      String runId,
      TraceWriter trace,
      List<ScoredFinding> publishable,
      int skippedDuplicate,
      List<String> degradations,
      boolean degraded,
      String suspectWarning) {
    List<ScoredFinding> fallbacks =
        publishable.stream().filter(s -> s.anchor() == AnchorStatus.SUMMARY_FALLBACK).toList();
    List<InlineComment> inline =
        publishable.stream()
            .filter(s -> s.anchor() == AnchorStatus.INLINE)
            .map(
                s ->
                    new InlineComment(
                        s.finding().file(),
                        s.finding().line(),
                        s.finding().side(),
                        inlineBody(s, runId, request.headSha()),
                        s.finding().id()))
            .toList();
    String summaryBody =
        summaryBody(
            request,
            runId,
            publishable,
            fallbacks,
            skippedDuplicate,
            degradations,
            degraded,
            suspectWarning);
    if (request.dryRun()) {
      // dry-run：全程零写（05§6），发布器不触达 adapter 与账本写侧
      trace.emit(
          "publish",
          Map.of(
              "dry_run",
              true,
              "comments",
              inline.size(),
              "skipped_duplicate",
              skippedDuplicate,
              "suppressed_by_truncation",
              0,
              "done_marker",
              false));
      return publishable.size();
    }
    String reviewBody =
        inline.isEmpty()
            ? "cra 评审（run_" + runId + "）：无可 inline 的发现，详见本 PR 的评审 Summary 评论。"
            : "cra 评审（run_"
                + runId
                + "）：发现 "
                + inline.size()
                + " 条问题（inline），详见本 PR 的评审 Summary 评论。";
    PostReviewCommand cmd =
        new PostReviewCommand(
            request.repoOwner(),
            request.repoName(),
            request.prNumber(),
            request.headSha(),
            reviewBody,
            inline,
            summaryBody);
    PrReviewResult result = adapter.postReview(cmd);
    if (result.items().stream().anyMatch(i -> i.publishStatus() == PublishStatus.SUMMARY_FALLBACK)
        && !inline.isEmpty()) {
      // 整包 422 逐条降级产生了额外 SUMMARY_FALLBACK（05§2.2），run 降级记录
      degradations.add("422-per-item-fallback");
    }
    long publishedActual =
        result.items().stream().filter(i -> i.publishStatus() == PublishStatus.PUBLISHED).count();
    long fallbackActual =
        result.items().stream()
            .filter(i -> i.publishStatus() == PublishStatus.SUMMARY_FALLBACK)
            .count();
    trace.emit(
        "publish",
        Map.of(
            "comments",
            result.items().size(),
            "skipped_duplicate",
            skippedDuplicate,
            "suppressed_by_truncation",
            0,
            "done_marker",
            result.summaryCommentId().isPresent()));
    if (result.summaryCommentId().isEmpty()) {
      // Summary 发布失败 = 无完成标记 = 不完整 run：不回写账本、不做过期收尾，重跑走指纹级补发（02§4.1）
      return (int) (publishedActual + fallbackActual);
    }
    recordToLedger(request, runId, publishable, result, skippedDuplicate, degraded);
    int supersedeFailures = supersedePreviousSummaries(request, runId, trace);
    if (supersedeFailures > 0) {
      degradations.add("summary-supersede-failed:x" + supersedeFailures);
    }
    return (int) (publishedActual + fallbackActual);
  }

  // ---------- 账本写侧与过期收尾（02§5） ----------

  /**
   * 发布成功后账本化登记：完成标记 + 全部应发 finding（含 Summary 降级条目 commentId=0）。GitHub 形态 下为无操作（标记已内嵌评论）；SQLite
   * 形态由此获得幂等闸与补发的事实源（02§4.1/05§1.5）。
   */
  private void recordToLedger(
      ReviewRequest request,
      String runId,
      List<ScoredFinding> publishable,
      PrReviewResult result,
      int skippedDuplicate,
      boolean degraded) {
    String status = degraded ? LedgerRunMarker.STATUS_DEGRADED : LedgerRunMarker.STATUS_DONE;
    long summaryCommentId = result.summaryCommentId().orElseThrow();
    ledger.recordRunCompletion(
        request.prNumber(),
        new LedgerRunMarker(
            runId,
            request.headSha(),
            status,
            publishable.size(),
            skippedDuplicate,
            summaryCommentId));
    Map<String, Long> commentIds =
        result.items().stream()
            .filter(i -> i.commentId() != null)
            .collect(
                Collectors.toMap(
                    ReviewItemResult::findingId, ReviewItemResult::commentId, (a, b) -> a));
    for (ScoredFinding s : publishable) {
      ledger.recordPublishedFinding(
          request.prNumber(),
          new LedgerFindingMarker(
              s.fingerprint(),
              s.finding().file(),
              s.finding().line(),
              categoryOf(s.finding()),
              s.finding().id(),
              runId,
              request.headSha(),
              commentIds.getOrDefault(s.finding().id(), 0L)));
    }
  }

  /**
   * 过期收尾（02§5）：新 run 发布成功后，对同 PR 旧 head_sha 的 bot Summary issue comment 加前缀 {@code [已被 run_xxx
   * 取代]}（PATCH 端点，inline 评论不删——GitHub 自动标 outdated，处置采集依赖其在）。 幂等：已带取代前缀的不再叠加；body 不可得（SQLite 形态）或
   * PATCH 失败只入 trace/degradation， 不回滚完成标记（run 已完整发布）。
   *
   * @return PATCH 失败次数
   */
  private int supersedePreviousSummaries(ReviewRequest request, String runId, TraceWriter trace) {
    int failures = 0;
    for (LedgerRunMarker old : ledger.completedRuns(request.prNumber())) {
      if (old.commitSha().equalsIgnoreCase(request.headSha()) || old.commentId() <= 0) {
        continue;
      }
      String body =
          ledger.comment(request.prNumber(), old.commentId()).map(LedgerComment::body).orElse(null);
      if (body == null) {
        trace.emit(
            "summary_supersede", Map.of("comment_id", old.commentId(), "status", "unavailable"));
        continue;
      }
      if (body.startsWith(SUPERSEDED_PREFIX)) {
        trace.emit("summary_supersede", Map.of("comment_id", old.commentId(), "status", "skipped"));
        continue;
      }
      try {
        adapter.updateSummaryComment(
            new RepoRef(request.repoOwner(), request.repoName()),
            request.prNumber(),
            old.commentId(),
            "[已被 " + runId + " 取代]\n\n" + body);
        trace.emit(
            "summary_supersede",
            Map.of("comment_id", old.commentId(), "status", "patched", "superseded_by", runId));
      } catch (RuntimeException e) {
        // 收尾失败不回滚完成标记（降级写状态，09§4）；次数计入 stats 供 Summary/健康度可见
        trace.emit(
            "summary_supersede",
            Map.of(
                "comment_id",
                old.commentId(),
                "status",
                "failed",
                "error",
                String.valueOf(e.getMessage())));
        failures++;
      }
    }
    return failures;
  }

  // ---------- 组装：inline 评论与 Summary 正文 ----------

  private String inlineBody(ScoredFinding s, String runId, String commitSha) {
    CandidateFinding f = s.finding();
    StringBuilder body = new StringBuilder();
    body.append("**[")
        .append(f.severityProposed().name().toUpperCase(Locale.ROOT))
        .append("] ")
        .append(escape(f.title()))
        .append("** — `")
        .append(f.file())
        .append(":")
        .append(f.line())
        .append("`\n\n");
    body.append(escape(f.description())).append("\n\n");
    body.append("**失败模式**：").append(escape(f.failureMode())).append("\n\n");
    body.append("**证据**：\n```\n").append(f.evidence()).append("\n```\n");
    if (f.suggestion() != null && !f.suggestion().isBlank()) {
      body.append("\n**建议**：\n```\n").append(f.suggestion()).append("\n```\n");
    }
    // 规则 3（05§1.3）：标记行由发布器在全部正文之后追加（正文已全量转义 <!--）
    body.append("\n<!-- cra:v=")
        .append(MARKER_VERSION)
        .append(" | fp=")
        .append(s.fingerprint())
        .append(" | file=")
        .append(f.file())
        .append(" | cat=")
        .append(categoryOf(f))
        .append(" | id=")
        .append(f.id())
        .append(" | run=")
        .append(runId)
        .append(" | commit=")
        .append(commitSha)
        .append(" | line=")
        .append(f.line())
        .append(" -->");
    return body.toString();
  }

  private String summaryBody(
      ReviewRequest request,
      String runId,
      List<ScoredFinding> publishable,
      List<ScoredFinding> fallbacks,
      int skippedDuplicate,
      List<String> degradations,
      boolean degraded,
      String suspectWarning) {
    StringBuilder body = new StringBuilder();
    body.append("## cra 评审 Summary（").append(runId).append("）\n\n");
    body.append("变更：PR #")
        .append(request.prNumber())
        .append(" ")
        .append(shortSha(request.baseSha()))
        .append("..")
        .append(shortSha(request.headSha()))
        .append("\n\n");
    if (!suspectWarning.isEmpty()) {
      // 05§1.3 规则 2：可疑标记 ≥3 → Summary 警告疑似注入尝试
      body.append("> ⚠️ ").append(suspectWarning).append("\n\n");
    }
    List<ScoredFinding> important = bySeverity(publishable, Severity.IMPORTANT);
    List<ScoredFinding> nits = bySeverity(publishable, Severity.NIT);
    if (important.isEmpty() && nits.isEmpty() && fallbacks.isEmpty()) {
      body.append(
          publishable.isEmpty() && skippedDuplicate == 0
              ? "本次未发现问题（评审返回空结果）。\n\n"
              : "本次无新发现（候选均被指纹去重抑制 " + skippedDuplicate + " 条）。\n\n");
    } else {
      if (!important.isEmpty()) {
        body.append("### Important\n");
        for (ScoredFinding s : important) {
          body.append(listLine(s));
        }
        body.append("\n");
      }
      if (!nits.isEmpty()) {
        body.append("### Nit\n");
        for (ScoredFinding s : nits) {
          body.append(listLine(s));
        }
        body.append("\n");
      }
      if (!fallbacks.isEmpty()) {
        body.append("### 位置非法（降级进 Summary）\n");
        for (ScoredFinding s : fallbacks) {
          body.append("- **")
              .append(escape(s.finding().title()))
              .append("** — `")
              .append(s.finding().file())
              .append(":")
              .append(s.finding().line())
              .append("`：")
              .append(escape(s.finding().description()))
              .append("\n");
        }
        body.append("\n");
      }
    }
    body.append("### 覆盖与统计\n");
    body.append("- 指纹去重抑制 ")
        .append(skippedDuplicate)
        .append(" 条；位置非法降级 ")
        .append(fallbacks.size())
        .append(" 条\n");
    if (!degradations.isEmpty()) {
      body.append("- 降级事件：").append(String.join("、", degradations)).append("\n");
    }
    // run 完成标记（05§1.3）：DEGRADED 也是完整发布（02§4.1）；suppressed = 指纹抑制计数
    body.append("\n<!-- cra:v=")
        .append(MARKER_VERSION)
        .append(" | run=")
        .append(runId)
        .append(" | commit=")
        .append(request.headSha())
        .append(" | status=")
        .append(degraded ? "DEGRADED" : "DONE")
        .append(" | findings=")
        .append(publishable.size())
        .append(" | suppressed=")
        .append(skippedDuplicate)
        .append(" -->");
    return body.toString();
  }

  private static String listLine(ScoredFinding s) {
    return "- **"
        + escape(s.finding().title())
        + "** — `"
        + s.finding().file()
        + ":"
        + s.finding().line()
        + "`\n";
  }

  // ---------- 去重与锚定 ----------

  /**
   * 文件内容缓存读取（新代码与旧代码共用，key = path|sha）：二进制/不可得为 empty——空内容锚仍确定性， 该 finding 走 possible_duplicate
   * 分支而非崩溃（09§4 降级写状态）。
   */
  private Optional<String> contentOf(
      String path, String sha, ReviewRequest request, Map<String, Optional<String>> cache) {
    return cache.computeIfAbsent(
        path + "|" + sha,
        k -> {
          try {
            FileContent file =
                adapter.readFile(new RepoRef(request.repoOwner(), request.repoName()), sha, path);
            return file.binary() || file.text() == null
                ? Optional.empty()
                : Optional.of(file.text());
          } catch (RuntimeException e) {
            return Optional.empty();
          }
        });
  }

  /**
   * 账本标记的发布时锚行内容哈希（按标记 commit_sha 取旧文件内容重算——滑窗匹配键，02§4.1 M1 实现口径）。 旧内容不可得（force-push/删除）时为
   * empty：该标记不参与滑窗，仅保留 exact 与 possible_duplicate。
   */
  private Optional<String> oldAnchorHashOf(
      LedgerFindingMarker marker, ReviewRequest request, Map<String, Optional<String>> cache) {
    return contentOf(marker.file(), marker.commitSha(), request, cache)
        .map(
            text ->
                FingerprintCalculator.sha256(
                    FingerprintDeduper.lineAt(List.of(text.split("\n", -1)), marker.line())
                        .strip()));
  }

  private static AnchorStatus anchorStatus(DiffResult diff, CandidateFinding finding) {
    if (!Side.RIGHT.equals(finding.side())) {
      return AnchorStatus.SUMMARY_FALLBACK;
    }
    Optional<DiffFile> file =
        diff.files().stream().filter(f -> f.path().equals(finding.file())).findFirst();
    return file.map(
            f ->
                f.containsLine(Side.RIGHT, finding.line())
                    ? AnchorStatus.INLINE
                    : AnchorStatus.SUMMARY_FALLBACK)
        .orElse(AnchorStatus.SUMMARY_FALLBACK);
  }

  // ---------- 公共 ----------

  private ReviewResult finish(
      TraceWriter trace,
      String runId,
      ReviewRequest request,
      RunStatus status,
      ReviewStats stats,
      String summaryLine) {
    Map<String, Object> end = new LinkedHashMap<>();
    end.put("status", status.name().toLowerCase(Locale.ROOT));
    end.put("findings_published", stats.findingsPublished());
    end.put("skipped_duplicate", stats.skippedDuplicate());
    end.put("tokens_in", stats.tokensIn());
    end.put("tokens_out", stats.tokensOut());
    end.put("latency_ms", stats.latencyMs());
    trace.emit("run_end", end);
    return new ReviewResult(
        runId,
        request.repoOwner(),
        request.repoName(),
        request.prNumber(),
        request.headSha(),
        status,
        stats,
        "file://" + trace.runDir(),
        summaryLine);
  }

  private static List<ScoredFinding> bySeverity(List<ScoredFinding> findings, Severity severity) {
    return findings.stream().filter(s -> s.finding().severityProposed() == severity).toList();
  }

  private static String categoryOf(CandidateFinding finding) {
    return finding.category().name().toLowerCase(Locale.ROOT);
  }

  private static String escape(String text) {
    // 05§1.3 规则 3：正文 <!-- → &lt;!--（正文永远不可能再产生未转义 HTML 注释）
    return text.replace("<!--", "&lt;!--");
  }

  private static String shortSha(String sha) {
    return sha.substring(0, Math.min(7, sha.length()));
  }

  private static String newRunId() {
    return "run_"
        + LocalDateTime.now().format(TS)
        + "-"
        + String.format("%06x", RANDOM.nextInt(1 << 24));
  }

  /** finding + 指纹 + 锚定状态载体。 */
  record ScoredFinding(CandidateFinding finding, String fingerprint, AnchorStatus anchor) {}
}
