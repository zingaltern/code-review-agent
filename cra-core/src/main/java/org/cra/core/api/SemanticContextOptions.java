package org.cra.core.api;

import java.util.Set;
import org.cra.common.semantic.SymbolIndexOptions;

/**
 * 语义上下文（L2 跨文件切片）配置（M4e 池内项，ADR-0014；05§5 的 {@code semantic_context.*} 运行时形态）。
 *
 * <p>默认关闭（{@code enabled=false}）：关闭时流水线不构建索引、不注入切片、不产生覆盖声明——reviewer brief 与开关引入前逐字节一致 （ADR-0014 决策
 * 5）。开启后仅 standard/deep 档生效（quick 不触发，02§4.3 档位联动）， 且只对 BH-dataflow / Security / Verifier 三类 brief
 * 注入（04§1 L2 的按角色默认注入集）。
 *
 * @param enabled 总开关（CRA_SEMANTIC_CONTEXT_ENABLED；默认 false）
 * @param sliceTokens 单 brief 切片 token 上限（默认 6000；超预算截断并写覆盖声明）
 * @param indexOptions 索引构建选项（maxFiles/maxFileBytes/include/exclude/超限策略）
 */
public record SemanticContextOptions(
    boolean enabled, long sliceTokens, SymbolIndexOptions indexOptions) {

  /** 单 brief 切片默认上限（04§1 L2；04§2.2 算术中从 L1 预算扣除；AI Coding 放宽档：4000 → 6000）。 */
  public static final long DEFAULT_SLICE_TOKENS = 6_000;

  /** 切片预算下限（更小的值无实用意义，按配置错误拒绝）。 */
  private static final long MIN_SLICE_TOKENS = 500;

  /** 切片预算上限（防止单 brief 被切片挤爆；超出属配置错误）。 */
  private static final long MAX_SLICE_TOKENS = 12_000;

  public SemanticContextOptions {
    if (sliceTokens < MIN_SLICE_TOKENS || sliceTokens > MAX_SLICE_TOKENS) {
      throw new IllegalArgumentException(
          "sliceTokens must be in [%d,%d]: %d"
              .formatted(MIN_SLICE_TOKENS, MAX_SLICE_TOKENS, sliceTokens));
    }
    indexOptions = indexOptions == null ? SymbolIndexOptions.defaults() : indexOptions;
  }

  /**
   * 关闭形态（默认；与开关引入前的行为逐字节一致）。
   *
   * @return 关闭配置
   */
  public static SemanticContextOptions disabled() {
    return new SemanticContextOptions(false, DEFAULT_SLICE_TOKENS, SymbolIndexOptions.defaults());
  }

  /**
   * 开启形态（生产默认上限）。
   *
   * @return 开启配置
   */
  public static SemanticContextOptions enabledWithDefaults() {
    return new SemanticContextOptions(true, DEFAULT_SLICE_TOKENS, SymbolIndexOptions.defaults());
  }

  /**
   * 显式构造（CLI/server 配置映射用）。
   *
   * @param enabled 总开关
   * @param maxFiles 索引扫描文件数上限
   * @param maxFileBytes 单文件字节上限
   * @param sliceTokens 单 brief 切片 token 上限
   * @return 配置
   */
  public static SemanticContextOptions of(
      boolean enabled, int maxFiles, long maxFileBytes, long sliceTokens) {
    return new SemanticContextOptions(
        enabled,
        sliceTokens,
        new SymbolIndexOptions(
            maxFiles,
            maxFileBytes,
            Set.of(),
            Set.of(),
            SymbolIndexOptions.OverLimitPolicy.TRUNCATE));
  }
}
