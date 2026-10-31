package org.cra.server.history;

/**
 * 事件史采集参数（M4f-full，ADR-0012；05§5 配置项的服务端映射）。
 *
 * <p>窗口长度归调度任务（{@link EventHistoryCollectionJob} 计算 {@code since}），本类型只承载采集器内的重试与单次上限。
 *
 * @param overlapHours 与上次水位的重叠小时数（吸收迟到事件；幂等键保证重叠不产生重复）
 * @param maxAttempts 单次读取/入库的最大尝试次数（含首次；1 = 不重试）
 * @param retryBackoffMillis 重试退避基数（毫秒；第 n 次重试等待 n × 基数）
 * @param maxEventsPerRun 单次入库上限（超出按发生时间升序截断，水位只推进到已入库部分）
 */
public record EventCollectionSettings(
    int overlapHours, int maxAttempts, long retryBackoffMillis, int maxEventsPerRun) {

  public EventCollectionSettings {
    if (overlapHours < 0 || overlapHours > 720) {
      throw new IllegalArgumentException(
          "CRA_SERVER_EVENT_HISTORY_OVERLAP_HOURS 必须在 0..720: " + overlapHours);
    }
    if (maxAttempts <= 0 || maxAttempts > 10) {
      throw new IllegalArgumentException(
          "CRA_SERVER_EVENT_HISTORY_MAX_ATTEMPTS 必须在 1..10: " + maxAttempts);
    }
    if (retryBackoffMillis < 0 || retryBackoffMillis > 60_000) {
      throw new IllegalArgumentException(
          "CRA_SERVER_EVENT_HISTORY_RETRY_BACKOFF_MS 必须在 0..60000: " + retryBackoffMillis);
    }
    if (maxEventsPerRun <= 0 || maxEventsPerRun > 100_000) {
      throw new IllegalArgumentException(
          "CRA_SERVER_EVENT_HISTORY_MAX_EVENTS 必须在 1..100000: " + maxEventsPerRun);
    }
  }
}
