package org.cra.eval.internal;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.cra.common.exception.CraIntegrationException;
import org.cra.common.git.RepoRef;

/**
 * run trace 读取（05§1.4 NDJSON；T3.4 漏斗/拒真池、T3.5 badcase 定位共用）。
 *
 * <p>只消费事件子集：run_start / promotion / judge / run_end。trace 缺 promotion 事件（旧形态或崩溃 run）时 漏斗标为不可得，不以 0
 * 假装"零候选"。
 */
final class TraceIngest {

  private TraceIngest() {}

  /** 单条 promotion 事件。 */
  record Promotion(
      String findingId,
      String verifierVerdict,
      String decision,
      String reason,
      boolean spotcheckFuse) {}

  /** 单条 judge 事件（终裁判定）。 */
  record Judge(String findingId, String verdict) {}

  /**
   * 单个 run 的 trace 事实。
   *
   * @param runId run 标识
   * @param repo run_start 的 repo（缺失为 null）
   * @param pr PR 编号（缺失为 0）
   * @param baseSha base（缺失为空串）
   * @param headSha head（缺失为空串）
   * @param startedAt 首行时间
   * @param status run_end 终态（缺失为 unknown）
   * @param dryRun run_start.dry_run
   * @param published run_end.findings_published
   * @param suppressed run_end.suppressed_by_truncation
   * @param promotions 晋升事件
   * @param judges judge 事件（按 finding_id 去重，后见覆盖）
   * @param runDir trace 所在目录（briefs 相对位置）
   */
  record RunTrace(
      String runId,
      String repo,
      int pr,
      String baseSha,
      String headSha,
      Instant startedAt,
      String status,
      boolean dryRun,
      int published,
      int suppressed,
      List<Promotion> promotions,
      Map<String, String> judges,
      Path runDir) {

    RunTrace {
      promotions = List.copyOf(promotions == null ? List.of() : promotions);
      judges = Map.copyOf(judges == null ? Map.of() : judges);
    }
  }

  /**
   * 扫描 trace 根目录（本地 {@code .cra/runs} 或 CI artifact 解包目录）。
   *
   * @param roots 根目录/单个 trace 文件
   * @param repoFilter 仓库过滤（null = 不过滤）
   * @param since 窗口起点（null = 不过滤）
   * @return 按文件路径排序的 run trace
   */
  static List<RunTrace> scan(List<Path> roots, RepoRef repoFilter, Instant since) {
    List<Path> traceFiles = new ArrayList<>();
    Set<Path> seen = new HashSet<>();
    for (Path root : roots) {
      collectTraceFiles(root, seen, traceFiles);
    }
    String repoName = repoFilter == null ? null : repoFilter.owner() + "/" + repoFilter.repo();
    return traceFiles.stream()
        .map(TraceIngest::parse)
        .filter(trace -> inScope(trace, repoName, since))
        .toList();
  }

  /**
   * 收集单个根下的 trace.ndjson（文件形态直接收录；目录递归至 6 层，去重按绝对路径）。
   *
   * @param root 根目录/单个 trace 文件
   * @param seen 已收录绝对路径
   * @param traceFiles 输出
   */
  private static void collectTraceFiles(Path root, Set<Path> seen, List<Path> traceFiles) {
    if (Files.isRegularFile(root) && root.getFileName().toString().equals("trace.ndjson")) {
      if (seen.add(root.toAbsolutePath())) {
        traceFiles.add(root);
      }
      return;
    }
    if (!Files.isDirectory(root)) {
      return;
    }
    try (Stream<Path> walk = Files.walk(root, 6)) {
      walk.filter(p -> p.getFileName().toString().equals("trace.ndjson"))
          .filter(Files::isRegularFile)
          .map(Path::toAbsolutePath)
          .sorted()
          .forEach(
              p -> {
                if (seen.add(p)) {
                  traceFiles.add(p);
                }
              });
    } catch (IOException e) {
      throw new CraIntegrationException("trace 目录扫描失败: " + root, e);
    }
  }

