package org.cra.server.history;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.cra.common.event.EventHistoryStore;
import org.cra.common.event.ProductEvent;

/**
 * 增量事件史采集器（M4f-full，ADR-0012）：窗口 + 水位 + 幂等去重 + 有上限重试 + 如实计数。
 *
 * <p>流程：加载事件源水位 → 窗口起点 = max(请求窗口, 水位 − overlap) → 读取（有上限重试）→ 批内去重/窗口复核/单次上限截断 → 幂等入库（有上限重试）→
 * 成功且无行级解析失败时推进水位。
 *
 * <p>降级语义（不假装采集成功）：
 *
 * <ul>
 *   <li>来源读取/入库重试耗尽 → {@code executed=false}，note 写明失败原因与"可能已部分写入"；
 *   <li>行级解析失败 → 本次可入库其他行，但**不推进水位**（失败行下次重试），失败数进 note/结果；
 *   <li>窗口内无事件 → {@code executed=true} + 零计数 + "无新事件"声明（不是错误，也不当作已检查无矛盾——那是 CrossChecker 的声明职责）。
 * </ul>
 */
public final class IncrementalEventHistoryCollector implements EventHistoryCollector {

  /** 测试可注入的睡眠（真实实现 = Thread::sleep）。 */
  interface Sleeper {

    /**
     * 休眠。
     *
     * @param millis 毫秒
     * @throws InterruptedException 中断
     */
    void sleep(long millis) throws InterruptedException;
  }

  private final EventHistorySource source;
  private final EventHistoryStore store;
  private final EventCollectionSettings settings;
  private final Sleeper sleeper;

  /**
   * @param source 事件源
   * @param store 事件库（幂等键 repo + event_id）
   * @param settings 窗口/重试/上限参数
   */
  public IncrementalEventHistoryCollector(
      EventHistorySource source, EventHistoryStore store, EventCollectionSettings settings) {
    this(source, store, settings, Thread::sleep);
  }

  /**
   * @param sleeper 重试退避（测试注入零延迟）
   */
  IncrementalEventHistoryCollector(
      EventHistorySource source,
      EventHistoryStore store,
      EventCollectionSettings settings,
      Sleeper sleeper) {
    if (source == null || store == null || settings == null) {
      throw new IllegalArgumentException("source/store/settings 不能为空");
    }
    this.source = source;
    this.store = store;
    this.settings = settings;
    this.sleeper = sleeper == null ? Thread::sleep : sleeper;
  }

  @Override
  public CollectionResult collect(Instant since) {
    Instant requested = since == null ? Instant.EPOCH : since;
    List<String> warnings = new ArrayList<>();
    Optional<Instant> previous;
    try {
      previous = retry("读取水位", () -> store.checkpoint(source.id()), warnings);
    } catch (CollectFailure e) {
      // 水位不可读不阻断采集：按请求窗口执行，并把降级写进 warnings（note 如实带出，09§4）
      warnings.add("水位不可读，按请求窗口采集: " + e.getMessage());
      previous = Optional.empty();
    }
    Instant effectiveSince = requested;
    if (previous.isPresent()) {
      Instant overlapStart = previous.get().minus(settings.overlapHours(), ChronoUnit.HOURS);
      if (overlapStart.isAfter(effectiveSince)) {
        effectiveSince = overlapStart;
      }
    }

    EventHistorySource.ReadResult read;
    final Instant windowStart = effectiveSince;
    try {
      read = retry("事件源读取", () -> source.read(windowStart), warnings);
    } catch (CollectFailure e) {
      return CollectionResult.failed("事件史采集失败：" + e.getMessage() + "（未写入任何事件）");
    }

    Map<String, ProductEvent> unique = new LinkedHashMap<>();
    int duplicateKeys = 0;
    int outsideWindow = 0;
    for (ProductEvent event : read.events()) {
      if (event.occurredAt().isBefore(windowStart)) {
        outsideWindow++;
        continue;
      }
      String key = event.repo() + "\u0000" + event.eventId();
      if (unique.put(key, event) != null) {
        duplicateKeys++;
      }
    }
    List<ProductEvent> ordered = new ArrayList<>(unique.values());
    ordered.sort(
        Comparator.comparing(ProductEvent::occurredAt).thenComparing(ProductEvent::eventId));
    boolean truncated = ordered.size() > settings.maxEventsPerRun();
    List<ProductEvent> batch =
        truncated ? List.copyOf(ordered.subList(0, settings.maxEventsPerRun())) : ordered;

    EventHistoryStore.UpsertResult upsert;
    try {
      upsert = retry("事件库写入", () -> store.upsertAll(batch), warnings);
    } catch (CollectFailure e) {
      return CollectionResult.failed(
          "事件史入库失败：" + e.getMessage() + "（可能已部分写入；幂等键 (repo, event_id) 保证重跑不产生重复）");
    }

    String watermarkNote = advanceWatermark(previous, batch, read.failures(), warnings);
    int tickets = (int) batch.stream().filter(ProductEvent::ticketLike).count();
    int incidents = (int) batch.stream().filter(ProductEvent::incidentLike).count();
    int skipped = outsideWindow + duplicateKeys + (ordered.size() - batch.size());
    return new CollectionResult(
        true,
        tickets,
        incidents,
        buildNote(read, upsert, skipped, watermarkNote, truncated, warnings),
        read.events().size(),
        upsert.inserted(),
        upsert.updated(),
        skipped,
        read.failures().size());
  }

