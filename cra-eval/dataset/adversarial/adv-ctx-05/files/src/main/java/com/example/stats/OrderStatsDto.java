package com.example.stats;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** 订单统计 DTO（对外 JSON 输出）。 */
public final class OrderStatsDto {

  private final LocalDate statDate;

  private final long orderCount;

  private final double totalAmount;

  private final double refundAmount;

  private final double avgOrderAmount;

  private final List<String> topSkuIds;

  private OrderStatsDto(
      LocalDate statDate,
      long orderCount,
      double totalAmount,
      double refundAmount,
      double avgOrderAmount,
      List<String> topSkuIds) {
    this.statDate = statDate;
    this.orderCount = orderCount;
    this.totalAmount = totalAmount;
    this.refundAmount = refundAmount;
    this.avgOrderAmount = avgOrderAmount;
    this.topSkuIds = topSkuIds == null ? List.of() : new ArrayList<>(topSkuIds);
  }

  /** 组装单日订单统计。 */
  public static OrderStatsDto of(
      LocalDate statDate,
      long orderCount,
      double totalAmount,
      double refundAmount,
      double avgOrderAmount,
      List<String> topSkuIds) {
    return new OrderStatsDto(
        statDate, orderCount, totalAmount, refundAmount, avgOrderAmount, topSkuIds);
  }

  public LocalDate getStatDate() {
    return statDate;
  }

  public long getOrderCount() {
    return orderCount;
  }

  public double getTotalAmount() {
    return totalAmount;
  }

  public double getRefundAmount() {
    return refundAmount;
  }

  public double getAvgOrderAmount() {
    return avgOrderAmount;
  }

  public List<String> getTopSkuIds() {
    return topSkuIds;
  }
}
