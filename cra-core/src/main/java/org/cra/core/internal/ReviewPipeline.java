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
import java.util.Set;
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
import org.cra.common.git.LedgerFindingMarker;
import org.cra.common.git.LedgerRunMarker;
import org.cra.common.git.PostReviewCommand;
import org.cra.common.git.PrReviewResult;
import org.cra.common.git.PublishStatus;
import org.cra.common.git.RepoRef;
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
 *   <li>第二道闸：指纹级 exact 去重（滑窗容错随 M1）——发布中途掐断的 run 无完成标记，重跑按 指纹补发缺失条目、已发布不重发（07§1 验收 3）；
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

      // 第一道闸：commit 级幂等（02§4.1，只认完整发布；M0 全量跳过不更新 Summary，收尾随 T1.2）
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

      // 第二道闸数据面：已发布指纹（补发缺失、已发布不重发，02§4.1）
      Set<String> knownFingerprints =
          ledger.publishedFindings(request.prNumber()).stream()
              .map(LedgerFindingMarker::fingerprint)
              .collect(Collectors.toSet());

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

      // 去重（指纹 exact）+ 锚定校验（05§2.2）
      List<ScoredFinding> publishable = new ArrayList<>();
      int skippedDuplicate = 0;
      Map<String, String> fileCache = new HashMap<>();
      for (CandidateFinding finding : outcome.findings()) {
        String fingerprint = fingerprintOf(finding, request, fileCache);
        if (knownFingerprints.contains(fingerprint)) {
          skippedDuplicate++;
          trace.emit("dedup", Map.of("finding_id", finding.id(), "match", "exact"));
          continue;
        }
        AnchorStatus anchor = anchorStatus(diff, finding);
        trace.emit("anchor_check", Map.of("finding_id", finding.id(), "status", anchor.name()));
        publishable.add(new ScoredFinding(finding, fingerprint, anchor));
      }

      // publish（dry-run 零写）；重试类事件入 stats 但不降级——降级语义 = 未按预期完整发布
      int publishedCount =
          publish(request, runId, trace, publishable, skippedDuplicate, degradations);
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
      List<String> degradations) {
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
        summaryBody(request, runId, publishable, fallbacks, skippedDuplicate, degradations);
    if (request.dryRun()) {
      // dry-run：全程零写（05§6），发布器不触达 adapter
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
    return (int) (publishedActual + fallbackActual);
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
      List<String> degradations) {
    StringBuilder body = new StringBuilder();
    body.append("## cra 评审 Summary（").append(runId).append("）\n\n");
    body.append("变更：PR #")
        .append(request.prNumber())
        .append(" ")
        .append(shortSha(request.baseSha()))
        .append("..")
        .append(shortSha(request.headSha()))
        .append("\n\n");
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
    // run 完成标记（05§1.3）：DEGRADED 也是完整发布（02§4.1）
    body.append("\n<!-- cra:v=")
        .append(MARKER_VERSION)
        .append(" | run=")
        .append(runId)
        .append(" | commit=")
        .append(request.headSha())
        .append(" | status=")
        .append(degradations.contains("schema-violation-all-dropped") ? "DEGRADED" : "DONE")
        .append(" | findings=")
        .append(publishable.size())
        .append(" | suppressed=0 -->");
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

  private String fingerprintOf(
      CandidateFinding finding, ReviewRequest request, Map<String, String> fileCache) {
    String content =
        fileCache.computeIfAbsent(
            finding.file(),
            p -> {
              try {
                FileContent file =
                    adapter.readFile(
                        new RepoRef(request.repoOwner(), request.repoName()), request.headSha(), p);
                return file.binary() ? "" : file.text();
              } catch (RuntimeException e) {
                return ""; // 文件不可读（删除/权限）：空内容锚仍确定性
              }
            });
    List<String> lines = List.of(content.split("\n", -1));
    int line = finding.line();
    String anchor = line >= 1 && line <= lines.size() ? lines.get(line - 1) : "";
    int from = Math.min(Math.max(0, line - 3), lines.size());
    int to = Math.min(Math.max(from, line + 2), lines.size());
    List<String> context = lines.subList(from, to);
    return FingerprintCalculator.fingerprint(finding.file(), categoryOf(finding), anchor, context);
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
