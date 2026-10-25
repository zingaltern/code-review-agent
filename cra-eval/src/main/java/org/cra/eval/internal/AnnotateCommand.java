package org.cra.eval.internal;

import java.nio.file.Path;
import java.util.List;
import org.cra.common.exception.CraConfigException;
import org.cra.eval.api.FixtureMeta;

/** {@code cra-eval annotate} 执行（06§3 半自动标注：hunk 预填 + 人工确认断言 + relabel 复核通道格式）。 */
public final class AnnotateCommand {

  private AnnotateCommand() {}

  /**
   * 执行 annotate。
   *
   * @param args 已解析参数
   */
  public static void execute(EvalCliArgs.AnnotateArgs args) {
    switch (args.action()) {
      case "prefill" -> {
        Path draft = AnnotateService.prefill(args.fixture(), null, args.annotator());
        ReportWriter.printLines(
            List.of(
                "预填完成: " + draft,
                "人工确认步骤：",
                "  1. 编辑草稿：核对每条 expected 的 file/line/category/keywords（关键词 = 全部出现于 finding 正文才算命中）",
                "  2. 对抗用例补 negative_baits（幻觉/注入目标行为）",
                "  3. cra-eval annotate --fixture "
                    + args.fixture()
                    + " --action confirm --annotator <name>",
                "  4. 将 meta.json 的 snapshot.sha256 用生成工具回填后再 run"));
      }
      case "confirm" -> {
        Path done = AnnotateService.confirm(args.fixture(), args.annotator());
        ReportWriter.printLines(List.of("已确认: " + done));
      }
      case "relabel" -> {
        AnnotateService.relabel(args.fixture(), args.toSet(), args.annotator(), args.reason());
        FixtureMeta meta = Json.readMeta(args.fixture().resolve("meta.json"));
        ReportWriter.printLines(
            List.of(
                "已改标: "
                    + meta.caseId()
                    + " → "
                    + args.toSet()
                    + "（recheck=relabeled；该样本挪集、当次门禁统计作废重跑——06§3）"));
      }
      default -> throw new CraConfigException("未知 annotate action: " + args.action());
    }
  }
}
