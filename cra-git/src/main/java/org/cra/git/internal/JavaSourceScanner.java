package org.cra.git.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.cra.common.semantic.SymbolDefinition;
import org.cra.common.semantic.SymbolKind;
import org.cra.common.semantic.TypeImport;

/**
 * Java 词法/结构抽取（语义索引第一版；ADR-0014 决策 2：不用 JavaParser——零新依赖、零许可证/体积代价，代价是启发式抽取）。
 *
 * <p>抽取口径（全部确定性、无外部状态）：
 *
 * <ul>
 *   <li>类型：{@code class|interface|enum|record} + 名字（含 {@code @interface}、嵌套类型、局部类型）；
 *   <li>方法：类型体内"返回类型 + 名字 + {@code (}" 形态（含构造器、接口抽象方法、{@code default} 方法、泛型方法）；
 *   <li>字段：类型体内"类型 + 名字 + {@code =|;|,}" 形态（含 enum 常量；record 组件与局部变量不抽取）；
 *   <li>import：single-type / on-demand / static（解析是否指向仓库内文件在索引层完成）；
 *   <li>标识符出现：注释与字符串/字符/文本块内容不计（避免把文案当引用），关键字与字面量不计。
 * </ul>
 *
 * <p>已知偏差（ADR-0014 局限）：不解析泛型边界与注解参数语义、不区分重载、不建类型绑定；同名字段/方法在不同类型下合并为同一符号名。
 */
final class JavaSourceScanner {

  /** 摘录最大字符数（切片内联证据的行长度上限；超长行截断）。 */
  static final int SNIPPET_MAX_CHARS = 160;

  /** 单文件内单个标识符记录的出现行上限（防病态大文件拖垮内存；计数按已记录行为准）。 */
  static final int MAX_LINES_PER_NAME_PER_FILE = 100;

  /** Java 关键字与字面量（不计入符号引用；contextual 关键字一并排除）。 */
  private static final Set<String> KEYWORDS =
      Set.of(
          "abstract",
          "assert",
          "boolean",
          "break",
          "byte",
          "case",
          "catch",
          "char",
          "class",
          "const",
          "continue",
          "default",
          "do",
          "double",
          "else",
          "enum",
          "extends",
          "final",
          "finally",
          "float",
          "for",
          "goto",
          "if",
          "implements",
          "import",
          "instanceof",
          "int",
          "interface",
          "long",
          "native",
          "new",
          "package",
          "private",
          "protected",
          "public",
          "return",
          "short",
          "static",
          "strictfp",
          "super",
          "switch",
          "synchronized",
          "this",
          "throw",
          "throws",
          "transient",
          "try",
          "void",
          "volatile",
          "while",
          "true",
          "false",
          "null",
          "var",
          "record",
          "sealed",
          "permits",
          "yield",
          "non",
          "exports",
          "module",
          "open",
          "opens",
          "provides",
          "requires",
          "to",
          "transitive",
          "uses",
          "with");

  /** 成员修饰符（成员起点跳过用）。 */
  private static final Set<String> MODIFIERS =
      Set.of(
          "public",
          "private",
          "protected",
          "static",
          "final",
          "abstract",
          "synchronized",
          "native",
          "strictfp",
          "default",
          "transient",
          "volatile");

  /** 原始类型（可作返回/字段类型，且不是成员名）。 */
  private static final Set<String> PRIMITIVES =
      Set.of("boolean", "byte", "char", "short", "int", "long", "float", "double", "void");

  /** 类型声明关键字。 */
  private static final Set<String> TYPE_KEYWORDS = Set.of("class", "interface", "enum", "record");

  /** 成员起点前允许的记号（类型体边界、成员分隔符、注解/数组/泛型收尾）。 */
  private static final Set<String> MEMBER_START_PREV =
      Set.of("{", "}", ";", ",", "@", ")", "]", ">");

  /** 词法记号类型。 */
  private enum TokenKind {
    IDENT,
    PUNCT,
    NUMBER
  }

  /** 词法记号（text + 1 基行号）。 */
  private record Token(String text, int line, TokenKind kind) {}

