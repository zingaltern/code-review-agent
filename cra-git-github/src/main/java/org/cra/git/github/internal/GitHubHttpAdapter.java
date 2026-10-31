package org.cra.git.github.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.CharacterCodingException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.LongConsumer;
import java.util.regex.Pattern;
import org.cra.common.context.PathGlobs;
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
import org.cra.common.git.DispositionSupport;
import org.cra.common.git.FileContent;
import org.cra.common.git.InlineComment;
import org.cra.common.git.LedgerComment;
import org.cra.common.git.PostCommitReviewCommand;
import org.cra.common.git.PostIssueCommand;
import org.cra.common.git.PostReviewCommand;
import org.cra.common.git.PrReviewResult;
import org.cra.common.git.PublishStatus;
import org.cra.common.git.PullRequestRef;
import org.cra.common.git.RepoRef;
import org.cra.common.git.ReviewItemResult;
import org.cra.common.text.Texts;
import org.cra.git.api.GitPlatformAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * GitHub REST v3 实现（05§2.3 端点清单锁定）：compare diff / contents raw / PR review（只装
 * inline，event=COMMENT，commit_id 绑定 head sha）/ issue comments（Summary 载体）/ 账本拉取 （inline 与 issue
 * 两端点合并，只认 bot 自身评论）。
 *
 * <p>传输与退避在 {@link GitHubHttpTransport}；hunk 解析/严格 UTF-8/路径编码等纯辅助原在 {@link GitHubPayloadSupport}，
 * 其中可下沉部分已收敛至 cra-common（{@link DiffHunk#parseAll}、{@link Texts#strictUtf8}、{@link
 * org.cra.common.context.PathGlobs}、{@link DispositionSupport}，ADR-0016）；本类只保留端口语义、响应映射与发布 payload
 * 组装。整包 422 逐条降级在实现内处理（09§4 边界转换），处理不了的以 {@link CraIntegrationException} 上抛。
 */
public class GitHubHttpAdapter implements GitPlatformAdapter, CommentSource {

