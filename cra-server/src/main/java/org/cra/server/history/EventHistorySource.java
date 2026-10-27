package org.cra.server.history;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import org.cra.common.event.ProductEvent;

/**
 * 事件史来源端口（M4f-full，ADR-0012；11§2 登记的扩展点）。
 *
 * <p>第一版实现：本地文件 / 对象存储 HTTP(S) URL 的 JSONL（{@link JsonlEventHistorySource}）。Jira / Confluence / 服务台
 * API 等外部连接器是后续扩展点：实现本接口即可接入调度与入库，不改编排层。
 *
 * <p>实现只负责读取与解析；增量水位、去重、失败重试与计数由 {@link EventHistoryCollector} 统一负责。
 */
public interface EventHistorySource {

  /**
   * 来源标识（水位持久化键；同一部署内应稳定，如文件路径或对象 URL）。
   *
   * @return 来源标识
   */
  String id();

  /**
   * 读取窗口内的事件。
   *
   * @param since 窗口起点（含；来源可据此提前过滤，但不得改变语义）
   * @return 读取结果（事件 + 行级失败 + 读取行数）
   * @throws IOException 来源不可读（网络/文件/权限）；由采集器做有上限重试
   */
  ReadResult read(Instant since) throws IOException;

  /**
   * 读取结果。
   *
   * @param events 解析成功且落在窗口内的事件
   * @param failures 行级失败说明（含行号与原因；有失败时采集器不推进水位）
   * @param linesRead 读取的非空行数
   */
  record ReadResult(List<ProductEvent> events, List<String> failures, int linesRead) {

    public ReadResult {
      events = List.copyOf(events == null ? List.of() : events);
      failures = List.copyOf(failures == null ? List.of() : failures);
    }
  }
}
