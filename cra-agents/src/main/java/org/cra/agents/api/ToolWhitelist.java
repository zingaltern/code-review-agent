package org.cra.agents.api;

import java.util.Set;

/**
 * 子代理工具白名单（03§2.1：默认只读；M0 上下文全部内联进 brief，白名单为空集—— 无网络、无写盘、无 shell 是实现层硬编码，不靠 prompt，04§5 反模式 8）。
 *
 * @param tools 允许的工具名集合（如 read_file(scoped)），空集 = 纯单次调用
 */
public record ToolWhitelist(Set<String> tools) {

  /** 只读文件工具名（03§2.3 默认白名单 read_file(scoped)）。 */
  public static final String READ_FILE_NAME = "read_file";

  /** 范围内检索工具名（03§2.3 SecurityReviewer 形态）。 */
  public static final String GREP_PATTERN_NAME = "grep_pattern";

  /** 空白名单（M0 形态：上下文全部内联，无受限工具）。 */
  public static final ToolWhitelist NONE = new ToolWhitelist(Set.of());

  /** 只读文件工具（03§2.3 默认白名单 read_file(scoped)）。 */
  public static final ToolWhitelist READ_FILE = of(READ_FILE_NAME);

  /** 只读文件 + 范围内检索（03§2.3：SecurityReviewer 形态）。 */
  public static final ToolWhitelist READ_FILE_AND_GREP = of(READ_FILE_NAME, GREP_PATTERN_NAME);

  /**
   * 由工具名构造白名单（重复名自动去重）。
   *
   * @param names 允许的工具名
   * @return 白名单
   */
  public static ToolWhitelist of(String... names) {
    return new ToolWhitelist(Set.of(names));
  }

  public ToolWhitelist {
    tools = Set.copyOf(tools == null ? Set.of() : tools);
  }

  /**
   * 判断工具是否被允许（null 视为未允许，不抛错）。
   *
   * @param tool 工具名
   * @return true 若在白名单内
   */
  public boolean allows(String tool) {
    return tool != null && tools.contains(tool);
  }
}
