package org.cra.git.github.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.http.HttpRequest;
import java.nio.charset.CharacterCodingException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.cra.common.exception.CraIntegrationException;
import org.cra.common.finding.AnchorStatus;
import org.cra.common.finding.Side;
import org.cra.common.git.CommentLedger;
import org.cra.common.git.CommentSource;
import org.cra.common.git.CommitSummary;
import org.cra.common.git.DiffFile;
import org.cra.common.git.DiffResult;
import org.cra.common.git.DispositionSignal;
import org.cra.common.git.FileContent;
import org.cra.common.git.InlineComment;
import org.cra.common.git.LedgerComment;
import org.cra.common.git.LedgerFindingMarker;
import org.cra.common.git.LedgerRunMarker;
import org.cra.common.git.PostCommitReviewCommand;
import org.cra.common.git.PostIssueCommand;
import org.cra.common.git.PostReviewCommand;
import org.cra.common.git.PrReviewResult;
import org.cra.common.git.PublishStatus;
import org.cra.common.git.PullRequestRef;
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
 * 两端点合并，只认 bot 自身评论）。
 *
 * <p>传输与退避在 {@link GitHubHttpTransport}，payload 解析在 {@link GitHubPayloadSupport}，处置标签在 {@link
 * GitHubDispositionSupport}；本类只保留端口语义、响应映射与发布 payload 组装。整包 422 逐条降级在实现内处理（09§4 边界转换），处理不了的以 {@link
 * CraIntegrationException} 上抛。
 */
public class GitHubHttpAdapter implements GitPlatformAdapter, CommentSource {

