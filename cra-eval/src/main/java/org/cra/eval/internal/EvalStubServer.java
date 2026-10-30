package org.cra.eval.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 评测用 GitHub REST v3 stub 服务器（JDK 自带 httpserver，无新依赖）：以 fixture 的 diff.patch 与解包快照为数据面离线驱动被测
 * CLI（06§1.1"评测 job 用离线 fixture 不拉 GitHub"），并捕获发布写请求（POST reviews / issue comments）作为 findings
 * 观测面——被测对象保持黑盒（cra-runner-cli 子进程，10§2）。
 *
 * <p>端点面 = M0 流水线实际触达的 8 个（cra-git-github GitHubHttpAdapter）：compare / contents raw / git trees /
 * user / pulls comments / issues comments（读，账本恒空 → 幂等闸不命中）+ reviews / issues comments（写，捕获）。
 */
public final class EvalStubServer implements AutoCloseable {

  /** 捕获的发布写请求。 */
  public record CapturedReview(
      String commitId, String reviewBody, List<CapturedComment> comments) {}

  /** 捕获的 inline 评论。 */
  public record CapturedComment(String path, int line, String side, String body) {}

  /** 捕获的 Summary issue comment。 */
  public record CapturedSummary(String body) {}

  private record Seed(
      List<DiffSplitter.PatchedFile> files, String headSha, String baseSha, Path snapshotRoot) {}

  /** 请求体上限（stub 只接收评测 CLI 的发布写请求）。 */
  private static final int MAX_BODY_BYTES = 4 * 1024 * 1024;

  private final HttpServer server;
  private final java.util.concurrent.ExecutorService executor;
  private final Seed seed;
  private final ConcurrentLinkedQueue<CapturedReview> reviews = new ConcurrentLinkedQueue<>();
  private final ConcurrentLinkedQueue<CapturedSummary> summaries = new ConcurrentLinkedQueue<>();
  private final AtomicLong ids = new AtomicLong(5000);

  private EvalStubServer(
      Seed seed, HttpServer server, java.util.concurrent.ExecutorService executor) {
    this.seed = seed;
    this.server = server;
    this.executor = executor;
  }

