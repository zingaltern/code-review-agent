package org.cra.eval.api;

import java.util.List;
import java.util.Map;
import org.cra.common.exception.CraException;
import org.cra.eval.internal.AnnotateCommand;
import org.cra.eval.internal.BadcaseCommand;
import org.cra.eval.internal.EvalCliArgs;
import org.cra.eval.internal.GateCommand;
import org.cra.eval.internal.ReportCommand;
import org.cra.eval.internal.RunCommand;
import org.cra.eval.internal.VerifyCommand;

/**
 * cra-eval CLI 入口（05§3：评测命令行壳；M1 run/annotate + M3 verify/gate/report/badcase）。
 *
 * <pre>
 * cra-eval run --dataset &lt;dir&gt; [--set ...] [--k N] [--depth quick] [--tier auto|l1|l2|l3]
 *             [--commit SHA] [--fail-on-red] [--sha256-out PATH] [--crosschecker-enabled] [--json PATH]
 * cra-eval annotate --fixture &lt;caseDir&gt; [--action prefill|confirm|relabel] ...
 * cra-eval verify --report &lt;json&gt; [--sha256 &lt;hex&gt;|--sha256-file &lt;path&gt;] [--tier l1|l2|l3] [--expect-commit SHA]
 *             [--signature &lt;envelope&gt; [--signing-key-env NAME|--signing-key-file PATH] [--signing-key-id ID]]
 * cra-eval gate --report &lt;json&gt; [--tier l1|l2|l3] [--json-out PATH] [--sha256-out PATH]
 * cra-eval report --repo &lt;owner/name&gt; --since 7d [--json PATH] [--ledger-fixture DIR] ...
 * cra-eval badcase add &lt;run_ref&gt; &lt;finding_id&gt; --label ... --dataset &lt;dir&gt; ...
 * </pre>
 *
 * <p>退出码（05§3）：0 正常完成（门禁红绿在报告中呈现，不改变退出码语义）；2 自身故障（参数/数据集/被测对象启动失败/ 平台取数失败）；5 门禁红（{@code
 * --fail-on-red} 生效）或 verify 校验失败——供 CI 的 L1/L2 job 判定（04§2.4）。
 */
public final class EvalCli {

  /** 门禁红 / verify 校验失败的退出码（CI 判定用，05§3）。 */
  public static final int GATE_RED_EXIT = 5;

  private EvalCli() {}

  /**
   * 进程入口。
   *
   * @param args 命令行参数
   */
  public static void main(String[] args) {
    int code = execute(args, System.getenv());
    if (code != 0) {
      System.exit(code);
    }
  }

  /**
   * 可测执行入口。
   *
   * @param args 命令行参数
   * @param env 环境变量视图
   * @return 退出码
   */
  public static int execute(String[] args, Map<String, String> env) {
    try {
      Object parsed = EvalCliArgs.parse(args, env);
      if (parsed instanceof EvalCliArgs.RunArgs runArgs) {
        return RunCommand.execute(runArgs, env);
      }
      if (parsed instanceof EvalCliArgs.AnnotateArgs annotateArgs) {
        AnnotateCommand.execute(annotateArgs);
        return 0;
      }
      if (parsed instanceof EvalCliArgs.VerifyArgs verifyArgs) {
        return VerifyCommand.execute(verifyArgs);
      }
      if (parsed instanceof EvalCliArgs.GateArgs gateArgs) {
        return GateCommand.execute(gateArgs);
      }
      if (parsed instanceof EvalCliArgs.ReportArgs reportArgs) {
        ReportCommand.execute(reportArgs, env);
        return 0;
      }
      if (parsed instanceof EvalCliArgs.BadcaseArgs badcaseArgs) {
        BadcaseCommand.execute(badcaseArgs, env);
        return 0;
      }
      return 2;
    } catch (CraException e) {
      org.cra.eval.internal.ReportWriter.printLines(List.of("cra-eval 参数/数据错误: " + e.getMessage()));
      return 2;
    }
  }
}