  /**
   * 单文件抽取结果。
   *
   * @param path 文件路径（相对仓库根）
   * @param packageName 包名（无包声明为空串）
   * @param definitions 定义（源码顺序）
   * @param imports import 语句（resolvedPath 为空，解析在索引层统一进行）
   * @param identifierLines 标识符 → 出现行（不含 package/import 段、注释与字面量；证据条数按上限截断）
   * @param identifierCounts 标识符 → 精确出现次数（不受证据条数上限影响，ADR-0014 §2.3）
   * @param sourceLines 源码行（摘录用；0 基索引 = line - 1）
   */
  record FileScan(
      String path,
      String packageName,
      List<SymbolDefinition> definitions,
      List<TypeImport> imports,
      Map<String, List<Integer>> identifierLines,
      Map<String, Integer> identifierCounts,
      String[] sourceLines) {

    /** 空扫描（读取失败等降级用）。 */
    static FileScan empty(String path) {
      return new FileScan(path, "", List.of(), List.of(), Map.of(), Map.of(), new String[] {""});
    }
  }

  private JavaSourceScanner() {}

  /**
   * 抽取单个 Java 源文件（无异常上抛：括号不平衡等病态输入按尽力抽取处理）。
   *
   * @param path 文件路径（相对仓库根，写进定义/import）
   * @param text 源文件文本（UTF-8 解码后）
   * @return 抽取结果
   */
  static FileScan scan(String path, String text) {
    if (text == null) {
      return FileScan.empty(path);
    }
    String[] sourceLines = text.split("\n", -1);
    List<Token> tokens = lex(text);
    List<TypeImport> imports = new ArrayList<>();
    Header header = parseHeader(path, tokens, imports);
    int index = header.index();
    String packageName = header.packageName();

    List<SymbolDefinition> definitions = new ArrayList<>();
    Map<String, List<Integer>> identifierLines = new LinkedHashMap<>();
    Map<String, Integer> identifierCounts = new LinkedHashMap<>();
    int depth = 0;
    List<String> typeStack = new ArrayList<>();
    List<Integer> bodyDepths = new ArrayList<>();
    String pendingType = null;
    int pendingTypeDepth = -1;
    int lastMemberName = -1;
    Token previous = null;
    int i = index;
    while (i < tokens.size()) {
      Token token = tokens.get(i);
      if (token.kind() == TokenKind.IDENT) {
        if (!KEYWORDS.contains(token.text())) {
          recordIdentifier(identifierLines, identifierCounts, token);
        }
        if (TYPE_KEYWORDS.contains(token.text())
            && i + 1 < tokens.size()
            && tokens.get(i + 1).kind() == TokenKind.IDENT
            && !KEYWORDS.contains(tokens.get(i + 1).text())) {
          Token name = tokens.get(i + 1);
          definitions.add(
              new SymbolDefinition(
                  path,
                  name.text(),
                  SymbolKind.TYPE,
                  name.line(),
                  null,
                  snippet(sourceLines, name.line())));
          pendingType = name.text();
          pendingTypeDepth = depth;
        }
      }
      if (isBraceOpen(token)) {
        depth++;
        if (pendingType != null && depth == pendingTypeDepth + 1) {
          typeStack.add(pendingType);
          bodyDepths.add(depth);
          pendingType = null;
          pendingTypeDepth = -1;
        }
      } else if (isBraceClose(token)) {
        if (!bodyDepths.isEmpty() && bodyDepths.get(bodyDepths.size() - 1) == depth) {
          bodyDepths.remove(bodyDepths.size() - 1);
          typeStack.remove(typeStack.size() - 1);
        }
        if (depth > 0) {
          depth--;
        }
      } else if (!bodyDepths.isEmpty()
          && depth == bodyDepths.get(bodyDepths.size() - 1)
          && i > lastMemberName
          && (previous == null || MEMBER_START_PREV.contains(previous.text()))) {
        int nameIndex =
            tryMember(
                path, tokens, i, typeStack.get(typeStack.size() - 1), sourceLines, definitions);
        if (nameIndex >= 0) {
          lastMemberName = nameIndex;
        }
      }
      previous = token;
      i++;
    }
    return new FileScan(
        path,
        packageName,
        List.copyOf(definitions),
        List.copyOf(imports),
        identifierLines,
        identifierCounts,
        sourceLines);
  }

