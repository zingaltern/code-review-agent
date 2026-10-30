package org.cra.eval.internal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.cra.common.exception.CraConfigException;
import org.cra.common.exception.CraIntegrationException;
import org.cra.common.text.Texts;
import org.cra.eval.api.WeeklyReport;
import org.cra.git.github.api.GitHubAssembly;
import org.cra.git.github.api.GitHubComponents;

/**
 * {@code cra-eval report} 执行（T3.4/T3.6）：经 CommentSource 端口取账本（ADR-0005，构造器注入）， 叠加 trace（漏斗/拒真池）与已固化
 * badcase（事后推翻），聚合为四类指标 + 漏斗 + 样本量门槛 + T3.6 评估。
 *
 * <p>本地跑无服务依赖：真实模式只需 GITHUB_TOKEN；{@code --ledger-fixture} 走离线回放（测试/脱敏样例）。
 */
public final class ReportCommand {

  private ReportCommand() {}

  /**
   * 执行 report。
   *
   * @param args 已解析参数
   * @param env 环境变量（token 兜底）
   */
  public static void execute(EvalCliArgs.ReportArgs args, Map<String, String> env) {
    Instant now = args.asOf() != null ? args.asOf() : Instant.now();
    Duration windowLength = DurationSpec.parse(args.since());
    Instant since = now.minus(windowLength);
    ReportWindowSource source;
    if (args.ledgerFixture() != null) {
      source = new ReplayWindowSource(args.ledgerFixture());
    } else {
      String token =
          Texts.firstNonBlank(args.token(), env.get("GITHUB_TOKEN"), env.get("GH_TOKEN"));
      if (token == null) {
        throw new CraConfigException(
            "report 需要 GitHub 只读 token：--token / GITHUB_TOKEN / GH_TOKEN（或用 --ledger-fixture 离线回放）");
      }
      GitHubComponents components = GitHubAssembly.createComponents(args.apiBase(), token);
      source = new GitHubWindowSource(components.commentSource(), components.adapter());
    }
    WindowData.Window base = source.load(args.repo(), since);
    List<Path> traceDirs = new ArrayList<>(args.traceDirs());
    List<String> extraNotes = new ArrayList<>();
    if (traceDirs.isEmpty() && Files.isDirectory(Path.of(".cra", "runs"))) {
      traceDirs.add(Path.of(".cra", "runs"));
      extraNotes.add("未指定 --trace-dir，默认读取本地 .cra/runs（本地 pre-PR 模式 trace）");
    }
    for (Path dir : traceDirs) {
      if (!Files.isDirectory(dir) && !Files.isRegularFile(dir)) {
        extraNotes.add("trace 目录不存在: " + dir);
      }
    }
    List<WindowData.RunFacts> runs = new ArrayList<>();
    for (TraceIngest.RunTrace trace : TraceIngest.scan(traceDirs, args.repo(), since)) {
      runs.add(TraceIngest.toFacts(trace));
    }
    if (traceDirs.isEmpty()) {
      extraNotes.add("未提供 --trace-dir：拒真池与漏斗的 trace 数据面为空");
    } else if (runs.isEmpty()) {
      extraNotes.add("trace 目录中无窗口内 run（" + traceDirs + "）");
    }
    List<String> notes = new ArrayList<>(base.notes());
    notes.addAll(extraNotes);
    WindowData.Window window =
        new WindowData.Window(
            base.repo(), base.source(), base.prs(), runs, BadcaseIndex.scan(args.dataset()), notes);
    List<WeeklyReport> previous = loadPrevious(args.previous());
    WeeklyReport report = WeeklyReportBuilder.build(window, now, args.since(), since, previous);
    ReportWriter.printLines(WeeklyReportWriter.renderText(report));
    if (args.jsonOut() != null) {
      Path out = args.jsonOut().toAbsolutePath();
      if (out.getParent() != null) {
        try {
          Files.createDirectories(out.getParent());
        } catch (IOException e) {
          throw new CraIntegrationException("输出目录创建失败: " + out.getParent(), e);
        }
      }
      WeeklyReportWriter.writeJson(report, out);
      ReportWriter.printLines(List.of("JSON 报告: " + out));
    }
  }

  private static List<WeeklyReport> loadPrevious(List<Path> files) {
    List<WeeklyReport> out = new ArrayList<>();
    for (Path file : files) {
      try {
        out.add(Json.MAPPER.readValue(file.toFile(), WeeklyReport.class));
      } catch (IOException e) {
        throw new CraConfigException("--previous 报告解析失败: " + file + "（" + e.getMessage() + "）");
      }
    }
    return out;
  }

  /** 窗口长度解析（7d / 12h / 2w）。 */
  static final class DurationSpec {

    private DurationSpec() {}

    /**
     * 解析窗口长度。
     *
     * @param text 形如 7d / 12h / 2w
     * @return Duration
     */
    static Duration parse(String text) {
      if (text == null || !text.matches("\\d+[dhw]")) {
        throw new CraConfigException("--since 非法: " + text + "（示例: 7d / 12h / 2w）");
      }
      long value = Long.parseLong(text.substring(0, text.length() - 1));
      if (value <= 0) {
        throw new CraConfigException("--since 必须为正: " + text);
      }
      return switch (text.charAt(text.length() - 1)) {
        case 'h' -> Duration.ofHours(value);
        case 'd' -> Duration.ofDays(value);
        case 'w' -> Duration.ofDays(value * 7);
        default -> throw new CraConfigException("--since 非法: " + text);
      };
    }
  }
}
