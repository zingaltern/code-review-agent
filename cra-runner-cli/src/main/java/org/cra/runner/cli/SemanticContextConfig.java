package org.cra.runner.cli;

import org.cra.core.api.SemanticContextOptions;

/**
 * 语义上下文（L2 跨文件切片）运行配置（05§5 {@code semantic_context} 的 CLI 形态；M4e，ADR-0014）。
 *
 * <p>默认关闭（{@code enabled=false}）：关闭时流水线不构建索引、不注入切片——reviewer brief 与开关引入前逐字节一致。 开启后只对 standard/deep
 * 档的 BH-dataflow / Security / Verifier brief 注入（04§1），切片占用该代理的输入预算。
 *
 * @param enabled 总开关（CRA_SEMANTIC_CONTEXT_ENABLED；默认 false）
 * @param maxFiles 索引扫描 Java 文件数上限（CRA_SEMANTIC_CONTEXT_MAX_FILES；默认 2000）
 * @param maxFileBytes 单文件字节上限（CRA_SEMANTIC_CONTEXT_MAX_FILE_BYTES；默认 256 KiB）
 * @param sliceTokens 单 brief 切片 token 上限（CRA_SEMANTIC_CONTEXT_SLICE_TOKENS；默认 4000）
 */
record SemanticContextConfig(boolean enabled, int maxFiles, long maxFileBytes, long sliceTokens) {

  SemanticContextConfig {
    if (maxFiles < 1) {
      throw new org.cra.common.exception.CraConfigException(
          "semantic_context.maxFiles 必须 ≥1: " + maxFiles);
    }
    if (maxFileBytes < 1_024) {
      throw new org.cra.common.exception.CraConfigException(
          "semantic_context.maxFileBytes 必须 ≥1024: " + maxFileBytes);
    }
    if (sliceTokens < 500 || sliceTokens > 12_000) {
      throw new org.cra.common.exception.CraConfigException(
          "semantic_context.sliceTokens 必须在 [500,12000]: " + sliceTokens);
    }
  }

  /**
   * 默认配置（关闭 + 文档默认上限）。
   *
   * @return 默认配置
   */
  static SemanticContextConfig defaults() {
    return new SemanticContextConfig(false, 2_000, 262_144L, 4_000L);
  }

  /**
   * 编排参数形态。
   *
   * @return SemanticContextOptions
   */
  SemanticContextOptions toOptions() {
    return SemanticContextOptions.of(enabled, maxFiles, maxFileBytes, sliceTokens);
  }
}
