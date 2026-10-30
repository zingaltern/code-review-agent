package org.cra.eval.internal;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.cra.common.exception.CraConfigException;
import org.cra.common.git.RepoRef;

/** {@code cra-eval report} 参数解析（T3.4）。 */
final class ReportArgsParser {

  /** report 接受的全部旗标（与 EvalUsage 同步）。 */
  private static final List<String> FLAGS =
      List.of(
          "--repo",
          "--since",
          "--json",
          "--ledger-fixture",
          "--trace-dir",
          "--previous",
          "--dataset",
          "--token",
          "--api-base",
          "--as-of");

  private ReportArgsParser() {}

  static EvalCliArgs.ReportArgs parse(EvalArgOptions options, Map<String, String> env) {
    options.rejectUnknownFlags(FLAGS);
    options.rejectPositional();
    Map<String, String> named = options.named();
    String repo = named.get("--repo");
    if (repo == null || repo.isBlank()) {
      throw new CraConfigException("缺参数: --repo <owner/name>");
    }
    String since = named.getOrDefault("--since", "7d");
    ReportCommand.DurationSpec.parse(since);
    String json = named.get("--json");
    String fixture = named.get("--ledger-fixture");
    String dataset = named.get("--dataset");
    Path datasetPath = dataset != null ? Path.of(dataset) : EvalArgsSupport.defaultDataset();
    String token = named.get("--token");
    String apiBase = named.get("--api-base");
    if (apiBase == null || apiBase.isBlank()) {
      apiBase = env.getOrDefault("GITHUB_API_URL", "https://api.github.com");
    }
    String asOfRaw = named.get("--as-of");
    java.time.Instant asOf = null;
    if (asOfRaw != null) {
      try {
        asOf = java.time.Instant.parse(asOfRaw);
      } catch (java.time.format.DateTimeParseException e) {
        throw new CraConfigException("--as-of 需为 ISO-8601 时刻（如 2026-10-29T00:00:00Z）: " + asOfRaw);
      }
    }
    return new EvalCliArgs.ReportArgs(
        RepoRef.parse(repo),
        since,
        json == null ? null : Path.of(json),
        fixture == null ? null : Path.of(fixture),
        EvalArgsSupport.pathList(named.get("--trace-dir")),
        EvalArgsSupport.pathList(named.get("--previous")),
        datasetPath,
        token,
        apiBase,
        asOf);
  }
}