  /**
   * 启动 stub 服务器（绑定随机端口）。
   *
   * @param files 由 diff.patch 拆出的逐文件 patch
   * @param baseSha base
   * @param headSha head
   * @param snapshotRoot 解包快照根（contents 端点数据面）
   * @return 运行中的服务器
   */
  public static EvalStubServer start(
      List<DiffSplitter.PatchedFile> files, String baseSha, String headSha, Path snapshotRoot) {
    Seed seed = new Seed(files, headSha, baseSha, snapshotRoot);
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      // 默认实现是单线程：一个慢请求会让并发的 contents 请求排队，评测被误判为超时
      java.util.concurrent.ExecutorService executor =
          java.util.concurrent.Executors.newFixedThreadPool(
              8,
              runnable -> {
                Thread thread = new Thread(runnable, "cra-eval-stub");
                thread.setDaemon(true);
                return thread;
              });
      server.setExecutor(executor);
      EvalStubServer stub = new EvalStubServer(seed, server, executor);
      server.createContext("/", stub::handle);
      server.start();
      return stub;
    } catch (IOException e) {
      throw new UncheckedIOException("stub 服务器启动失败", e);
    }
  }

  /** 监听端口。 */
  public int port() {
    return server.getAddress().getPort();
  }

  /** base URL。 */
  public String baseUrl() {
    return "http://127.0.0.1:" + port();
  }

  /** 捕获的 PR review（含 inline）。 */
  public List<CapturedReview> reviews() {
    return new ArrayList<>(reviews);
  }

  /** 捕获的 Summary issue comment。 */
  public List<CapturedSummary> summaries() {
    return new ArrayList<>(summaries);
  }

  @Override
  public void close() {
    server.stop(0);
    executor.shutdownNow();
  }

  /** contents 路径提取（任意长度 owner/repo；组 1 = 文件路径）。 */
  private static final java.util.regex.Pattern CONTENTS_PATH =
      java.util.regex.Pattern.compile("^/repos/[^/]+/[^/]+/contents/(.+)$");

  // ---------- 请求分发 ----------

  private void handle(HttpExchange exchange) throws IOException {
    try {
      String method = exchange.getRequestMethod();
      String path = exchange.getRequestURI().getRawPath();
      String query = exchange.getRequestURI().getRawQuery();
      if ("GET".equals(method)) {
        routeGet(exchange, path, query);
      } else if ("POST".equals(method)) {
        routePost(exchange, path);
      } else {
        respondJson(exchange, 405, Map.of("message", "method not allowed"));
      }
    } catch (RuntimeException e) {
      // 非法请求是协议面事实（400），不是"网络故障"：必须回响应，否则 CLI 端只能看到连接被关
      try {
        respondJson(exchange, 400, Map.of("message", "bad request: " + e.getMessage()));
      } catch (IOException ignored) {
        // 响应头已发出/连接已断：无可恢复动作
      }
    } finally {
      exchange.close();
    }
  }

  private void routeGet(HttpExchange exchange, String path, String query) throws IOException {
    // /repos/{o}/{r}/compare/{base}...{head}
    if (path.matches("/repos/[^/]+/[^/]+/compare/.+")) {
      respondJson(exchange, 200, compareJson());
      return;
    }
    // /repos/{o}/{r}/contents/{path}
    java.util.regex.Matcher contents = CONTENTS_PATH.matcher(path);
    if (contents.matches()) {
      // 前缀解析必须按正则提取（owner/repo 长度不定；硬编码 "/repos/a/b/contents/" 的 substring
      // 长度在真实 owner/repo 下会截断路径 → 评测形态 REVIEW.md 与文件全文恒 404，M1 起潜伏）
      String filePath = percentDecode(contents.group(1));
      Path resolved = seed.snapshotRoot().resolve(filePath).normalize();
      if (!resolved.startsWith(seed.snapshotRoot().normalize()) || !Files.isRegularFile(resolved)) {
        respondJson(exchange, 404, Map.of("message", "Not Found"));
        return;
      }
      byte[] body = Files.readAllBytes(resolved);
      exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      return;
    }
    // /repos/{o}/{r}/git/trees/{sha}
    if (path.matches("/repos/[^/]+/[^/]+/git/trees/.+")) {
      respondJson(exchange, 200, Map.of("sha", seed.headSha(), "tree", List.of()));
      return;
    }
    // /user（bot 身份）
    if ("/user".equals(path)) {
      respondJson(exchange, 200, Map.of("login", "cra-eval-stub-bot"));
      return;
    }
    // 账本读端点：恒空（幂等闸不命中，重复评测可重放）
    if (path.matches("/repos/[^/]+/[^/]+/pulls/\\d+/comments")
        || path.matches("/repos/[^/]+/[^/]+/issues/\\d+/comments")) {
      respondJson(exchange, 200, List.of());
      return;
    }
    respondJson(exchange, 404, Map.of("message", "Not Found: " + path));
  }

  private void routePost(HttpExchange exchange, String path) throws IOException {
    byte[] raw = readBody(exchange);
    JsonNode payload = Json.MAPPER.readTree(raw);
    if (payload == null || !payload.isObject()) {
      respondJson(exchange, 400, Map.of("message", "body must be a JSON object"));
      return;
    }
    if (path.matches("/repos/[^/]+/[^/]+/pulls/\\d+/reviews")) {
      List<CapturedComment> comments = new ArrayList<>();
      for (JsonNode c : payload.path("comments")) {
        comments.add(
            new CapturedComment(
                c.path("path").asText(),
                c.path("line").asInt(),
                c.path("side").asText("RIGHT"),
                c.path("body").asText()));
      }
      reviews.add(
          new CapturedReview(
              payload.path("commit_id").asText(), payload.path("body").asText(), comments));
      respondJson(exchange, 200, reviewResponseJson(comments.size()));
      return;
    }
    if (path.matches("/repos/[^/]+/[^/]+/issues/\\d+/comments")) {
      summaries.add(new CapturedSummary(payload.path("body").asText()));
      respondJson(exchange, 200, Map.of("id", ids.incrementAndGet()));
      return;
    }
    respondJson(exchange, 404, Map.of("message", "Not Found: " + path));
  }

  // ---------- 数据面构造 ----------

  private JsonNode compareJson() {
    var root = Json.MAPPER.createObjectNode();
    root.put("status", "diverged");
    root.put("ahead_by", 1);
    root.put("total_commits", 1);
    var commits = root.putArray("commits");
    var commit = commits.addObject();
    commit.put("sha", seed.headSha());
    commit.putObject("commit").put("message", "fixture diff (base=" + seed.baseSha() + ")");
    var files = root.putArray("files");
    for (DiffSplitter.PatchedFile f : seed.files()) {
      var node = files.addObject();
      node.put("filename", f.newPath() != null ? f.newPath() : f.oldPath());
      if (f.oldPath() != null && f.newPath() != null && !f.oldPath().equals(f.newPath())) {
        node.put("previous_filename", f.oldPath());
      }
      node.put("status", f.status());
      if (f.patch() != null) {
        node.put("patch", f.patch());
      } else {
        node.putNull("patch");
      }
    }
    return root;
  }

  private JsonNode reviewResponseJson(int commentCount) {
    var root = Json.MAPPER.createObjectNode();
    root.put("id", ids.incrementAndGet());
    var comments = root.putArray("comments");
    for (int i = 0; i < commentCount; i++) {
      comments.addObject().put("id", ids.incrementAndGet());
    }
    return root;
  }

  private void respondJson(HttpExchange exchange, int status, Object body) throws IOException {
    byte[] bytes = Json.MAPPER.writeValueAsBytes(body);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
  }

  private static byte[] readBody(HttpExchange exchange) throws IOException {
    byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
    if (body.length > MAX_BODY_BYTES) {
      throw new IllegalArgumentException("请求体超过上限 " + MAX_BODY_BYTES + " 字节");
    }
    return body;
  }

  /** 仅解码 %XX（不解 '+'——路径中 '+' 是合法字符）；按 UTF-8 字节序列解码（非 ASCII 文件名）。 */
  private static String percentDecode(String value) {
    java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(value.length());
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '%' && i + 2 < value.length()) {
        int hi = Character.digit(value.charAt(i + 1), 16);
        int lo = Character.digit(value.charAt(i + 2), 16);
        if (hi >= 0 && lo >= 0) {
          bytes.write((hi << 4) | lo);
          i += 2;
          continue;
        }
      }
      if (c < 0x80) {
        bytes.write(c);
      } else {
        bytes.writeBytes(String.valueOf(c).getBytes(StandardCharsets.UTF_8));
      }
    }
    return bytes.toString(StandardCharsets.UTF_8);
  }

  /** 供测试：快照根（数据面只读）。 */
  public String snapshotText(String relativePath) throws IOException {
    return Files.readString(seed.snapshotRoot().resolve(relativePath), StandardCharsets.UTF_8);
  }
}
