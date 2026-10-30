package org.cra.core.internal.pipeline;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.cra.common.context.TokenMath;
import org.cra.common.finding.CandidateFinding;
import org.cra.common.finding.FindingCategory;
import org.cra.common.git.DiffFile;
import org.cra.common.git.DiffResult;
import org.cra.common.semantic.SymbolIndexSnapshot;
import org.cra.common.semantic.TypeImport;
import org.cra.core.internal.pipeline.FindingAggregator.Scored;

/**
 * bug 类候选的确定性调用点索引（ADR-0015 §2.2；跨层有界版）：核心组装期完成，无 LLM。
 *
 * <p>符号抽取 = bug 类 finding 的自由文本（evidence → title → failureMode）中出现的标识符 ∩ 该文件全文（不可得时退回 diff
 * 补丁）中出现的标识符（去停用词），按出现顺序取 ≤3 个。
 *
 * <p>候选集 = 同目录文件 + 通过 {@code import} / {@code import static} 引用变更符号所属类型的文件（有语义索引时用其 import
 * 关系；无则退回同目录并声明"调用链不完整"）。上限：候选文件 ≤60（含批次文件）、条目 ≤40、渲染 ≤1000 token； 超限与不可得都写一行声明（不静默）。
 *
 * <p>条目标注方向：{@code self} = 批次文件自身，{@code caller} = 其他文件（调用方）。
 *
 * <p>近似性如实声明：词法级（按符号名匹配，不区分重载、不做 DI/运行期装配还原）；Spring 运行期注入与代理调用无法 100% 覆盖； 索引渲染文本随代码上下文一起 UNTRUSTED
 * 定界（内容来自被评审仓库）。
 */
public final class CallSiteIndex {

  /** 抽取的符号上限。 */
  static final int MAX_SYMBOLS = 3;

  /** 候选文件上限（含批次文件自身；同目录优先，其后为 import 关联文件）。 */
  static final int MAX_FILES = 60;

  /** 同目录候选上限（为 import 关联的跨包 caller 预留容量；02§4.4/ADR-0015 §2.2 修订）。 */
  static final int SAME_DIR_MAX = 30;

  /** 条目上限。 */
  static final int MAX_ENTRIES = 40;

  /** 渲染 token 上限（ADR-0015：从 codeContext 预算内预留；700 → 1000，跨层 caller 证据需要更多行）。 */
  public static final long RENDER_TOKEN_CAP = 1_000;

  /** 方向：批次文件自身。 */
  public static final String DIRECTION_SELF = "self";

  /** 方向：其他文件（调用方）。 */
  public static final String DIRECTION_CALLER = "caller";

