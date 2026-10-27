package org.cra.common.semantic;

import java.util.Objects;
import java.util.Set;

/**
 * 语义索引构建选项（ADR-0014；11§4 第 3 条——入参只含 cra-common 纯类型）。
 *
 * <p>确定性约束：扫描按路径升序、上限固定、跳过的文件与截断事实进快照 notes；同 head + 同 options 必须产出同快照。
 *
 * <p>include/exclude 模式串语义：{@code *} 匹配任意字符序列（含 {@code /}），其余字符按字面匹配，整串匹配路径。 默认不含任何模式 = 全收（仍受
 * Java-only 与内置目录跳过约束）。exclude 优先于 include。
 *
 * @param maxFiles 最多扫描的 Java 文件数（升序取前 N；其余记入截断声明）
 * @param maxFileBytes 单文件字节上限（超限跳过并记入 notes）
 * @param includePatterns 追加过滤（空 = 全收；命中才扫描）
 * @param excludePatterns 排除过滤（优先于 include）
 * @param overLimitPolicy 超限策略：TRUNCATE = 截断并声明；UNAVAILABLE = 整体标记不可得（不产出部分索引）
 */
public record SymbolIndexOptions(
    int maxFiles,
    long maxFileBytes,
    Set<String> includePatterns,
    Set<String> excludePatterns,
    OverLimitPolicy overLimitPolicy) {

  /** 扫描文件数默认上限（2000 文件 ≈ 中型仓库的 Java 源；ADR-0014 预算推演）。 */
  public static final int DEFAULT_MAX_FILES = 2_000;

  /** 单文件字节默认上限（256 KiB——超长生成文件的抽取收益低、内存代价高）。 */
  public static final long DEFAULT_MAX_FILE_BYTES = 262_144L;

  /** 超限策略。 */
  public enum OverLimitPolicy {
    /** 截断扫描集并如实声明（默认）。 */
    TRUNCATE,
    /** 超限即整体不可得（不产出部分索引，调用方按不可得声明）。 */
    UNAVAILABLE
  }

  public SymbolIndexOptions {
    if (maxFiles < 1) {
      throw new IllegalArgumentException("maxFiles must be >= 1: " + maxFiles);
    }
    if (maxFileBytes < 1_024) {
      throw new IllegalArgumentException("maxFileBytes must be >= 1024: " + maxFileBytes);
    }
    includePatterns = Set.copyOf(includePatterns == null ? Set.of() : includePatterns);
    excludePatterns = Set.copyOf(excludePatterns == null ? Set.of() : excludePatterns);
    overLimitPolicy = Objects.requireNonNull(overLimitPolicy, "overLimitPolicy");
  }

  /**
   * 默认选项（生产值；测试可注入更小上限）。
   *
   * @return 默认选项
   */
  public static SymbolIndexOptions defaults() {
    return new SymbolIndexOptions(
        DEFAULT_MAX_FILES, DEFAULT_MAX_FILE_BYTES, Set.of(), Set.of(), OverLimitPolicy.TRUNCATE);
  }
}
