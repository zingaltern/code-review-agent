package org.cra.server.support;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.cra.server.delivery.DeliveryRecord;
import org.cra.server.delivery.DeliveryStore;

/** 内存 delivery 登记（单测用；语义与 PG 实现一致：claim 幂等、release 只删 RECEIVED）。 */
public final class InMemoryDeliveryStore implements DeliveryStore {

  private final Map<String, DeliveryRecord> records = new ConcurrentHashMap<>();

  @Override
  public boolean claim(DeliveryRecord record) {
    return records.putIfAbsent(record.deliveryId(), record) == null;
  }

  @Override
  public Optional<DeliveryRecord> find(String deliveryId) {
    return Optional.ofNullable(records.get(deliveryId));
  }

  @Override
  public void markProcessing(String deliveryId) {
    records.computeIfPresent(
        deliveryId,
        (key, value) ->
            value.status().equals(DeliveryRecord.RECEIVED)
                ? copy(value, DeliveryRecord.PROCESSING, value.runId(), value.detail())
                : value);
  }

  @Override
  public void markCompleted(String deliveryId, String runId, String detail) {
    records.computeIfPresent(
        deliveryId, (key, value) -> copy(value, DeliveryRecord.COMPLETED, runId, detail));
  }

  @Override
  public void markFailed(String deliveryId, String detail) {
    records.computeIfPresent(
        deliveryId, (key, value) -> copy(value, DeliveryRecord.FAILED, value.runId(), detail));
  }

  @Override
  public void release(String deliveryId) {
    records.computeIfPresent(
        deliveryId, (key, value) -> value.status().equals(DeliveryRecord.RECEIVED) ? null : value);
  }

  @Override
  public synchronized boolean resetFailed(String deliveryId) {
    DeliveryRecord current = records.get(deliveryId);
    if (current == null || !current.status().equals(DeliveryRecord.FAILED)) {
      return false;
    }
    records.put(deliveryId, copy(current, DeliveryRecord.RECEIVED, null, null));
    return true;
  }

  private static DeliveryRecord copy(
      DeliveryRecord source, String status, String runId, String detail) {
    return new DeliveryRecord(
        source.deliveryId(),
        source.repo(),
        source.prNumber(),
        source.headSha(),
        source.eventAction(),
        status,
        runId,
        detail,
        source.receivedAt(),
        java.time.Instant.now());
  }
}
