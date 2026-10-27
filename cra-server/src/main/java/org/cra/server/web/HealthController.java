package org.cra.server.web;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import javax.sql.DataSource;
import org.cra.server.config.ServerConfig;
import org.cra.server.queue.ReviewQueue;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康检查（02§5 服务化）：/healthz = 进程与队列；/readyz = PostgreSQL 可达性。
 *
 * <p>不引入 Actuator：两项检查足以支撑 docker healthcheck 与负载均衡，避免额外依赖（09§7 白名单）。
 */
@RestController
public final class HealthController {

  private final ReviewQueue reviewQueue;
  private final DataSource dataSource;
  private final ServerConfig config;

  /**
   * @param reviewQueue 队列
   * @param dataSource PG 连接池
   * @param config 服务配置（事件史开关展示）
   */
  public HealthController(ReviewQueue reviewQueue, DataSource dataSource, ServerConfig config) {
    this.reviewQueue = reviewQueue;
    this.dataSource = dataSource;
    this.config = config;
  }

  /**
   * 存活检查。
   *
   * @return 200 + 队列统计
   */
  @GetMapping(path = "/healthz", produces = "application/json")
  public ResponseEntity<Map<String, Object>> health() {
    return ResponseEntity.ok(
        Map.of(
            "status",
            "UP",
            "platform",
            config.platform(),
            "queue",
            reviewQueue.stats(),
            "event_history_enabled",
            config.eventHistoryEnabled()));
  }

  /**
   * 就绪检查（DB 可达才 200）。
   *
   * @return 200/503
   */
  @GetMapping(path = "/readyz", produces = "application/json")
  public ResponseEntity<Map<String, Object>> ready() {
    try (Connection conn = dataSource.getConnection()) {
      if (conn.isValid(2)) {
        return ResponseEntity.ok(Map.of("status", "UP", "db", "reachable"));
      }
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
          .body(Map.of("status", "DOWN", "db", "invalid"));
    } catch (SQLException e) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
          .body(
              Map.of("status", "DOWN", "db", "unreachable", "error", e.getClass().getSimpleName()));
    }
  }
}
