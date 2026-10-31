package org.cra.eval.internal;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.cra.common.exception.CraConfigException;
import org.cra.common.exception.CraIntegrationException;
import org.cra.eval.api.EvalCli;
import org.cra.eval.api.EvalReport;
import org.cra.eval.api.ExpectedFindings;
import org.cra.eval.api.FixtureMeta;

/**
 * {@code cra-eval run} 执行（T1.8/T1.9 验收 7）：加载用例 → 黑盒子进程驱动被测流水线 → 断言 → 聚合报告（检出率 + Wilson + 门禁判定 +
 * per-run 统计）。
 */
public final class RunCommand {

  private RunCommand() {}

  /**
   * 执行 run。
   *
   * @param args 已解析参数
   * @param env 进程环境（LLM 配置来源）
   * @return 退出码（0 = 正常完成；5 = --fail-on-red 且门禁红）
   */
  public static int execute(EvalCliArgs.RunArgs args, Map<String, String> env) {
    List<Path> caseDirs = filterCases(args);
    Path cacheDir = args.workDir().toAbsolutePath().resolve("cache");
    Path runsDir = args.workDir().toAbsolutePath().resolve("cases");
    Map<String, FixtureMeta> metas = new LinkedHashMap<>();
    Map<String, ExpectedFindings> expecteds = new LinkedHashMap<>();
    EvalRunner runner = new EvalRunner(CliProcessRunner.javaJar(args.timeoutSec()));
    List<CaseObservation> observations =
        runCases(args, env, caseDirs, runner, cacheDir, runsDir, new CaseTables(metas, expecteds));
    EvalReport report = generateReport(args, env, metas, expecteds, observations);
    ReportWriter.renderText(report);
    writeJsonIfRequested(args, report);
    return gateExitCode(args, report);
  }

  /**
   * 解析并过滤用例集（--case 未知即拒绝；空集即拒绝）。
   *
   * @param args run 参数
   * @return 实际运行用例目录
   */
  private static List<Path> filterCases(EvalCliArgs.RunArgs args) {
    List<Path> caseDirs = DatasetLoader.listCases(args.dataset(), args.set());
    if (args.onlyCases() != null && !args.onlyCases().isEmpty()) {
      java.util.Set<String> requested =
          args.onlyCases().stream()
              .filter(id -> !id.isBlank())
              .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
      List<Path> filtered = new ArrayList<>();
      for (Path p : caseDirs) {
        String id = p.getFileName().toString();
        if (requested.contains(id)) {
          filtered.add(p);
        }
      }
      java.util.List<String> unknown =
          requested.stream()
              .filter(id -> filtered.stream().noneMatch(p -> p.getFileName().toString().equals(id)))
              .toList();
      if (!unknown.isEmpty()) {
        // 拼错的 --case 曾静默缩小样本面（少 1 例仍可能过门禁），必须显式失败
        throw new CraConfigException("--case 含未知用例: " + unknown + "（数据集: " + args.dataset() + "）");
      }
      caseDirs = filtered;
    }
    if (caseDirs.isEmpty()) {
      throw new CraConfigException("数据集为空或过滤后无用例: " + args.dataset() + " set=" + args.set());
    }
    return caseDirs;
  }

  /**
   * 逐用例逐次执行被测 CLI 并收集观测（draft/relabel/重复 id 的守卫保持原序）。
   *
   * @param args run 参数
   * @param env 进程环境
   * @param caseDirs 用例目录
   * @param runner 被测进程执行器
   * @param cacheDir 快照缓存目录
   * @param runsDir 各用例工作目录根
   * @param tables caseId → meta / 断言规格输出表
   * @return 全部观测
   */
  private static List<CaseObservation> runCases(
      EvalCliArgs.RunArgs args,
      Map<String, String> env,
      List<Path> caseDirs,
      EvalRunner runner,
      Path cacheDir,
      Path runsDir,
      CaseTables tables) {
    List<CaseObservation> observations = new ArrayList<>();
    int index = 0;
    for (Path caseDir : caseDirs) {
      // 状态机先于数据加载（06§3）：draft 只有草稿、无 expected_findings.json——先给出"未确认"的
      // 明确拒绝，而不是文件缺失的误导性解析错误
      FixtureMeta meta = Json.readMeta(caseDir.resolve("meta.json"));
      if (isRelabeled(meta)) {
        // 净化工序②：relabeled 样本已挪集，原集统计作废（06§3）——不计入本集合观测
        ReportWriter.printLines(List.of("跳过改标样本: " + meta.caseId() + "（recheck=relabeled）"));
        continue;
      }
      if (isDraftAnnotation(meta)) {
        throw new CraConfigException(
            "用例 " + meta.caseId() + " 标注仍为 draft——先 cra-eval annotate confirm 后再 run（禁止跑未确认断言）");
      }
      if (tables.metas().containsKey(meta.caseId())) {
        throw new CraConfigException(
            "重复 case_id: " + meta.caseId() + "（用例目录 " + caseDir + " 与其他目录冲突；聚合会按 id 合并样本）");
      }
      DatasetLoader.LoadedCase loaded = DatasetLoader.load(caseDir, cacheDir);
      tables.metas().put(meta.caseId(), meta);
      tables.expecteds().put(meta.caseId(), loaded.expected());
      Path workDir = runsDir.resolve(meta.caseId());
      ensureEmptyDir(workDir);
      for (int k = 0; k < args.k(); k++) {
        index++;
        CaseObservation obs =
            runner.runOnce(
                loaded,
                k,
                args.depth(),
                args.cliJar(),
                workDir,
                env,
                args.crosscheckerEnabled(),
                args.semanticContext());
        observations.add(obs);
        ReportWriter.printLines(
            List.of(
                String.format(
                    "[%d/%d] %s#%d status=%s published=%d matched=%d/%d%s",
                    index,
                    caseDirs.size() * args.k(),
                    obs.caseId(),
                    obs.runIndex(),
                    obs.runStatus(),
                    obs.findings().size(),
                    FindingAsserter.matchAll(loaded.expected(), obs.findings()).size(),
                    loaded.expected().expected().size(),
                    obs.completed() ? "" : "  ⚠ " + obs.failureReason())));
      }
    }
    return observations;
  }

