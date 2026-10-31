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
          "non");

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
      List<String> sourceLines) {

    /** 空扫描（读取失败等降级用）。 */
    static FileScan empty(String path) {
      return new FileScan(path, "", List.of(), List.of(), Map.of(), Map.of(), List.of(""));
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
    List<String> sourceLines = List.of(text.split("\n", -1));
    List<Token> tokens = lex(text);
    List<TypeImport> imports = new ArrayList<>();
    Header header = parseHeader(path, tokens, imports);
    ScanContext context = new ScanContext(path, tokens, sourceLines);
    int i = header.index();
    while (i < tokens.size()) {
      Token token = tokens.get(i);
      if (token.kind() == TokenKind.IDENT) {
        if (!KEYWORDS.contains(token.text())) {
          recordIdentifier(context.identifierLines, context.identifierCounts, token);
        }
        if (isTypeDefinition(tokens, i)) {
          recordTypeDefinition(context, i);
        }
      }
      trackStructure(context, i, token);
      context.previous = token;
      i++;
    }
    return new FileScan(
        path,
        header.packageName(),
        List.copyOf(context.definitions),
        List.copyOf(imports),
        context.identifierLines,
        context.identifierCounts,
        sourceLines);
  }

  /**
   * 主扫描可变状态（括号深度、类型体栈、待定类型与成员起点）。
   *
   * <p>从 scan 抽出的状态载体：主循环每步只做标识符/类型/括号/成员四类派发，状态显式化便于评审。
   */
  private static final class ScanContext {

    private final String path;
    private final List<Token> tokens;
    private final List<String> sourceLines;
    private final List<SymbolDefinition> definitions = new ArrayList<>();
    private final Map<String, List<Integer>> identifierLines = new LinkedHashMap<>();
    private final Map<String, Integer> identifierCounts = new LinkedHashMap<>();
    private final List<String> typeStack = new ArrayList<>();
    private final List<Boolean> enumBodies = new ArrayList<>();
    private final List<Integer> bodyDepths = new ArrayList<>();
    private int depth;
    private String pendingType;
    private boolean pendingEnum;
    private int pendingTypeDepth = -1;
    private int lastMemberName = -1;
    private Token previous;

    private ScanContext(String path, List<Token> tokens, List<String> sourceLines) {
      this.path = path;
      this.tokens = tokens;
      this.sourceLines = sourceLines;
    }
  }

  /**
   * 当前 token 是否是类型声明（class/interface/enum/record + 合法名字）。
   *
   * @param tokens 记号流
   * @param i 当前下标
   * @return true 若是类型声明起点
   */
  private static boolean isTypeDefinition(List<Token> tokens, int i) {
    return TYPE_KEYWORDS.contains(tokens.get(i).text())
        && i + 1 < tokens.size()
        && tokens.get(i + 1).kind() == TokenKind.IDENT
        && !KEYWORDS.contains(tokens.get(i + 1).text());
  }

  /**
   * 记录类型定义并挂起类型体识别（下一个开括号进入该类型体）。
   *
   * @param context 扫描状态
   * @param i 类型关键字下标
   */
  private static void recordTypeDefinition(ScanContext context, int i) {
    Token name = context.tokens.get(i + 1);
    context.definitions.add(
        new SymbolDefinition(
            context.path,
            name.text(),
            SymbolKind.TYPE,
            name.line(),
            null,
            snippet(context.sourceLines, name.line())));
    context.pendingType = name.text();
    context.pendingEnum = "enum".equals(context.tokens.get(i).text());
    context.pendingTypeDepth = context.depth;
  }

  /**
   * 跟踪括号结构与类型体内成员起点。
   *
   * @param context 扫描状态
   * @param i 当前下标（成员识别用）
   * @param token 当前 token
   */
  private static void trackStructure(ScanContext context, int i, Token token) {
    if (isBraceOpen(token)) {
      openBody(context);
    } else if (isBraceClose(token)) {
      closeBody(context);
    } else if (!context.bodyDepths.isEmpty()
        && context.depth == context.bodyDepths.get(context.bodyDepths.size() - 1)
        && i > context.lastMemberName
        && (context.previous == null || MEMBER_START_PREV.contains(context.previous.text()))) {
      int nameIndex =
          tryMember(
              context.path,
              context.tokens,
              i,
              context.typeStack.get(context.typeStack.size() - 1),
              context.enumBodies.get(context.enumBodies.size() - 1),
              context.sourceLines,
              context.definitions);
      if (nameIndex >= 0) {
        context.lastMemberName = nameIndex;
      }
    }
  }

  /**
   * 进入一层花括号：若刚识别的类型声明在此层开体，则压入类型栈。
   *
   * @param context 扫描状态
   */
  private static void openBody(ScanContext context) {
    context.depth++;
    if (context.pendingType != null && context.depth == context.pendingTypeDepth + 1) {
      context.typeStack.add(context.pendingType);
      context.enumBodies.add(context.pendingEnum);
      context.bodyDepths.add(context.depth);
      context.pendingType = null;
      context.pendingEnum = false;
      context.pendingTypeDepth = -1;
    }
  }

  /**
   * 退出一层花括号：命中类型体起点深度则弹出类型栈。
   *
   * @param context 扫描状态
   */
  private static void closeBody(ScanContext context) {
    if (!context.bodyDepths.isEmpty()
        && context.bodyDepths.get(context.bodyDepths.size() - 1) == context.depth) {
      context.bodyDepths.remove(context.bodyDepths.size() - 1);
      context.typeStack.remove(context.typeStack.size() - 1);
      context.enumBodies.remove(context.enumBodies.size() - 1);
    }
    if (context.depth > 0) {
      context.depth--;
    }
  }

  /**
   * 行摘录（去首尾空白 + 长度上限；行号越界返回空串）。
   *
   * @param sourceLines 源码行（0 基）
   * @param line 1 基行号
   * @return 摘录
   */
  static String snippet(List<String> sourceLines, int line) {
    if (sourceLines == null || line < 1 || line > sourceLines.size()) {
      return "";
    }
    String trimmed = sourceLines.get(line - 1).strip();
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
      } else if (Character.isWhitespace(c)) {
        i++;
      } else if (c == '/' && i + 1 < n && text.charAt(i + 1) == '/') {
        i = skipLineComment(text, i);
      } else if (c == '/' && i + 1 < n && text.charAt(i + 1) == '*') {
        int end = skipBlockComment(text, i);
        line += countNewlines(text, i, end);
        i = end;
      } else if (text.startsWith("\"\"\"", i)) {
        int end = skipTextBlock(text, i);
        line += countNewlines(text, i, end);
        i = end;
      } else if (c == '"' || c == '\'') {
        int end = skipQuoted(text, i, c);
        line += countNewlines(text, i, end);
        i = end;
      } else if (Character.isJavaIdentifierStart(c)) {
        i = lexIdentifier(text, i, line, tokens);
      } else if (Character.isDigit(c)) {
        i = lexNumber(text, i, line, tokens);
      } else {
        tokens.add(new Token(String.valueOf(c), line, TokenKind.PUNCT));
        i++;
      }
    }
    return tokens;
  }

  /**
   * 跳过单行注释（停在换行符前，换行由主循环计行）。
   *
   * @param text 源文本
   * @param start {@code //} 起点
   * @return 换行符下标或文本末尾
   */
  private static int skipLineComment(String text, int start) {
    int i = start;
    while (i < text.length() && text.charAt(i) != '\n') {
      i++;
    }
    return i;
  }

  /**
   * 跳过块注释（未闭合时吞到文本末尾）。
   *
   * @param text 源文本
   * @param start {@code /*} 起点
   * @return 收尾之后的下标
   */
  private static int skipBlockComment(String text, int start) {
    int i = start + 2;
    int n = text.length();
    while (i < n && !(text.charAt(i) == '*' && i + 1 < n && text.charAt(i + 1) == '/')) {
      i++;
    }
    return Math.min(n, i + 2);
  }

  /**
   * 跳过文本块（{@code """} 定界；未闭合吞到末尾）。
   *
   * @param text 源文本
   * @param start 起始三引号下标
   * @return 收尾之后的下标
   */
  private static int skipTextBlock(String text, int start) {
    int i = start + 3;
    int n = text.length();
    while (i < n && !text.startsWith("\"\"\"", i)) {
      i++;
    }
    return Math.min(n, i + 3);
  }

  /**
   * 词法化一个标识符。
   *
   * @param text 源文本
   * @param start 标识符起点
   * @param line 当前行号
   * @param tokens 输出
   * @return 下一个待处理下标
   */
  private static int lexIdentifier(String text, int start, int line, List<Token> tokens) {
    int i = start;
    int n = text.length();
    while (i < n && Character.isJavaIdentifierPart(text.charAt(i))) {
      i++;
    }
    tokens.add(new Token(text.substring(start, i), line, TokenKind.IDENT));
    return i;
  }

  /**
   * 词法化一个数字字面量。
   *
   * @param text 源文本
   * @param start 数字起点
   * @param line 当前行号
   * @param tokens 输出
   * @return 下一个待处理下标
   */
  private static int lexNumber(String text, int start, int line, List<Token> tokens) {
    int i = start;
    int n = text.length();
    while (i < n
        && (Character.isLetterOrDigit(text.charAt(i))
            || text.charAt(i) == '.'
            || text.charAt(i) == '_')) {
      i++;
    }
    tokens.add(new Token(text.substring(start, i), line, TokenKind.NUMBER));
    return i;
  }

  /**
   * 统计区间内换行数（跳过多行注释/文本块/字面量后补行号）。
   *
   * @param text 源文本
   * @param from 起点（含）
   * @param to 终点（不含）
   * @return 换行数
   */
  private static int countNewlines(String text, int from, int to) {
    int count = 0;
    for (int i = from; i < to; i++) {
      if (text.charAt(i) == '\n') {
        count++;
      }
    }
    return count;
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
    while (i < tokens.size() && tokens.get(i).kind() == TokenKind.IDENT) {
      Token token = tokens.get(i);
      if ("package".equals(token.text())) {
        i = parsePackage(tokens, i, packageName);
      } else if ("import".equals(token.text())) {
        i = parseImport(tokens, i, path, imports);
      } else {
        break;
      }
    }
    return new Header(i, packageName.toString());
  }

  /**
   * 解析 package 声明，返回分号之后的下标。
   *
   * @param tokens 记号流
   * @param start package 关键字下标
   * @param packageName 包名输出
   * @return 下一个待处理下标
   */
  private static int parsePackage(List<Token> tokens, int start, StringBuilder packageName) {
    int i = start + 1;
    while (i < tokens.size() && !";".equals(tokens.get(i).text())) {
      packageName.append(tokens.get(i).text());
      i++;
    }
    return Math.min(tokens.size(), i + 1);
  }

  /**
   * 解析 import 声明并登记，返回分号之后的下标。
   *
   * @param tokens 记号流
   * @param start import 关键字下标
   * @param path 文件路径
   * @param imports import 输出
   * @return 下一个待处理下标
   */
  private static int parseImport(
      List<Token> tokens, int start, String path, List<TypeImport> imports) {
    int line = tokens.get(start).line();
    int i = start + 1;
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
    return i;
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
      boolean ownerIsEnum,
      List<String> sourceLines,
      List<SymbolDefinition> out) {
    int i = skipLeading(tokens, start);
    if (i < 0 || i >= tokens.size() || tokens.get(i).kind() != TokenKind.IDENT) {
      return -1;
    }
    Token first = tokens.get(i);
    if (KEYWORDS.contains(first.text()) && !PRIMITIVES.contains(first.text())) {
      return -1;
    }
    int next = i + 1;
    if (next < tokens.size() && "(".equals(tokens.get(next).text())) {
      // 构造器（或 record 紧凑构造器形态外的无返回类型方法——语法上仅构造器合法）
      return namedMember(path, first, owner, ownerIsEnum, sourceLines, out, i);
    }
    if (next < tokens.size() && isFieldSeparator(tokens.get(next).text())) {
      // 裸声明符 / enum 常量
      return bareDeclaration(path, first, owner, sourceLines, out, i);
    }
    if (next < tokens.size() && "{".equals(tokens.get(next).text())) {
      int named = namedMember(path, first, owner, ownerIsEnum, sourceLines, out, i);
      if (named >= 0) {
        return named;
      }
    }
    return typedMember(path, tokens, i, owner, sourceLines, out);
  }

  /**
   * 跳过成员前导（注解、修饰符、可选泛型参数段），返回首个候选 token 下标。
   *
   * @param tokens 记号流
   * @param start 成员起点
   * @return 首个候选下标；泛型段不平衡返回 -1
   */
  private static int skipLeading(List<Token> tokens, int start) {
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
    return i;
  }

  /**
   * 名字后直接跟 {@code (} 或 {@code {} 的成员：构造器 / 带实参的 enum 常量 / record 紧凑构造器。
   *
   * @param path 文件路径
   * @param first 名字 token
   * @param owner 所属类型
   * @param ownerIsEnum 所属类型是否 enum
   * @param sourceLines 源码行
   * @param out 定义输出
   * @param index 名字下标
   * @return 名字下标；不是成员返回 -1
   */
  private static int namedMember(
      String path,
      Token first,
      String owner,
      boolean ownerIsEnum,
      List<String> sourceLines,
      List<SymbolDefinition> out,
      int index) {
    if (owner != null && owner.equals(first.text())) {
      out.add(method(path, first, owner, sourceLines));
      return index;
    }
    if (ownerIsEnum) {
      // 带实参/类体的 enum 常量（A(1) / A { ... }）：类型体内此形态只能是常量
      out.add(field(path, first, owner, sourceLines));
      return index;
    }
    return -1;
  }

  /**
   * 裸声明符 / enum 常量（名字后直接 {@code =|;|,}）。
   *
   * @param path 文件路径
   * @param first 名字 token
   * @param owner 所属类型
   * @param sourceLines 源码行
   * @param out 定义输出
   * @param index 名字下标
   * @return 名字下标；原名是类型/关键字时返回 -1
   */
  private static int bareDeclaration(
      String path,
      Token first,
      String owner,
      List<String> sourceLines,
      List<SymbolDefinition> out,
      int index) {
    if (PRIMITIVES.contains(first.text()) || KEYWORDS.contains(first.text())) {
      return -1;
    }
    out.add(field(path, first, owner, sourceLines));
    return index;
  }

  /**
   * 带返回类型的成员（类型 + 名字 + {@code (} / 字段分隔符）。
   *
   * @param path 文件路径
   * @param tokens 记号流
   * @param start 类型起点
   * @param owner 所属类型
   * @param sourceLines 源码行
   * @param out 定义输出
   * @return 名字下标；不是成员返回 -1
   */
  private static int typedMember(
      String path,
      List<Token> tokens,
      int start,
      String owner,
      List<String> sourceLines,
      List<SymbolDefinition> out) {
    int afterType = parseTypeRef(tokens, start);
    if (afterType <= start
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
    if (afterDims < tokens.size() && isFieldSeparator(tokens.get(afterDims).text())) {
      out.add(field(path, name, owner, sourceLines));
      return afterType;
    }
    return -1;
  }

  /**
   * 字段分隔符（裸声明符 / enum 常量形态）。
   *
   * @param text token 文本
   * @return true 若是 {@code =|;|,}
   */
  private static boolean isFieldSeparator(String text) {
    return "=".equals(text) || ";".equals(text) || ",".equals(text);
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
      } else if ("non".equals(text)
          && i + 2 < tokens.size()
          && "-".equals(tokens.get(i + 1).text())
          && "sealed".equals(tokens.get(i + 2).text())) {
        // non-sealed（三个 token：non - sealed）
        i += 3;
      } else {
        break;
      }
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
      String path, Token name, String owner, List<String> sourceLines) {
    return new SymbolDefinition(
        path,
        name.text(),
        SymbolKind.METHOD,
        name.line(),
        owner,
        snippet(sourceLines, name.line()));
  }

  private static SymbolDefinition field(
      String path, Token name, String owner, List<String> sourceLines) {
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