  /**
   * 行摘录（去首尾空白 + 长度上限；行号越界返回空串）。
   *
   * @param sourceLines 源码行（0 基）
   * @param line 1 基行号
   * @return 摘录
   */
  static String snippet(String[] sourceLines, int line) {
    if (sourceLines == null || line < 1 || line > sourceLines.length) {
      return "";
    }
    String trimmed = sourceLines[line - 1].strip();
    if (trimmed.length() <= SNIPPET_MAX_CHARS) {
      return trimmed;
    }
    return trimmed.substring(0, SNIPPET_MAX_CHARS) + "…";
  }

  // ---------- 词法 ----------

  /** 词法扫描：注释与字符串/字符/文本块内容丢弃（保留行号），标识符/标点/数字保留。 */
  private static List<Token> lex(String text) {
    List<Token> tokens = new ArrayList<>();
    int i = 0;
    int line = 1;
    int n = text.length();
    while (i < n) {
      char c = text.charAt(i);
      if (c == '\n') {
        line++;
        i++;
        continue;
      }
      if (Character.isWhitespace(c)) {
        i++;
        continue;
      }
      if (c == '/' && i + 1 < n) {
        char next = text.charAt(i + 1);
        if (next == '/') {
          while (i < n && text.charAt(i) != '\n') {
            i++;
          }
          continue;
        }
        if (next == '*') {
          i += 2;
          while (i < n && !(text.charAt(i) == '*' && i + 1 < n && text.charAt(i + 1) == '/')) {
            if (text.charAt(i) == '\n') {
              line++;
            }
            i++;
          }
          i = Math.min(n, i + 2);
          continue;
        }
      }
      if (text.startsWith("\"\"\"", i)) {
        i += 3;
        while (i < n && !text.startsWith("\"\"\"", i)) {
          if (text.charAt(i) == '\n') {
            line++;
          }
          i++;
        }
        i = Math.min(n, i + 3);
        continue;
      }
      if (c == '"' || c == '\'') {
        int end = skipQuoted(text, i, c);
        for (int j = i; j < end; j++) {
          if (text.charAt(j) == '\n') {
            line++;
          }
        }
        i = end;
        continue;
      }
      if (Character.isJavaIdentifierStart(c)) {
        int start = i;
        while (i < n && Character.isJavaIdentifierPart(text.charAt(i))) {
          i++;
        }
        tokens.add(new Token(text.substring(start, i), line, TokenKind.IDENT));
        continue;
      }
      if (Character.isDigit(c)) {
        int start = i;
        while (i < n
            && (Character.isLetterOrDigit(text.charAt(i))
                || text.charAt(i) == '.'
                || text.charAt(i) == '_')) {
          i++;
        }
        tokens.add(new Token(text.substring(start, i), line, TokenKind.NUMBER));
        continue;
      }
      tokens.add(new Token(String.valueOf(c), line, TokenKind.PUNCT));
      i++;
    }
    return tokens;
  }

  /** 跳过字符串/字符字面量（返回结束后的下标；未闭合时在换行处容错退出）。 */
  private static int skipQuoted(String text, int start, char quote) {
    int i = start + 1;
    int n = text.length();
    while (i < n) {
      char c = text.charAt(i);
      if (c == '\\') {
        i += 2;
        continue;
      }
      if (c == quote) {
        return i + 1;
      }
      if (c == '\n') {
        // 未闭合字面量：容错退出，避免吞掉后续整文件
        return i;
      }
      i++;
    }
    return n;
  }

  // ---------- 头部（package / import） ----------

  /** 头部解析结果：主扫描起点 + 包名。 */
  private record Header(int index, String packageName) {}

  /** 解析 package/import 段（返回主扫描起点与包名；annotations 等其它 token 视为头部结束）。 */
  private static Header parseHeader(String path, List<Token> tokens, List<TypeImport> imports) {
    StringBuilder packageName = new StringBuilder();
    int i = 0;
    while (i < tokens.size()) {
      Token token = tokens.get(i);
      if (token.kind() != TokenKind.IDENT) {
        break;
      }
      if ("package".equals(token.text())) {
        i++;
        while (i < tokens.size() && !";".equals(tokens.get(i).text())) {
          packageName.append(tokens.get(i).text());
          i++;
        }
        i = Math.min(tokens.size(), i + 1);
        continue;
      }
      if ("import".equals(token.text())) {
        int line = token.line();
        i++;
        boolean staticImport = i < tokens.size() && "static".equals(tokens.get(i).text());
        if (staticImport) {
          i++;
        }
        StringBuilder qualified = new StringBuilder();
        boolean wildcard = false;
        while (i < tokens.size() && !";".equals(tokens.get(i).text())) {
          String piece = tokens.get(i).text();
          if ("*".equals(piece)) {
            wildcard = true;
          }
          qualified.append(piece);
          i++;
        }
        i = Math.min(tokens.size(), i + 1);
        String imported = qualified.toString();
        String simpleName = wildcard ? "*" : lastSegment(imported);
        imports.add(new TypeImport(path, imported, simpleName, staticImport, line, null));
        continue;
      }
      break;
    }
    return new Header(i, packageName.toString());
  }

