package org.cra.eval.internal;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.cra.common.exception.CraIntegrationException;
import org.cra.common.text.Texts;
import org.cra.eval.api.FixtureMeta;

/**
 * 评测 runner（06§3 加载器→流水线→断言器的执行面）：逐用例起 stub 服务器 → 以黑盒子进程驱动被测 CLI → 捕获发布写请求 → 解析观测。全程离线（fixture
 * 数据面）、零外发写（写请求落在本进程捕获队列）。
 */
public final class EvalRunner {

  /** Summary"位置非法（降级进 Summary）"列表行（fallback 观测来源）。 */
  private static final Pattern FALLBACK_LINE =
      Pattern.compile("^- \\*\\*(.+?)\\*\\* — `(.+?):(\\d+)`：", Pattern.MULTILINE);

  private final CliProcessRunner.CliInvoker invoker;

  /**
   * @param invoker 被测 CLI 启动器（默认 JAVA_JAR，测试可注入）
   */
  public EvalRunner(CliProcessRunner.CliInvoker invoker) {
    this.invoker = invoker;
  }

  /**
   * 执行单用例单次 run。
   *
   * @param loaded 加载完成的用例
   * @param runIndex run 序号（0 基）
   * @param depth 深度档位
   * @param cliJar 被测 CLI jar
   * @param workDir 该用例工作目录（trace、日志、stub 数据落盘处）
   * @param processEnv 进程环境（LLM 配置来源）
   * @param crosschecker 是否透传 --crosschecker（M4f-lite）
   * @param semanticContext 是否透传 --semantic-context（ADR-0014；快照无 .git 时为如实降级）
   * @return 观测
   */
  public CaseObservation runOnce(
      DatasetLoader.LoadedCase loaded,
      int runIndex,
      String depth,
      Path cliJar,
      Path workDir,
      Map<String, String> processEnv,
      boolean crosschecker,
      boolean semanticContext) {
    FixtureMeta meta = loaded.meta();
    Path runRoot = workDir.resolve("run-" + runIndex);
    List<DiffSplitter.PatchedFile> files = DiffSplitter.split(loaded.diffPatch());
    try (EvalStubServer server =
        EvalStubServer.start(files, meta.baseSha(), meta.headSha(), loaded.snapshotRoot())) {
      Path logsDir = workDir.resolve("logs");
      Files.createDirectories(runRoot);
      Files.createDirectories(logsDir);
      Path stdoutLog = logsDir.resolve("cli-stdout-" + runIndex + ".log");
      Path stderrLog = logsDir.resolve("cli-stderr-" + runIndex + ".log");
      List<String> args =
          CliProcessRunner.reviewArgs(
              cliJar,
              meta.reviewRequest().repoOwner(),
              meta.reviewRequest().repoName(),
              meta.reviewRequest().prNumber(),
              meta.baseSha(),
              meta.headSha(),
              depth,
              server.baseUrl(),
              runRoot.toAbsolutePath(),
              loaded.snapshotRoot(),
              crosschecker,
              semanticContext);
      int exitCode =
          invoker.invoke(
              args,
              CliProcessRunner.llmEnv(processEnv),
              workDir.toAbsolutePath(),
              stdoutLog,
              stderrLog);
      return observe(meta, runIndex, server, exitCode, stdoutLog, stderrLog, runRoot);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new CraIntegrationException("eval run 被中断: " + meta.caseId(), e);
    } catch (Exception e) {
      String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
      return new CaseObservation(
          meta.caseId(),
          meta.set(),
          meta.adversarialSubtype(),
          runIndex,
          false,
          "failed",
          List.of(),
          Texts.truncate(reason, 200),
          CaseObservation.RunStats.EMPTY);
    }
  }

  private CaseObservation observe(
      FixtureMeta meta,
      int runIndex,
      EvalStubServer server,
      int exitCode,
      Path stdoutLog,
      Path stderrLog,
      Path runRoot) {
    List<EvalStubServer.CapturedReview> reviews = server.reviews();
    List<EvalStubServer.CapturedSummary> summaries = server.summaries();
    List<ObservedFinding> findings = new ArrayList<>();
    List<String> inlineBodies = new ArrayList<>();
    String reviewBody = "";
    for (EvalStubServer.CapturedReview review : reviews) {
      if (review.reviewBody() != null && !review.reviewBody().isBlank()) {
        reviewBody = review.reviewBody();
      }
      for (EvalStubServer.CapturedComment c : review.comments()) {
        inlineBodies.add(c.body());
      }
    }
    for (String body : inlineBodies) {
      for (EvalMarkerParser.FindingMarker marker : EvalMarkerParser.parseFindingMarkers(body)) {
        findings.add(
            new ObservedFinding(
                ObservedFinding.INLINE, marker.file(), marker.line(), marker.category(), body));
      }
    }
    String summaryBody = summaries.isEmpty() ? "" : summaries.get(summaries.size() - 1).body();
    Matcher fallback = FALLBACK_LINE.matcher(summaryBody);
    while (fallback.find()) {
      findings.add(
          new ObservedFinding(
              ObservedFinding.SUMMARY_FALLBACK,
              fallback.group(2),
              Integer.parseInt(fallback.group(3)),
              null,
              fallback.group(1) + "：" + sectionAfter(summaryBody, fallback.end())));
    }
    EvalMarkerParser.RunMarker runMarker = EvalMarkerParser.parseRunMarker(summaryBody);
    boolean completed = runMarker != null;
    String status = completed ? runMarker.status().toLowerCase(Locale.ROOT) : "failed";
    String failure =
        completed
            ? ""
            : "no completion marker (exit="
                + exitCode
                + ") "
                + Texts.truncate(CliProcessRunner.readQuietly(stderrLog).strip(), 200);
    if (!completed && exitCode == 0) {
      failure = "exit=0 但无完成标记（发布未收尾?）";
    }
    return new CaseObservation(
        meta.caseId(),
        meta.set(),
        meta.adversarialSubtype(),
        runIndex,
        completed,
        status,
        findings,
        failure,
        RunStatsReader.read(runRoot),
        reviewBody,
        summaryBody);
  }

  private static String sectionAfter(String body, int from) {
    int end = body.indexOf('\n', from);
    if (end < 0) {
      end = Math.min(body.length(), from + 400);
    }
    return body.substring(Math.min(from, body.length()), end).strip();
  }
}
