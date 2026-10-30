package org.cra.common.context;

import java.util.regex.Pattern;

/**
 * 路径 glob 匹配（04§3.1 Priority paths / Skip paths 语义）：{@code **} 跨目录段，{@code *} 单段（不跨 {@code
 * /}），其余字符按字面匹配。 纯函数工具，cra-context（解析）与 cra-core（范围裁决）共用。
 */
public final class PathGlobs {

  private PathGlobs() {}

  /**
   * 判断路径是否命中 glob 模式。
   *
   * @param glob glob 模式（如 {@code generated/**}、{@code billing/*}、精确路径 {@code src/Foo.java}）
   * @param path 相对仓库根的文件路径
   * @return true 若命中；glob 为空白时恒 false
   */
  public static boolean matches(String glob, String path) {
    if (glob == null || glob.isBlank() || path == null || path.isBlank()) {
      return false;
    }
    return compile(glob.strip()).matcher(path).matches();
  }

  /**
   * 任一模式命中即 true（空模式列表恒 false）。
   *
   * @param globs glob 模式列表
   * @param path 文件路径
   * @return true 若任一命中
   */
  public static boolean matchesAny(Iterable<String> globs, String path) {
    for (String glob : globs) {
      if (matches(glob, path)) {
        return true;
      }
    }
    return false;
  }

  private static Pattern compile(String glob) {
    StringBuilder regex = new StringBuilder("^");
    for (int i = 0; i < glob.length(); i++) {
      char c = glob.charAt(i);
      if (c == '*') {
        if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
          if (i + 2 < glob.length() && glob.charAt(i + 2) == '/') {
            // `**/` 匹配零或多级目录（与 GitHub/GitLab 适配器的 glob 语义一致）：
            // `**/foo` 命中根级 foo，`a/**/b` 命中 a/b 与 a/x/y/b
            regex.append("(?:.*/)?");
            i += 2;
          } else {
            regex.append(".*");
            i++;
          }
        } else {
          regex.append("[^/]*");
        }
      } else if (c == '?' || c == '.' || c == '(' || c == ')' || c == '[' || c == ']' || c == '{'
          || c == '}' || c == '\\' || c == '^' || c == '$' || c == '+' || c == '|') {
        regex.append('\\').append(c);
      } else {
        regex.append(c);
      }
    }
    return Pattern.compile(regex.append('$').toString());
  }
}
