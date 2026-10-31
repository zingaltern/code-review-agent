package org.cra.server.history;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 事件史采集定时任务（07§5 M4f-full，ADR-0012）。
 *
 * <p>默认关闭（CRA_SERVER_EVENT_HISTORY_ENABLED=false）；开启时按 CRA_SERVER_EVENT_HISTORY_CRON（Spring 六段式）调度，
 * 调用 {@link EventHistoryCollector} 并如实记录结果。采集器失败时 {@code executed=false} 且 note 写明原因——不伪装采集成功。
 *
 * <p>cron 由 Spring 占位符直接解析（环境变量/application.yml 同源），ServerConfig 组装期已用 {@code CronExpression}
 * 校验合法性。
 */
public final class EventHistoryCollectionJob {

  private static final Logger LOG = LoggerFactory.getLogger(EventHistoryCollectionJob.class);

  private final boolean enabled;
  private final EventHistoryCollector collector;
  private final int lookbackDays;
  private final Clock clock;

  /**
   * @param enabled 开关（CRA_SERVER_EVENT_HISTORY_ENABLED）
   * @param collector 采集端口（关闭时装配 Noop）
   * @param lookbackDays 单次窗口（天；任务调度周期应不大于它，避免窗口空洞）
   */
  public EventHistoryCollectionJob(
      boolean enabled, EventHistoryCollector collector, int lookbackDays) {
    this(enabled, collector, lookbackDays, Clock.systemUTC());
  }

  /**
   * @param clock 窗口锚点（测试可固定）
   */
  EventHistoryCollectionJob(
      boolean enabled, EventHistoryCollector collector, int lookbackDays, Clock clock) {
    this.enabled = enabled;
    this.collector = collector;
    this.lookbackDays = lookbackDays;
    this.clock = clock == null ? Clock.systemUTC() : clock;
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
    Instant since = clock.instant().minus(lookbackDays, ChronoUnit.DAYS);
    LOG.info("event-history 采集开始: since={} lookbackDays={}", since, lookbackDays);
    EventHistoryCollector.CollectionResult result = collector.collect(since);
    LOG.info(
        "event-history 采集结束: executed={} fetched={} inserted={} updated={} skipped={} failed={}"
            + " tickets={} incidents={} note={}",
        result.executed(),
        result.fetched(),
        result.inserted(),
        result.updated(),
        result.skipped(),
        result.failed(),
        result.tickets(),
        result.incidents(),
        result.note());
    if (!result.executed()) {
      LOG.warn("event-history 采集未成功（如实记录，不重试到调度之外）: {}", result.note());
    }
    return result;
  }
}