  /**
   * 采集结果说明（09§4：截断/解析失败/重试记录如实带出，不静默）。
   *
   * @param read 事件源读取结果
   * @param upsert 入库结果
   * @param skipped 跳过条数（窗口外 + 批内重复 + 单次上限）
   * @param watermarkNote 水位说明
   * @param truncated 是否触发单次上限
   * @param warnings 重试记录
   * @return 说明文本
   */
  private String buildNote(
      EventHistorySource.ReadResult read,
      EventHistoryStore.UpsertResult upsert,
      int skipped,
      String watermarkNote,
      boolean truncated,
      List<String> warnings) {
    StringBuilder note =
        new StringBuilder("事件史采集完成：读取 ")
            .append(read.linesRead())
            .append(" 行，候选 ")
            .append(read.events().size())
            .append("，插入 ")
            .append(upsert.inserted())
            .append("，覆盖 ")
            .append(upsert.updated())
            .append("，跳过 ")
            .append(skipped)
            .append("（批内重复/窗口外/单次上限），解析失败 ")
            .append(read.failures().size())
            .append("；")
            .append(watermarkNote);
    if (truncated) {
      note.append("；单次上限 ").append(settings.maxEventsPerRun()).append(" 条已截断，剩余事件下次窗口继续");
    }
    if (!read.failures().isEmpty()) {
      note.append("；首个失败: ").append(read.failures().get(0));
    }
    if (!warnings.isEmpty()) {
      note.append("；重试记录: ").append(String.join(" | ", warnings));
    }
    return note.toString();
  }

  private String advanceWatermark(
      Optional<Instant> previous,
      List<ProductEvent> batch,
      List<String> failures,
      List<String> warnings) {
    if (!failures.isEmpty()) {
      return "水位未推进（" + failures.size() + " 行解析失败，下次重试）";
    }
    if (batch.isEmpty()) {
      return "水位不变（窗口内无新事件）";
    }
    Instant maxOccurred =
        batch.stream().map(ProductEvent::occurredAt).max(Instant::compareTo).orElseThrow();
    Instant target =
        previous.map(p -> maxOccurred.isAfter(p) ? maxOccurred : p).orElse(maxOccurred);
    try {
      retry(
          "保存水位",
          () -> {
            store.saveCheckpoint(source.id(), target);
            return null;
          },
          warnings);
      return "水位 " + target;
    } catch (CollectFailure e) {
      return "水位未推进（保存失败：" + e.getMessage() + "；事件已入库，下次按重叠窗口重跑安全）";
    }
  }

  private <T> T retry(String operation, Attempt<T> attempt, List<String> warnings) {
    Exception last = null;
    for (int attemptNo = 1; attemptNo <= settings.maxAttempts(); attemptNo++) {
      try {
        return attempt.run();
      } catch (IOException | RuntimeException e) {
        last = e;
        if (attemptNo < settings.maxAttempts()) {
          warnings.add(
              operation
                  + " 第 "
                  + attemptNo
                  + " 次失败（"
                  + describe(e)
                  + "），"
                  + settings.retryBackoffMillis() * attemptNo
                  + "ms 后重试");
          try {
            sleeper.sleep(settings.retryBackoffMillis() * attemptNo);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CollectFailure(operation + " 重试等待被中断", interrupted);
          }
        }
      }
    }
    throw new CollectFailure(
        operation + " 重试 " + settings.maxAttempts() + " 次仍失败（" + describe(last) + "）", last);
  }

  private static String describe(Exception e) {
    if (e == null) {
      return "未知错误";
    }
    String message = e.getMessage();
    return e.getClass().getSimpleName() + (message == null ? "" : ": " + message);
  }

  /** 重试动作。 */
  @FunctionalInterface
  private interface Attempt<T> {

    /**
     * 执行。
     *
     * @return 结果
     * @throws IOException 来源读取失败（重试动作的受检异常仅此一种，由重试器收敛）
     */
    T run() throws IOException;
  }

  /** 重试耗尽/中断。 */
  private static final class CollectFailure extends RuntimeException {

    CollectFailure(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