  /**
   * trace 是否在仓库/时间窗口内。
   *
   * @param trace 解析结果
   * @param repoName 仓库名（null = 不限）
   * @param since 窗口起点（null = 不限）
   * @return true 若在窗口内
   */
  private static boolean inScope(RunTrace trace, String repoName, Instant since) {
    if (repoName != null && trace.repo() != null && !repoName.equals(trace.repo())) {
      return false;
    }
    return since == null || trace.startedAt() == null || !trace.startedAt().isBefore(since);
  }

  /**
   * 解析单个 trace 文件。
   *
   * @param file trace.ndjson
   * @return run 事实
   */
  static RunTrace parse(Path file) {
    String dirName = file.getParent() == null ? "" : file.getParent().getFileName().toString();
    String runId = "";
    Instant startedAt = null;
    List<Promotion> promotions = new ArrayList<>();
    Map<String, String> judges = new HashMap<>();
    TraceState state = new TraceState();
    for (String line : readLines(file)) {
      if (line.isBlank()) {
        continue;
      }
      JsonNode event = readEvent(file, line);
      if (startedAt == null && event.hasNonNull("ts")) {
        startedAt = Instant.parse(event.path("ts").asText());
      }
      if (runId.isEmpty() && event.hasNonNull("run_id")) {
        runId = event.path("run_id").asText("");
      }
      applyEvent(event, state, promotions, judges);
    }
    return new RunTrace(
        runId.isEmpty() ? dirName : runId,
        state.repo,
        state.pr,
        state.base,
        state.head,
        startedAt,
        state.status,
        state.dryRun,
        state.published,
        state.suppressed,
        promotions,
        judges,
        file.getParent());
  }

  /** trace 解析的 run_start/run_end 累计状态（后到事件覆盖先到值）。 */
  private static final class TraceState {
    private String repo;
    private int pr;
    private String base = "";
    private String head = "";
    private String status = "unknown";
    private boolean dryRun;
    private int published;
    private int suppressed;
  }

