package org.cra.runner.cli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CLI 入口（02§3：CI 模式；M2 起本地 pre-PR 模式）。
 *
 * <p>用法（05§3）：
 *
 * <pre>
 * cra review --repo "$GITHUB_REPOSITORY" --pr "$PR_NUMBER" \
 *   --base "$BASE_SHA" --head "$HEAD_SHA" --depth standard --token "$GITHUB_TOKEN" [--dry-run] [--security-block]
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
    int code;
    try {
      CraConfig config = CliArgs.parse(args, System.getenv());
      code = new ReviewCommand().execute(config);
    } catch (org.cra.common.exception.CraConfigException e) {
      LOG.error(
          "参数/配置错误: {}；用法: cra review --repo owner/name --pr N --base SHA --head SHA "
              + "[--depth quick|standard|deep] [--token T] [--dry-run] [--security-block]",
          e.getMessage());
      code = 2;
    }
    System.exit(code);
  }
}
