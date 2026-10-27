package org.cra.core.internal.pipeline;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.cra.common.git.DiffFile;
import org.cra.common.semantic.SymbolDefinition;
import org.cra.common.semantic.SymbolIndexSnapshot;
import org.cra.common.semantic.SymbolKind;

/**
 * 变更符号抽取（ADR-0014 决策 4）：从 diff 新增行 + 索引定义表推导"这次改动碰了哪些符号"。
 *
 * <p>两级优先序，确定性排序（tier → 出现次数降序 → 名字升序）：
 *
 * <ol>
 *   <li><b>Tier A（定义变更）</b>：新增行里出现的、且定义就在本 PR 变更文件内的符号名；以及新增行所在的最内层定义（方法体改动而签名未变时，
 *       仍定位到方法符号）——这两类直接决定"谁在调用我"，召回优先级最高；
 *   <li><b>Tier B（引用变更）</b>：新增行里出现的、定义在仓库其他文件的符号名，且全仓出现 ≥2 次（一次出现多为局部变量/参数， 排除局部噪声）；此级过滤 STOPLIST
 *       的高频通用名（{@code get/size/toString} 等），避免切片被通用方法名淹没。
 * </ol>
 *
 * <p>显式排除：Java 关键字/字面量、JDK 常见类型名（{@link #STDLIB_TYPES}）、长度 &lt; 3 的标识符。命中索引才是候选—— 未定义的标识符（局部变量、第三方
 * API）不产生切片（不从猜测试图生成上下文）。
 */
final class ChangedSymbols {

  /** 单次切片最多考虑的符号数（04§2.2 预算：每符号 ≤3 文件 ≤6 引用位置）。 */
  static final int MAX_SYMBOLS = 8;

  /** 单文件新增行处理上限（防超大 diff 拖慢确定性抽取）。 */
  private static final int MAX_ADDED_LINES_PER_FILE = 4_000;

  private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");

  /** JDK 常见类型（变更行里出现不代表本仓符号；索引里同名定义仍可命中，本集合只做兜底排除）。 */
  private static final Set<String> STDLIB_TYPES =
      Set.of(
          "String",
          "Object",
          "Integer",
          "Long",
          "Double",
          "Float",
          "Boolean",
          "Byte",
          "Short",
          "Character",
          "Number",
          "Math",
          "System",
          "Thread",
          "Class",
          "Enum",
          "Record",
          "Void",
          "Exception",
          "RuntimeException",
          "Throwable",
          "Error",
          "IllegalArgumentException",
          "IllegalStateException",
          "UnsupportedOperationException",
          "NullPointerException",
          "List",
          "ArrayList",
          "LinkedList",
          "Map",
          "HashMap",
          "LinkedHashMap",
          "TreeMap",
          "Set",
          "HashSet",
          "LinkedHashSet",
          "TreeSet",
          "Collection",
          "Collections",
          "Arrays",
          "Optional",
          "Stream",
          "Collectors",
          "Objects",
          "Comparator",
          "Iterator",
          "Iterable",
          "Duration",
          "Instant",
          "LocalDate",
          "LocalDateTime",
          "Clock",
          "ZoneId",
          "BigDecimal",
          "BigInteger",
          "Pattern",
          "Matcher",
          "Path",
          "Paths",
          "Files",
          "File",
          "IOException",
          "UUID",
          "Date",
          "Calendar",
          "StringBuilder",
          "StringBuffer",
          "CharSequence",
          "Runnable",
          "Callable",
          "Supplier",
          "Function",
          "Consumer",
          "Predicate",
          "BiFunction",
          "UnaryOperator",
          "CompletableFuture",
          "ExecutorService",
          "Executors",
          "Future",
          "TimeUnit",
          "AtomicInteger",
          "AtomicLong",
          "AtomicBoolean",
          "ConcurrentHashMap",
          "ReentrantLock",
          "CountDownLatch",
          "Semaphore",
          "Logger",
          "LoggerFactory",
          "Override");

