package org.cra.runner.cli;

import java.util.HashMap;
import java.util.Map;
import org.cra.common.exception.CraConfigException;

/**
 * `cra review` 参数解析（05§3 CLI 契约）。缺参/非法值 → CraConfigException（退出码 2）。 参数优先于环境变量；密钥类只认环境变量来源的传递（CI 中经
 * --token 传入亦视为环境侧提供）。
 */
final class CliArgs {

  private CliArgs() {}

  /**
   * 解析命令行参数与环境变量。
   *
   * @param args 命令行参数（review 子命令之后）
   * @param env 环境变量视图（测试注入）
   * @return 运行配置
   */
  static CraConfig parse(String[] args, Map<String, String> env) {
    // java -jar cra-runner-cli.jar review --repo …（05§3/05§4）：可选 review 子命令前缀
    if (args.length > 0 && "review".equals(args[0])) {
      args = java.util.Arrays.copyOfRange(args, 1, args.length);
    }
    Map<String, String> named = new HashMap<>();
    boolean dryRun = false;
    boolean securityBlock = false;
    for (int i = 0; i < args.length; i++) {
      String arg = args[i];
      switch (arg) {
        case "--dry-run" -> dryRun = true;
        case "--dry-run=true", "--dry-run=false" -> dryRun = arg.endsWith("true");
        case "--security-block" -> securityBlock = true;
        case "--repo",
            "--pr",
            "--base",
            "--head",
            "--depth",
            "--token",
            "--github-api",
            "--run-root" -> {
          if (i + 1 >= args.length) {
            throw new CraConfigException("参数缺值: " + arg);
          }
          named.put(arg, args[++i]);
        }
        default -> throw new CraConfigException("未知参数: " + arg);
      }
    }
    String repo = required(named, "--repo", env.get("GITHUB_REPOSITORY"));
    int pr = parsePr(required(named, "--pr", env.get("PR_NUMBER")));
    String base = required(named, "--base", env.get("BASE_SHA"));
    String head = required(named, "--head", env.get("HEAD_SHA"));
    String depth = named.getOrDefault("--depth", "standard");
    if (!"quick".equals(depth) && !"standard".equals(depth) && !"deep".equals(depth)) {
      throw new CraConfigException("depth 非法: " + depth + "（quick|standard|deep）");
    }
    String token = required(named, "--token", env.get("GITHUB_TOKEN"));
    String llmApiKey = env.get("CRA_LLM_API_KEY");
    if (llmApiKey == null || llmApiKey.isBlank()) {
      throw new CraConfigException("CRA_LLM_API_KEY 未设置（密钥只从环境变量读，05§6）");
    }
    String llmBaseUrl = env.get("CRA_LLM_BASE_URL");
    if (llmBaseUrl == null || llmBaseUrl.isBlank()) {
      throw new CraConfigException("CRA_LLM_BASE_URL 未设置");
    }
    String llmModel = env.get("CRA_LLM_MODEL");
    if (llmModel == null || llmModel.isBlank()) {
      throw new CraConfigException("CRA_LLM_MODEL 未设置");
    }
    String[] ownerRepo = repo.split("/");
    if (ownerRepo.length != 2) {
      throw new CraConfigException("--repo 形如 owner/name: " + repo);
    }
    return new CraConfig(
        ownerRepo[0],
        ownerRepo[1],
        pr,
        base,
        head,
        depth,
        dryRun,
        securityBlock,
        token,
        named.getOrDefault("--github-api", "https://api.github.com"),
        llmApiKey,
        llmBaseUrl,
        llmModel,
        named.getOrDefault("--run-root", ".cra/runs"));
  }

  private static String required(Map<String, String> named, String key, String envValue) {
    String value = named.get(key);
    if (value == null || value.isBlank()) {
      value = envValue;
    }
    if (value == null || value.isBlank()) {
      throw new CraConfigException("缺参数: " + key + "（或对应环境变量）");
    }
    return value;
  }

  private static int parsePr(String value) {
    try {
      int pr = Integer.parseInt(value);
      if (pr <= 0) {
        throw new NumberFormatException();
      }
      return pr;
    } catch (NumberFormatException e) {
      throw new CraConfigException("--pr 必须为正整数: " + value);
    }
  }
}
