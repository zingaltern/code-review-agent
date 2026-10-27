package org.cra.common.semantic;

import java.util.Objects;

/**
 * import 关系（语义索引快照内容之一；ADR-0014）。
 *
 * <p>{@code resolvedPath} 只在被引类型由本仓库定义时可解析：仓库内同名类型唯一，或（同名多处时） {@code package + "." + simpleName}
 * 与被引限定名完全一致。外部类型（JDK / 第三方库）保持 null——索引不假装知道仓库外的定义。
 *
 * @param path import 语句所在文件（相对仓库根）
 * @param imported 被引限定名（如 {@code demo.SessionKeepAlive}；通配 import 为 {@code demo.*}）
 * @param simpleName 被引简单名（通配 import 为 {@code *}；静态 import 为成员名）
 * @param staticImport 是否 {@code import static}
 * @param line import 语句行（1 基）
 * @param resolvedPath 仓库内定义该类型的文件（不可解析为 null）
 */
public record TypeImport(
    String path,
    String imported,
    String simpleName,
    boolean staticImport,
    int line,
    String resolvedPath) {

  public TypeImport {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(imported, "imported");
    simpleName = simpleName == null ? "" : simpleName;
    if (line < 1) {
      throw new IllegalArgumentException("line must be >= 1: " + line);
    }
  }

  /**
   * 是否解析到仓库内文件。
   *
   * @return true 若 resolvedPath 非空
   */
  public boolean resolvedInRepo() {
    return resolvedPath != null && !resolvedPath.isBlank();
  }

  /**
   * 同一条 import 语句的解析结果替换（解析在扫描后统一进行）。
   *
   * @param target 仓库内定义文件；null 表示不可解析
   * @return 带解析结果的新实例
   */
  public TypeImport withResolvedPath(String target) {
    return new TypeImport(path, imported, simpleName, staticImport, line, target);
  }
}