  /** 高频通用名（Tier B 排除；Tier A 保留——定义在本 PR 变更文件里的符号就是主证据）。 */
  private static final Set<String> STOPLIST =
      Set.of(
          "toString",
          "equals",
          "hashCode",
          "get",
          "set",
          "put",
          "add",
          "remove",
          "size",
          "length",
          "isEmpty",
          "isPresent",
          "orElse",
          "orElseThrow",
          "map",
          "filter",
          "collect",
          "stream",
          "forEach",
          "valueOf",
          "format",
          "append",
          "build",
          "create",
          "init",
          "main",
          "run",
          "call",
          "apply",
          "accept",
          "test",
          "compare",
          "compareTo",
          "iterator",
          "next",
          "hasNext",
          "close",
          "start",
          "stop",
          "read",
          "write",
          "send",
          "parse",
          "of",
          "to",
          "from",
          "name",
          "value",
          "values",
          "keys",
          "entrySet",
          "keySet",
          "contains",
          "containsKey",
          "containsValue",
          "getOrDefault",
          "computeIfAbsent",
          "addAll",
          "putAll",
          "removeAll",
          "subList",
          "indexOf",
          "charAt",
          "substring",
          "trim",
          "strip",
          "split",
          "join",
          "println",
          "printf",
          "log",
          "info",
          "debug",
          "warn",
          "error",
          "trace",
          "assertThat",
          "isEqualTo",
          "isNotNull",
          "assertEquals",
          "verify",
          "when",
          "thenReturn");

  private ChangedSymbols() {}

  /**
   * 单个变更符号候选。
   *
   * @param name 符号名
   * @param tier 0 = 变更文件内定义；1 = 其他文件定义
   * @param occurrences 全仓出现次数（排序用）
   */
  record Symbol(String name, int tier, int occurrences) {}

  /**
   * 抽取变更符号（确定性：同输入同输出序）。
   *
   * @param files 评审范围文件（chunk 或单文件；用于解析新增行与"变更文件内定义"判定）
   * @param index 符号索引快照（必须 available，调用方保证）
   * @param maxSymbols 符号数上限
   * @return 符号列表（已按优先级排序）
   */
  static List<Symbol> extract(List<DiffFile> files, SymbolIndexSnapshot index, int maxSymbols) {
    if (files == null || files.isEmpty() || index == null || !index.available()) {
      return List.of();
    }
    Set<String> changedPaths = new LinkedHashSet<>();
    for (DiffFile file : files) {
      changedPaths.add(file.path());
    }
    Map<String, Integer> bestTier = new LinkedHashMap<>();
    Map<String, Integer> occurrences = new LinkedHashMap<>();
    for (DiffFile file : files) {
      Map<Integer, String> addedLines = addedLines(file);
      if (addedLines.isEmpty()) {
        continue;
      }
      Map<Integer, SymbolDefinition> enclosing =
          enclosingDefinitions(addedLines.keySet(), index.definitionsOfPath(file.path()));
      for (Map.Entry<Integer, String> entry : addedLines.entrySet()) {
        for (String identifier : identifiersOf(entry.getValue())) {
          if (!isCandidateName(identifier)) {
            continue;
          }
          List<SymbolDefinition> definitions = index.definitionsOf(identifier);
          if (definitions.isEmpty()) {
            continue;
          }
          boolean definedHere =
              definitions.stream().anyMatch(definition -> changedPaths.contains(definition.path()));
          int occurrencesOfName = index.occurrencesOf(identifier);
          if (definedHere) {
            recordCandidate(bestTier, occurrences, identifier, 0, occurrencesOfName);
          } else if (!STOPLIST.contains(identifier) && occurrencesOfName >= 2) {
            recordCandidate(bestTier, occurrences, identifier, 1, occurrencesOfName);
          }
        }
        SymbolDefinition enclosingDefinition = enclosing.get(entry.getKey());
        if (enclosingDefinition != null && isCandidateName(enclosingDefinition.name())) {
          recordCandidate(
              bestTier,
              occurrences,
              enclosingDefinition.name(),
              0,
              Math.max(1, index.occurrencesOf(enclosingDefinition.name())));
        }
      }
    }
    List<Symbol> symbols = new ArrayList<>();
    for (Map.Entry<String, Integer> entry : bestTier.entrySet()) {
      symbols.add(
          new Symbol(
              entry.getKey(), entry.getValue(), occurrences.getOrDefault(entry.getKey(), 0)));
    }
    symbols.sort(
        Comparator.comparingInt(Symbol::tier)
            .thenComparing(Comparator.comparingInt(Symbol::occurrences).reversed())
            .thenComparing(Symbol::name));
    return List.copyOf(symbols.subList(0, Math.min(maxSymbols, symbols.size())));
  }

