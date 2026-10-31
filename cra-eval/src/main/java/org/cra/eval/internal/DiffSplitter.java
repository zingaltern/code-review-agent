package org.cra.eval.internal;

import java.util.ArrayList;
import java.util.List;
import org.cra.common.exception.CraConfigException;

/**
 * unified diff（git 格式）拆分器：把 fixture 的 diff.patch 拆成逐文件 patch（GitHub compare API files[].patch 形态 =
 * 仅 hunk 行，无 ---/+++ 头），供 stub 服务器与 annotate 预填使用。
 */
public final class DiffSplitter {

  private static final String DIFF_GIT = "diff --git ";

  private DiffSplitter() {}

  /** 拆分结果：单文件 patch。 */
  public record PatchedFile(String oldPath, String newPath, String status, String patch) {

    public boolean isTestFile() {
      return isTestPath(newPath) || isTestPath(oldPath);
    }
  }

  /**
   * hunk 级测试文件过滤（06§3 净化工序①）：src/test/**、*Test.java 等。 只认测试目录（src/test/**，含 maven/gradle
   * 变体）与测试类命名后缀， 不做宽松的路径包含匹配——避免把 main 源码中 test 包名的文件误过滤。
   */
  public static boolean isTestPath(String path) {
    if (path == null) {
      return false;
    }
    String p = path.replace('\\', '/');
    return p.startsWith("src/test/")
        || p.contains("/src/test/")
        || p.contains("/test/java/")
        || p.endsWith("Test.java")
        || p.endsWith("Tests.java")
        || p.endsWith("TestCase.java")
        || p.endsWith("IT.java");
  }

  /**
   * 拆分 multi-file unified diff。
   *
   * @param diffText git diff 全文
   * @return 逐文件 patch（顺序保持 diff 原序）
   */
  public static List<PatchedFile> split(String diffText) {
    List<PatchedFile> files = new ArrayList<>();
    if (diffText == null || diffText.isBlank()) {
      return files;
    }
    List<String> sections = new ArrayList<>();
    List<String> current = null;
    for (String line : diffText.split("\n", -1)) {
      if (line.startsWith(DIFF_GIT)) {
        if (current != null) {
          sections.add(String.join("\n", current));
        }
        current = new ArrayList<>();
        current.add(line);
      } else if (current != null) {
        current.add(line);
      }
    }
    if (current != null) {
      sections.add(String.join("\n", current));
    }
    files.addAll(sections.stream().map(DiffSplitter::parseSection).toList());
    return files;
  }

  private static PatchedFile parseSection(String section) {
    String[] lines = section.split("\n", -1);
    SectionState state = new SectionState();
    for (String line : lines) {
      applyLine(state, line);
      if (!state.inHunks
          && (line.startsWith("Binary files") || line.startsWith("GIT binary patch"))) {
        return new PatchedFile(state.oldPath, state.newPath, state.status, null);
      }
    }
    String p = state.patch.toString();
    if (state.oldPath == null || state.newPath == null) {
      throw new CraConfigException("diff 段缺 --git 头，无法解析: " + firstLine(section));
    }
    return new PatchedFile(state.oldPath, state.newPath, state.status, p.isBlank() ? null : p);
  }

  /**
   * 处理 diff 段的一行（头字段与 hunk 内容累计）。
   *
   * @param state 段解析状态
   * @param line 当前行
   */
  private static void applyLine(SectionState state, String line) {
    if (line.startsWith(DIFF_GIT)) {
      String[] paths = parseDiffGitHeader(line);
      state.oldPath = paths[0];
      state.newPath = paths[1];
    } else if (line.startsWith("new file mode")) {
      state.status = "added";
    } else if (line.startsWith("deleted file mode")) {
      state.status = "removed";
    } else if (line.startsWith("rename from ")) {
      state.status = "renamed";
      state.oldPath = renamePath(line.substring("rename from ".length()), line);
    } else if (line.startsWith("rename to ")) {
      state.status = "renamed";
      state.newPath = renamePath(line.substring("rename to ".length()), line);
    } else if (!state.inHunks && (line.startsWith("--- a/") || line.startsWith("--- /dev/null"))) {
      // 保持 GitHub patch 形态：跳过 ---/+++ 头（仅在 hunk 之前——hunk 内容行可能以 "--- " 开头）
    } else if (!state.inHunks && (line.startsWith("+++ b/") || line.startsWith("+++ /dev/null"))) {
      // 同上
    } else if (line.startsWith("@@")) {
      state.inHunks = true;
      state.patch.append(line).append('\n');
    } else if (state.inHunks) {
      state.patch.append(line).append('\n');
    }
  }