  private static String lastSegment(String qualified) {
    int dot = qualified.lastIndexOf('.');
    return dot < 0 ? qualified : qualified.substring(dot + 1);
  }

  // ---------- 成员抽取 ----------

  /**
   * 尝试在 {@code start} 处识别一个类型体成员（注解/修饰符/泛型/数组均可前导）。
   *
   * @return 成员名所在 token 下标；不是成员返回 -1（调用方不推进，主循环继续逐 token 收集标识符）
   */
  private static int tryMember(
      String path,
      List<Token> tokens,
      int start,
      String owner,
      String[] sourceLines,
      List<SymbolDefinition> out) {
    int i = start;
    while (i < tokens.size() && "@".equals(tokens.get(i).text())) {
      int after = skipAnnotation(tokens, i);
      if (after <= i) {
        break;
      }
      i = after;
    }
    i = skipModifiers(tokens, i);
    if (i < tokens.size() && "<".equals(tokens.get(i).text())) {
      int after = skipBalanced(tokens, i, "<", ">");
      if (after <= i) {
        return -1;
      }
      i = skipModifiers(tokens, after);
    }
    if (i >= tokens.size() || tokens.get(i).kind() != TokenKind.IDENT) {
      return -1;
    }
    Token first = tokens.get(i);
    if (KEYWORDS.contains(first.text()) && !PRIMITIVES.contains(first.text())) {
      return -1;
    }
    int next = i + 1;
    if (next < tokens.size() && "(".equals(tokens.get(next).text())) {
      // 构造器（或 record 紧凑构造器形态外的无返回类型方法——语法上仅构造器合法）
      if (owner != null && owner.equals(first.text())) {
        out.add(method(path, first, owner, sourceLines));
        return i;
      }
      return -1;
    }
    if (next < tokens.size()
        && ("=".equals(tokens.get(next).text())
            || ";".equals(tokens.get(next).text())
            || ",".equals(tokens.get(next).text()))) {
      // 裸声明符 / enum 常量
      if (PRIMITIVES.contains(first.text()) || KEYWORDS.contains(first.text())) {
        return -1;
      }
      out.add(field(path, first, owner, sourceLines));
      return i;
    }
    if (next < tokens.size()
        && "{".equals(tokens.get(next).text())
        && owner != null
        && owner.equals(first.text())) {
      // record 紧凑构造器：TypeName { ... }
      out.add(method(path, first, owner, sourceLines));
      return i;
    }
    int afterType = parseTypeRef(tokens, i);
    if (afterType <= i
        || afterType >= tokens.size()
        || tokens.get(afterType).kind() != TokenKind.IDENT) {
      return -1;
    }
    Token name = tokens.get(afterType);
    if (KEYWORDS.contains(name.text())) {
      return -1;
    }
    int afterName = afterType + 1;
    if (afterName < tokens.size() && "(".equals(tokens.get(afterName).text())) {
      out.add(method(path, name, owner, sourceLines));
      return afterType;
    }
    int afterDims = skipArrayDims(tokens, afterName);
    if (afterDims < tokens.size()
        && ("=".equals(tokens.get(afterDims).text())
            || ";".equals(tokens.get(afterDims).text())
            || ",".equals(tokens.get(afterDims).text()))) {
      out.add(field(path, name, owner, sourceLines));
      return afterType;
    }
    return -1;
  }

