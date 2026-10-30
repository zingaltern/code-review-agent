package org.cra.archtest;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * 09§1 规则自检测试（cra-archtest 持有，10§5）：源码文本扫描，管"写法"—— System.out / 吞异常 / parallelStream / import 通配符 /
 * SPI Javadoc。
 *
 * <p>扫描范围 = 各模块 src/main/java（测试源码不扫）；扫描器经相邻目录解析各模块源码根， 带<strong>最小文件数断言</strong>（目标模块 0
 * 个源文件即红，防"扫描 0 文件、断言空集、 绿灯空转"，10§5 v1 基线修正）；仅在本仓库检出内有效——检出外（如孤立 IDE 运行）按 JUnit assumption
 * 跳过（09§1"仅根构建有效"）。
 */
class SourceScanRulesTest {

  /** 扫描目标模块：M0 有主代码（或将获得主代码）的全部模块；cra-archtest 自身测试代码不扫。 */
  private static final List<String> SCAN_MODULES =
      List.of(
          "cra-common",
          "cra-llm",
          "cra-git",
          "cra-git-github",
          "cra-git-gitlab",
          "cra-context",
          "cra-agents",
          "cra-store",
          "cra-core",
          "cra-runner-cli",
          "cra-eval",
          "cra-server");

  private static final Path REPO_ROOT =
      Path.of(System.getProperty("user.dir")).toAbsolutePath().getParent();

  /** SPI Javadoc 强制包（09§8）：公共 SPI 接口与数据模型必写 Javadoc。 */
  private static final List<String> JAVADOC_PACKAGES =
      List.of(
          "org.cra.common",
          "org.cra.llm.api",
          "org.cra.git.api",
          "org.cra.git.github.api",
          "org.cra.git.gitlab.api",
          "org.cra.context.api",
          "org.cra.agents.api",
          "org.cra.store.api",
          "org.cra.core.api");

  private static final Pattern PUBLIC_TYPE =
      Pattern.compile(
          "^\\s*public\\s+(?:final\\s+|abstract\\s+|sealed\\s+|non-sealed\\s+)*"
              + "(class|interface|record|enum)\\s+(\\w+)");
  private static final Pattern METHOD_DECL =
      Pattern.compile(
          "^\\s{2}(?:public\\s+|default\\s+|protected\\s+)?[\\w<>,\\[\\].?\\s]+\\s+\\w+\\s*\\(");

  /** 构造器委托续行（{@code this(} / {@code super(}）——不是方法声明，Javadoc 规则不适用。 */
  private static final Pattern CONSTRUCTOR_DELEGATION =
      Pattern.compile("^\\s+(?:this|super)\\s*\\(\\s*$");

  private static final Pattern WILDCARD_IMPORT =
      Pattern.compile("^\\s*import\\s+(?:static\\s+)?[\\w.]+\\.\\*\\s*;");
  private static final Pattern EMPTY_CATCH = Pattern.compile("catch\\s*\\([^)]*\\)\\s*\\{\\s*\\}");

  private static final Map<String, List<Path>> MODULE_SOURCES = new LinkedHashMap<>();

  @BeforeAll
  static void collectSources() throws IOException {
    Assumptions.assumeTrue(
        REPO_ROOT != null
            && Files.isDirectory(REPO_ROOT.resolve("docs"))
            && Files.isRegularFile(REPO_ROOT.resolve("docs/07-roadmap.md")),
        "不在仓库检出内运行，源码扫描跳过（09§1：仅根构建有效）");
    for (String module : SCAN_MODULES) {
      Path root = REPO_ROOT.resolve(module).resolve("src/main/java");
      List<Path> files = new ArrayList<>();
      if (Files.isDirectory(root)) {
        try (Stream<Path> walk = Files.walk(root)) {
          walk.filter(p -> p.toString().endsWith(".java")).sorted().forEach(files::add);
        }
      }
      assertThat(files).as("模块 %s 主源码为空——扫描将在空集上空转（10§5）", module).isNotEmpty();
      MODULE_SOURCES.put(module, files);
    }
  }