  /** diff 段解析状态（路径/状态/累积 patch）。 */
  private static final class SectionState {
    private String oldPath;
    private String newPath;
    private String status = "modified";
    private final StringBuilder patch = new StringBuilder();
    private boolean inHunks;
  }

  private static String[] parseDiffGitHeader(String line) {
    // diff --git a/<path> b/<path>；git 对非 ASCII/含空格路径加 C 式引号（core.quotePath）
    String body = line.substring(DIFF_GIT.length());
    if (body.startsWith("\"")) {
      List<String> tokens = quotedTokens(body);
      if (tokens.size() == 2) {
        return new String[] {stripPrefix(tokens.get(0)), stripPrefix(tokens.get(1))};
      }
      throw new CraConfigException("diff --git 头引号形态无法解析: " + line);
    }
    int mid = body.indexOf(" b/");
    if (mid < 0) {
      throw new CraConfigException("diff --git 头无法解析: " + line);
    }
    String a = body.substring(0, mid);
    String b = body.substring(mid + 1);
    return new String[] {stripPrefix(a), stripPrefix(b)};
  }

  /** 解析两个连续 C 式引号串（"a/..." "b/..."），含八进制转义字节（UTF-8）。 */
  private static List<String> quotedTokens(String body) {
    List<String> out = new ArrayList<>();
    int i = 0;
    while (i < body.length() && out.size() < 2) {
      while (i < body.length() && Character.isWhitespace(body.charAt(i))) {
        i++;
      }
      if (i >= body.length() || body.charAt(i) != '"') {
        break;
      }
      i++;
      java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
      while (i < body.length() && body.charAt(i) != '"') {
        char c = body.charAt(i);
        if (c != '\\') {
          if (c < 0x80) {
            bytes.write(c);
          } else {
            bytes.writeBytes(String.valueOf(c).getBytes(java.nio.charset.StandardCharsets.UTF_8));
          }
          i++;
        } else {
          i++;
          if (i >= body.length()) {
            break;
          }
          i = decodeEscape(body, i, bytes);
        }
      }
      if (i < body.length() && body.charAt(i) == '"') {
        i++;
      }
      out.add(bytes.toString(java.nio.charset.StandardCharsets.UTF_8));
    }
    return out;
  }

  /**
   * 解码一个反斜杠转义（含 1–3 位八进制字节），返回转义后的下标。
   *
   * @param body 原文
   * @param i 转义字符下标（反斜杠之后）
   * @param bytes 输出字节流
   * @return 下一个待处理下标
   */
  private static int decodeEscape(String body, int i, java.io.ByteArrayOutputStream bytes) {
    char esc = body.charAt(i);
    switch (esc) {
      case 'n' -> {
        bytes.write('\n');
        return i + 1;
      }
      case 't' -> {
        bytes.write('\t');
        return i + 1;
      }
      case 'r' -> {
        bytes.write('\r');
        return i + 1;
      }
      case '\\' -> {
        bytes.write('\\');
        return i + 1;
      }
      case '"' -> {
        bytes.write('"');
        return i + 1;
      }
      default -> {
        if (esc >= '0' && esc <= '7' && i + 2 < body.length()) {
          int value = 0;
          int digits = 0;
          int j = i;
          while (digits < 3
              && j < body.length()
              && body.charAt(j) >= '0'
              && body.charAt(j) <= '7') {
            value = value * 8 + (body.charAt(j) - '0');
            j++;
            digits++;
          }
          bytes.write(value & 0xFF);
          return j;
        }
        bytes.write(esc);
        return i + 1;
      }
    }
  }

  /** rename from/to 行按 C 风格引号解码（与 diff --git 头一致）；未加引号时保留原样路径。 */
  private static String renamePath(String value, String line) {
    String token = value.trim();
    if (!token.startsWith("\"")) {
      return token;
    }
    List<String> tokens = quotedTokens(token);
    if (tokens.size() != 1) {
      throw new CraConfigException("rename 头引号形态无法解析: " + line);
    }
    return tokens.get(0);
  }

  private static String stripPrefix(String path) {
    if (path.startsWith("a/") || path.startsWith("b/")) {
      return path.substring(2);
    }
    return path;
  }

  private static String firstLine(String section) {
    int idx = section.indexOf('\n');
    return idx < 0 ? section : section.substring(0, idx);
  }
}