  private static void recordCandidate(
      Map<String, Integer> bestTier,
      Map<String, Integer> occurrences,
      String name,
      int tier,
      int occurrencesOfName) {
    bestTier.merge(name, tier, Math::min);
    occurrences.merge(name, occurrencesOfName, Math::max);
  }

  private static boolean isCandidateName(String name) {
    return name != null
        && name.length() >= 3
        && !STDLIB_TYPES.contains(name)
        && !STOPLIST.contains(name);
  }

  /** 新增行的最内层定义：对本文件每个新增行号，取定义行 ≤ 该行的最后一个方法/字段（方法体改动的符号归属）。 类型定义不参与（类名由"新增行出现同名定义"路径覆盖）。 */
  private static Map<Integer, SymbolDefinition> enclosingDefinitions(
      Set<Integer> addedLines, List<SymbolDefinition> definitionsInFile) {
    Map<Integer, SymbolDefinition> enclosing = new LinkedHashMap<>();
    List<SymbolDefinition> members =
        definitionsInFile.stream()
            .filter(definition -> definition.kind() != SymbolKind.TYPE)
            .sorted(Comparator.comparingInt(SymbolDefinition::line))
            .toList();
    if (members.isEmpty()) {
      return enclosing;
    }
    for (int line : addedLines) {
      SymbolDefinition best = null;
      for (SymbolDefinition member : members) {
        if (member.line() <= line) {
          best = member;
        } else {
          break;
        }
      }
      if (best != null) {
        enclosing.put(line, best);
      }
    }
    return enclosing;
  }

  /** 新增行（head 侧行号 → 行文本）。按 patch 逐行推进，{@code +} 行计数、{@code -} 行不计。 */
  private static Map<Integer, String> addedLines(DiffFile file) {
    Map<Integer, String> added = new LinkedHashMap<>();
    String patch = file.patch();
    if (patch == null || patch.isBlank()) {
      return added;
    }
    int newLine = 0;
    for (String raw : patch.split("\n", -1)) {
      if (raw.startsWith("@@")) {
        newLine = parseNewStart(raw);
        continue;
      }
      if (raw.startsWith("+++") || raw.startsWith("---") || raw.startsWith("\\")) {
        continue;
      }
      if (raw.startsWith("+")) {
        if (newLine > 0 && added.size() < MAX_ADDED_LINES_PER_FILE) {
          added.put(newLine, raw.substring(1));
        }
        newLine++;
      } else if (raw.startsWith(" ")) {
        newLine++;
      }
    }
    return added;
  }

  /** {@code @@ -a,b +c,d @@} → c（解析失败返回 0，该 hunk 的增行不参与抽取）。 */
  private static int parseNewStart(String hunkHeader) {
    int plus = hunkHeader.indexOf('+');
    if (plus < 0) {
      return 0;
    }
    int end = plus + 1;
    while (end < hunkHeader.length() && Character.isDigit(hunkHeader.charAt(end))) {
      end++;
    }
    if (end == plus + 1) {
      return 0;
    }
    try {
      return Integer.parseInt(hunkHeader.substring(plus + 1, end));
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  private static List<String> identifiersOf(String line) {
    if (line == null || line.isBlank()) {
      return List.of();
    }
    String stripped = stripLiteralsAndComments(line);
    List<String> names = new ArrayList<>();
    Matcher matcher = IDENTIFIER.matcher(stripped);
    while (matcher.find()) {
      names.add(matcher.group());
    }
    return names;
  }

  /** 单行内剥离字符串/字符字面量与行注释（多行字面量的残余行按无标识符处理）。 */
  private static String stripLiteralsAndComments(String line) {
    StringBuilder out = new StringBuilder(line.length());
    int i = 0;
    int n = line.length();
    while (i < n) {
      char c = line.charAt(i);
      if (c == '/' && i + 1 < n && line.charAt(i + 1) == '/') {
        break;
      }
      if (c == '"' || c == '\'') {
        char quote = c;
        i++;
        while (i < n) {
          char ch = line.charAt(i);
          if (ch == '\\') {
            i += 2;
            continue;
          }
          if (ch == quote) {
            i++;
            break;
          }
          i++;
        }
        out.append(' ');
        continue;
      }
      out.append(c);
      i++;
    }
    return out.toString();
  }
}