  /** 过滤掉的通用词（Java 关键字/常见类型名/字面量，无鉴别力）。 */
  private static final Set<String> STOP_WORDS =
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
          "delete",
          "do",
          "double",
          "else",
          "enum",
          "extends",
          "false",
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
          "null",
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
          "true",
          "try",
          "void",
          "volatile",
          "while",
          "string",
          "object",
          "integer",
          "override",
          "equals",
          "hashcode",
          "tostring",
          "get",
          "set",
          "add",
          "size",
          "value",
          "values",
          "result",
          "args",
          "arg",
          "var",
          "len",
          "length",
          "index",
          "count",
          "item",
          "items",
          "list",
          "map",
          "node",
          "name",
          "type",
          "data",
          "code",
          "line",
          "file",
          "path",
          "nullptr");

  private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]{3,}");

  private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.$]+)\\s*;");

  private static final Pattern TYPE_DEFINITION =
      Pattern.compile("\\b(?:class|interface|enum|record)\\s+([A-Za-z_$][A-Za-z0-9_$]*)");

  private final boolean available;
  private final String reason;
  private final List<String> symbols;
  private final List<Entry> entries;
  private final int filesScanned;
  private final int candidateFiles;
  private final int callerCandidates;
  private final boolean importsAvailable;
  private final List<String> notes;

  private CallSiteIndex(
      boolean available,
      String reason,
      List<String> symbols,
      List<Entry> entries,
      int filesScanned,
      int candidateFiles,
      int callerCandidates,
      boolean importsAvailable,
      List<String> notes) {
    this.available = available;
    this.reason = reason;
    this.symbols = List.copyOf(symbols);
    this.entries = List.copyOf(entries);
    this.filesScanned = filesScanned;
    this.candidateFiles = candidateFiles;
    this.callerCandidates = callerCandidates;
    this.importsAvailable = importsAvailable;
    this.notes = List.copyOf(notes);
  }

  /**
   * 单条调用点。
   *
   * @param path 文件路径
   * @param line 1 基行号
   * @param snippet 行摘录（≤120 字符）
   * @param direction {@link #DIRECTION_SELF} 或 {@link #DIRECTION_CALLER}
   */
  public record Entry(String path, int line, String snippet, String direction) {}

  /** 索引不可得（如实声明原因）。 */
  public static CallSiteIndex unavailable(String reason) {
    return new CallSiteIndex(
        false,
        reason == null ? "unknown" : reason,
        List.of(),
        List.of(),
        0,
        0,
        0,
        false,
        List.of());
  }

  public boolean available() {
    return available;
  }

  public String reason() {
    return reason;
  }

  public List<String> symbols() {
    return symbols;
  }

  public List<Entry> entries() {
    return entries;
  }

  /** 实际读取内容并扫描的候选文件数（≤ {@link #candidateFiles()}；内容不可得的候选不计入）。 */
  public int filesScanned() {
    return filesScanned;
  }

  /** 候选文件数（截断前）。 */
  /** 纳入本轮扫描计划的候选文件数（含 self；上限 {@value #MAX_FILES}，记录截断前数值）。 */
  public int candidateFiles() {
    return candidateFiles;
  }

  /** 跨包 caller 候选数（import 关联；同目录之外的候选）。 */
  public int callerCandidates() {
    return callerCandidates;
  }

  /** import 关系是否可得（false = 只扫描同目录，调用链不完整）。 */
  public boolean importsAvailable() {
    return importsAvailable;
  }

  /** 声明行（截断/近似性/调用链完整性；确定性顺序）。 */
  public List<String> notes() {
    return notes;
  }

  /**
   * 构建索引（仅同目录候选；兼容形态）。
   *
   * @param batchFile 批次文件路径
   * @param batch 该批候选
   * @param diff 评审范围 diff（符号限定来源）
   * @param javaFiles head 侧全部 Java 文件路径（空 = 仓库树不可得）
   * @param reader 文件读取（path → 全文；不可得为 empty）
   * @return 索引（不可得时如实声明，不抛异常）
   */
  public static CallSiteIndex build(
      String batchFile,
      List<Scored> batch,
      DiffResult diff,
      List<String> javaFiles,
      Function<String, Optional<String>> reader) {
    return build(batchFile, batch, diff, javaFiles, reader, null);
  }

  /**
   * 构建索引（同目录 + import 关联的跨包 caller）。
   *
   * @param batchFile 批次文件路径
   * @param batch 该批候选
   * @param diff 评审范围 diff（符号限定来源）
   * @param javaFiles head 侧全部 Java 文件路径（空 = 仓库树不可得）
   * @param reader 文件读取（path → 全文；不可得为 empty）
   * @param importsIndex 语义索引快照（提供 import 关系；null/不可得 = 退回同目录并声明）
   * @return 索引（不可得时如实声明，不抛异常）
   */
  public static CallSiteIndex build(
      String batchFile,
      List<Scored> batch,
      DiffResult diff,
      List<String> javaFiles,
      Function<String, Optional<String>> reader,
      SymbolIndexSnapshot importsIndex) {
    if (javaFiles == null || javaFiles.isEmpty()) {
      return unavailable("repo-tree-unavailable");
    }
    String batchFileText = reader.apply(batchFile).orElse(patchOf(diff, batchFile));
    List<String> symbols = extractSymbols(batchFileText, batch);
    if (symbols.isEmpty()) {
      return unavailable("no-symbols");
    }
    boolean importsAvailable = importsIndex != null && importsIndex.available();
    String dir = parentDir(batchFile);
    List<String> candidates = new ArrayList<>();
    candidates.add(batchFile);
    List<String> peers = new ArrayList<>();
    for (String path : javaFiles) {
      if (path == null || path.equals(batchFile) || !parentDir(path).equals(dir)) {
        continue;
      }
      peers.add(path);
    }
    peers.sort(String::compareTo);
    int sameDirDropped = 0;
    for (String peer : peers) {
      if (candidates.size() - 1 >= SAME_DIR_MAX || candidates.size() >= MAX_FILES) {
        sameDirDropped++;
        continue;
      }
      candidates.add(peer);
    }
    List<String> notes = new ArrayList<>();
    if (sameDirDropped > 0) {
      // 同目录不宜吃满候选槽：跨层 caller（service/controller）通常在别的包，必须预留容量
      notes.add(
          "同目录候选上限 " + SAME_DIR_MAX + "：另有 " + sameDirDropped + " 个同目录文件未扫描（为跨包 caller 预留容量）");
    }
    int callerCandidates = 0;
    if (importsAvailable) {
      List<String> callers =
          importCallers(batchFile, batchFileText, importsIndex, Set.copyOf(candidates));
      callerCandidates = callers.size();
      int dropped = 0;
      for (String caller : callers) {
        if (candidates.size() >= MAX_FILES) {
          dropped++;
          continue;
        }
        candidates.add(caller);
      }
      if (dropped > 0) {
        notes.add("候选文件上限 " + MAX_FILES + "：另有 " + dropped + " 个 import 关联文件未扫描（按路径升序截断）");
      }
    } else {
      notes.add("调用链不完整：语义索引不可得（仅扫描同目录），跨包 caller 未纳入");
    }
    List<Entry> entries = new ArrayList<>();
    int scanned = 0;
    boolean entryTruncated = false;
    for (String path : candidates) {
      Optional<String> content = reader.apply(path);
      scanned++;
      if (content.isEmpty()) {
        continue;
      }
      String direction = path.equals(batchFile) ? DIRECTION_SELF : DIRECTION_CALLER;
      String[] lines = content.get().split("\n", -1);
      for (int i = 0; i < lines.length; i++) {
        if (containsSymbol(lines[i], symbols)) {
          entries.add(new Entry(path, i + 1, snippet(lines[i]), direction));
          if (entries.size() >= MAX_ENTRIES) {
            entryTruncated = true;
            break;
          }
        }
      }
      if (entryTruncated) {
        break;
      }
    }
    if (entryTruncated) {
      notes.add("条目上限 " + MAX_ENTRIES + "：命中已截断（只显示前 " + MAX_ENTRIES + " 条）");
    }
    return new CallSiteIndex(
        true,
        "",
        symbols,
        entries,
        scanned,
        candidates.size(),
        callerCandidates,
        importsAvailable,
        notes);
  }

  /**
   * import 关联的候选文件（跨包 caller）：{@code import pkg.Type} 解析到批次文件、{@code import static pkg.Type.member}
   * 按前缀命中、{@code import pkg.*} 按包名命中（较弱证据，仍需逐行匹配符号）。
   *
   * @return 去重后的候选路径（按路径升序；不含批次文件与已入选的同目录文件）
   */
  /**
   * import 关联 caller（词法启发式，ADR-0014 近似性）：精确解析（{@code resolvedInRepo}）、静态成员导入（包内类型前缀）、 通配导入（{@code
   * pkg.*}）。不做类型绑定：同名成员跨类型/运行期 DI 装配不在覆盖范围，渲染端已声明近似性。
   */
  private static List<String> importCallers(
      String batchFile, String batchFileText, SymbolIndexSnapshot index, Set<String> excluded) {
    String packageName = packageOf(batchFileText);
    Set<String> types = new TreeSet<>(typesOf(batchFileText));
    Set<String> callers = new TreeSet<>();
    for (TypeImport entry : index.imports()) {
      String path = entry.path();
      if (path == null || path.equals(batchFile) || excluded.contains(path)) {
        continue;
      }
      if (entry.resolvedInRepo() && batchFile.equals(entry.resolvedPath())) {
        callers.add(path);
        continue;
      }
      if (entry.staticImport()) {
        String imported = entry.imported();
        for (String type : types) {
          String prefix = packageName.isBlank() ? type + "." : packageName + "." + type + ".";
          if (imported != null && imported.startsWith(prefix)) {
            callers.add(path);
            break;
          }
        }
        continue;
      }
      if (!packageName.isBlank() && (packageName + ".*").equals(entry.imported())) {
        callers.add(path);
      }
    }
    List<String> ordered = new ArrayList<>(callers);
    ordered.sort(String::compareTo);
    return ordered;
  }

  /**
   * 渲染 brief 段（≤{@value #RENDER_TOKEN_CAP} token；不可得时给出如实声明行）。
   *
   * @return 渲染文本（非空）
   */
  public String render() {
    return render(RENDER_TOKEN_CAP);
  }

  /**
   * 渲染 brief 段（指定 token 上限；Judge 案件用更小配额）。
   *
   * @param tokenCap 渲染上限
   * @return 渲染文本（非空）
   */
  public String render(long tokenCap) {
    if (tokenCap < MIN_RENDER_CAP) {
      // 严格上限语义：调用方给多小就注入多小（只留声明行），不得用内部下限顶回调用方预算
      return "## 受影响符号的调用点索引（确定性预取；调用链不完整，未列出不代表不存在调用者）\n（渲染预算不足（"
          + tokenCap
          + " < "
          + MIN_RENDER_CAP
          + "），条目未注入；如需调用方证据可用 followup_file 请求具体文件）\n";
    }
    long cap = tokenCap;
    StringBuilder sb = new StringBuilder();
    sb.append("## 受影响符号的调用点索引（确定性预取；调用链不完整，未列出不代表不存在调用者）\n");
    if (!available) {
      sb.append("（索引不可得：").append(reason).append("；如需调用方/测试证据，可用 followup_file 请求具体文件）\n");
      return sb.toString();
    }
    sb.append("符号：");
    for (int i = 0; i < symbols.size(); i++) {
      if (i > 0) {
        sb.append(", ");
      }
      sb.append('`').append(symbols.get(i)).append('`');
    }
    sb.append("；扫描候选文件 ").append(filesScanned).append(" 个");
    sb.append(importsAvailable ? "（含 import 关联）" : "（同目录）");
    sb.append("；命中 ").append(entries.size()).append(" 条\n");
    sb.append("近似性：词法级符号匹配（不区分重载；不做 DI/运行期装配还原，Spring 注入调用无法 100% 覆盖）\n");
    for (String note : notes) {
      sb.append("（").append(note).append("）\n");
    }
    if (entries.isEmpty()) {
      sb.append("（可见范围内未发现调用点——不能据此推断调用方行为）\n");
    }
    long used = TokenMath.estimateTokens(sb.toString());
    for (Entry entry : entries) {
      String line =
          "- ["
              + entry.direction()
              + "] "
              + entry.path()
              + ":"
              + entry.line()
              + "  "
              + entry.snippet()
              + "\n";
      long cost = TokenMath.estimateTokens(line) + 1;
      if (used + cost > cap) {
        sb.append("…（超预算截断）\n");
        break;
      }
      sb.append(line);
      used += cost;
    }
    return sb.toString();
  }

  /** 渲染下限（低于此值只剩标题，按此值兜底）。 */
  private static final long MIN_RENDER_CAP = 200;

  // ---------- 抽取 ----------

  /** 符号 = bug 类 finding 文本中的标识符 ∩ 被改动文件文本（退回 diff 补丁）中的标识符（保持出现顺序，≤3）。 */
  private static List<String> extractSymbols(String fileText, List<Scored> batch) {
    if (fileText == null || fileText.isBlank()) {
      return List.of();
    }
    Set<String> inFile = new LinkedHashSet<>();
    Matcher pm = IDENTIFIER.matcher(fileText);
    while (pm.find()) {
      inFile.add(pm.group());
    }
    LinkedHashSet<String> symbols = new LinkedHashSet<>();
    for (Scored scored : batch) {
      CandidateFinding finding = scored.finding();
      if (finding.category() != FindingCategory.BUG) {
        continue;
      }
      for (String text : List.of(finding.evidence(), finding.title(), finding.failureMode())) {
        if (text == null) {
          continue;
        }
        Matcher matcher = IDENTIFIER.matcher(text);
        while (matcher.find() && symbols.size() < MAX_SYMBOLS) {
          String token = matcher.group();
          if (STOP_WORDS.contains(token.toLowerCase(Locale.ROOT)) || token.length() < 4) {
            continue;
          }
          if (inFile.contains(token)) {
            symbols.add(token);
          }
        }
      }
      if (symbols.size() >= MAX_SYMBOLS) {
        break;
      }
    }
    return new ArrayList<>(symbols);
  }

  /** 批次文件的 package 声明（无 package 返回空串）。 */
  static String packageOf(String fileText) {
    if (fileText == null) {
      return "";
    }
    Matcher matcher = PACKAGE.matcher(fileText);
    return matcher.find() ? matcher.group(1) : "";
  }

  /** 批次文件中定义的类型名（按出现顺序去重；用于 static/wildcard import 匹配与声明）。 */
  static List<String> typesOf(String fileText) {
    if (fileText == null || fileText.isBlank()) {
      return List.of();
    }
    LinkedHashSet<String> types = new LinkedHashSet<>();
    Matcher matcher = TYPE_DEFINITION.matcher(fileText);
    while (matcher.find()) {
      types.add(matcher.group(1));
    }
    return new ArrayList<>(types);
  }

  private static String patchOf(DiffResult diff, String file) {
    if (diff == null || file == null || file.isEmpty()) {
      return "";
    }
    for (DiffFile diffFile : diff.files()) {
      if (diffFile.path().equals(file) && diffFile.patch() != null) {
        return diffFile.patch();
      }
    }
    return "";
  }

  private static boolean containsSymbol(String line, List<String> symbols) {
    for (String symbol : symbols) {
      int from = 0;
      while (true) {
        int at = line.indexOf(symbol, from);
        if (at < 0) {
          break;
        }
        boolean leftOk = at == 0 || !isIdentifierChar(line.charAt(at - 1));
        int end = at + symbol.length();
        boolean rightOk = end >= line.length() || !isIdentifierChar(line.charAt(end));
        if (leftOk && rightOk) {
          return true;
        }
        from = at + 1;
      }
    }
    return false;
  }

  private static boolean isIdentifierChar(char c) {
    return Character.isLetterOrDigit(c) || c == '_' || c == '$';
  }

  private static String snippet(String line) {
    String trimmed = line.strip();
    return trimmed.length() <= 120 ? trimmed : trimmed.substring(0, 120) + "…";
  }

  private static String parentDir(String path) {
    int slash = path.lastIndexOf('/');
    return slash < 0 ? "" : path.substring(0, slash);
  }
}
