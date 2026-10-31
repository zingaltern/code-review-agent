package org.cra.runner.cli;

import java.io.BufferedReader;
import java.io.BufferedWriter;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.cra.common.exception.CraConfigException;
import org.cra.common.exception.CraIntegrationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * pre-PR 实跑执行器（07 T2.10：默认实跑 {@code mvn -B -ntp verify}，命令可配）。 核心纪律 = <b>展示真实输出而非声称完成</b>（docs/00
 * §4 自检即现成命令）：输出逐行实时回显（真输出先于结论）， 全量落盘留证，报告提取失败摘录置顶；退出码原样上报，不翻译、不掩盖。
 */
final class BuildVerifyRunner {

  /** 失败摘录最大行数（超出截断，完整输出以日志文件为准）。 */
  static final int EXCERPT_CAP = 40;

  /** 非 Maven 输出的兜底摘录行数（无 [ERROR] 行时取末尾若干非空行）。 */
  static final int TAIL_LINES = 20;

  /** 内存留存输出的上限（完整输出仍逐行落盘；超出后报告声明截断，避免失控构建耗尽内存）。 */
  static final int MAX_CAPTURED_CHARS = 2_000_000;

  /** 实跑默认超时秒数：失控构建不得永久挂起本地 pre-PR（系统属性可覆盖）。 */
  static final long DEFAULT_TIMEOUT_SECONDS = 1_800;

  /** 超时强杀后等待父进程退出与读线程收尾的上限。 */
  private static final long KILL_WAIT_SECONDS = 5;

  private static final long READER_JOIN_MS = 5_000;

  private static final long READER_JOIN_AFTER_KILL_MS = 2_000;

  private static final Logger LOG = LoggerFactory.getLogger(BuildVerifyRunner.class);

  private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

  /** 子进程敏感环境变量剥离：被测仓库的测试/插件不得读取父进程的密钥（05§6）。 */
  private static final Pattern SENSITIVE_ENV =
      Pattern.compile("(?i).*(token|secret|api[_-]?key|apikey|password|passwd|credential).*");

  private static final Pattern TIMEOUT_PROP = Pattern.compile("\\d{1,6}");

  private final Path runRoot;
  private final int maxCapturedChars;

  /**
   * @param runRoot 实跑日志落盘根（与 trace 同根，.cra/runs）
   */
  BuildVerifyRunner(Path runRoot) {
    this(runRoot, MAX_CAPTURED_CHARS);
  }

  /** 测试形态：自定义内存留存上限（日志文件始终完整）。 */
  BuildVerifyRunner(Path runRoot, int maxCapturedChars) {
    if (maxCapturedChars <= 0) {
      throw new IllegalArgumentException("maxCapturedChars must be positive");
    }
    this.runRoot = runRoot;
    this.maxCapturedChars = maxCapturedChars;
  }

