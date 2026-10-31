package org.cra.core.internal.pipeline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.cra.common.context.TokenMath;
import org.cra.common.git.DiffFile;
import org.cra.common.semantic.SymbolDefinition;
import org.cra.common.semantic.SymbolIndexSnapshot;
import org.cra.common.semantic.SymbolReferenceSite;
import org.cra.common.semantic.TypeImport;
import org.cra.common.text.Texts;

/**
 * L2 跨文件切片组装（04§1 L2；ADR-0014 决策 4/7）：变更符号 → 定义行 + 跨文件引用位置 + import 关联，按预算裁成 brief 段。
 *
 * <p>设计口径：
 *
 * <ul>
 *   <li><b>证据自足</b>：每行引用都带 {@code 文件:行 + 行摘录}（索引内联快照），不额外调用平台接口——切片是纯函数、可重放；
 *   <li><b>不重复 diff</b>：切片排除本 PR 变更文件（其内容已在 L0/L1 中），只补"看不到的文件"；
 *   <li><b>预算</b>：默认 4000 token（{@link
 *       org.cra.core.api.SemanticContextOptions#DEFAULT_SLICE_TOKENS}），超预算按符号顺序截断， 末尾必带截断声明（04§1
 *       裁剪优先级：先丢低价值切片，不静默裁剪）；
 *   <li><b>确定性</b>：符号顺序由 {@link ChangedSymbols} 决定，位置按 (path,line) 升序，输出同输入同结果。
 * </ul>
 */
final class CrossFileSliceAssembler {

  /** 单符号最多注入的引用位置数（04§2.2：切片证据条数上限）。 */
  static final int MAX_SITES_PER_SYMBOL = 6;

  /** 单符号最多注入的文件数。 */
  static final int MAX_FILES_PER_SYMBOL = 3;

  /** 单符号最多注入的定义行数（同名重载/多文件同名定义）。 */
  static final int MAX_DEFINITIONS_PER_SYMBOL = 2;

  /** import 关联最多注入的文件数。 */
  static final int MAX_IMPORT_FILES = 5;

  /** 截断声明的 token 预留（保证声明本身不被预算裁掉）。 */
  private static final long FOOTER_RESERVE = 120;

  /** 默认注入 L2 切片的三类 brief（04§1：BH-dataflow、Security、Verifier；其余角色按需）。 */
  static final Set<String> SLICE_ROLES =
      Set.of("bug-hunter/dataflow", "security-reviewer", "verifier");

  private CrossFileSliceAssembler() {}

  /**
   * 该角色是否默认注入 L2 切片（04§1；Verifier 走验证 brief 通道，本方法供评审侧判定）。
   *
   * @param roleId 角色 id（{@code AgentRole.id()}）
   * @return true 若默认注入
   */
  static boolean isSliceRole(String roleId) {
    return roleId != null && SLICE_ROLES.contains(roleId);
  }

