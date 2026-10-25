package org.cra.agents.api;

import java.util.Set;

/**
 * 子代理工具白名单（03§2.1：默认只读；M0 上下文全部内联进 brief，白名单为空集—— 无网络、无写盘、无 shell 是实现层硬编码，不靠 prompt，04§5 反模式 8）。
 *
 * @param tools 允许的工具名集合（如 read_file(scoped)），空集 = 纯单次调用
 */
public record ToolWhitelist(Set<String> tools) {

  /** 空白名单（M0 形态：上下文全部内联，无受限工具）。 */
  public static final ToolWhitelist NONE = new ToolWhitelist(Set.of());

  public ToolWhitelist {
    tools = Set.copyOf(tools == null ? Set.of() : tools);
  }

  /**
   * 判断工具是否被允许。
   *
   * @param tool 工具名
   * @return true 若在白名单内
   */
  public boolean allows(String tool) {
    return tools.contains(tool);
  }
}