  /**
   * 聚合观测并执行门禁判定。
   *
   * @param args run 参数
   * @param env 进程环境
   * @param metas caseId → meta
   * @param expecteds caseId → 断言规格
   * @param observations 全部观测
   * @return 报告
   */
  private static EvalReport generateReport(
      EvalCliArgs.RunArgs args,
      Map<String, String> env,
      Map<String, FixtureMeta> metas,
      Map<String, ExpectedFindings> expecteds,
      List<CaseObservation> observations) {
    GateTier tier = GateTier.parse(args.tier(), args.k(), args.depth());
    return AggregateEngine.aggregate(
        args.dataset().toAbsolutePath().toString(),
        args.cliJar().toString(),
        args.depth(),
        args.k(),
        CliProcessRunner.llmConfigured(env),
        Instant.now().toString(),
        metas,
        expecteds,
        observations,
        tier.id(),
        args.commit(),
        GatePolicy.of(tier),
        args.crosscheckerEnabled());
  }

  /**
   * 按需写出 JSON 报告与摘要文件。
   *
   * @param args run 参数
   * @param report 报告
   */
  private static void writeJsonIfRequested(EvalCliArgs.RunArgs args, EvalReport report) {
    if (args.jsonOut() == null) {
      return;
    }
    Path out = args.jsonOut().toAbsolutePath();
    if (out.getParent() != null) {
      mkdirs(out.getParent());
    }
    ReportWriter.writeJson(report, out);
    ReportWriter.printLines(List.of("JSON 报告: " + out));
    if (args.sha256Out() != null) {
      String hex = ReportHasher.writeSha256File(out, args.sha256Out());
      ReportWriter.printLines(List.of("报告哈希 (SHA-256): " + hex));
    }
  }

  /**
   * 门禁结论输出与退出码（红且 --fail-on-red 才返回失败码）。
   *
   * @param args run 参数
   * @param report 报告
   * @return 退出码
   */
  private static int gateExitCode(EvalCliArgs.RunArgs args, EvalReport report) {
    if (report.gate() != null && !report.gate().passed()) {
      ReportWriter.printLines(
          List.of(
              "门禁结论: 红（tier="
                  + report.gate().tier()
                  + "）"
                  + (args.failOnRed() ? "——--fail-on-red 生效，退出码 " + EvalCli.GATE_RED_EXIT : "")));
      return args.failOnRed() ? EvalCli.GATE_RED_EXIT : 0;
    }
    if (report.gate() != null) {
      ReportWriter.printLines(List.of("门禁结论: 绿（tier=" + report.gate().tier() + "）"));
    }
    return 0;
  }

  private static boolean isRelabeled(FixtureMeta meta) {
    return meta.recheck() != null && "relabeled".equals(meta.recheck().status());
  }

  private static boolean isDraftAnnotation(FixtureMeta meta) {
    return meta.annotation() != null && "draft".equals(meta.annotation().status());
  }

  private static void ensureEmptyDir(Path dir) {
    try {
      if (Files.exists(dir)) {
        try (var walk = Files.walk(dir)) {
          walk.sorted(java.util.Comparator.reverseOrder())
              .forEach(
                  p -> {
                    try {
                      Files.delete(p);
                    } catch (java.io.IOException e) {
                      throw new CraIntegrationException("工作目录清理失败: " + p, e);
                    }
                  });
        }
      }
      Files.createDirectories(dir);
    } catch (java.io.IOException e) {
      throw new CraIntegrationException("工作目录创建失败: " + dir, e);
    }
  }

  private static void mkdirs(Path dir) {
    try {
      Files.createDirectories(dir);
    } catch (java.io.IOException e) {
      throw new CraIntegrationException("输出目录创建失败: " + dir, e);
    }
  }

  /**
   * 用例元数据与断言规格输出表（同一轮 run 的聚合输入）。
   *
   * @param metas caseId → meta
   * @param expecteds caseId → 断言规格
   */
  private record CaseTables(
      Map<String, FixtureMeta> metas, Map<String, ExpectedFindings> expecteds) {}
}