  @TestFactory
  Stream<DynamicTest> noSystemOutOrPrintStackTraceOrParallelStream() {
    return forEachFile(
        "无 System.out/printStackTrace/parallelStream",
        (module, path, lines) -> {
          List<String> code = codeLines(lines);
          // ADR-0007：CLI 展示层（stdout 即用户界面）豁免 System.out/err 禁令——T2.10 实跑真实输出与报告契约；
          // System.err 同样豁免仅因同属 CLI 展示通道；printStackTrace/parallelStream 全模块维持
          boolean presentationModule = "cra-runner-cli".equals(module);
          for (int i = 0; i < code.size(); i++) {
            String line = code.get(i);
            if (!presentationModule) {
              assertClean(
                  module, path, lines, i, !line.contains("System.out."), "禁 System.out（09§5）");
              assertClean(
                  module, path, lines, i, !line.contains("System.err."), "禁 System.err（09§5）");
            }
            assertClean(
                module,
                path,
                lines,
                i,
                !line.contains("printStackTrace("),
                "禁 printStackTrace，一律 SLF4J（09§5）");
            assertClean(
                module,
                path,
                lines,
                i,
                !line.contains("parallelStream("),
                "业务路径禁 parallelStream（09§6）");
          }
        });
  }

  @TestFactory
  Stream<DynamicTest> noSwallowCatch() {
    return forEachFile(
        "catch 必须三选一，禁仅 log 吞掉",
        (module, path, lines) -> {
          String code = String.join("\n", codeLines(lines));
          assertThat(code).as("%s：空 catch 块（09§4）", path).doesNotContainPattern(EMPTY_CATCH);
          List<int[]> blocks = catchBlocks(codeLines(lines));
          for (int[] block : blocks) {
            if (isLogOnlyBody(codeLines(lines), block[0], block[1])) {
              throw new AssertionError(
                  String.format(
                      "%s:%d catch 后仅 log 吞掉——必须转 CraException 上抛 / 降级并写状态与 trace / 有上限重试（09§4）",
                      path, block[0] + 1));
            }
          }
        });
  }

  @TestFactory
  Stream<DynamicTest> noWildcardImports() {
    return forEachFile(
        "禁 import 通配符（09§2）",
        (module, path, lines) -> {
          for (int i = 0; i < lines.size(); i++) {
            assertClean(
                module,
                path,
                lines,
                i,
                !WILDCARD_IMPORT.matcher(lines.get(i)).matches(),
                "禁 import 通配符（09§2）");
          }
        });
  }

  @TestFactory
  Stream<DynamicTest> spiTypesAndMethodsHaveJavadoc() {
    return forEachFile(
        "SPI 接口与数据模型必写 Javadoc（09§8）",
        (module, path, lines) -> {
          if (filePackage(lines).filter(JAVADOC_PACKAGES::contains).isEmpty()) {
            return;
          }
          for (int i = 0; i < lines.size(); i++) {
            var type = PUBLIC_TYPE.matcher(lines.get(i));
            if (type.find()) {
              assertClean(
                  module,
                  path,
                  lines,
                  i,
                  hasJavadocBefore(lines, i),
                  "公共类型 " + type.group(2) + " 缺 Javadoc（09§8）");
              if ("record".equals(type.group(1))) {
                checkRecordParams(module, path, lines, i);
              }
            }
            if (METHOD_DECL.matcher(lines.get(i)).matches()
                && !isInvocationLine(lines.get(i))
                && !CONSTRUCTOR_DELEGATION.matcher(lines.get(i)).matches()) {
              assertClean(
                  module,
                  path,
                  lines,
                  i,
                  hasJavadocBefore(lines, i),
                  "SPI 包 public 方法缺 Javadoc（09§8）");
            }
          }
        });
  }

  private void checkRecordParams(String module, Path path, List<String> lines, int declLine) {
    String header = recordHeader(lines, declLine);
    List<String> components = splitTopLevel(header, ',');
    String javadoc = javadocTextBefore(lines, declLine);
    for (String component : components) {
      String name = component.trim().isEmpty() ? "" : lastIdentifier(component.trim());
      assertThat(javadoc)
          .as("%s:%d record 组件 %s 缺 @param（09§8）", path, declLine + 1, name)
          .contains("@param " + name);
    }
  }

