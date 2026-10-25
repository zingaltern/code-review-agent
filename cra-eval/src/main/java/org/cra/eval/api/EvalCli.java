package org.cra.eval.api;

import java.util.List;
import java.util.Map;
import org.cra.common.exception.CraException;
import org.cra.eval.internal.AnnotateCommand;
import org.cra.eval.internal.EvalCliArgs;
import org.cra.eval.internal.RunCommand;

/**
 * cra-eval CLI 入口（05§3：评测命令行壳；M1 支持 run / annotate，report 随 T3.4）。
 *
 * <pre>
 * cra-eval run --dataset &lt;dir&gt; [--set ...] [--k N] [--depth quick] [--cli-jar PATH] [--json PATH]
 * cra-eval annotate --fixture &lt;caseDir&gt; [--action prefill|confirm|relabel] ...
 * </pre>
 *
 * <p>退出码：0 正常完成（门禁红绿在报告中呈现，不改变退出码语义——分级阻塞随 04§2.4 的 L2 门禁接入）； 2 自身故障（参数/数据集/被测对象启动失败）。
 */
public final class EvalCli {

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
        RunCommand.execute(runArgs, env);
      } else if (parsed instanceof EvalCliArgs.AnnotateArgs annotateArgs) {
        AnnotateCommand.execute(annotateArgs);
      } else {
        return 2;
      }
      return 0;
    } catch (CraException e) {
      org.cra.eval.internal.ReportWriter.printLines(List.of("cra-eval 参数/数据错误: " + e.getMessage()));
      return 2;
    }
  }
}
