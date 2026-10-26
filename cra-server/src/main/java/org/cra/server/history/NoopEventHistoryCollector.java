package org.cra.server.history;

import java.time.Instant;

/** M4f-full 占位实现：只声明"未实现"，不伪造采集结果（07§5 M4b 只留接口与调度骨架）。 */
public final class NoopEventHistoryCollector implements EventHistoryCollector {

  @Override
  public CollectionResult collect(Instant since) {
    return CollectionResult.skipped("event-history 采集未实现（M4b 仅接口与调度骨架；具体采集在 M4f-full）");
  }
}
