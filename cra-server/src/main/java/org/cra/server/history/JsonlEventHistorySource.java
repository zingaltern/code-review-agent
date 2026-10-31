package org.cra.server.history;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import org.cra.common.event.ProductEvent;

/**
 * JSONL 事件源（M4f-full 第一版，ADR-0012）：本地文件 / {@code file:} URI / 对象存储 HTTP(S) URL（含 S3 预签名 URL）。
 *
 * <p>每行一个 JSON 对象，字段见 ADR-0012；未知字段忽略，{@code raw_json} 缺省时保存整行原始对象。行级解析失败不中断整批——
 * 返回失败说明，采集器据此**不推进水位**（下次重试），不静默丢事件。
 *
 * <p>刻意不引入云 SDK：对象存储通过 HTTP(S) 读取，Jira/Confluence 等连接器是 11§2 登记的后续扩展点。
 */
public final class JsonlEventHistorySource implements EventHistorySource {

  private static final int MAX_FAILURE_MESSAGES = 100;

  private static final int MAX_FAILURE_MESSAGE_CHARS = 300;

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

  private final String location;
  private final long maxBytes;
  private final ObjectMapper mapper;
  private final Clock clock;

  /**
   * @param location 文件路径、{@code file:} URI 或 {@code http(s):} URL
   * @param maxBytes 单次读取字节上限（防误配巨大对象；超出按读取失败处理）
   * @param clock 采集时间时钟（{@code collected_at} 缺省值）
   */
  public JsonlEventHistorySource(String location, long maxBytes, Clock clock) {
    if (location == null || location.isBlank()) {
      throw new IllegalArgumentException("事件源位置不能为空");
    }
    if (maxBytes <= 0) {
      throw new IllegalArgumentException("maxBytes 必须为正: " + maxBytes);
    }
    this.location = location.strip();
    this.maxBytes = maxBytes;
    this.mapper = new ObjectMapper();
    this.clock = clock == null ? Clock.systemUTC() : clock;
  }

  /**
   * 水位键 = 去查询串的位置：预签名 URL 的查询串是临时凭证（会进入 checkpoint 表并随轮换漂移），不能当身份；文件路径原样保留（`?` 是合法文件名字符）。
   *
   * @return 来源标识
   */
  @Override
  public String id() {
    return baseLocation();
  }

  @Override
  public ReadResult read(Instant since) throws IOException {
    Instant windowStart = since == null ? Instant.EPOCH : since;
    byte[] payload = readAll();
    String text = new String(payload, StandardCharsets.UTF_8);
    List<ProductEvent> events = new ArrayList<>();
    List<String> failures = new ArrayList<>();
    int linesRead = 0;
    int failureCount = 0;
    String[] lines = text.split("\n", -1);
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i].strip();
      if (line.isEmpty() || line.startsWith("#")) {
        continue;
      }
      linesRead++;
      try {
        ProductEvent event = parse(line);
        if (!event.occurredAt().isBefore(windowStart)) {
          events.add(event);
        }
      } catch (IOException | RuntimeException e) {
        failureCount++;
        if (failures.size() < MAX_FAILURE_MESSAGES) {
          failures.add("line " + (i + 1) + ": " + cap(message(e)));
        }
      }
    }
    if (failureCount > failures.size()) {
      failures.add("…（另有 " + (failureCount - failures.size()) + " 条解析失败省略）");
    }
    return new ReadResult(events, failures, linesRead);
  }

  private ProductEvent parse(String line) throws IOException {
    JsonNode node = mapper.readTree(line);
    if (node == null || !node.isObject()) {
      throw new IllegalArgumentException("JSONL 行必须是对象");
    }
    Instant collectedAt =
        node.hasNonNull("collected_at")
            ? parseInstant(node.get("collected_at").asText())
            : clock.instant();
    String rawJson =
        node.hasNonNull("raw_json") && node.get("raw_json").isObject()
            ? mapper.writeValueAsString(node.get("raw_json"))
            : mapper.writeValueAsString(node);
    return new ProductEvent(
        requiredText(node, "event_id"),
        requiredText(node, "repo"),
        optionalText(node, "service"),
        optionalText(node, "tenant"),
        parseInstant(requiredText(node, "occurred_at")),
        requiredText(node, "type"),
        requiredText(node, "summary"),
        optionalText(node, "source_url"),
        stringList(node.get("commit_refs")),
        stringList(node.get("paths")),
        rawJson,
        collectedAt);
  }

  private byte[] readAll() throws IOException {
    if (location.startsWith("http://") || location.startsWith("https://")) {
      return readHttp();
    }
    Path path = location.startsWith("file:") ? Path.of(URI.create(location)) : Path.of(location);
    try (InputStream in = Files.newInputStream(path)) {
      return readLimited(in);
    }
  }

  private byte[] readHttp() throws IOException {
    HttpClient client =
        HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(location))
            .timeout(REQUEST_TIMEOUT)
            .GET()
            .header("Accept", "application/x-ndjson, application/json")
            .build();
    try {
      HttpResponse<InputStream> response =
          client.send(request, HttpResponse.BodyHandlers.ofInputStream());
      int status = response.statusCode();
      if (status < 200 || status >= 300) {
        response.body().close();
        throw new IOException("事件源 HTTP " + status + "（" + displayLocation() + "）");
      }
      try (InputStream in = response.body()) {
        return readLimited(in);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("事件源读取被中断（" + displayLocation() + "）", e);
    }
  }

  private byte[] readLimited(InputStream in) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    long total = 0;
    int read;
    while ((read = in.read(buffer)) >= 0) {
      total += read;
      if (total > maxBytes) {
        throw new IOException(
            "事件源超过读取上限 " + maxBytes + " 字节（" + displayLocation() + "）；请拆分对象或调大上限");
      }
      out.write(buffer, 0, read);
    }
    return out.toByteArray();
  }

  /** 日志/异常里的来源展示（查询串可能是预签名凭证，只留脱敏标记）。 */
  private String displayLocation() {
    String base = baseLocation();
    return base.equals(location) ? location : base + "?<redacted>";
  }

  private String baseLocation() {
    if (!location.startsWith("http://") && !location.startsWith("https://")) {
      return location;
    }
    int query = location.indexOf('?');
    return query < 0 ? location : location.substring(0, query);
  }

  private static String requiredText(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull() || value.asText().isBlank()) {
      throw new IllegalArgumentException("缺少必填字段 " + field);
    }
    return value.asText();
  }

  private static String optionalText(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText();
  }

  private static List<String> stringList(JsonNode node) {
    if (node == null || node.isNull()) {
      return List.of();
    }
    List<String> out = new ArrayList<>();
    if (node.isArray()) {
      for (JsonNode item : node) {
        if (item != null && !item.isNull() && !item.asText().isBlank()) {
          out.add(item.asText());
        }
      }
      return out;
    }
    if (node.isTextual()) {
      for (String part : node.asText().split(",")) {
        if (!part.isBlank()) {
          out.add(part.strip());
        }
      }
      return out;
    }
    throw new IllegalArgumentException("字段必须是数组或逗号分隔字符串: " + node.getNodeType());
  }

  private static Instant parseInstant(String value) {
    try {
      return Instant.parse(value);
    } catch (DateTimeParseException e) {
      return OffsetDateTime.parse(value).toInstant();
    }
  }

  private static String message(Exception e) {
    return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
  }

  private static String cap(String value) {
    return value.length() <= MAX_FAILURE_MESSAGE_CHARS
        ? value
        : value.substring(0, MAX_FAILURE_MESSAGE_CHARS) + "…";
  }
}
