package org.cra.server.history;

import java.time.Instant;

/** 关闭形态占位实现：只声明"未启用"，不伪造采集结果（M4f-full 真实采集见 {@link IncrementalEventHistoryCollector}）。 */
public final class NoopEventHistoryCollector implements EventHistoryCollector {

  @Override
  public CollectionResult collect(Instant since) {
    return CollectionResult.skipped(
        "event-history 采集未启用（Noop；CRA_SERVER_EVENT_HISTORY_ENABLED=false 时装配）");
  }
}