  /**
   * 组装切片。
   *
   * @param files 评审范围文件（chunk 或单文件）
   * @param index 符号索引快照（不可用 = 空切片）
   * @param budgetTokens 本 brief 的切片 token 上限
   * @return 切片 + 覆盖声明
   */
  static CrossFileSlices assemble(
      List<DiffFile> files, SymbolIndexSnapshot index, long budgetTokens) {
    if (files == null || files.isEmpty() || index == null || !index.available()) {
      return CrossFileSlices.empty();
    }
    List<ChangedSymbols.Symbol> symbols =
        ChangedSymbols.extract(files, index, ChangedSymbols.MAX_SYMBOLS);
    List<String> notes = new ArrayList<>();
    if (symbols.isEmpty()) {
      notes.add("L2 跨文件切片未注入：新增行未命中索引内符号定义（ADR-0014 变更符号抽取口径）");
      return new CrossFileSlices("", List.copyOf(notes), 0, 0, 0, false);
    }
    Set<String> changedPaths = new LinkedHashSet<>();
    for (DiffFile file : files) {
      changedPaths.add(file.path());
    }

    String header = renderHeader(index, symbols.size());
    StringBuilder body = new StringBuilder();
    long used = TokenMath.estimateTokens(header);
    int renderedSymbols = 0;
    int renderedSites = 0;
    int renderedFiles = 0;
    int skippedSymbols = 0;
    int skippedSites = 0;
    Set<String> renderedPaths = new LinkedHashSet<>();
    for (ChangedSymbols.Symbol symbol : symbols) {
      Section section = renderSymbol(symbol.name(), index, changedPaths);
      if (section == null) {
        skippedSymbols++;
        continue;
      }
      long sectionTokens = TokenMath.estimateTokens(section.text());
      if (used + sectionTokens + FOOTER_RESERVE > budgetTokens) {
        skippedSymbols++;
        skippedSites += section.sites();
        continue;
      }
      body.append(section.text());
      used += sectionTokens;
      renderedSymbols++;
      renderedSites += section.sites();
      renderedPaths.addAll(section.paths());
    }
    Section importSection = renderImports(index, changedPaths, renderedPaths);
    if (importSection != null) {
      long importTokens = TokenMath.estimateTokens(importSection.text());
      if (used + importTokens + FOOTER_RESERVE <= budgetTokens) {
        body.append(importSection.text());
        renderedPaths.addAll(importSection.paths());
      }
    }
    renderedFiles = renderedPaths.size();
    boolean truncated = skippedSymbols > 0;
    StringBuilder text = new StringBuilder(header);
    text.append('\n').append(body);
    if (renderedSymbols == 0) {
      notes.add(
          "L2 跨文件切片未注入："
              + symbols.size()
              + " 个候选符号均超出切片预算 "
              + budgetTokens
              + " token（ADR-0014 预算裁剪）");
      return new CrossFileSlices("", List.copyOf(notes), 0, 0, 0, true);
    }
    if (truncated) {
      String footer =
          "\n<!-- 截断声明：L2 切片预算 "
              + budgetTokens
              + " token，已注入 "
              + renderedSymbols
              + "/"
              + symbols.size()
              + " 个符号、"
              + renderedSites
              + " 处引用；另有 "
              + skippedSymbols
              + " 个符号"
              + (skippedSites > 0 ? "（约 " + skippedSites + " 处引用）" : "")
              + "未注入（04§1 裁剪优先级）。 -->";
      text.append(footer);
      notes.add(
          "L2 跨文件切片按预算 "
              + budgetTokens
              + " token 截断：注入 "
              + renderedSymbols
              + "/"
              + symbols.size()
              + " 个符号（截断声明已写入 brief）");
    }
    notes.add(
        "L2 跨文件切片："
            + renderedSymbols
            + " 个变更符号 → "
            + renderedFiles
            + " 个关联文件 / "
            + renderedSites
            + " 处引用（语义索引扫描 "
            + index.filesScanned()
            + " 个 Java 文件）");
    return new CrossFileSlices(
        text.toString().stripTrailing(),
        List.copyOf(notes),
        renderedSymbols,
        renderedFiles,
        renderedSites,
        truncated);
  }

  /** 相对头部（索引范围 + Java-only 声明）。 */
  private static String renderHeader(SymbolIndexSnapshot index, int candidateSymbols) {
    return "## L2 跨文件上下文（确定性符号索引；head "
        + Texts.shortSha(index.headSha(), 7, "unknown")
        + "；仅 Java）\n"
        + "索引范围：扫描 "
        + index.filesScanned()
        + " 个 Java 文件（跳过 "
        + index.filesSkipped()
        + "），定义 "
        + index.definitions().size()
        + " 个符号；本次变更候选符号 "
        + candidateSymbols
        + " 个（按 ADR-0014 抽取口径）。\n";
  }

