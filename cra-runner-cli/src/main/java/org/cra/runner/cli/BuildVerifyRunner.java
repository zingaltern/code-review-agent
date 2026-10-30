package org.cra.runner.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.cra.common.exception.CraConfigException;
import org.cra.common.exception.CraIntegrationException;

/**
 * pre-PR 实跑执行器（07 T2.10：默认实跑 {@code mvn -B -ntp verify}，命令可配）。 核心纪律 = <b>展示真实输出而非声称完成</b>（docs/00
 * §4 自检即现成命令）：输出逐行实时回显（真输出先于结论）， 全量落盘留证，报告提取失败摘录置顶；退出码原样上报，不翻译、不掩盖。
 */
final class BuildVerifyRunner {

  /** 失败摘录最大行数（超出截断，完整输出以日志文件为准）。 */
  static final int EXCERPT_CAP = 40;

  private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

  /** 内存留存输出的上限（完整输出仍逐行落盘；超出后报告声明截断，避免失控构建耗尽内存）。 */
  static final int MAX_CAPTURED_CHARS = 2_000_000;

  /** 实跑默认超时秒数：失控构建不得永久挂起本地 pre-PR（系统属性可覆盖）。 */
  static final long DEFAULT_TIMEOUT_SECONDS = 1_800;

  /** 子进程环境白名单化：被测仓库的测试/插件不得读取父进程的密钥（05§6）。 */
  private static final Pattern SENSITIVE_ENV =
      Pattern.compile("(?i).*(token|secret|api[_-]?key|apikey|password|passwd|credential).*");

  private static final Pattern TIMEOUT_PROP = Pattern.compile("\\d{1,6}");

  private final Path runRoot;

  /**
   * @param runRoot 实跑日志落盘根（与 trace 同根，.cra/runs）
   */
  BuildVerifyRunner(Path runRoot) {
    this.runRoot = runRoot;
  }

  /**
   * 单次实跑结果。
   *
   * @param exitCode 进程退出码（原样，非零 = 构建/测试失败）
   * @param output 合并输出全文（stdout+stderr，保留行序）
   * @param logFile 全量输出日志文件
   * @param durationMs 耗时
   */
  record ExecResult(int exitCode, String output, Path logFile, long durationMs) {
    public boolean failed() {
      return exitCode != 0;
    }
  }

  /**
   * 在工作区实跑命令（空白分词；shell 语义如引号/管道不支持——实跑命令面向构建工具原样参数）。
   *
   * @param workDir 工作目录
   * @param command 命令行
   * @return 实跑结果
   */
  ExecResult run(Path workDir, String command) {
    String[] cmd = command.strip().split("\\s+");
    if (cmd.length == 0 || cmd[0].isEmpty()) {
      throw new CraConfigException("--exec 实跑命令为空");
    }
    long start = System.currentTimeMillis();
    Process process;
    try {
      ProcessBuilder builder =
          new ProcessBuilder(cmd).directory(workDir.toFile()).redirectErrorStream(true);
      sanitizeEnvironment(builder.environment());
      process = builder.start();
    } catch (IOException e) {
      throw new CraIntegrationException("实跑命令启动失败（检查 " + cmd[0] + " 是否可用）: " + e.getMessage(), e);
    }
    StringBuilder captured = new StringBuilder();
    boolean[] truncated = {false};
    Thread reader =
        new Thread(
            () -> {
              try (BufferedReader stdout =
                  new BufferedReader(
                      new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = stdout.readLine()) != null) {
                  // 真实输出逐行实时回显——pre-PR 的第一公民是输出本身，不是结论
                  System.out.println(line);
                  synchronized (captured) {
                    if (captured.length() < MAX_CAPTURED_CHARS) {
                      captured.append(line).append('\n');
                    } else {
                      truncated[0] = true;
                    }
                  }
                }
              } catch (IOException e) {
                synchronized (captured) {
                  truncated[0] = true;
                }
              }
            },
            "cra-pre-pr-reader");
    reader.setDaemon(true);
    reader.start();
    long timeoutSeconds = timeoutSeconds();
    try {
      if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        process.waitFor(5, TimeUnit.SECONDS);
        reader.join(2_000);
        throw new CraIntegrationException(
            "实跑超时（" + timeoutSeconds + "s）被强杀: " + command + "（系统属性 cra.verify.timeoutSec 可覆盖）");
      }
      reader.join(5_000);
      int exitCode = process.exitValue();
      String output;
      synchronized (captured) {
        output = captured.toString();
        if (truncated[0]) {
          output += "\n…（输出超过 " + MAX_CAPTURED_CHARS + " 字符，内存留存已截断；完整输出仍见日志文件）\n";
        }
      }
      Path logFile = persistQuietly(command, exitCode, output);
      return new ExecResult(exitCode, output, logFile, System.currentTimeMillis() - start);
    } catch (InterruptedException e) {
      process.destroyForcibly();
      Thread.currentThread().interrupt();
      throw new CraIntegrationException("实跑被中断", e);
    }
  }

  private static void sanitizeEnvironment(Map<String, String> env) {
    env.keySet().removeIf(key -> SENSITIVE_ENV.matcher(key).matches());
  }

  static long timeoutSeconds() {
    String prop = System.getProperty("cra.verify.timeoutSec");
    if (prop != null && TIMEOUT_PROP.matcher(prop).matches()) {
      return Long.parseLong(prop);
    }
    return DEFAULT_TIMEOUT_SECONDS;
  }

  /**
   * 失败摘录提取（Maven 语义优先：[ERROR] 行按序；非 Maven 命令无 [ERROR] 时取末尾 20 行）。
   *
   * @param output 全量输出
   * @return 摘录行（≤{@link #EXCERPT_CAP} 行）
   */
  static List<String> failureExcerpts(String output) {
    String[] lines = output.split("\n", -1);
    List<String> errors = new ArrayList<>();
    for (String line : lines) {
      if (line.strip().startsWith("[ERROR]")) {
        errors.add(line);
      }
    }
    if (errors.isEmpty()) {
      int from = Math.max(0, lines.length - 20);
      for (int i = from; i < lines.length; i++) {
        if (!lines[i].isBlank()) {
          errors.add(lines[i]);
        }
      }
      return errors;
    }
    return errors.size() <= EXCERPT_CAP ? errors : new ArrayList<>(errors.subList(0, EXCERPT_CAP));
  }

  /** 摘录是否被截断（报告注明"完整输出见日志"）。 */
  static boolean excerptTruncated(String output) {
    long count = output.split("\n", -1).length;
    int errors = 0;
    for (String line : output.split("\n", -1)) {
      if (line.strip().startsWith("[ERROR]")) {
        errors++;
      }
    }
    return errors > EXCERPT_CAP || (errors == 0 && count > 20);
  }

  private Path persistQuietly(String command, int exitCode, String output) {
    try {
      Files.createDirectories(runRoot);
      Path logFile = runRoot.resolve("build-" + LocalDateTime.now().format(TS) + ".log");
      String header =
          "command: " + command + "\nexit code: " + exitCode + "\n" + "-".repeat(60) + "\n";
      Files.writeString(logFile, header + output, StandardCharsets.UTF_8);
      return logFile;
    } catch (IOException e) {
      // 日志落盘失败不吞掉实跑结论：退出码与摘录仍在，报告声明日志缺失（LocalPrePrReport 处理 null）
      System.out.println("⚠️ 实跑日志落盘失败，报告不含日志路径: " + e.getMessage());
      return null;
    }
  }
}
