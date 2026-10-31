package org.cra.common.context;

import java.util.regex.Pattern;

/**
 * 路径 glob 匹配的单一事实源（04§3.1 Priority paths / Skip paths；05§2.1 {@code listFiles} 扫描；ADR-0016）：
 * 双星号加斜杠匹配零或多级目录（零目录可选），裸双星号跨段，单星号单段（不跨 {@code /}）， 问号单段通配（不匹配 {@code /}），其余字符按字面匹配。
 *
 * <p>此前 github/gitlab 两个 PayloadSupport 与 jgit 适配器各持一份同义 globToRegex 复制（ADR-0016 下沉统一）； 问号采纳 jgit
 * 实现的更严语义（单段通配 {@code [^/]}，而非 github/gitlab 的跨段 {@code .}）—— 现网调用点（REVIEW.md skip/priority、双星号形式的
 * java 扫描）不含问号字面量，行为不变。纯函数工具，cra-context（解析）、cra-core （范围裁决）与 git 适配器（文件树扫描）共用。
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

  /**
   * glob → 锚定 regex（{@code ^...$}，配合 {@link Pattern#matcher(CharSequence)} 的 {@code matches()}
   * 全串匹配）。
   *
   * <p>语义：双星号加斜杠 → 零或多级目录可选（双星号加斜杠加 foo 命中根级 foo）；裸双星号 → 跨段任意匹配； 单星号 → 单段；问号 → 单段通配（不跨 {@code
   * /}，ADR-0016 收紧，非字面量）；其余字符按字面转义。
   *
   * @param glob glob 模式（null 时 NPE，与各适配器既有调用约定一致）
   * @return 全串匹配用的 Pattern（每次调用新编译，适配器循环内应复用同一实例）
   */
  public static Pattern compile(String glob) {
    StringBuilder regex = new StringBuilder("^");
    int i = 0;
    while (i < glob.length()) {
      i = appendToken(glob, i, regex);
    }
    return Pattern.compile(regex.append('$').toString());
  }

  /**
   * 追加 {@code glob[i]} 起的一个 glob token 到正则，返回下一个待处理下标。
   *
   * @param glob glob 模式
   * @param i 当前字符下标
   * @param regex 目标正则
   * @return 下一个待处理下标
   */
  private static int appendToken(String glob, int i, StringBuilder regex) {
    char c = glob.charAt(i);
    if (c == '*') {
      return appendStar(glob, i, regex);
    }
    if (c == '?') {
      // 单段通配（不跨 /）：采纳 jgit 适配器的更严语义（ADR-0016），非字面量 '?'
      regex.append("[^/]");
    } else if (isRegexMeta(c)) {
      regex.append('\\').append(c);
    } else {
      regex.append(c);
    }
    return i + 1;
  }

  /**
   * 追加 {@code *} / {@code **} / {@code **}{@code /} 三种星号形态（ADR-0016 语义）。
   *
   * @param glob glob 模式
   * @param i 星号起始下标
   * @param regex 目标正则
   * @return 下一个待处理下标
   */
  private static int appendStar(String glob, int i, StringBuilder regex) {
    if (i + 1 >= glob.length() || glob.charAt(i + 1) != '*') {
      regex.append("[^/]*");
      return i + 1;
    }
    if (i + 2 < glob.length() && glob.charAt(i + 2) == '/') {
      // `**/` 匹配零或多级目录：**/foo 命中根级 foo，a/**/b 命中 a/b 与 a/x/y/b
      regex.append("(?:.*/)?");
      return i + 3;
    }
    regex.append(".*");
    return i + 2;
  }

  /**
   * 是否需要正则转义的 glob 字面字符（与既有转义集合逐字符一致）。
   *
   * @param c 字符
   * @return true 若是正则元字符
   */
  private static boolean isRegexMeta(char c) {
    return c == '.' || c == '(' || c == ')' || c == '[' || c == ']' || c == '{' || c == '}'
        || c == '\\' || c == '^' || c == '$' || c == '+' || c == '|';
  }
}
