package org.cra.common.event;

import java.util.List;
import java.util.Optional;

/**
 * 事件史检索端口（ADR-0012；11§2 登记的扩展点）。
 *
 * <p>提供方：服务形态的 PostgreSQL 实现（{@code PostgresEventHistoryStore}）；CLI / 无事件史的部署装配 {@link
 * #empty()}——空实现返回空结果，调用方按"无数据优雅跳过"处理并声明，不得当作"已检查且无矛盾"。
 *
 * <p>不可得语义（11§4 第 5 条）：实现遇到后端故障可抛出运行时异常；消费方（CrossChecker 装配）捕获后降级为 available=false + 覆盖声明，不静默当作空集。
 */
public interface EventHistoryQuery {

  /**
   * 按条件检索事件（时间倒序；实现可返回少于 limit 的结果）。
   *
   * @param query 检索条件（非 null）
   * @return 命中事件（只读列表）
   */
  List<ProductEvent> search(EventQuery query);

  /**
   * 按幂等键取单条事件。
   *
   * @param repo 仓库（{@code owner/name}，大小写不敏感）
   * @param eventId 事件标识
   * @return 事件；不存在为空
   */
  Optional<ProductEvent> find(String repo, String eventId);

  /**
   * 空实现（无事件史数据面）；查询恒为空——调用方据此走"无数据优雅跳过"并声明。
   *
   * @return 空查询端口
   */
  static EventHistoryQuery empty() {
    return new EventHistoryQuery() {
      @Override
      public List<ProductEvent> search(EventQuery query) {
        return List.of();
      }

      @Override
      public Optional<ProductEvent> find(String repo, String eventId) {
        return Optional.empty();
      }
    };
  }
}
