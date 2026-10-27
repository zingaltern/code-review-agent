package org.cra.core.internal.pipeline;

import java.util.List;
import org.cra.common.event.ProductEvent;

/**
 * CrossChecker 模式 A 的产品事件史输入（M4f-full，ADR-0012；03§3.1 输入 b 的事件面）。
 *
 * <p>空形态（{@link #none()}）= 未启用/未装配/quick 档：{@code available=false} 且无条目——编排器不派发时不产生任何副作用。
 *
 * @param entries 近 N 周与变更路径/commit/关键词相关的事件（时间倒序）
 * @param available 事件史数据面是否可得（false = 未装配/检索失败，按"无数据优雅跳过"处理并声明）
 * @param notes 覆盖声明（命中数、查询面、失败原因、tribal knowledge 硬边界）
 */
public record CrossCheckEvents(List<ProductEvent> entries, boolean available, List<String> notes) {

  public CrossCheckEvents {
    entries = List.copyOf(entries == null ? List.of() : entries);
    notes = List.copyOf(notes == null ? List.of() : notes);
  }

  /**
   * 空形态（未启用/未查询）。
   *
   * @return 空事件史
   */
  public static CrossCheckEvents none() {
    return new CrossCheckEvents(List.of(), false, List.of());
  }

  /**
   * 是否有可用于模式 A 的事件数据。
   *
   * @return true 若有命中
   */
  public boolean hasData() {
    return !entries.isEmpty();
  }
}
