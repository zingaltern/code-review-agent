package org.cra.runner.cli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CLI 入口（02§3：CI 模式；M2 起本地 pre-PR 模式）。
 *
 * <p>用法（05§3）：
 *
 * <pre>
 * # CI 模式
 * cra review --repo "$GITHUB_REPOSITORY" --pr "$PR_NUMBER" \
 *   --base "$BASE_SHA" --head "$HEAD_SHA" --depth standard --token "$GITHUB_TOKEN" [--dry-run] [--security-block]
 *
 * # CI 模式（GitLab，11§6 / ADR-0013）
 * cra review --platform gitlab --gitlab-project "$CI_PROJECT_PATH" --pr "$CI_MERGE_REQUEST_IID" \
 *   --base "$CI_MERGE_REQUEST_DIFF_BASE_SHA" --head "$CI_COMMIT_SHA" [--depth standard]
 *
 * # 本地 pre-PR 模式（T2.10：默认实跑 mvn -B -ntp verify + 本地评审，SQLite 账本，默认 dry-run）
 * cra review --local [--base main] [--depth standard] [--exec "mvn -B -ntp verify"]
 *            [--ledger-db ~/.cra/ledger.db] [--dry-run=false] [--model-strong M] [--fallback-chain standard,light]
 * </pre>
 */
public final class Main {

  private static final Logger LOG = LoggerFactory.getLogger(Main.class);

  private Main() {}

  /**
   * 进程入口。
   *
   * @param args 命令行参数
   */
  public static void main(String[] args) {
    System.exit(run(args));
  }

  /**
   * 执行入口（返回退出码，不调 System.exit——e2e 测试在进程内驱动，08 场景 16）。
   *
   * @param args 命令行参数
   * @return 退出码（05§3：0/2/3 + 本地实跑失败 4）
   */
  static int run(String[] args) {
    try {
      if (args.length > 0 && "sweep".equals(args[0])) {
        // M4d 夜间存量评审（05§3）：`cra sweep --repo owner/name --since 30d --max-prs 10`
        return new SweepCommand()
            .execute(
                SweepArgs.parse(
                    java.util.Arrays.copyOfRange(args, 1, args.length), System.getenv()));
      }
      CraConfig config = CliArgs.parse(args, System.getenv());
      return new ReviewCommand().execute(config);
    } catch (org.cra.common.exception.CraConfigException e) {
      LOG.error(
          "参数/配置错误: {}；用法: cra review [--local|--push] [--platform github|gitlab] --repo owner/name "
              + "--pr N --base SHA --head SHA "
              + "[--depth quick|standard|deep] [--token T] [--dry-run] [--security-block] "
              + "[--exec CMD] [--ledger-db PATH]；GitLab: --gitlab-project group/project + CRA_GITLAB_TOKEN；"
              + "或 cra sweep --repo owner/name --since 30d --max-prs 10",
          e.getMessage());
      return 2;
    } catch (org.cra.common.exception.CraException e) {
      // 05§3：自身故障（本地实跑启动失败/run 级异常等）统一退出码 2，不打未捕获栈
      LOG.error("cra 运行故障: {}", e.getMessage());
      return 2;
    }
  }
}
