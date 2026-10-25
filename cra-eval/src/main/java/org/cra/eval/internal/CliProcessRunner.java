package org.cra.eval.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.cra.common.exception.CraIntegrationException;

/** 被测 CLI 子进程启动器（05§3 命令行黑盒驱动）。eval 与被测对象唯一交互 = 进程参数 + 环境变量 + HTTP 协议面。 */
public final class CliProcessRunner {

  /** CLI 调用抽象（测试注入：不经真实 java -jar 也可驱动 stub 协议面）。 */
  public interface CliInvoker {

    /**
     * 执行一次被测 CLI。
     *
     * @param args 进程参数（不含可执行文件本身）
     * @param env 进程环境变量（叠加在默认环境之上）
     * @param workDir 工作目录
     * @param stdoutLog stdout 落盘路径
     * @param stderrLog stderr 落盘路径
     * @return 退出码（超时抛异常）
     */
    int invoke(
        List<String> args, Map<String, String> env, Path workDir, Path stdoutLog, Path stderrLog)
        throws Exception;
  }

  /** 默认 invoker：java -jar 子进程。 */
  public static final CliInvoker JAVA_JAR =
      (args, env, workDir, stdoutLog, stderrLog) -> {
        List<String> command = new ArrayList<>();
        command.add("java");
        command.add("-jar");
        command.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workDir.toFile());
        pb.environment().putAll(env);
        pb.redirectOutput(stdoutLog.toFile());
        pb.redirectError(stderrLog.toFile());
        Process process = pb.start();
        if (!process.waitFor(timeoutSeconds(), TimeUnit.SECONDS)) {
          process.destroyForcibly();
          throw new CraIntegrationException(
              "被测 CLI 超时（" + timeoutSeconds() + "s）被强杀: " + String.join(" ", args));
        }
        return process.exitValue();
      };

  private CliProcessRunner() {}

  /**
   * 单样本 run 超时秒数（默认 900；系统属性 cra.eval.cliTimeoutSec 可覆盖——测试用）。
   *
   * @return 超时秒数
   */
  static long timeoutSeconds() {
    String prop = System.getProperty("cra.eval.cliTimeoutSec");
    if (prop != null && prop.matches("\\d{1,6}")) {
      return Long.parseLong(prop);
    }
    return 900;
  }

  /**
   * 组装被测 CLI 进程参数（cra review 子命令，05§3 契约）。
   *
   * @param cliJar shaded jar 路径
   * @param owner 合成属主
   * @param name 合成仓库名
   * @param prNumber 合成 PR 号
   * @param baseSha base
   * @param headSha head
   * @param depth 深度档位
   * @param stubBaseUrl stub 服务器地址（--github-api）
   * @param workspace 本地仓库根（--workspace：CrossChecker 变更史 jgit 数据面 = 解包快照根；null 用被测进程 cwd）
   * @param crosschecker 是否启用 CrossChecker（--crosschecker，M4f-lite；05§5 agents.crosschecker）
   * @param runRoot 该次 run 的 trace 根目录
   * @return 进程参数
   */
  public static List<String> reviewArgs(
      Path cliJar,
      String owner,
      String name,
      int prNumber,
      String baseSha,
      String headSha,
      String depth,
      String stubBaseUrl,
      Path runRoot,
      Path workspace,
      boolean crosschecker) {
    List<String> args =
        new java.util.ArrayList<>(
            List.of(
                cliJar.toString(),
                "review",
                "--repo",
                owner + "/" + name,
                "--pr",
                String.valueOf(prNumber),
                "--base",
                baseSha,
                "--head",
                headSha,
                "--depth",
                depth,
                "--token",
                "cra-eval-stub-token",
                "--github-api",
                stubBaseUrl,
                "--run-root",
                runRoot.toString()));
    if (workspace != null) {
      args.add("--workspace");
      args.add(workspace.toString());
    }
    if (crosschecker) {
      args.add("--crosschecker");
    }
    return List.copyOf(args);
  }

  /**
   * 组装被测 CLI 进程环境：LLM 配置透传（有真实配置即真实 LLM run）；缺省时给占位值让 run 在 LLM 步骤如实失败。
   *
   * @param env 进程环境变量视图
   * @return 叠加后的环境
   */
  public static Map<String, String> llmEnv(Map<String, String> env) {
    Map<String, String> out = new java.util.HashMap<>(env);
    out.putIfAbsent("CRA_LLM_API_KEY", "cra-eval-no-key");
    out.putIfAbsent("CRA_LLM_BASE_URL", "http://127.0.0.1:9");
    out.putIfAbsent("CRA_LLM_MODEL", "cra-eval-none");
    return out;
  }

  /**
   * 是否配置了真实 LLM（CRA_LLM_API_KEY 与 BASE_URL 均存在且非空）。
   *
   * @param env 进程环境变量视图
   * @return true = 真实 LLM run
   */
  public static boolean llmConfigured(Map<String, String> env) {
    String key = env.get("CRA_LLM_API_KEY");
    String url = env.get("CRA_LLM_BASE_URL");
    return key != null && !key.isBlank() && url != null && !url.isBlank();
  }

  /**
   * 读取文本文件（容错：不存在返回 ""）。
   *
   * @param file 路径
   * @return 内容
   */
  public static String readQuietly(Path file) {
    try {
      return Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      return "";
    }
  }
}
