package org.cra.core.internal.pipeline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.cra.common.git.DiffFile;
import org.cra.common.semantic.SymbolDefinition;
import org.cra.common.semantic.SymbolIndexSnapshot;
import org.cra.common.semantic.SymbolReferenceSite;
import org.cra.common.semantic.TypeImport;
import org.cra.core.api.SemanticContextOptions;

/**
 * 单次 run 的语义上下文（ADR-0014）：索引快照 + 切片预算 + 覆盖声明，供评审 brief 与验证 brief 共用。
 *
 * <p>三种形态：
 *
 * <ul>
 *   <li>{@link #disabled()}：开关关闭——不构建索引、不注入、不写声明，行为与开关引入前逐字节一致（ADR-0014 决策 5）；
 *   <li>不可得：开关开启但索引构建失败/无本地仓库/超限——切片为空 + 覆盖声明如实说明（11§4 第 5 条）；
 *   <li>可用：切片按 brief 预算组装（{@link CrossFileSliceAssembler}）。
 * </ul>
 */
public final class SemanticContext {

  private final SemanticContextOptions options;
  private final SymbolIndexSnapshot snapshot;

  private SemanticContext(SemanticContextOptions options, SymbolIndexSnapshot snapshot) {
    this.options = options;
    this.snapshot = snapshot;
  }

  /**
   * 关闭形态（默认；零副作用）。
   *
   * @return 关闭上下文
   */
  public static SemanticContext disabled() {
    return new SemanticContext(SemanticContextOptions.disabled(), null);
  }

  /**
   * 开启形态：装配索引快照（可能是不可得快照）。
   *
   * @param options 配置（enabled 必须为 true）
   * @param snapshot 索引快照（null 视为不可得）
   * @return 语义上下文
   */
  public static SemanticContext of(SemanticContextOptions options, SymbolIndexSnapshot snapshot) {
    if (options == null || !options.enabled()) {
      return disabled();
    }
    return new SemanticContext(
        options,
        snapshot == null ? SymbolIndexSnapshot.unavailable("", "语义索引未构建；L2 切片按不可得声明") : snapshot);
  }

  /**
   * 是否启用（关闭时一切产出为空且无声明）。
   *
   * @return true 若开关开启
   */
  public boolean enabled() {
    return options.enabled();
  }

  /**
   * 索引是否可用。
   *
   * @return true 若快照可用
   */
  public boolean available() {
    return snapshot != null && snapshot.available();
  }

  /**
   * 索引快照（关闭/未装配为 null）。
   *
   * @return 快照
   */
  public SymbolIndexSnapshot snapshot() {
    return snapshot;
  }

  /**
   * 单 brief 切片预算（token）。
   *
   * @return 预算
   */
  public long sliceTokens() {
    return options.sliceTokens();
  }

  /**
   * 组装给定文件的 L2 切片（关闭/不可得返回空切片，无声明）。
   *
   * @param files 评审范围文件（chunk 或单文件）
   * @return 切片
   */
  public CrossFileSlices slicesFor(List<DiffFile> files) {
    if (!enabled() || !available()) {
      return CrossFileSlices.empty();
    }
    return CrossFileSliceAssembler.assemble(files, snapshot, options.sliceTokens());
  }

  /**
   * 覆盖声明（仅启用时非空）：索引可得性、Java-only 与截断事实——Summary 必须可见（02§4、ADR-0014 验收 5）。
   *
   * @return 声明行
   */
  public List<String> coverageNotes() {
    if (!enabled()) {
      return List.of();
    }
    List<String> notes = new ArrayList<>();
    if (!available()) {
      notes.add("L2 跨文件切片不可得：" + snapshot.reason());
      return List.copyOf(notes);
    }
    notes.add(
        "L2 跨文件切片启用（语义索引 @"
            + org.cra.common.text.Texts.shortSha(snapshot.headSha(), 7, "unknown")
            + "）：扫描 "
            + snapshot.filesScanned()
            + " 个 Java 文件 / 定义 "
            + snapshot.definitions().size()
            + " 个符号 / 引用 "
            + snapshot.references().size()
            + " 个符号名（跳过 "
            + snapshot.filesSkipped()
            + " 个文件）");
    notes.addAll(snapshot.notes());
    return List.copyOf(notes);
  }

  /**
   * 索引增强的上下游路径集（03§3.1 CrossChecker 模式 A 输入 b 的路径面）：变更文件 + 定义/引用/import 关联文件。
   *
   * <p>路径集只做"检索面"扩展，不改变 CrossChecker 的判定逻辑；上限由 {@code cap} 控制（确定性截断：先变更文件、后按 (path) 升序）。
   *
   * @param files 评审范围文件
   * @param cap 路径数上限（含变更文件本身）
   * @return 排序后的路径集（未启用/不可得时仅变更文件）
   */
  public Set<String> relatedPaths(List<DiffFile> files, int cap) {
    Set<String> ordered = new LinkedHashSet<>();
    if (files == null) {
      return ordered;
    }
    for (DiffFile file : files) {
      ordered.add(file.path());
    }
    if (!enabled() || !available() || cap <= 0) {
      return ordered;
    }
    List<ChangedSymbols.Symbol> symbols =
        ChangedSymbols.extract(files, snapshot, ChangedSymbols.MAX_SYMBOLS);
    Set<String> extra = new LinkedHashSet<>();
    for (ChangedSymbols.Symbol symbol : symbols) {
      for (SymbolDefinition definition : snapshot.definitionsOf(symbol.name())) {
        extra.add(definition.path());
      }
      for (SymbolReferenceSite site : snapshot.referencesOf(symbol.name())) {
        extra.add(site.path());
      }
    }
    Set<String> changed = new LinkedHashSet<>(ordered);
    for (TypeImport entry : snapshot.imports()) {
      if (entry.resolvedInRepo() && changed.contains(entry.resolvedPath())) {
        extra.add(entry.path());
      }
    }
    List<String> sortedExtra = new ArrayList<>(extra);
    sortedExtra.sort(String::compareTo);
    for (String path : sortedExtra) {
      if (ordered.size() >= cap) {
        break;
      }
      ordered.add(path);
    }
    return ordered;
  }

  /**
   * trace 事件字段（{@code semantic_context}；仅启用时调用）。
   *
   * @return 有序字段（可用性/规模/截断）
   */
  public Map<String, Object> traceFields() {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("enabled", true);
    fields.put("available", available());
    if (!available()) {
      fields.put("reason", snapshot == null ? "未构建" : snapshot.reason());
      return fields;
    }
    fields.put("files_scanned", snapshot.filesScanned());
    fields.put("files_skipped", snapshot.filesSkipped());
    fields.put("definitions", snapshot.definitions().size());
    fields.put("references", snapshot.references().size());
    fields.put("imports", snapshot.imports().size());
    fields.put("truncated", snapshot.truncated());
    fields.put("slice_tokens", options.sliceTokens());
    return fields;
  }
}
