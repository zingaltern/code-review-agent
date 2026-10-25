package org.cra.git.github.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.cra.common.exception.CraIntegrationException;
import org.cra.common.finding.AnchorStatus;
import org.cra.common.finding.Side;
import org.cra.common.git.CommentLedger;
import org.cra.common.git.CommentSource;
import org.cra.common.git.CommitSummary;
import org.cra.common.git.DiffFile;
import org.cra.common.git.DiffHunk;
import org.cra.common.git.DiffResult;
import org.cra.common.git.DispositionSignal;
import org.cra.common.git.FileContent;
import org.cra.common.git.InlineComment;
import org.cra.common.git.LedgerComment;
import org.cra.common.git.LedgerFindingMarker;
import org.cra.common.git.LedgerRunMarker;
import org.cra.common.git.PostReviewCommand;
import org.cra.common.git.PrReviewResult;
import org.cra.common.git.PublishStatus;
import org.cra.common.git.RepoRef;
import org.cra.common.git.ReviewItemResult;
import org.cra.git.api.GitPlatformAdapter;
import org.cra.git.github.internal.ledger.LedgerMarkerParser;
import org.cra.git.github.internal.ledger.LedgerMarkerParser.MarkerParseResult;
import org.cra.git.github.internal.ledger.LedgerMarkerParser.ParseOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * GitHub REST v3 实现（05§2.3 端点清单锁定）：compare diff / contents raw / PR review（只装
 * inline，event=COMMENT，commit_id 绑定 head sha）/ issue comments（Summary 载体）/ 账本拉取 （inline 与 issue
 * 两端点合并，只认 bot 自身评论）。限流告警与 429/403 退避（≤3 次）、整包 422 逐条降级在实现内处理（09§4 边界转换），处理不了的以 {@link
 * CraIntegrationException} 上抛。
 */
public class GitHubHttpAdapter implements GitPlatformAdapter, CommentSource {

