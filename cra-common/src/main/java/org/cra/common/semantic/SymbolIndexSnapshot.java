package org.cra.common.semantic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 语义索引快照（确定性符号索引；ADR-0014）。
 *
 * <p>生成方 = {@code cra-git} 的 {@code SymbolIndex} 实现（jgit 树读取 + Java 词法/结构抽取）；消费方 = cra-core
 * 的跨文件切片组装。 快照 不可变，列表按确定性顺序排序（定义按 path/line，引用按 name，import 按 path/imported，文件统计按 path）， 同 head +
 * 同选项必须逐字段相等。
 *
 * <p>"不可得"是合法形态（11§4 第 5 条）：无本地仓库 / head 不可解析 / 构建异常 / 超限策略为 {@link
 * SymbolIndexOptions.OverLimitPolicy#UNAVAILABLE} 时返回 {@link #unavailable(String, String)}——
 * 调用方降级为覆盖声明，不抛异常不炸 run。
 */
public final class SymbolIndexSnapshot {

  private static final Comparator<SymbolDefinition> DEFINITION_ORDER =
      Comparator.comparing(SymbolDefinition::path)
          .thenComparingInt(SymbolDefinition::line)
          .thenComparing(SymbolDefinition::name);

  private final boolean available;
  private final String headSha;
  private final String reason;
  private final int filesScanned;
  private final int filesSkipped;
  private final boolean truncated;
  private final List<String> notes;
  private final List<SymbolDefinition> definitions;
  private final List<SymbolReferences> references;
  private final List<TypeImport> imports;
  private final List<FileReferenceStats> fileStats;
  private final Map<String, List<SymbolDefinition>> definitionsByName;
  private final Map<String, List<SymbolDefinition>> definitionsByPath;
  private final Map<String, List<SymbolReferenceSite>> referencesByName;
  private final Map<String, Integer> occurrencesByName;
  private final Map<String, List<TypeImport>> importsByPath;

  /**
   * 构造快照（调用方保证各列表内容确定；本类按固定顺序重排副本）。
   *
   * @param available 是否可用（false = 不可得，查询按空结果处理）
   * @param headSha 索引锚定 commit（不可得时记录请求的 sha）
   * @param reason 不可得原因（可用时为空串）
   * @param filesScanned 实际扫描的 Java 文件数
   * @param filesSkipped 跳过文件数（超长/二进制/读取失败——原因见 notes）
   * @param truncated 是否发生截断（maxFiles 或位置上限之外的裁剪；notes 有明细）
   * @param notes 覆盖声明（Java-only、跳过/截断事实、构建提示；确定性顺序）
   * @param definitions 符号定义
   * @param references 符号引用统计
   * @param imports import 关系
   * @param fileStats 文件级引用统计
   */
  public SymbolIndexSnapshot(
      boolean available,
      String headSha,
      String reason,
      int filesScanned,
      int filesSkipped,
      boolean truncated,
      List<String> notes,
      List<SymbolDefinition> definitions,
      List<SymbolReferences> references,
      List<TypeImport> imports,
      List<FileReferenceStats> fileStats) {
    this.available = available;
    this.headSha = headSha == null ? "" : headSha;
    this.reason = reason == null ? "" : reason;
    this.filesScanned = Math.max(0, filesScanned);
    this.filesSkipped = Math.max(0, filesSkipped);
    this.truncated = truncated;
    this.notes = List.copyOf(notes == null ? List.of() : notes);

    List<SymbolDefinition> sortedDefinitions =
        new ArrayList<>(definitions == null ? List.of() : definitions);
    sortedDefinitions.sort(DEFINITION_ORDER);
    this.definitions = List.copyOf(sortedDefinitions);

    List<SymbolReferences> sortedReferences =
        new ArrayList<>(references == null ? List.of() : references);
    sortedReferences.sort(Comparator.comparing(SymbolReferences::name));
    this.references = List.copyOf(sortedReferences);

    List<TypeImport> sortedImports = new ArrayList<>(imports == null ? List.of() : imports);
    sortedImports.sort(
        Comparator.comparing(TypeImport::path)
            .thenComparingInt(TypeImport::line)
            .thenComparing(TypeImport::imported));
    this.imports = List.copyOf(sortedImports);

    List<FileReferenceStats> sortedStats =
        new ArrayList<>(fileStats == null ? List.of() : fileStats);
    sortedStats.sort(Comparator.comparing(FileReferenceStats::path));
    this.fileStats = List.copyOf(sortedStats);

    Map<String, List<SymbolDefinition>> byName = new LinkedHashMap<>();
    for (SymbolDefinition definition : this.definitions) {
      byName.computeIfAbsent(definition.name(), key -> new ArrayList<>()).add(definition);
    }
    byName.replaceAll((key, value) -> List.copyOf(value));
    this.definitionsByName = Map.copyOf(byName);

    Map<String, List<SymbolDefinition>> byPath = new LinkedHashMap<>();
    for (SymbolDefinition definition : this.definitions) {
      byPath.computeIfAbsent(definition.path(), key -> new ArrayList<>()).add(definition);
    }
    byPath.replaceAll((key, value) -> List.copyOf(value));
    this.definitionsByPath = Map.copyOf(byPath);

    Map<String, List<SymbolReferenceSite>> sitesByName = new LinkedHashMap<>();
    Map<String, Integer> countsByName = new LinkedHashMap<>();
    for (SymbolReferences entry : this.references) {
      sitesByName.put(entry.name(), entry.sites());
      countsByName.put(entry.name(), entry.occurrences());
    }
    this.referencesByName = Map.copyOf(sitesByName);
    this.occurrencesByName = Map.copyOf(countsByName);

    Map<String, List<TypeImport>> importsOf = new LinkedHashMap<>();
    for (TypeImport entry : this.imports) {
      importsOf.computeIfAbsent(entry.path(), key -> new ArrayList<>()).add(entry);
    }
    importsOf.replaceAll((key, value) -> List.copyOf(value));
    this.importsByPath = Map.copyOf(importsOf);
  }

  /**
   * 不可得快照（无本地仓库 / 构建失败 / 超限即不可得）。
   *
   * @param headSha 请求的锚点 commit（可空）
   * @param reason 不可得原因（进覆盖声明）
   * @return 不可得快照
   */
  public static SymbolIndexSnapshot unavailable(String headSha, String reason) {
    return new SymbolIndexSnapshot(
        false, headSha, reason, 0, 0, false, List.of(), List.of(), List.of(), List.of(), List.of());
  }

  /**
   * 是否可用。
   *
   * @return true 若索引构建成功
   */
  public boolean available() {
    return available;
  }

  /**
   * 索引锚定 commit。
   *
   * @return 完整 sha（不可得时为空或请求值）
   */
  public String headSha() {
    return headSha;
  }

  /**
   * 不可得原因。
   *
   * @return 原因文本；可用时为空串
   */
  public String reason() {
    return reason;
  }

  /**
   * 实际扫描的 Java 文件数。
   *
   * @return 文件数
   */
  public int filesScanned() {
    return filesScanned;
  }

  /**
   * 跳过文件数。
   *
   * @return 文件数
   */
  public int filesSkipped() {
    return filesSkipped;
  }

  /**
   * 是否发生截断。
   *
   * @return true 若 maxFiles / 位置上限触发了裁剪（明细在 notes）
   */
  public boolean truncated() {
    return truncated;
  }

  /**
   * 覆盖声明（确定性顺序）。
   *
   * @return 提示列表（不可得时含原因）
   */
  public List<String> notes() {
    return notes;
  }

  /**
   * 全部符号定义（path/line 升序）。
   *
   * @return 定义列表
   */
  public List<SymbolDefinition> definitions() {
    return definitions;
  }

  /**
   * 全部符号引用统计（name 升序）。
   *
   * @return 引用统计
   */
  public List<SymbolReferences> references() {
    return references;
  }

  /**
   * 全部 import 关系（path/line 升序）。
   *
   * @return import 列表
   */
  public List<TypeImport> imports() {
    return imports;
  }

  /**
   * 文件级引用统计（path 升序）。
   *
   * @return 文件统计
   */
  public List<FileReferenceStats> fileStats() {
    return fileStats;
  }

  /**
   * 按符号名查定义（同名多处全部返回，path/line 升序）。
   *
   * @param name 符号名
   * @return 定义列表（无命中为空列表）
   */
  public List<SymbolDefinition> definitionsOf(String name) {
    if (name == null || name.isBlank()) {
      return List.of();
    }
    return definitionsByName.getOrDefault(name, List.of());
  }

  /**
   * 按符号名查引用位置（path/line 升序、有上限）。
   *
   * @param name 符号名
   * @return 引用位置（未索引的名为空列表）
   */
  public List<SymbolReferenceSite> referencesOf(String name) {
    if (name == null || name.isBlank()) {
      return List.of();
    }
    return referencesByName.getOrDefault(name, List.of());
  }

  /**
   * 按文件查本文件内定义（path/line 升序）。
   *
   * @param path 文件路径
   * @return 定义列表（无定义为空列表）
   */
  public List<SymbolDefinition> definitionsOfPath(String path) {
    if (path == null || path.isBlank()) {
      return List.of();
    }
    return definitionsByPath.getOrDefault(path, List.of());
  }

  /**
   * 按文件查 import 关系。
   *
   * @param path 文件路径
   * @return import 列表（无命中为空列表）
   */
  public List<TypeImport> importsOf(String path) {
    if (path == null || path.isBlank()) {
      return List.of();
    }
    return importsByPath.getOrDefault(path, List.of());
  }

  /**
   * 符号名的全仓出现次数（含定义行自身）。
   *
   * @param name 符号名
   * @return 次数（未索引的名为 0）
   */
  public int occurrencesOf(String name) {
    if (name == null || name.isBlank()) {
      return 0;
    }
    return occurrencesByName.getOrDefault(name, 0);
  }

  /**
   * 符号名是否在索引内有定义。
   *
   * @param name 符号名
   * @return true 若有定义
   */
  public boolean isDefined(String name) {
    return !definitionsOf(name).isEmpty();
  }

  /**
   * 是否为空索引（可用但无任何定义/引用/import）。
   *
   * @return true 若无内容
   */
  public boolean isEmpty() {
    return definitions.isEmpty() && references.isEmpty() && imports.isEmpty();
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof SymbolIndexSnapshot that)) {
      return false;
    }
    return available == that.available
        && filesScanned == that.filesScanned
        && filesSkipped == that.filesSkipped
        && truncated == that.truncated
        && headSha.equals(that.headSha)
        && reason.equals(that.reason)
        && notes.equals(that.notes)
        && definitions.equals(that.definitions)
        && references.equals(that.references)
        && imports.equals(that.imports)
        && fileStats.equals(that.fileStats);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        available,
        headSha,
        reason,
        filesScanned,
        filesSkipped,
        truncated,
        notes,
        definitions,
        references,
        imports,
        fileStats);
  }

  @Override
  public String toString() {
    return "SymbolIndexSnapshot{available=%s, headSha=%s, files=%d/%d, truncated=%s, definitions=%d, references=%d, imports=%d}"
        .formatted(
            available,
            headSha,
            filesScanned,
            filesSkipped,
            truncated,
            definitions.size(),
            references.size(),
            imports.size());
  }
}