  /**
   * 单次实跑结果。
   *
   * @param exitCode 进程退出码（原样，非零 = 构建/测试失败）
   * @param output 合并输出全文（stdout+stderr，保留行序）
   * @param logFile 全量输出日志文件
   * @param durationMs 耗时
   * @param logComplete 日志是否完整（超时/读失败/落盘失败时为 false）
   */
  record ExecResult(
      int exitCode, String output, Path logFile, long durationMs, boolean logComplete) {

    /** 兼容构造（测试与调用方默认视为完整）。 */
    ExecResult(int exitCode, String output, Path logFile, long durationMs) {
      this(exitCode, output, logFile, durationMs, true);
    }

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
    Process process = startProcess(workDir, cmd);
    OutputCapture capture = new OutputCapture(maxCapturedChars);
    AtomicBoolean logFailed = new AtomicBoolean();
    AtomicBoolean readFailed = new AtomicBoolean();
    Path logFile = runRoot.resolve("build-" + LocalDateTime.now().format(TS) + ".log");
    BufferedWriter logWriter = openLog(logFile, command);
    if (logWriter == null) {
      logFile = null;
    }
    Thread reader = startReader(process, capture, logWriter, logFailed, readFailed);
    long timeoutSeconds = timeoutSeconds();
    try {
      if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
        destroyTree(process);
        process.waitFor(KILL_WAIT_SECONDS, TimeUnit.SECONDS);
        reader.join(READER_JOIN_AFTER_KILL_MS);
        throw new CraIntegrationException(
            "实跑超时（" + timeoutSeconds + "s）被强杀: " + command + "（系统属性 cra.verify.timeoutSec 可覆盖）");
      }
      reader.join(READER_JOIN_MS);
      if (reader.isAlive()) {
        readFailed.set(true);
      }
      int exitCode = process.exitValue();
      if (logWriter != null && !reader.isAlive()) {
        writeLogLine(logWriter, "exit code: " + exitCode, logFailed);
        flushLog(logWriter, logFailed);
      }
      return buildResult(exitCode, capture, logFile, logFailed, readFailed, start);
    } catch (InterruptedException e) {
      destroyTree(process);
      Thread.currentThread().interrupt();
      throw new CraIntegrationException("实跑被中断", e);
    } finally {
      closeLog(logWriter);
    }
  }

  private static Process startProcess(Path workDir, String[] cmd) {
    try {
      ProcessBuilder builder =
          new ProcessBuilder(cmd).directory(workDir.toFile()).redirectErrorStream(true);
      sanitizeEnvironment(builder.environment());
      return builder.start();
    } catch (IOException e) {
      throw new CraIntegrationException("实跑命令启动失败（检查 " + cmd[0] + " 是否可用）: " + e.getMessage(), e);
    }
  }

  private Thread startReader(
      Process process,
      OutputCapture capture,
      BufferedWriter logWriter,
      AtomicBoolean logFailed,
      AtomicBoolean readFailed) {
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
                  // 完整落盘：内存可以截断，日志文件不能（"完整输出见日志文件"必须为真）
                  if (logWriter != null) {
                    writeLogLine(logWriter, line, logFailed);
                  }
                  capture.append(line);
                }
              } catch (IOException e) {
                readFailed.set(true);
                LOG.warn("实跑输出读取失败（日志可能不完整）: {}", e.getMessage());
              }
            },
            "cra-pre-pr-reader");
    reader.setDaemon(true);
    reader.start();
    return reader;
  }

  private ExecResult buildResult(
      int exitCode,
      OutputCapture capture,
      Path logFile,
      AtomicBoolean logFailed,
      AtomicBoolean readFailed,
      long start) {
    Path effectiveLog = logFile;
    if (logFailed.get() && effectiveLog != null) {
      // 边读边写失败：不能再声称完整输出在日志文件里；删除部分日志避免误导
      try {
        Files.deleteIfExists(effectiveLog);
      } catch (IOException e) {
        LOG.warn("实跑日志文件清理失败（保留部分日志）: {}", e.getMessage());
      }
      effectiveLog = null;
    }
    String output = capture.render(readFailed.get(), effectiveLog != null);
    boolean logComplete = effectiveLog != null && !readFailed.get();
    return new ExecResult(
        exitCode, output, effectiveLog, System.currentTimeMillis() - start, logComplete);
  }

  /** 打开完整日志文件并写入命令头；失败时返回 null（报告声明日志不可用）。 */
  private static BufferedWriter openLog(Path logFile, String command) {
    BufferedWriter writer = null;
    try {
      Files.createDirectories(logFile.getParent());
      writer = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8);
      writer.write("command: " + command);
      writer.newLine();
      writer.flush();
      return writer;
    } catch (IOException e) {
      closeQuietly(writer);
      LOG.warn("实跑日志文件创建失败，报告不含日志路径: {}", e.getMessage());
      return null;
    }
  }

  private static void writeLogLine(BufferedWriter writer, String line, AtomicBoolean failed) {
    try {
      synchronized (writer) {
        writer.write(line);
        writer.newLine();
      }
    } catch (IOException e) {
      failed.set(true);
    }
  }

  private static void flushLog(BufferedWriter writer, AtomicBoolean failed) {
    try {
      synchronized (writer) {
        writer.flush();
      }
    } catch (IOException e) {
      failed.set(true);
    }
  }

  private static void closeLog(BufferedWriter writer) {
    if (writer == null) {
      return;
    }
    try {
      synchronized (writer) {
        writer.close();
      }
    } catch (IOException e) {
      LOG.warn("实跑日志文件关闭失败: {}", e.getMessage());
    }
  }

  private static void closeQuietly(BufferedWriter writer) {
    if (writer == null) {
      return;
    }
    try {
      writer.close();
    } catch (IOException e) {
      LOG.warn("实跑日志文件初始化失败后清理失败: {}", e.getMessage());
    }
  }

  /** 超时/中断时清理整棵进程树（Maven Surefire 会 fork 测试 JVM，只杀父进程会留下孤儿）。 */
  private static void destroyTree(Process process) {
    process.toHandle().descendants().forEach(ProcessHandle::destroyForcibly);
    process.destroyForcibly();
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
   * 失败摘录提取（Maven 语义优先：[ERROR] 行按序；非 Maven 命令无 [ERROR] 时取末尾 {@link #TAIL_LINES} 行）。
   *
   * @param output 全量输出
   * @return 摘录行（Maven 形态 ≤{@link #EXCERPT_CAP} 行，兜底形态 ≤{@link #TAIL_LINES} 行）
   */
  static List<String> failureExcerpts(String output) {
    List<String> errors = errorLines(output);
    if (errors.isEmpty()) {
      List<String> lines = nonBlankLines(output);
      int from = Math.max(0, lines.size() - TAIL_LINES);
      return new ArrayList<>(lines.subList(from, lines.size()));
    }
    return errors.size() <= EXCERPT_CAP ? errors : new ArrayList<>(errors.subList(0, EXCERPT_CAP));
  }

  /** 摘录是否被截断（报告注明"完整输出见日志"）。 */
  static boolean excerptTruncated(String output) {
    int errors = errorLines(output).size();
    return errors > 0 ? errors > EXCERPT_CAP : nonBlankLines(output).size() > TAIL_LINES;
  }

  private static List<String> errorLines(String output) {
    List<String> errors = new ArrayList<>();
    for (String line : output.split("\n", -1)) {
      if (line.strip().startsWith("[ERROR]")) {
        errors.add(line);
      }
    }
    return errors;
  }

  private static List<String> nonBlankLines(String output) {
    List<String> lines = new ArrayList<>();
    for (String line : output.split("\n", -1)) {
      if (!line.isBlank()) {
        lines.add(line);
      }
    }
    return lines;
  }

  /** 读线程与主线程共享的输出留存（截断标记与文本在同一把锁内）。 */
  private static final class OutputCapture {

    private final int maxChars;
    private final StringBuilder text = new StringBuilder();
    private boolean sizeTruncated;

    OutputCapture(int maxChars) {
      this.maxChars = maxChars;
    }

    synchronized void append(String line) {
      if (text.length() < maxChars) {
        text.append(line).append('\n');
      } else {
        sizeTruncated = true;
      }
    }

    synchronized String render(boolean readInterrupted, boolean logAvailable) {
      StringBuilder out = new StringBuilder(text);
      if (sizeTruncated) {
        out.append("\n…（输出超过 ")
            .append(maxChars)
            .append(" 字符，内存留存已截断")
            .append(logAvailable ? "；完整输出仍见日志文件" : "；日志文件不可用")
            .append("）\n");
      }
      if (readInterrupted) {
        out.append("\n…（输出读取未完成，日志可能不完整）\n");
      }
      return out.toString();
    }
  }
}