  private static final Logger LOG = LoggerFactory.getLogger(GitHubHttpAdapter.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final GitHubHttpTransport transport;
  private final GitHubCommentReader commentReader;

  /**
   * @param apiBase API 根（默认 https://api.github.com，测试注入 WireMock 地址）
   * @param token GITHUB_TOKEN
   * @param http HTTP 客户端
   * @param sleeper 退避睡眠（测试注入即时实现）
   */
  public GitHubHttpAdapter(String apiBase, String token, HttpClient http, LongConsumer sleeper) {
    this.transport = new GitHubHttpTransport(apiBase, token, http, sleeper);
    this.commentReader = new GitHubCommentReader(transport);
  }

  // ---------- 读：diff / 文件 / 文件树 ----------

  @Override
  public DiffResult fetchDiff(RepoRef repo, String baseSha, String headSha) {
    JsonNode root =
        transport.getJson(
            repo,
            "/compare/"
                + GitHubPayloadSupport.urlEncode(baseSha)
                + "..."
                + GitHubPayloadSupport.urlEncode(headSha));
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
              DiffHunk.parseAll(patch)));
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
        transport.getBytes(
            repo,
            "/contents/"
                + GitHubPayloadSupport.encodePath(path)
                + "?ref="
                + GitHubPayloadSupport.urlEncode(sha),
            "application/vnd.github.raw");
    try {
      return new FileContent(path, Texts.strictUtf8(bytes), false);
    } catch (CharacterCodingException e) {
      return new FileContent(path, null, true);
    }
  }

  @Override
  public List<String> listFiles(RepoRef repo, String sha, String pathGlob) {
    JsonNode root =
        transport.getJson(
            repo, "/git/trees/" + GitHubPayloadSupport.urlEncode(sha) + "?recursive=1");
    if (root.path("truncated").asBoolean(false)) {
      // ADR-0015 §2.2：不可得不静默——截断的文件树会让"调用方/测试覆盖"判定把未看到的文件当成不存在
      throw new CraIntegrationException("GitHub tree 被平台截断（>100k 条目或 >7MB），无法作为完整文件索引: sha=" + sha);
    }
    Pattern glob = PathGlobs.compile(pathGlob);
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
        reviewId = posted.reviewId().isBlank() ? Optional.empty() : Optional.of(posted.reviewId());
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
        if (!GitHubHttpTransport.isUnprocessable(e)) {
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
    JsonNode response =
        transport.postJson(
            cmd.repoOwner(), cmd.repoName(), "/pulls/" + cmd.prNumber() + "/reviews", payload);
    List<Long> ids = new ArrayList<>();
    for (JsonNode c : response.path("comments")) {
      ids.add(c.path("id").asLong(0));
    }
    JsonNode id = response.path("id");
    String reviewId = id.isMissingNode() || id.isNull() ? "" : id.asText("");
    return new PostedReview(reviewId, ids);
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
        JsonNode response =
            transport.postJson(
                cmd.repoOwner(), cmd.repoName(), "/pulls/" + cmd.prNumber() + "/reviews", payload);
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
        if (!GitHubHttpTransport.isUnprocessable(e)) {
          throw e; // 逐条发布时的鉴权/服务端故障仍是 run 级失败，只有锚定 422 降级
        }
        items.add(fallbackItem(one, e.getMessage()));
      }
    }
  }

  private static ReviewItemResult fallbackItem(InlineComment comment, String reason) {
    return new ReviewItemResult(
        comment.findingId(),
        comment.path(),
        comment.line(),
        PublishStatus.SUMMARY_FALLBACK,
        AnchorStatus.SUMMARY_FALLBACK,
        null,
        reason);
  }

  private Optional<Long> postSummary(PostReviewCommand cmd) {
    ObjectNode payload = MAPPER.createObjectNode();
    payload.put("body", cmd.summaryBody());
    try {
      JsonNode response =
          transport.postJson(
              cmd.repoOwner(), cmd.repoName(), "/issues/" + cmd.prNumber() + "/comments", payload);
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
        transport
            .request(path)
            .header("Accept", "application/vnd.github+json")
            .header("Content-Type", "application/json")
            .method("PATCH", HttpRequest.BodyPublishers.ofString(payload.toString()))
            .build();
    transport.execute(request, path);
  }

  private record PostedReview(String reviewId, List<Long> commentIds) {}

  // ---------- 写：commit comment（M4c）/ issue（M4d）载体 ----------

  @Override
  public Optional<Long> postCommitReview(PostCommitReviewCommand cmd) {
    ObjectNode payload = MAPPER.createObjectNode();
    payload.put("body", cmd.body());
    JsonNode response =
        transport.postJson(
            cmd.repoOwner(),
            cmd.repoName(),
            "/commits/" + GitHubPayloadSupport.urlEncode(cmd.commitSha()) + "/comments",
            payload);
    long id = response.path("id").asLong(0);
    return id > 0 ? Optional.of(id) : Optional.empty();
  }

  @Override
  public Optional<Long> postIssueReview(PostIssueCommand cmd) {
    ObjectNode payload = MAPPER.createObjectNode();
    payload.put("title", cmd.title());
    payload.put("body", cmd.body());
    if (!cmd.labels().isEmpty()) {
      ArrayNode labels = payload.putArray("labels");
      cmd.labels().forEach(labels::add);
    }
    JsonNode response = transport.postJson(cmd.repoOwner(), cmd.repoName(), "/issues", payload);
    long number = response.path("number").asLong(0);
    return number > 0 ? Optional.of(number) : Optional.empty();
  }

  // ---------- CommentSource / 账本与处置读侧（实现委托 GitHubCommentReader） ----------

  @Override
  public CommentLedger fetchBotComments(RepoRef repo, int prNumber) {
    return commentReader.fetchBotComments(repo, prNumber);
  }

  @Override
  public CommentLedger fetchCommitBotComments(RepoRef repo, String commitSha) {
    return commentReader.fetchCommitBotComments(repo, commitSha);
  }

  @Override
  public CommentLedger fetchIssueBotComments(RepoRef repo, String commitSha) {
    return commentReader.fetchIssueBotComments(repo, commitSha);
  }

  @Override
  public List<LedgerComment> listBotIssues(RepoRef repo, Instant since) {
    return commentReader.listBotIssues(repo, since);
  }

  @Override
  public List<PullRequestRef> listPullRequests(RepoRef repo, Instant since) {
    return commentReader.listPullRequests(repo, since);
  }

  @Override
  public DispositionSignal[] fetchDispositionSignals(RepoRef repo, int prNumber) {
    return commentReader.fetchDispositionSignals(repo, prNumber);
  }
}