  private static final Logger LOG = LoggerFactory.getLogger(GitHubHttpAdapter.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Pattern HUNK_HEADER =
      Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*");
  private static final int MAX_BACKOFF_RETRIES = 3;
  private static final int PAGE_SIZE = 100;
  private static final int MAX_PAGES = 10;

  private final String apiBase;
  private final String token;
  private final HttpClient http;
  private final java.util.function.LongConsumer sleeper;
  private final LedgerMarkerParser markerParser = new LedgerMarkerParser();
  private String cachedLogin;

  /**
   * @param apiBase API 根（默认 https://api.github.com，测试注入 WireMock 地址）
   * @param token GITHUB_TOKEN
   * @param http HTTP 客户端
   * @param sleeper 退避睡眠（测试注入即时实现）
   */
  public GitHubHttpAdapter(
      String apiBase, String token, HttpClient http, java.util.function.LongConsumer sleeper) {
    this.apiBase = apiBase.replaceAll("/+$", "");
    this.token = token;
    this.http = http;
    this.sleeper = sleeper;
  }

  // ---------- 读：diff / 文件 / 文件树 ----------

  @Override
  public DiffResult fetchDiff(RepoRef repo, String baseSha, String headSha) {
    JsonNode root = getJson(repo, "/compare/" + urlEncode(baseSha) + "..." + urlEncode(headSha));
    List<DiffFile> files = new ArrayList<>();
    for (JsonNode f : root.path("files")) {
      String patch =
          f.path("patch").isMissingNode() || f.path("patch").isNull()
              ? null
              : f.path("patch").asText();
      files.add(
          new DiffFile(
              f.path("filename").asText(),
              f.path("previous_filename").isMissingNode()
                  ? null
                  : f.path("previous_filename").asText(),
              f.path("status").asText("modified"),
              patch,
              patch == null ? List.of() : parseHunks(patch)));
    }
    List<CommitSummary> commits = new ArrayList<>();
    for (JsonNode c : root.path("commits")) {
      commits.add(
          new CommitSummary(c.path("sha").asText(), c.path("commit").path("message").asText("")));
    }
    return new DiffResult(baseSha, headSha, files, commits);
  }

  @Override
  public FileContent readFile(RepoRef repo, String sha, String path) {
    byte[] bytes =
        getBytes(
            repo,
            "/contents/" + encodePath(path) + "?ref=" + urlEncode(sha),
            "application/vnd.github.raw");
    try {
      return new FileContent(path, strictUtf8(bytes), false);
    } catch (CharacterCodingException e) {
      return new FileContent(path, null, true);
    }
  }

  @Override
  public List<String> listFiles(RepoRef repo, String sha, String pathGlob) {
    JsonNode root = getJson(repo, "/git/trees/" + urlEncode(sha) + "?recursive=1");
    Pattern glob = globToRegex(pathGlob);
    List<String> out = new ArrayList<>();
    for (JsonNode entry : root.path("tree")) {
      if ("blob".equals(entry.path("type").asText())) {
        String p = entry.path("path").asText();
        if (glob.matcher(p).matches()) {
          out.add(p);
        }
      }
    }
    return out;
  }

  // ---------- 写：PR review（inline）→ Summary issue comment（05§2.2 顺序） ----------

  @Override
  public PrReviewResult postReview(PostReviewCommand cmd) {
    List<ReviewItemResult> items = new ArrayList<>();
    Optional<String> reviewId = Optional.empty();
    if (!cmd.inlineComments().isEmpty()) {
      try {
        PostedReview posted = postInlineReview(cmd, cmd.inlineComments());
        reviewId = Optional.of(posted.reviewId());
        List<Long> ids = posted.commentIds();
        for (int i = 0; i < cmd.inlineComments().size(); i++) {
          InlineComment c = cmd.inlineComments().get(i);
          long commentId = i < ids.size() ? ids.get(i) : 0;
          items.add(
              new ReviewItemResult(
                  c.findingId(),
                  c.path(),
                  c.line(),
                  PublishStatus.PUBLISHED,
                  AnchorStatus.INLINE,
                  commentId > 0 ? commentId : null,
                  ""));
        }
      } catch (CraIntegrationException e) {
        if (!isUnprocessable(e)) {
          throw e; // 非 422 故障不降级，交由 run 级失败处置（02§4.2）
        }
        perItemFallback(cmd, items);
      }
    }
    // Summary 最后发：其 DONE 标记是"完整发布"证据；失败 = 无标记 = 重跑走指纹级补发（02§4.1）
    Optional<Long> summaryId = postSummary(cmd);
    return new PrReviewResult(cmd.prNumber(), reviewId, summaryId, items);
  }

  private PostedReview postInlineReview(PostReviewCommand cmd, List<InlineComment> inline) {
    ObjectNode payload = MAPPER.createObjectNode();
    payload.put("commit_id", cmd.commitId());
    payload.put("event", "COMMENT");
    payload.put("body", cmd.reviewBody());
    ArrayNode comments = payload.putArray("comments");
    for (InlineComment c : inline) {
      ObjectNode node = comments.addObject();
      node.put("path", c.path());
      node.put("line", c.line());
      node.put("side", c.side() == Side.LEFT ? "LEFT" : "RIGHT");
      node.put("body", c.body());
    }
    JsonNode response = postJson(cmd, "/pulls/" + cmd.prNumber() + "/reviews", payload);
    List<Long> ids = new ArrayList<>();
    for (JsonNode c : response.path("comments")) {
      ids.add(c.path("id").asLong(0));
    }
    return new PostedReview(response.path("id").asText(), ids);
  }

  private void perItemFallback(PostReviewCommand cmd, List<ReviewItemResult> items) {
    // 整包 422 → 逐条提交，仍失败的 anchor_status=SUMMARY_FALLBACK（05§2.2）
    for (InlineComment one : cmd.inlineComments()) {
      ObjectNode payload = MAPPER.createObjectNode();
      payload.put("commit_id", cmd.commitId());
      payload.put("event", "COMMENT");
      payload.put("body", cmd.reviewBody());
      ObjectNode node = payload.putArray("comments").addObject();
      node.put("path", one.path());
      node.put("line", one.line());
      node.put("side", one.side() == Side.LEFT ? "LEFT" : "RIGHT");
      node.put("body", one.body());
      try {
        JsonNode response = postJson(cmd, "/pulls/" + cmd.prNumber() + "/reviews", payload);
        long commentId = response.path("comments").path(0).path("id").asLong(0);
        items.add(
            new ReviewItemResult(
                one.findingId(),
                one.path(),
                one.line(),
                PublishStatus.PUBLISHED,
                AnchorStatus.INLINE,
                commentId > 0 ? commentId : null,
                ""));
      } catch (CraIntegrationException e) {
        items.add(
            new ReviewItemResult(
                one.findingId(),
                one.path(),
                one.line(),
                PublishStatus.SUMMARY_FALLBACK,
                AnchorStatus.SUMMARY_FALLBACK,
                null,
                e.getMessage()));
      }
    }
  }

  private Optional<Long> postSummary(PostReviewCommand cmd) {
    ObjectNode payload = MAPPER.createObjectNode();
    payload.put("body", cmd.summaryBody());
    try {
      JsonNode response = postJson(cmd, "/issues/" + cmd.prNumber() + "/comments", payload);
      long id = response.path("id").asLong(0);
      return id > 0 ? Optional.of(id) : Optional.empty();
    } catch (CraIntegrationException e) {
      LOG.warn(
          "summary publish failed; run has no DONE marker, rerun will republish: {}",
          e.getMessage());
      return Optional.empty();
    }
  }

  @Override
  public void updateSummaryComment(RepoRef repo, int prNumber, long commentId, String body) {
    // 过期收尾（02§5）：PATCH issue comments 端点（05§2.3 端点清单），issue comment 可编辑而
    // PR review body 不可。失败以 CraIntegrationException 上抛，调用方按降级处置（run 已完整发布，
    // 收尾失败不回滚完成标记）
    ObjectNode payload = MAPPER.createObjectNode();
    payload.put("body", body);
    String path = "/repos/" + repo.owner() + "/" + repo.repo() + "/issues/comments/" + commentId;
    HttpRequest request =
        request(path)
            .header("Accept", "application/vnd.github+json")
            .header("Content-Type", "application/json")
            .method("PATCH", HttpRequest.BodyPublishers.ofString(payload.toString()))
            .build();
    execute(request, path);
  }

  private record PostedReview(String reviewId, List<Long> commentIds) {}

  // ---------- 账本：bot 评论 + 标记解析（05§1.3） ----------

  @Override
  public CommentLedger fetchBotComments(RepoRef repo, int prNumber) {
    String login = botLogin(repo);
    List<LedgerComment> comments = new ArrayList<>();
    List<LedgerFindingMarker> findings = new ArrayList<>();
    List<LedgerRunMarker> runs = new ArrayList<>();
    int[] suspectCount = {0};
    collectPaged(
        repo, "/pulls/" + prNumber + "/comments", login, LedgerComment.KIND_INLINE, comments);
    collectPaged(
        repo, "/issues/" + prNumber + "/comments", login, LedgerComment.KIND_ISSUE, comments);
    for (LedgerComment c : comments) {
      ParseOutcome outcome = markerParser.parse(c.body());
      suspectCount[0] += outcome.suspects().size();
      if (outcome.marker().isPresent()) {
        MarkerParseResult parsed = outcome.marker().get();
        if (parsed instanceof MarkerParseResult.FindingMark f) {
          findings.add(
              new LedgerFindingMarker(
                  f.fingerprint(),
                  f.file(),
                  f.line(),
                  f.category(),
                  f.findingId(),
                  f.runId(),
                  f.commitSha(),
                  c.id()));
        } else if (parsed instanceof MarkerParseResult.RunMark r) {
          runs.add(
              new LedgerRunMarker(
                  r.runId(), r.commitSha(), r.status(), r.findings(), r.suppressed(), c.id()));
        }
      }
    }
    return new CommentLedger(comments, findings, runs, suspectCount[0]);
  }

  private void collectPaged(
      RepoRef repo, String path, String login, String kind, List<LedgerComment> out) {
    for (int page = 1; page <= MAX_PAGES; page++) {
      JsonNode root = getJson(repo, path + "?per_page=" + PAGE_SIZE + "&page=" + page);
      if (!root.isArray() || root.isEmpty()) {
        return;
      }
      for (JsonNode c : root) {
        if (!login.equals(c.path("user").path("login").asText())) {
          continue; // 只认 bot 自身评论（账本语义，02§5）
        }
        out.add(
            new LedgerComment(
                c.path("id").asLong(),
                kind,
                login,
                c.path("body").asText(""),
                c.path("created_at").isMissingNode()
                    ? Instant.EPOCH
                    : Instant.parse(c.path("created_at").asText()),
                c.path("commit_id").isMissingNode() || c.path("commit_id").isNull()
                    ? null
                    : c.path("commit_id").asText()));
      }
      if (root.size() < PAGE_SIZE) {
        return;
      }
    }
  }

  @Override
  public DispositionSignal[] fetchDispositionSignals(RepoRef repo, int prNumber) {
    // M0 不采（06§1.1 采集随 M3 处置闭环落地）：不可得语义 = 空数组 + 声明（11§4 第 5 条）
    return new DispositionSignal[0];
  }

  private String botLogin(RepoRef repo) {
    if (cachedLogin == null) {
      // /user 不在 /repos/{o}/{r} 前缀下（05§2.3：bot 身份按 token 对应 login）
      HttpRequest request =
          request("/user").header("Accept", "application/vnd.github+json").GET().build();
      cachedLogin = parseJson(execute(request, "/user").body(), "/user").path("login").asText();
    }
    return cachedLogin;
  }

  // ---------- HTTP 基础设施 ----------

  private JsonNode getJson(RepoRef repo, String pathAndQuery) {
    HttpRequest request =
        request("/repos/" + repo.owner() + "/" + repo.repo() + pathAndQuery)
            .header("Accept", "application/vnd.github+json")
            .GET()
            .build();
    HttpResponse<String> response = execute(request, pathAndQuery);
    return parseJson(response.body(), pathAndQuery);
  }

  private String getRaw(RepoRef repo, String pathAndQuery, String accept) {
    HttpRequest request =
        request("/repos/" + repo.owner() + "/" + repo.repo() + pathAndQuery)
            .header("Accept", accept)
            .GET()
            .build();
    return execute(request, pathAndQuery).body();
  }

  private byte[] getBytes(RepoRef repo, String pathAndQuery, String accept) {
    HttpRequest request =
        request("/repos/" + repo.owner() + "/" + repo.repo() + pathAndQuery)
            .header("Accept", accept)
            .GET()
            .build();
    return executeBytes(request, pathAndQuery);
  }

  private JsonNode postJson(PostReviewCommand cmd, String pathAndQuery, ObjectNode payload) {
    HttpRequest request =
        request("/repos/" + cmd.repoOwner() + "/" + cmd.repoName() + pathAndQuery)
            .header("Accept", "application/vnd.github+json")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
            .build();
    HttpResponse<String> response = execute(request, pathAndQuery);
    return parseJson(response.body(), pathAndQuery);
  }

  private JsonNode parseJson(String body, String path) {
    try {
      return MAPPER.readTree(body);
    } catch (IOException e) {
      throw new CraIntegrationException("non-JSON response from " + path, e);
    }
  }

  private HttpRequest.Builder request(String pathAndQuery) {
    return HttpRequest.newBuilder()
        .uri(URI.create(apiBase + pathAndQuery))
        .header("Authorization", "Bearer " + token)
        .header("X-GitHub-Api-Version", "2022-11-28");
  }

  private HttpResponse<String> execute(HttpRequest request, String path) {
    for (int attempt = 0; ; attempt++) {
      HttpResponse<String> response = send(request, path);
      int status = response.statusCode();
      if (status == 200 || status == 201) {
        throttleIfCloseToLimit(response);
        return response;
      }
      if (isRetryable(response) && attempt < MAX_BACKOFF_RETRIES) {
        sleeper.accept(retryAfterMs(response, attempt)); // 指数退避（05§2.3 ≤3 次）
        continue;
      }
      throw new CraIntegrationException(
          "GitHub API " + status + " on " + path + ": " + abbreviate(response.body()));
    }
  }

  private byte[] executeBytes(HttpRequest request, String path) {
    for (int attempt = 0; ; attempt++) {
      HttpResponse<byte[]> response = sendBytes(request, path);
      int status = response.statusCode();
      if (status == 200 || status == 201) {
        return response.body();
      }
      if (isRetryable(response) && attempt < MAX_BACKOFF_RETRIES) {
        sleeper.accept(retryAfterMs(response, attempt));
        continue;
      }
      throw new CraIntegrationException(
          "GitHub API "
              + status
              + " on "
              + path
              + ": "
              + abbreviate(new String(response.body(), StandardCharsets.UTF_8)));
    }
  }

  private HttpResponse<String> send(HttpRequest request, String path) {
    try {
      return http.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new CraIntegrationException("transport failure on " + path, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new CraIntegrationException("interrupted on " + path, e);
    }
  }

  private HttpResponse<byte[]> sendBytes(HttpRequest request, String path) {
    try {
      return http.send(request, HttpResponse.BodyHandlers.ofByteArray());
    } catch (IOException e) {
      throw new CraIntegrationException("transport failure on " + path, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new CraIntegrationException("interrupted on " + path, e);
    }
  }

  private static boolean isRetryable(HttpResponse<?> response) {
    return response.statusCode() == 429
        || (response.statusCode() == 403
            && response.headers().firstValue("Retry-After").isPresent());
  }

  private long retryAfterMs(HttpResponse<?> response, int attempt) {
    String retryAfter = response.headers().firstValue("Retry-After").orElse(null);
    // 正则前置校验替代 try/parse：无效头不进 catch（09§4 禁吞异常），直接走指数退避
    if (retryAfter != null && retryAfter.matches("\\d{1,9}")) {
      return Long.parseLong(retryAfter) * 1000L;
    }
    return 1_000L * (1L << Math.min(attempt, 4));
  }

  private void throttleIfCloseToLimit(HttpResponse<?> response) {
    String remaining = response.headers().firstValue("X-RateLimit-Remaining").orElse(null);
    if (remaining != null && remaining.matches("\\d{1,9}") && Integer.parseInt(remaining) < 20) {
      // 主动节流告警不硬睡（CI 单 run 调用量小，硬睡拖尾；05§2.3）
      LOG.warn("github rate limit low: remaining={}", remaining);
    }
  }

  private static boolean isUnprocessable(CraIntegrationException e) {
    return e.getMessage() != null && e.getMessage().contains("GitHub API 422");
  }

  private static List<DiffHunk> parseHunks(String patch) {
    List<DiffHunk> hunks = new ArrayList<>();
    for (String line : patch.split("\n", -1)) {
      Matcher m = HUNK_HEADER.matcher(line);
      if (m.matches()) {
        hunks.add(
            new DiffHunk(
                Integer.parseInt(m.group(1)),
                m.group(2) == null ? 1 : Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(3)),
                m.group(4) == null ? 1 : Integer.parseInt(m.group(4))));
      }
    }
    return hunks;
  }

  private static String strictUtf8(byte[] bytes) throws CharacterCodingException {
    return StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString();
  }

  private static Pattern globToRegex(String glob) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < glob.length(); i++) {
      char c = glob.charAt(i);
      if (c == '*') {
        if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
          sb.append(".*");
          i++;
        } else {
          sb.append("[^/]*");
        }
      } else if (c == '?') {
        sb.append('.');
      } else {
        sb.append(Pattern.quote(String.valueOf(c)));
      }
    }
    return Pattern.compile(sb.toString());
  }

  private static String encodePath(String path) {
    return String.join(
        "/", Arrays.stream(path.split("/")).map(GitHubHttpAdapter::urlEncode).toList());
  }

  private static String urlEncode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static String abbreviate(String s) {
    if (s == null) {
      return "";
    }
    return s.length() <= 200 ? s : s.substring(0, 200) + "…";
  }
}
