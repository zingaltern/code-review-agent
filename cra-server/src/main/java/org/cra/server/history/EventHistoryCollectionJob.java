package org.cra.server.history;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 事件史采集定时任务骨架（07§5 M4b：只留接口与调度骨架，具体采集在 M4f-full）。
 *
 * <p>默认关闭（CRA_SERVER_EVENT_HISTORY_ENABLED=false）；开启时按 CRA_SERVER_EVENT_HISTORY_CRON（Spring 六段式）调度，
 * 调用 {@link EventHistoryCollector} 并记录结果。M4b 装配的是 {@link
 * NoopEventHistoryCollector}——日志会明确写"未实现"，不伪装采集成功。
 */
public final class EventHistoryCollectionJob {

  private static final Logger LOG = LoggerFactory.getLogger(EventHistoryCollectionJob.class);

  private final boolean enabled;
  private final EventHistoryCollector collector;

  /**
   * @param enabled 开关（CRA_SERVER_EVENT_HISTORY_ENABLED）
   * @param collector 采集端口（M4b = Noop）
   */
  public EventHistoryCollectionJob(boolean enabled, EventHistoryCollector collector) {
    this.enabled = enabled;
    this.collector = collector;
  }

  /** 调度入口（cron 由配置注入；enabled=false 时只记一条 debug）。 */
  @Scheduled(cron = "${CRA_SERVER_EVENT_HISTORY_CRON:0 0 3 * * *}")
  public void scheduled() {
    runOnce();
  }

  /**
   * 执行一次采集（测试直接调用；不依赖调度器）。
   *
   * @return 采集结果
   */
  public EventHistoryCollector.CollectionResult runOnce() {
    if (!enabled) {
      LOG.debug("event-history 采集未启用（CRA_SERVER_EVENT_HISTORY_ENABLED=false）");
      return EventHistoryCollector.CollectionResult.skipped("disabled");
    }
    Instant since = Instant.now().minusSeconds(7L * 24 * 3600);
    LOG.info("event-history 采集开始: since={}", since);
    EventHistoryCollector.CollectionResult result = collector.collect(since);
    LOG.info(
        "event-history 采集结束: executed={} tickets={} incidents={} note={}",
        result.executed(),
        result.tickets(),
        result.incidents(),
        result.note());
    return result;
  }
}