  private static final Logger LOG = LoggerFactory.getLogger(GitHubHttpAdapter.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String CI_BOT_LOGIN = "github-actions[bot]";
  private static final int PAGE_SIZE = 100;
  private static final int MAX_PAGES = 100;

  /** 分页硬上限（100×100 = 10k 条）：触顶即显式失败，禁止把部分结果冒充"全部评论"。 */
  private static final int MAX_ITEMS = PAGE_SIZE * MAX_PAGES;

  private final GitHubHttpTransport transport;
  private final LedgerMarkerParser markerParser = new LedgerMarkerParser();
  private String cachedLogin;

  /**
   * @param apiBase API 根（默认 https://api.github.com，测试注入 WireMock 地址）
   * @param token GITHUB_TOKEN
   * @param http HTTP 客户端
   * @param sleeper 退避睡眠（测试注入即时实现）
   */
  public GitHubHttpAdapter(
      String apiBase,
      String token,
      java.net.http.HttpClient http,
      java.util.function.LongConsumer sleeper) {
    this.transport = new GitHubHttpTransport(apiBase, token, http, sleeper);
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
              patch == null ? List.of() : GitHubPayloadSupport.parseHunks(patch)));
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
      return new FileContent(path, GitHubPayloadSupport.strictUtf8(bytes), false);
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
      throw new org.cra.common.exception.CraIntegrationException(
          "GitHub tree 被平台截断（>100k 条目或 >7MB），无法作为完整文件索引: sha=" + sha);
    }
    java.util.regex.Pattern glob = GitHubPayloadSupport.globToRegex(pathGlob);
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
      JsonNode root = transport.getJson(repo, path + "?per_page=" + PAGE_SIZE + "&page=" + page);
      if (!root.isArray() || root.isEmpty()) {
        return;
      }
      if (page == MAX_PAGES) {
        throw new org.cra.common.exception.CraIntegrationException(
            "评论分页超过上限 " + MAX_ITEMS + " 条，无法保证账本完整: " + path);
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
  public CommentLedger fetchCommitBotComments(RepoRef repo, String commitSha) {
    String login = botLogin(repo);
    List<LedgerComment> comments = new ArrayList<>();
    List<LedgerFindingMarker> findings = new ArrayList<>();
    List<LedgerRunMarker> runs = new ArrayList<>();
    int[] suspectCount = {0};
    collectPaged(
        repo,
        "/commits/" + GitHubPayloadSupport.urlEncode(commitSha) + "/comments",
        login,
        LedgerComment.KIND_COMMIT,
        comments);
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

  /**
   * issue 载体账本（M4d，05§2.5）：列出 bot 的 issue，只保留 run 完成标记内 commit 与 {@code commitSha} 一致者——issue
   * 载体本身不带 PR 号，head sha 即键（05§1.3）。
   */
  @Override
  public CommentLedger fetchIssueBotComments(RepoRef repo, String commitSha) {
    List<LedgerComment> matched = new ArrayList<>();
    List<LedgerFindingMarker> findings = new ArrayList<>();
    List<LedgerRunMarker> runs = new ArrayList<>();
    int suspectCount = 0;
    for (LedgerComment c : listBotIssues(repo, null)) {
      ParseOutcome outcome = markerParser.parse(c.body());
      if (!marksCommit(outcome, commitSha)) {
        continue; // 其它 PR / 其它 commit 的夜间 issue 不属于本键作用域
      }
      suspectCount += outcome.suspects().size();
      matched.add(c);
      MarkerParseResult parsed = outcome.marker().orElseThrow();
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
    return new CommentLedger(matched, findings, runs, suspectCount);
  }

  @Override
  public List<LedgerComment> listBotIssues(RepoRef repo, Instant since) {
    String login = botLogin(repo);
    List<LedgerComment> out = new ArrayList<>();
    String sinceQuery =
        since == null ? "" : "&since=" + GitHubPayloadSupport.urlEncode(since.toString());
    for (int page = 1; page <= MAX_PAGES; page++) {
      JsonNode root =
          transport.getJson(
              repo,
              "/issues?state=all&sort=updated&direction=desc&per_page="
                  + PAGE_SIZE
                  + "&page="
                  + page
                  + sinceQuery);
      if (!root.isArray() || root.isEmpty()) {
        break;
      }
      boolean reachedOlder = false;
      for (JsonNode issue : root) {
        if (!issue.path("pull_request").isMissingNode()) {
          continue; // /issues 端点同时返回 PR，PR 条目不是夜间 issue 载体
        }
        Instant updated = GitHubDispositionSupport.instantOrNull(issue.path("updated_at"));
        if (since != null && updated != null && updated.isBefore(since)) {
          reachedOlder = true;
          continue;
        }
        if (!login.equals(issue.path("user").path("login").asText())) {
          continue; // 只认 bot 发布的 issue
        }
        long number = issue.path("number").asLong(0);
        if (number <= 0) {
          continue;
        }
        out.add(
            new LedgerComment(
                number,
                LedgerComment.KIND_ISSUE,
                login,
                issue.path("body").asText(""),
                GitHubDispositionSupport.instantOrEpoch(issue.path("created_at")),
                null));
      }
      if (root.size() < PAGE_SIZE || reachedOlder) {
        break;
      }
    }
    return out;
  }

  private static boolean marksCommit(ParseOutcome outcome, String commitSha) {
    if (outcome.marker().isEmpty()) {
      return false;
    }
    MarkerParseResult parsed = outcome.marker().get();
    if (parsed instanceof MarkerParseResult.RunMark r) {
      return r.commitSha().equalsIgnoreCase(commitSha);
    }
    return ((MarkerParseResult.FindingMark) parsed).commitSha().equalsIgnoreCase(commitSha);
  }

  @Override
  public List<PullRequestRef> listPullRequests(RepoRef repo, Instant since) {
    List<PullRequestRef> out = new ArrayList<>();
    for (int page = 1; page <= MAX_PAGES; page++) {
      JsonNode root =
          transport.getJson(
              repo,
              "/pulls?state=all&sort=updated&direction=desc&per_page="
                  + PAGE_SIZE
                  + "&page="
                  + page);
      if (!root.isArray() || root.isEmpty()) {
        break;
      }
      boolean reachedOlder = false;
      for (JsonNode pr : root) {
        Instant updated = GitHubDispositionSupport.instantOrNull(pr.path("updated_at"));
        if (updated == null || updated.isBefore(since)) {
          // 按 updated desc 排序：本页出现早于窗口的条目，后续页只会更早
          reachedOlder = true;
          continue;
        }
        out.add(
            new PullRequestRef(
                pr.path("number").asInt(),
                pr.path("head").path("sha").asText(""),
                pr.path("merge_commit_sha").isNull() ? null : pr.path("merge_commit_sha").asText(),
                pr.path("state").asText(PullRequestRef.STATE_CLOSED),
                updated,
                GitHubDispositionSupport.instantOrNull(pr.path("merged_at")),
                pr.path("base").path("sha").asText(null)));
      }
      if (root.size() < PAGE_SIZE || reachedOlder) {
        break;
      }
    }
    return out;
  }

  /**
   * 处置信号（06§1.1）：人类对 bot inline 评论的 reply 标签 + Summary issue comment 的 👍/👎。
   *
   * <p>reply 走 `GET /pulls/{n}/comments` 全量评论（人类回复与 bot 评论同端点，按 in_reply_to_id 关联，05§2.3）；reaction
   * 走 `GET /issues/comments/{id}/reactions`。resolve 状态 GraphQL-only，不采。
   */
  @Override
  public DispositionSignal[] fetchDispositionSignals(RepoRef repo, int prNumber) {
    String login = botLogin(repo);
    List<JsonNode> reviewComments = getPaged(repo, "/pulls/" + prNumber + "/comments");
    Set<Long> botCommentIds = new HashSet<>();
    for (JsonNode c : reviewComments) {
      if (login.equals(c.path("user").path("login").asText())) {
        botCommentIds.add(c.path("id").asLong());
      }
    }
    List<DispositionSignal> out = new ArrayList<>();
    for (JsonNode c : reviewComments) {
      long parent = c.path("in_reply_to_id").asLong(0);
      if (parent <= 0 || !botCommentIds.contains(parent)) {
        continue; // 只认对 bot 评论的回复；PR 内人类互相回复不是处置信号
      }
      String body = c.path("body").asText("");
      String tag = GitHubDispositionSupport.replyTag(body);
      if (tag == null) {
        continue; // 无标签回复不改变处置分类（ignored 口径据此保持噪声语义）
      }
      out.add(
          new DispositionSignal(
              DispositionSignal.KIND_REPLY_TAG,
              parent,
              GitHubDispositionSupport.instantOrEpoch(c.path("created_at")),
              tag,
              c.path("user").path("login").asText(""),
              GitHubDispositionSupport.excerpt(body)));
    }
    List<JsonNode> issueComments = getPaged(repo, "/issues/" + prNumber + "/comments");
    for (JsonNode c : issueComments) {
      if (!login.equals(c.path("user").path("login").asText())) {
        continue; // 只采 bot 的 Summary 评论 reaction（run 满意度载体，06§1.1）
      }
      long commentId = c.path("id").asLong(0);
      if (commentId <= 0) {
        continue;
      }
      for (JsonNode r : getPaged(repo, "/issues/comments/" + commentId + "/reactions")) {
        String content = r.path("content").asText("");
        if (!DispositionSignal.REACTION_UP.equals(content)
            && !DispositionSignal.REACTION_DOWN.equals(content)) {
          continue;
        }
        out.add(
            new DispositionSignal(
                DispositionSignal.KIND_REACTION,
                commentId,
                GitHubDispositionSupport.instantOrEpoch(r.path("created_at")),
                content,
                r.path("user").path("login").asText(""),
                ""));
      }
    }
    return out.toArray(new DispositionSignal[0]);
  }

  private List<JsonNode> getPaged(RepoRef repo, String path) {
    List<JsonNode> out = new ArrayList<>();
    for (int page = 1; page <= MAX_PAGES; page++) {
      JsonNode root = transport.getJson(repo, path + "?per_page=" + PAGE_SIZE + "&page=" + page);
      if (!root.isArray() || root.isEmpty()) {
        break;
      }
      root.forEach(out::add);
      if (root.size() < PAGE_SIZE) {
        break;
      }
    }
    return out;
  }

  private String botLogin(RepoRef repo) {
    if (cachedLogin == null) {
      // /user 不在 /repos/{o}/{r} 前缀下（05§2.3：bot 身份按 token 对应 login）
      HttpRequest request =
          transport.request("/user").header("Accept", "application/vnd.github+json").GET().build();
      try {
        cachedLogin =
            transport
                .parseJson(transport.execute(request, "/user").body(), "/user")
                .path("login")
                .asText();
      } catch (CraIntegrationException e) {
        if (!isUserForbidden(e)) {
          throw e;
        }
        // Actions 集成 token（GITHUB_TOKEN）是 App 语义，/user 恒 403——CI 内 bot 身份即
        // 工作流 bot（05§2.3 钉死：CI 内为 github-actions[bot]）
        cachedLogin = CI_BOT_LOGIN;
      }
    }
    return cachedLogin;
  }

  private static boolean isUserForbidden(CraIntegrationException e) {
    return e.getMessage() != null && e.getMessage().contains("GitHub API 403 on /user");
  }
}
