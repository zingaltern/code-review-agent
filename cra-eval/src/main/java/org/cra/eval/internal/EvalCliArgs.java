package org.cra.eval.internal;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.cra.common.exception.CraConfigException;
import org.cra.common.git.RepoRef;

/**
 * {@code cra-eval} 命令参数模型与分发（run | annotate | verify | gate | report | badcase 子命令，05§3）。缺参/非法值 →
 * CraConfigException（退出码 2，与 cra review 同语义）。
 *
 * <p>各子命令的解析器分别位于 {@link RunArgsParser} / {@link AnnotateArgsParser} / {@link VerifyArgsParser} /
 * {@link GateArgsParser} / {@link ReportArgsParser} / {@link BadcaseArgsParser}；公共切分与校验见 {@link
 * EvalArgOptions} / {@link EvalArgsSupport}。
 */
public final class EvalCliArgs {

  /**
   * run 命令参数。
   *
   * @param dataset 数据集根
   * @param set 集合过滤
   * @param k 每样本运行次数
   * @param depth 深度档位
   * @param cliJar 被测 CLI jar
   * @param workDir 工作目录
   * @param jsonOut JSON 报告输出路径（可空）
   * @param timeoutSec 单 run 超时
   * @param onlyCases 仅跑指定用例（空 = 全部）
   * @param tier 门禁档位 auto|l1|l2|l3（auto 按 k/depth 推导）
   * @param commit 产出报告的代码提交（L2 哈希校验绑定；缺省取 GITHUB_SHA）
   * @param failOnRed 门禁红时以退出码 5 结束（CI L1/L2 判定用）
   * @param sha256Out 报告哈希输出路径（L2 本地跑 + CI 校验，04§2.4）
   * @param crosscheckerEnabled M4f CrossChecker 是否启用（context 类是否计入门禁）
   * @param semanticContext 是否启用语义上下文（ADR-0014：被测 CLI `--semantic-context` 透传；默认关闭）
   */
  public record RunArgs(
      Path dataset,
      String set,
      int k,
      String depth,
      Path cliJar,
      Path workDir,
      Path jsonOut,
      long timeoutSec,
      List<String> onlyCases,
      String tier,
      String commit,
      boolean failOnRed,
      Path sha256Out,
      boolean crosscheckerEnabled,
      boolean semanticContext) {}

  /** annotate 命令参数。 */
  public record AnnotateArgs(
      Path fixture, String action, String toSet, String annotator, String reason) {}

  /**
   * verify 命令参数（T3.3：CI 校验本地 L2 报告；M4b 增服务端签名校验）。
   *
   * @param report 报告 JSON 路径
   * @param sha256 期望哈希（与 sha256File/signature 至少给一个）
   * @param sha256File 哈希文件路径
   * @param tier 要求档位（可空）
   * @param expectCommit 要求报告 commit（CI 传 PR head）
   * @param signature 服务端签名信封路径（可空；04§2.4 M4b）
   * @param signingKeyEnv 读取 HMAC 密钥的环境变量名（可空 = CRA_REPORT_SIGNING_KEY）
   * @param signingKeyFile HMAC 密钥文件（可空；与 signingKeyEnv 二选一）
   * @param signingKeyId 要求签名信封的 key_id（可空 = 不校验）
   */
  public record VerifyArgs(
      Path report,
      String sha256,
      Path sha256File,
      String tier,
      String expectCommit,
      String repo,
      Path signature,
      String signingKeyEnv,
      Path signingKeyFile,
      String signingKeyId) {}

  /**
   * gate 命令参数（T3.1：对已有报告离线重判，不重跑 LLM）。
   *
   * @param report 报告 JSON 路径
   * @param tier 档位（可空 = 沿用报告 tier，再退化为按 k/depth 推导）
   * @param jsonOut 重判报告输出路径（可空）
   * @param sha256Out 重判报告哈希输出路径（可空）
   * @param crosscheckerEnabled M4f 是否启用（context 类是否计入判定）
   */
  public record GateArgs(
      Path report, String tier, Path jsonOut, Path sha256Out, boolean crosscheckerEnabled) {}

  /**
   * report 命令参数（T3.4）。
   *
   * @param repo 仓库
   * @param since 窗口长度（7d/12h/2w）
   * @param jsonOut JSON 输出路径（可空）
   * @param ledgerFixture 离线账本回放目录（可空 = 走 GitHub）
   * @param traceDirs run trace 目录（漏斗/拒真池）
   * @param previous 历史周报 JSON（趋势基线）
   * @param dataset 数据集根（扫描 badcase.json；可空）
   * @param token GitHub token（可空，命令层回退环境变量）
   * @param apiBase GitHub API 根
   */
  public record ReportArgs(
      RepoRef repo,
      String since,
      Path jsonOut,
      Path ledgerFixture,
      List<Path> traceDirs,
      List<Path> previous,
      Path dataset,
      String token,
      String apiBase) {}

  /**
   * badcase add 命令参数（T3.5）。
   *
   * @param dataset 数据集根
   * @param runRef 原始 run 引用（本地路径或 artifact://）
   * @param findingId finding 标识
   * @param label missed|false-positive|relabeled
   * @param toSet relabeled 目标集合（可空）
   * @param caseId 自定义 case id（可空）
   * @param annotator 标注人
   * @param reason 理由
   * @param diffFile 离线 diff（可空）
   * @param snapshotDir 离线快照目录（可空）
   * @param artifactRoot artifact:// 解析根（可空）
   * @param token GitHub token（可空）
   * @param apiBase GitHub API 根
   * @param file 覆盖 finding 文件（可空）
   * @param line 覆盖 finding 行号（可空）
   */
  public record BadcaseArgs(
      Path dataset,
      String runRef,
      String findingId,
      String label,
      String toSet,
      String caseId,
      String annotator,
      String reason,
      Path diffFile,
      Path snapshotDir,
      Path artifactRoot,
      String token,
      String apiBase,
      String file,
      Integer line) {}

  private EvalCliArgs() {}

  /**
   * 解析参数。
   *
   * @param args 命令行参数（首个为子命令）
   * @param env 环境变量（LLM 配置检测用）
   * @return 解析结果（各子命令 Args record）
   */
  public static Object parse(String[] args, Map<String, String> env) {
    EvalArgOptions options = EvalArgOptions.parse(args);
    return switch (options.subcommand()) {
      case "run" -> RunArgsParser.parse(options, env);
      case "annotate" -> AnnotateArgsParser.parse(options);
      case "verify" -> VerifyArgsParser.parse(options);
      case "gate" -> GateArgsParser.parse(options);
      case "report" -> ReportArgsParser.parse(options, env);
      case "badcase" -> BadcaseArgsParser.parse(options, env);
      default ->
          throw new CraConfigException("未知子命令: " + options.subcommand() + "；" + EvalUsage.text());
    };
  }
}