  // ---------- 扫描骨架 ----------

  private interface FileCheck {
    void check(String module, Path path, List<String> lines) throws IOException;
  }

  private Stream<DynamicTest> forEachFile(String prefix, FileCheck check) {
    return MODULE_SOURCES.entrySet().stream()
        .flatMap(e -> e.getValue().stream().map(p -> Map.entry(e.getKey(), p)))
        .map(
            entry ->
                DynamicTest.dynamicTest(
                    prefix + ": " + entry.getValue(),
                    () -> {
                      List<String> lines =
                          Files.readAllLines(entry.getValue(), StandardCharsets.UTF_8);
                      check.check(entry.getKey(), entry.getValue(), lines);
                    }));
  }

  private static void assertClean(
      String module, Path path, List<String> lines, int line, boolean clean, String message) {
    if (!clean) {
      throw new AssertionError(String.format("%s [%s:%d] %s", module, path, line + 1, message));
    }
  }

  /** 去注释与字符串字面量后的代码行（内容类检查用；Javadoc 检查用原始行）。 */
  private static List<String> codeLines(List<String> raw) {
    List<String> out = new ArrayList<>(raw.size());
    boolean inBlock = false;
    boolean inTextBlock = false;
    for (String line : raw) {
      String t = line;
      if (inBlock) {
        int end = t.indexOf("*/");
        if (end < 0) {
          out.add("");
          continue;
        }
        t = t.substring(end + 2);
        inBlock = false;
      }
      if (t.contains("\"\"\"")) {
        inTextBlock = !inTextBlock;
        t = t.replaceAll("\"\"\".*", "");
      }
      if (inTextBlock) {
        out.add("");
        continue;
      }
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < t.length(); i++) {
        String rest = t.substring(i);
        if (rest.startsWith("//")) {
          break;
        }
        if (rest.startsWith("/*")) {
          int end = t.indexOf("*/", i + 2);
          if (end < 0) {
            inBlock = true;
            break;
          }
          i = end + 1;
          continue;
        }
        if (t.charAt(i) == '"') {
          // 简单字符串（text block 已在上面截断处理）；跳过转义
          int j = i + 1;
          while (j < t.length() && t.charAt(j) != '"') {
            j += t.charAt(j) == '\\' ? 2 : 1;
          }
          i = Math.min(j, t.length() - 1);
          continue;
        }
        if (t.charAt(i) == '\'') {
          int j = i + 1;
          while (j < t.length() && t.charAt(j) != '\'') {
            j += t.charAt(j) == '\\' ? 2 : 1;
          }
          i = Math.min(j, t.length() - 1);
          continue;
        }
        sb.append(t.charAt(i));
      }
      out.add(sb.toString());
    }
    return out;
  }

  private static List<int[]> catchBlocks(List<String> code) {
    List<int[]> blocks = new ArrayList<>();
    for (int i = 0; i < code.size(); i++) {
      int open = code.get(i).indexOf('{');
      if (open >= 0 && code.get(i).contains("catch")) {
        int depth = 0;
        for (int j = i; j < code.size(); j++) {
          for (char c : code.get(j).toCharArray()) {
            if (c == '{') {
              depth++;
            } else if (c == '}') {
              depth--;
            }
          }
          if (depth <= 0 && j > i) {
            blocks.add(new int[] {i, j});
            break;
          }
          if (j == code.size() - 1) {
            blocks.add(new int[] {i, j});
          }
        }
      }
    }
    return blocks;
  }

  private static boolean isLogOnlyBody(List<String> code, int catchLine, int endLine) {
    // catch (…) { 仅一条 log 语句 } 的行内形态
    String inline = code.get(catchLine);
    int brace = inline.indexOf('{');
    if (brace >= 0 && inline.trim().endsWith("}")) {
      String body = inline.substring(brace + 1, inline.lastIndexOf('}')).trim();
      if (!body.isEmpty() && isLogStatement(body)) {
        return true;
      }
    }
    // 多行形态：body 内每条非空语句都是 log 调用
    List<String> body = new ArrayList<>();
    for (int i = catchLine + 1; i < endLine; i++) {
      String t = code.get(i).trim();
      if (!t.isEmpty()) {
        body.add(t);
      }
    }
    return !body.isEmpty() && body.stream().allMatch(SourceScanRulesTest::isLogStatement);
  }

  private static boolean isLogStatement(String statement) {
    String s = statement.endsWith(";") ? statement.substring(0, statement.length() - 1) : statement;
    return s.matches("(?:\\w+\\.)?[lL]og(?:ger)?\\.\\w+\\(.*") && !s.contains("throw");
  }

  /** 方法调用语句（return new X( / 表达式续行）不是声明，跳过 Javadoc 检查。 */
  private static boolean isInvocationLine(String line) {
    String t = line.trim();
    return t.startsWith("return ") || t.contains(" new ") || t.startsWith("new ");
  }

  private static java.util.Optional<String> filePackage(List<String> lines) {
    return lines.stream()
        .filter(l -> l.trim().startsWith("package "))
        .map(l -> l.replace("package ", "").replace(";", "").trim())
        .findFirst();
  }

  private static boolean hasJavadocBefore(List<String> raw, int declLine) {
    int i = declLine - 1;
    while (i >= 0) {
      String t = raw.get(i).trim();
      if (t.isEmpty() || t.startsWith("@")) {
        i--;
        continue;
      }
      break;
    }
    if (i < 0) {
      return false;
    }
    String first = raw.get(i).trim();
    if (first.startsWith("/**")) {
      return true; // 单行 Javadoc /** … */
    }
    if (!first.endsWith("*/")) {
      return false;
    }
    for (int j = i; j >= 0; j--) {
      String t = raw.get(j).trim();
      if (t.startsWith("/**")) {
        return true;
      }
      if (t.endsWith(";") || (t.endsWith("}") && !t.endsWith("*/"))) {
        return false;
      }
    }
    return false;
  }

  private static String javadocTextBefore(List<String> raw, int declLine) {
    StringBuilder sb = new StringBuilder();
    for (int j = declLine - 1; j >= 0; j--) {
      sb.insert(0, raw.get(j) + "\n");
      if (raw.get(j).trim().startsWith("/**")) {
        break;
      }
      if (raw.get(j).trim().endsWith("}") || raw.get(j).trim().endsWith(";")) {
        break;
      }
    }
    return sb.toString();
  }

  private static String recordHeader(List<String> raw, int declLine) {
    StringBuilder sb = new StringBuilder();
    int depth = 0;
    boolean started = false;
    outer:
    for (int i = declLine; i < raw.size(); i++) {
      for (char c : raw.get(i).toCharArray()) {
        if (c == '(') {
          depth++;
          started = true;
          continue;
        }
        if (c == ')') {
          depth--;
          if (started && depth == 0) {
            break outer;
          }
          continue;
        }
        if (started) {
          sb.append(c);
        }
      }
    }
    return sb.toString();
  }

  private static List<String> splitTopLevel(String s, char sep) {
    List<String> parts = new ArrayList<>();
    int angle = 0;
    int paren = 0;
    StringBuilder current = new StringBuilder();
    for (char c : s.toCharArray()) {
      if (c == '<') {
        angle++;
      } else if (c == '>') {
        angle = Math.max(0, angle - 1);
      } else if (c == '(') {
        paren++;
      } else if (c == ')') {
        paren = Math.max(0, paren - 1);
      }
      if (c == sep && angle == 0 && paren == 0) {
        parts.add(current.toString());
        current.setLength(0);
      } else {
        current.append(c);
      }
    }
    if (current.length() > 0) {
      parts.add(current.toString());
    }
    return parts;
  }

  private static String lastIdentifier(String component) {
    String[] tokens = component.trim().split("\\s+");
    return tokens[tokens.length - 1];
  }
}