  /** 渲染单个符号段（定义行 + 跨文件引用）；无证据返回 null。 */
  private static Section renderSymbol(
      String name, SymbolIndexSnapshot index, Set<String> changedPaths) {
    List<SymbolDefinition> definitions = new ArrayList<>();
    for (SymbolDefinition definition : index.definitionsOf(name)) {
      if (definitions.size() >= MAX_DEFINITIONS_PER_SYMBOL) {
        break;
      }
      definitions.add(definition);
    }
    if (definitions.isEmpty()) {
      return null;
    }
    List<SymbolReferenceSite> sites = new ArrayList<>();
    Set<String> siteFiles = new LinkedHashSet<>();
    for (SymbolReferenceSite site : index.referencesOf(name)) {
      if (changedPaths.contains(site.path())) {
        continue;
      }
      if (sites.size() >= MAX_SITES_PER_SYMBOL) {
        break;
      }
      boolean newFile = !siteFiles.contains(site.path());
      if (newFile && siteFiles.size() >= MAX_FILES_PER_SYMBOL) {
        continue;
      }
      if (newFile) {
        siteFiles.add(site.path());
      }
      sites.add(site);
    }
    SymbolDefinition primary = definitions.get(0);
    StringBuilder out = new StringBuilder();
    out.append("\n### 符号 `")
        .append(name)
        .append("`（")
        .append(primary.kind().name().toLowerCase(Locale.ROOT))
        .append(primary.owner() == null ? "" : "，owner " + primary.owner())
        .append("）\n");
    for (SymbolDefinition definition : definitions) {
      out.append("- 定义 `")
          .append(definition.path())
          .append(':')
          .append(definition.line())
          .append("`  L")
          .append(definition.line())
          .append(": ")
          .append(definition.snippet())
          .append('\n');
    }
    if (sites.isEmpty()) {
      out.append("- 跨文件引用：索引内无其他文件引用（仅定义处出现）\n");
    } else {
      out.append("- 跨文件引用（")
          .append(sites.size())
          .append(" 处 / ")
          .append(siteFiles.size())
          .append(" 文件）：\n");
      for (SymbolReferenceSite site : sites) {
        out.append("  - `")
            .append(site.path())
            .append(':')
            .append(site.line())
            .append("`  L")
            .append(site.line())
            .append(": ")
            .append(site.snippet())
            .append('\n');
      }
    }
    Set<String> paths = new LinkedHashSet<>();
    definitions.forEach(definition -> paths.add(definition.path()));
    sites.forEach(site -> paths.add(site.path()));
    return new Section(out.toString(), sites.size(), paths);
  }

  /** 渲染 import 关联段（谁 import 了本 PR 变更的类型）；无命中返回 null。 */
  private static Section renderImports(
      SymbolIndexSnapshot index, Set<String> changedPaths, Set<String> alreadyRendered) {
    Map<String, List<TypeImport>> byFile = new LinkedHashMap<>();
    Set<String> files = new LinkedHashSet<>();
    for (TypeImport entry : index.imports()) {
      if (!entry.resolvedInRepo()
          || !changedPaths.contains(entry.resolvedPath())
          || changedPaths.contains(entry.path())
          || alreadyRendered.contains(entry.path())) {
        continue;
      }
      if (!files.contains(entry.path()) && files.size() >= MAX_IMPORT_FILES) {
        continue;
      }
      files.add(entry.path());
      byFile.computeIfAbsent(entry.path(), key -> new ArrayList<>()).add(entry);
    }
    if (byFile.isEmpty()) {
      return null;
    }
    StringBuilder out = new StringBuilder();
    out.append("\n### import 关联（引用本 PR 变更类型的文件）\n");
    Set<String> paths = new LinkedHashSet<>();
    for (Map.Entry<String, List<TypeImport>> entry : byFile.entrySet()) {
      paths.add(entry.getKey());
      for (TypeImport typeImport : entry.getValue()) {
        out.append("- `")
            .append(typeImport.path())
            .append(':')
            .append(typeImport.line())
            .append("`  L")
            .append(typeImport.line())
            .append(": import ")
            .append(typeImport.staticImport() ? "static " : "")
            .append(typeImport.imported())
            .append('\n');
      }
    }
    return new Section(out.toString(), 0, paths);
  }

  /** 渲染段（文本 + 引用位置数 + 覆盖文件）。 */
  private record Section(String text, int sites, Set<String> paths) {}
}