  /** 解析类型引用（限定名 + 泛型参数 + 数组维度），返回类型之后的下标；不成立返回起始下标。 */
  private static int parseTypeRef(List<Token> tokens, int start) {
    if (start >= tokens.size() || tokens.get(start).kind() != TokenKind.IDENT) {
      return start;
    }
    String text = tokens.get(start).text();
    if (KEYWORDS.contains(text) && !PRIMITIVES.contains(text)) {
      return start;
    }
    int i = start + 1;
    while (i + 1 < tokens.size()
        && ".".equals(tokens.get(i).text())
        && tokens.get(i + 1).kind() == TokenKind.IDENT) {
      i += 2;
    }
    if (i < tokens.size() && "<".equals(tokens.get(i).text())) {
      int after = skipBalanced(tokens, i, "<", ">");
      if (after <= i) {
        return start;
      }
      i = after;
    }
    return skipArrayDims(tokens, i);
  }

  private static int skipAnnotation(List<Token> tokens, int start) {
    if (start >= tokens.size() || !"@".equals(tokens.get(start).text())) {
      return start;
    }
    int i = start + 1;
    if (i >= tokens.size()
        || tokens.get(i).kind() != TokenKind.IDENT
        || "interface".equals(tokens.get(i).text())) {
      return start;
    }
    i++;
    while (i + 1 < tokens.size()
        && ".".equals(tokens.get(i).text())
        && tokens.get(i + 1).kind() == TokenKind.IDENT) {
      i += 2;
    }
    if (i < tokens.size() && "(".equals(tokens.get(i).text())) {
      int after = skipBalanced(tokens, i, "(", ")");
      if (after > i) {
        return after;
      }
    }
    return i;
  }

  private static int skipModifiers(List<Token> tokens, int start) {
    int i = start;
    while (i < tokens.size() && tokens.get(i).kind() == TokenKind.IDENT) {
      String text = tokens.get(i).text();
      if (MODIFIERS.contains(text)) {
        i++;
        continue;
      }
      // non-sealed（三个 token：non - sealed）
      if ("non".equals(text)
          && i + 2 < tokens.size()
          && "-".equals(tokens.get(i + 1).text())
          && "sealed".equals(tokens.get(i + 2).text())) {
        i += 3;
        continue;
      }
      break;
    }
    return i;
  }

  private static int skipArrayDims(List<Token> tokens, int start) {
    int i = start;
    while (i + 1 < tokens.size()
        && "[".equals(tokens.get(i).text())
        && "]".equals(tokens.get(i + 1).text())) {
      i += 2;
    }
    return i;
  }

  /** 跳过成对定界符（含嵌套），返回收尾之后的下标；未闭合返回 startingIndex。 */
  private static int skipBalanced(List<Token> tokens, int start, String open, String close) {
    if (start >= tokens.size() || !open.equals(tokens.get(start).text())) {
      return start;
    }
    int depth = 0;
    for (int i = start; i < tokens.size(); i++) {
      String text = tokens.get(i).text();
      if (open.equals(text)) {
        depth++;
      } else if (close.equals(text)) {
        depth--;
        if (depth == 0) {
          return i + 1;
        }
      }
    }
    return start;
  }

  private static SymbolDefinition method(
      String path, Token name, String owner, String[] sourceLines) {
    return new SymbolDefinition(
        path,
        name.text(),
        SymbolKind.METHOD,
        name.line(),
        owner,
        snippet(sourceLines, name.line()));
  }

  private static SymbolDefinition field(
      String path, Token name, String owner, String[] sourceLines) {
    return new SymbolDefinition(
        path, name.text(), SymbolKind.FIELD, name.line(), owner, snippet(sourceLines, name.line()));
  }

  private static void recordIdentifier(
      Map<String, List<Integer>> identifierLines,
      Map<String, Integer> identifierCounts,
      Token token) {
    // 计数与证据条数解耦：≤100 行的上限只约束证据，不能把 occurrences 也截断（ADR-0014 §2.3）
    identifierCounts.merge(token.text(), 1, Integer::sum);
    List<Integer> lines = identifierLines.computeIfAbsent(token.text(), key -> new ArrayList<>());
    if (lines.size() >= MAX_LINES_PER_NAME_PER_FILE) {
      return;
    }
    lines.add(token.line());
  }

  private static boolean isBraceOpen(Token token) {
    return token.kind() == TokenKind.PUNCT && "{".equals(token.text());
  }

  private static boolean isBraceClose(Token token) {
    return token.kind() == TokenKind.PUNCT && "}".equals(token.text());
  }
}
