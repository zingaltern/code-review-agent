package org.cra.eval.internal;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.cra.common.exception.CraConfigException;
import org.cra.common.git.CommentSource;
import org.cra.common.text.Texts;
import org.cra.git.github.api.GitHubAssembly;
import org.cra.git.github.api.GitHubComponents;

/**
 * {@code cra-eval badcase add} 执行（T3.5）：run_ref 解析 + 平台端口装配 + 人工步骤提示。
 *
 * <pre>
 * cra-eval badcase add &lt;run_ref&gt; &lt;finding_id&gt; --label missed|false-positive|relabeled
 *     --dataset &lt;dir&gt; [--to-set bad|clean] [--case-id ID] [--annotator NAME] [--reason TEXT]
 *     [--diff-file PATH] [--snapshot-dir DIR] [--artifact-root DIR] [--token ...] [--api-base URL]
 * </pre>
 */
public final class BadcaseCommand {

  private BadcaseCommand() {}

  /**
   * 执行 badcase add。
   *
   * @param args 已解析参数
   * @param env 环境变量（token / artifact root 兜底）
   */
  public static void execute(EvalCliArgs.BadcaseArgs args, Map<String, String> env) {
    Path runRef = resolveRunRef(args.runRef(), artifactRoot(args.artifactRoot(), env));
    boolean needRemote = args.diffFile() == null || args.snapshotDir() == null;
    GitHubComponents components = null;
    if (needRemote) {
      String token =
          Texts.firstNonBlank(args.token(), env.get("GITHUB_TOKEN"), env.get("GH_TOKEN"));
      if (token == null) {
        throw new CraConfigException(
            "badcase add 需要 GitHub 只读 token（--token/GITHUB_TOKEN/GH_TOKEN）用于拉 diff/快照，"
                + "或提供 --diff-file + --snapshot-dir 离线输入");
      }
      components = GitHubAssembly.createComponents(args.apiBase(), token);
    }
    BadcaseService.Inputs inputs =
        new BadcaseService.Inputs(
            args.dataset(),
            args.runRef(),
            runRef,
            args.findingId(),
            args.label(),
            args.toSet(),
            args.caseId(),
            args.annotator(),
            args.reason(),
            args.diffFile(),
            args.snapshotDir(),
            args.file(),
            args.line());
    CommentSource comments = components == null ? null : components.commentSource();
    BadcaseService.Result result =
        BadcaseService.add(inputs, components == null ? null : components.adapter(), comments);
    ReportWriter.printLines(render(result));
  }

  private static List<String> render(BadcaseService.Result result) {
    return List.of(
        "已生成 badcase 用例: " + result.caseDir(),
        "  来源: run="
            + result.run().runId()
            + " finding="
            + result.finding().findingId()
            + " → set="
            + result.set()
            + " case_id="
            + result.caseId(),
        "  finding 定位: "
            + result.findingSource()
            + "  "
            + result.finding().file()
            + ":"
            + result.finding().line()
            + "  category="
            + result.finding().category()
            + (result.verifierVerdict().isBlank()
                ? "  verifier_verdict=（未记录）"
                : "  verifier_verdict=" + result.verifierVerdict()),
        "  diff: " + result.diffSource() + "（" + result.changedFiles() + " 文件）",
        "  快照: "
            + result.snapshot().files()
            + " 文件（sha256="
            + result.snapshot().sha256()
            + "）"
            + (result.snapshot().skippedBinary() > 0
                ? "，二进制跳过 " + result.snapshot().skippedBinary() + " 个"
                : ""),
        "下一步:",
        "  1. 人工核对 " + result.caseDir().resolve("expected_findings.draft.json") + "（位置/类别/关键词）",
        "  2. cra-eval annotate --fixture "
            + result.caseDir()
            + " --action confirm --annotator <name>",
        "  3. cra-eval run --dataset <dataset> --set "
            + result.set()
            + " --case "
            + result.caseId()
            + "（确认后进入下次回归；来源记录见 badcase.json）");
  }

  private static Path resolveRunRef(String raw, Path artifactRoot) {
    if (raw == null || raw.isBlank()) {
      throw new CraConfigException("缺少 run_ref（本地 run 目录或 artifact:// 引用）");
    }
    if (raw.startsWith("artifact://")) {
      String rest = raw.substring("artifact://".length());
      return artifactRoot.resolve(rest).normalize();
    }
    if (raw.startsWith("file://")) {
      return Path.of(URI.create(raw));
    }
    return Path.of(raw);
  }

  private static Path artifactRoot(Path explicit, Map<String, String> env) {
    if (explicit != null) {
      return explicit;
    }
    String fromEnv = env.get("CRA_ARTIFACT_ROOT");
    return fromEnv == null || fromEnv.isBlank() ? Path.of(".cra", "artifact") : Path.of(fromEnv);
  }
}
