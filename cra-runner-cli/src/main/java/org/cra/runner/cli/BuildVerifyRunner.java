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
      process =
          new ProcessBuilder(cmd).directory(workDir.toFile()).redirectErrorStream(true).start();
    } catch (IOException e) {
      throw new CraIntegrationException("实跑命令启动失败（检查 " + cmd[0] + " 是否可用）: " + e.getMessage(), e);
    }
    StringBuilder captured = new StringBuilder();
    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        // 真实输出逐行实时回显——pre-PR 的第一公民是输出本身，不是结论
        System.out.println(line);
        captured.append(line).append('\n');
      }
      int exitCode = process.waitFor();
      Path logFile = persist(command, exitCode, captured.toString());
      return new ExecResult(
          exitCode, captured.toString(), logFile, System.currentTimeMillis() - start);
    } catch (InterruptedException e) {
      process.destroyForcibly();
      Thread.currentThread().interrupt();
      throw new CraIntegrationException("实跑被中断", e);
    } catch (IOException e) {
      process.destroyForcibly();
      throw new CraIntegrationException("实跑输出读取失败: " + e.getMessage(), e);
    }
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

  private Path persist(String command, int exitCode, String output) {
    try {
      Files.createDirectories(runRoot);
      Path logFile = runRoot.resolve("build-" + LocalDateTime.now().format(TS) + ".log");
      String header =
          "command: " + command + "\nexit code: " + exitCode + "\n" + "-".repeat(60) + "\n";
      Files.writeString(logFile, header + output, StandardCharsets.UTF_8);
      return logFile;
    } catch (IOException e) {
      // 日志落盘失败不吞掉实跑结论：退出码与摘录仍在，日志缺失如实声明
      throw new CraIntegrationException("实跑日志落盘失败: " + e.getMessage(), e);
    }
  }
}