  /**
   * 读取 trace 行（读取失败按边界异常上抛）。
   *
   * @param file trace.ndjson
   * @return 行列表
   */
  private static List<String> readLines(Path file) {
    try {
      return Files.readAllLines(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new CraIntegrationException("trace 读取失败: " + file, e);
    }
  }

  /**
   * 解析单行 JSON 事件（坏行按边界异常上抛）。
   *
   * @param file trace.ndjson（错误信息用）
   * @param line 行文本
   * @return 事件节点
   */
  private static JsonNode readEvent(Path file, String line) {
    try {
      return Json.MAPPER.readTree(line);
    } catch (IOException e) {
      throw new CraIntegrationException("trace 行解析失败: " + file + "（" + e.getMessage() + "）", e);
    }
  }

  /**
   * 应用单个 trace 事件到解析状态。
   *
   * @param event 事件节点
   * @param state run_start/run_end 累计状态
   * @param promotions promotion 事件
   * @param judges judge 事件
   */
  private static void applyEvent(
      JsonNode event, TraceState state, List<Promotion> promotions, Map<String, String> judges) {
    switch (event.path("event").asText("")) {
      case "run_start" -> {
        state.repo = event.path("repo").asText(state.repo);
        state.pr = event.path("pr").asInt(state.pr);
        state.base = event.path("base").asText(state.base);
        state.head = event.path("head").asText(state.head);
        state.dryRun = event.path("dry_run").asBoolean(false);
      }
      case "promotion" ->
          promotions.add(
              new Promotion(
                  event.path("finding_id").asText(""),
                  event.path("verifier_verdict").asText(""),
                  event.path("decision").asText(""),
                  event.path("reason").asText(""),
                  event.path("spotcheck_fuse").asBoolean(false)));
      case "judge" -> {
        if (!event.path("finding_id").asText("").isBlank()) {
          judges.put(event.path("finding_id").asText(), event.path("verdict").asText(""));
        }
      }
      case "run_end" -> {
        state.status = event.path("status").asText(state.status);
        state.published = event.path("findings_published").asInt(state.published);
        state.suppressed = event.path("suppressed_by_truncation").asInt(state.suppressed);
      }
      default -> {
        // 其余事件与周报无关
      }
    }
  }

  /**
   * trace → 周报聚合事实。
   *
   * @param trace run trace
   * @return RunFacts
   */
  static WindowData.RunFacts toFacts(RunTrace trace) {
    Set<String> candidates = new LinkedHashSet<>();
    int confirmed = 0;
    int refuted = 0;
    int judgeUnavailable = 0;
    List<WindowData.RefusalSample> judgedRefusals = new ArrayList<>();
    Map<String, Boolean> perFinding = new LinkedHashMap<>();
    for (Promotion promotion : trace.promotions()) {
      if (promotion.findingId().isBlank()) {
        continue;
      }
      candidates.add(promotion.findingId());
      boolean isRefuted = "REFUTED".equals(promotion.verifierVerdict());
      if (isRefuted) {
        refuted++;
      }
      if ("publish".equals(promotion.decision())) {
        confirmed++;
      } else if ("judge".equals(promotion.decision())) {
        String verdict = trace.judges().get(promotion.findingId());
        if (!isVerdictAvailable(verdict)) {
          if (isRefuted) {
            judgeUnavailable++;
          }
        } else {
          boolean confirmedVerdict = "CONFIRMED".equalsIgnoreCase(verdict);
          if (confirmedVerdict) {
            confirmed++;
          }
          if (isRefuted) {
            perFinding.put(promotion.findingId(), confirmedVerdict);
          }
        }
      }
    }
    perFinding.forEach(
        (id, overturned) -> judgedRefusals.add(new WindowData.RefusalSample(id, overturned)));
    return new WindowData.RunFacts(
        trace.runId(),
        trace.repo(),
        trace.pr(),
        trace.startedAt(),
        trace.status(),
        trace.dryRun(),
        candidates.size(),
        confirmed,
        trace.published(),
        trace.suppressed(),
        refuted,
        judgeUnavailable,
        judgedRefusals,
        !trace.promotions().isEmpty());
  }

  /**
   * judge 判定是否可用（缺失/空白/unavailable 均不可用）。
   *
   * @param verdict judge verdict 原值
   * @return true 若可用
   */
  private static boolean isVerdictAvailable(String verdict) {
    return verdict != null && !verdict.isBlank() && !"unavailable".equalsIgnoreCase(verdict);
  }

  /**
   * 定位 run 目录（badcase add：run_ref 可为 trace 文件、run 目录或 runs 根目录的唯一子目录）。
   *
   * @param runRef 已解析的本地路径
   * @return trace 文件
   */
  static Path locateTrace(Path runRef) {
    if (Files.isRegularFile(runRef) && runRef.getFileName().toString().equals("trace.ndjson")) {
      return runRef.toAbsolutePath();
    }
    Path direct = runRef.resolve("trace.ndjson");
    if (Files.isRegularFile(direct)) {
      return direct.toAbsolutePath();
    }
    List<Path> found;
    try (Stream<Path> walk = Files.walk(runRef, 6)) {
      found =
          walk.filter(p -> p.getFileName().toString().equals("trace.ndjson"))
              .filter(Files::isRegularFile)
              .map(Path::toAbsolutePath)
              .sorted()
              .toList();
    } catch (IOException e) {
      throw new CraIntegrationException("run 目录扫描失败: " + runRef, e);
    }
    if (found.isEmpty()) {
      throw new org.cra.common.exception.CraConfigException(
          "run_ref 下未找到 trace.ndjson: " + runRef + "（支持本地 run 目录 / artifact 解包目录）");
    }
    if (found.size() > 1) {
      throw new org.cra.common.exception.CraConfigException(
          "run_ref 下有多个 trace.ndjson，请指向具体 run 目录: " + runRef + " → " + found);
    }
    return found.get(0);
  }
}
