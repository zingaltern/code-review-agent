package org.cra.git.gitlab.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.http.HttpClient;
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
import org.cra.common.git.DiffHunk;
import org.cra.common.git.DiffResult;
import org.cra.common.git.DispositionSignal;
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
import org.cra.git.api.GitPlatformAdapter;
import org.cra.git.gitlab.internal.ledger.LedgerMarkerParser;
import org.cra.git.gitlab.internal.ledger.LedgerMarkerParser.ParseOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * GitLab REST v4 实现（ADR-0011 端点映射）：compare diff / repository files / MR discussions（inline）/ MR
 * notes（Summary）/ commit comments / issues / 账本拉取。
 *
 * <p>与 GitHub 实现的结构差异：GitLab 没有"整包 review"对象，inline 逐条建 discussion；diff 锚定需要 base/head/start
 * 三元组；commit comment 无平台 id（由 {@link GitLabLedgerSupport} 生成稳定合成 id）。 账本标记格式原样移植
 * 05§1.3，解析器在本模块独立复制（实现模块互不依赖，11§4 第 4 条）。
 */
public class GitLabHttpAdapter implements GitPlatformAdapter, CommentSource {

  private static final Logger LOG = LoggerFactory.getLogger(GitLabHttpAdapter.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final int PAGE_SIZE = 100;
  private static final int MAX_PAGES = 100;

  private final GitLabHttpTransport transport;
  private final String botUsernameFallback;
  private final LedgerMarkerParser markerParser = new LedgerMarkerParser();
  private String cachedUsername;

  /**
   * @param apiBase API 根（默认 https://gitlab.com/api/v4，测试注入 WireMock 地址）
   * @param token GitLab access token（PRIVATE-TOKEN）
   * @param http HTTP 客户端
   * @param sleeper 退避睡眠（测试注入即时实现）
   * @param botUsernameFallback `/user` 不可读时的 bot 用户名（可空）
   */
  public GitLabHttpAdapter(
      String apiBase,
      String token,
      HttpClient http,
      java.util.function.LongConsumer sleeper,
      String botUsernameFallback) {
    this.transport = new GitLabHttpTransport(apiBase, token, http, sleeper);
    this.botUsernameFallback =
        botUsernameFallback == null || botUsernameFallback.isBlank() ? null : botUsernameFallback;
  }

  // ---------- 读：diff / 文件 / 文件树 ----------

  @Override
  public DiffResult fetchDiff(RepoRef repo, String baseSha, String headSha) {
    JsonNode root =
        transport.getJson(
            repo,
            "/repository/compare?from="
                + GitLabPayloadSupport.urlEncode(baseSha)
                + "&to="
                + GitLabPayloadSupport.urlEncode(headSha));
    if (root.path("compare_timeout").asBoolean(false)) {
      // 部分 diffs 不静默评审（ADR-0011 决策 11）：宁可失败也不产生覆盖声明造假的报告
      throw new CraIntegrationException(
          "GitLab compare timed out for " + baseSha + "..." + headSha + "（partial diffs refused）");
    }
    List<DiffFile> files = new ArrayList<>();
    for (JsonNode d : root.path("diffs")) {
      String path = textOrNull(d, "new_path");
      String oldPath = textOrNull(d, "old_path");
      String effective = path != null ? path : oldPath;
      if (effective == null) {
        LOG.warn("gitlab compare returned a diff without new_path/old_path; skipped");
        continue;
      }
      boolean renamed = d.path("renamed_file").asBoolean(false);
      String patch = d.path("too_large").asBoolean(false) ? null : textOrNull(d, "diff");
      files.add(
          new DiffFile(
              effective,
              renamed ? oldPath : null,
              statusOf(d),
              patch,
              patch == null ? List.of() : DiffHunk.parseAll(patch)));
    }
    List<CommitSummary> commits = new ArrayList<>();
    for (JsonNode c : root.path("commits")) {
      String sha = textOrNull(c, "id");
      if (sha == null) {
        continue;
      }
      String message = textOrNull(c, "message");
      if (message == null) {
        message = textOrNull(c, "title");
      }
      commits.add(new CommitSummary(sha, message == null ? "" : message));
    }
    return new DiffResult(baseSha, headSha, files, commits);
  }

  private static String statusOf(JsonNode diff) {
    if (diff.path("new_file").asBoolean(false)) {
      return "added";
    }
    if (diff.path("deleted_file").asBoolean(false)) {
      return "removed";
    }
    if (diff.path("renamed_file").asBoolean(false)) {
      return "renamed";
    }
    return "modified";
  }

  @Override
  public FileContent readFile(RepoRef repo, String sha, String path) {
    byte[] bytes =
        transport.getBytes(
            repo,
            "/repository/files/"
                + GitLabPayloadSupport.encodeFilePath(path)
                + "/raw?ref="
                + GitLabPayloadSupport.urlEncode(sha));
    try {
      return new FileContent(path, GitLabPayloadSupport.strictUtf8(bytes), false);
    } catch (CharacterCodingException e) {
      return new FileContent(path, null, true);
    }
  }

  @Override
  public List<String> listFiles(RepoRef repo, String sha, String pathGlob) {
    java.util.regex.Pattern glob = GitLabPayloadSupport.globToRegex(pathGlob);
    List<String> out = new ArrayList<>();
    for (JsonNode entry :
        getPagedArray(
            repo,
            "/repository/tree?ref=" + GitLabPayloadSupport.urlEncode(sha) + "&recursive=true")) {
      if (!"blob".equals(entry.path("type").asText())) {
        continue;
      }
      String path = entry.path("path").asText("");
      if (!path.isBlank() && glob.matcher(path).matches()) {
        out.add(path);
      }
    }
    return out;
  }

  // ---------- 写：inline discussions → Summary MR note（顺序同 05§2.2） ----------

  @Override
  public PrReviewResult postReview(PostReviewCommand cmd) {
    List<ReviewItemResult> items = new ArrayList<>();
    if (!cmd.inlineComments().isEmpty()) {
      DiffRefs refs = resolveDiffRefs(cmd);
      for (InlineComment c : cmd.inlineComments()) {
        if (refs == null) {
          items.add(fallback(c, "GitLab MR diff_refs unavailable; cannot anchor inline comment"));
          continue;
        }
        try {
          long noteId = createDiscussion(cmd, refs, c);
          items.add(
              new ReviewItemResult(
                  c.findingId(),
                  c.path(),
                  c.line(),
                  PublishStatus.PUBLISHED,
                  AnchorStatus.INLINE,
                  noteId > 0 ? noteId : null,
                  ""));
        } catch (CraIntegrationException e) {
          if (!GitLabHttpTransport.isUnprocessable(e)) {
            throw e; // 非锚定错误不降级，交由 run 级失败处置（02§4.2）
          }
          items.add(fallback(c, e.getMessage()));
        }
      }
    }
    // Summary 最后发：其 DONE 标记是"完整发布"证据；失败 = 无标记 = 重跑走指纹级补发（02§4.1）
    Optional<Long> summaryId = postSummary(cmd);
    // GitLab 无 review 聚合对象：reviewId 显式缺省（ADR-0011 决策 5）
    return new PrReviewResult(cmd.prNumber(), Optional.empty(), summaryId, items);
  }

  private static ReviewItemResult fallback(InlineComment c, String reason) {
    return new ReviewItemResult(
        c.findingId(),
        c.path(),
        c.line(),
        PublishStatus.SUMMARY_FALLBACK,
        AnchorStatus.SUMMARY_FALLBACK,
        null,
        reason);
  }

  private long createDiscussion(PostReviewCommand cmd, DiffRefs refs, InlineComment c) {
    ObjectNode payload = MAPPER.createObjectNode();
    payload.put("body", c.body());
    ObjectNode position = payload.putObject("position");
    position.put("base_sha", refs.baseSha());
    position.put("head_sha", refs.headSha());
    position.put("start_sha", refs.startSha());
    position.put("position_type", "text");
    position.put("old_path", c.path());
    position.put("new_path", c.path());
    if (c.side() == Side.LEFT) {
      position.put("old_line", c.line());
    } else {
      position.put("new_line", c.line());
    }
    JsonNode response =
        transport.postJson(
            cmd.repoOwner(),
            cmd.repoName(),
            "/merge_requests/" + cmd.prNumber() + "/discussions",
            payload);
    return response.path("notes").path(0).path("id").asLong(0);
  }

  private Optional<Long> postSummary(PostReviewCommand cmd) {
    ObjectNode payload = MAPPER.createObjectNode();
    payload.put("body", cmd.summaryBody());
    try {
      JsonNode response =
          transport.postJson(
              cmd.repoOwner(),
              cmd.repoName(),
              "/merge_requests/" + cmd.prNumber() + "/notes",
              payload);
      long id = response.path("id").asLong(0);
      return id > 0 ? Optional.of(id) : Optional.empty();
    } catch (CraIntegrationException e) {
      LOG.warn(
          "summary note publish failed; run has no DONE marker, rerun will republish: {}",
          e.getMessage());
      return Optional.empty();
    }
  }

  @Override
  public void updateSummaryComment(RepoRef repo, int prNumber, long commentId, String body) {
    ObjectNode payload = MAPPER.createObjectNode();
    payload.put("body", body);
    transport.putJson(
        repo.owner(), repo.repo(), "/merge_requests/" + prNumber + "/notes/" + commentId, payload);
  }

  /**
   * 解析 diff 锚定三元组：优先 MR 的 `diff_refs`；被评审 head 不是当前 head 时从 versions 中找同一 head 的版本；都不可得返回
   * null（逐条降级进 Summary，ADR-0011 决策 5）。
   */
  private DiffRefs resolveDiffRefs(PostReviewCommand cmd) {
    RepoRef repo = new RepoRef(cmd.repoOwner(), cmd.repoName());
    JsonNode mr = transport.getJson(repo, "/merge_requests/" + cmd.prNumber());
    DiffRefs current = toRefs(mr.path("diff_refs"));
    if (current != null && current.headSha().equalsIgnoreCase(cmd.commitId())) {
      return current;
    }
    try {
      for (JsonNode version :
          transport.getJson(repo, "/merge_requests/" + cmd.prNumber() + "/versions")) {
        if (cmd.commitId().equalsIgnoreCase(version.path("head_commit_sha").asText(""))) {
          DiffRefs matched = toVersionRefs(version);
          if (matched != null) {
            return matched;
          }
        }
      }
    } catch (CraIntegrationException e) {
      if (current == null) {
        throw e;
      }
      LOG.warn(
          "gitlab MR versions unavailable; falling back to current diff_refs: {}", e.getMessage());
    }
    if (current != null) {
      LOG.warn(
          "review head {} is not the current MR head {}; anchoring to current diff_refs",
          cmd.commitId(),
          current.headSha());
      return current;
    }
    return null;
  }

  private static DiffRefs toRefs(JsonNode refs) {
    if (refs == null || !refs.isObject()) {
      return null;
    }
    String base = textOrNull(refs, "base_sha");
    String head = textOrNull(refs, "head_sha");
    String start = textOrNull(refs, "start_sha");
    return base == null || head == null || start == null ? null : new DiffRefs(base, head, start);
  }

  private static DiffRefs toVersionRefs(JsonNode version) {
    String base = textOrNull(version, "base_commit_sha");
    String head = textOrNull(version, "head_commit_sha");
    String start = textOrNull(version, "start_commit_sha");
    return base == null || head == null || start == null ? null : new DiffRefs(base, head, start);
  }

  private record DiffRefs(String baseSha, String headSha, String startSha) {}

  // ---------- 写：commit comment（M4c）/ issue（M4d）载体 ----------

  @Override
  public Optional<Long> postCommitReview(PostCommitReviewCommand cmd) {
    ObjectNode payload = MAPPER.createObjectNode();
    payload.put("note", cmd.body());
    transport.postJson(
        cmd.repoOwner(),
        cmd.repoName(),
        "/repository/commits/" + GitLabPayloadSupport.urlEncode(cmd.commitSha()) + "/comments",
        payload);
    // GitLab 不返回 comment id（ADR-0011 决策 7）：返回稳定合成 id，供核心判定"完整发布"
    return Optional.of(GitLabLedgerSupport.syntheticCommitCommentId(cmd.commitSha(), cmd.body()));
  }

  @Override
  public Optional<Long> postIssueReview(PostIssueCommand cmd) {
    ObjectNode payload = MAPPER.createObjectNode();
    payload.put("title", cmd.title());
    payload.put("description", cmd.body());
    if (!cmd.labels().isEmpty()) {
      // GitLab labels 为逗号分隔；缺失标签会被自动创建（ADR-0011 决策 8）
      payload.put("labels", String.join(",", cmd.labels()));
    }
    JsonNode response = transport.postJson(cmd.repoOwner(), cmd.repoName(), "/issues", payload);
    long iid = response.path("iid").asLong(0);
    return iid > 0 ? Optional.of(iid) : Optional.empty();
  }

  // ---------- 账本：MR discussions / commit comments / issues（05§1.3） ----------

  @Override
  public CommentLedger fetchBotComments(RepoRef repo, int prNumber) {
    String login = botUsername(repo);
    List<LedgerComment> comments = new ArrayList<>();
    Set<Long> seen = new HashSet<>();
    for (JsonNode discussion :
        getPagedArray(repo, "/merge_requests/" + prNumber + "/discussions")) {
      for (JsonNode note : discussion.path("notes")) {
        if (note.path("system").asBoolean(false)) {
          continue;
        }
        if (!login.equals(note.path("author").path("username").asText(""))) {
          continue; // 只认 bot 自身评论（账本语义，02§5）
        }
        long id = note.path("id").asLong(0);
        if (id <= 0 || !seen.add(id)) {
          continue;
        }
        JsonNode position = note.path("position");
        boolean inline = position.isObject();
        comments.add(
            new LedgerComment(
                id,
                inline ? LedgerComment.KIND_INLINE : LedgerComment.KIND_ISSUE,
                login,
                note.path("body").asText(""),
                GitLabDispositionSupport.instantOrEpoch(note.path("created_at")),
                inline ? textOrNull(position, "head_sha") : null));
      }
    }
    return GitLabLedgerSupport.parse(comments, markerParser);
  }

  @Override
  public CommentLedger fetchCommitBotComments(RepoRef repo, String commitSha) {
    String login = botUsername(repo);
    List<LedgerComment> comments = new ArrayList<>();
    for (JsonNode c :
        getPagedArray(
            repo,
            "/repository/commits/" + GitLabPayloadSupport.urlEncode(commitSha) + "/comments")) {
      if (!login.equals(c.path("author").path("username").asText(""))) {
        continue;
      }
      String note = c.path("note").asText("");
      comments.add(
          new LedgerComment(
              GitLabLedgerSupport.syntheticCommitCommentId(commitSha, note),
              LedgerComment.KIND_COMMIT,
              login,
              note,
              GitLabDispositionSupport.instantOrEpoch(c.path("created_at")),
              commitSha));
    }
    return GitLabLedgerSupport.parse(comments, markerParser);
  }

  /**
   * issue 载体账本（M4d，05§2.5）：列出 bot 的 issue，只保留 run 完成标记内 commit 与 {@code commitSha} 一致者—— issue
   * 载体本身不带 PR 号，head sha 即键（05§1.3）。
   */
  @Override
  public CommentLedger fetchIssueBotComments(RepoRef repo, String commitSha) {
    List<LedgerComment> matched = new ArrayList<>();
    for (LedgerComment c : listBotIssues(repo, null)) {
      ParseOutcome outcome = markerParser.parse(c.body());
      if (GitLabLedgerSupport.marksCommit(outcome, commitSha)) {
        matched.add(c);
      }
    }
    return GitLabLedgerSupport.parse(matched, markerParser);
  }

  @Override
  public List<LedgerComment> listBotIssues(RepoRef repo, Instant since) {
    String login = botUsername(repo);
    List<LedgerComment> out = new ArrayList<>();
    String page = "1";
    for (int i = 0; i < MAX_PAGES; i++) {
      String query =
          "/issues?state=all&order_by=updated_at&sort=desc&author_username="
              + GitLabPayloadSupport.urlEncode(login)
              + (since == null
                  ? ""
                  : "&updated_after=" + GitLabPayloadSupport.urlEncode(since.toString()));
      GitLabHttpTransport.Page p = transport.getPage(repo, withPage(query, page));
      JsonNode root = p.body();
      if (!root.isArray() || root.isEmpty()) {
        break;
      }
      boolean reachedOlder = false;
      for (JsonNode issue : root) {
        Instant updated = GitLabDispositionSupport.instantOrNull(issue.path("updated_at"));
        if (since != null && updated != null && updated.isBefore(since)) {
          reachedOlder = true;
          continue;
        }
        if (!login.equals(issue.path("author").path("username").asText(""))) {
          continue; // 只认 bot 发布的 issue（服务端 author_username 之外的兜底）
        }
        long iid = issue.path("iid").asLong(0);
        if (iid <= 0) {
          continue;
        }
        out.add(
            new LedgerComment(
                iid,
                LedgerComment.KIND_ISSUE,
                login,
                issue.path("description").asText(""),
                GitLabDispositionSupport.instantOrEpoch(issue.path("created_at")),
                null));
      }
      if (root.size() < PAGE_SIZE || reachedOlder) {
        break;
      }
      page = nextPage(p, page);
      if (page == null) {
        break; // 异常分页头：页号无法递增，按无下一页处理
      }
    }
    return out;
  }

  @Override
  public List<PullRequestRef> listPullRequests(RepoRef repo, Instant since) {
    List<PullRequestRef> out = new ArrayList<>();
    String page = "1";
    for (int i = 0; i < MAX_PAGES; i++) {
      GitLabHttpTransport.Page p =
          transport.getPage(
              repo, withPage("/merge_requests?state=all&order_by=updated_at&sort=desc", page));
      JsonNode root = p.body();
      if (!root.isArray() || root.isEmpty()) {
        break;
      }
      boolean reachedOlder = false;
      for (JsonNode mr : root) {
        Instant updated = GitLabDispositionSupport.instantOrNull(mr.path("updated_at"));
        if (since != null && (updated == null || updated.isBefore(since))) {
          // 按 updated desc 排序：本页出现早于窗口的条目，后续页只会更早
          reachedOlder = true;
          continue;
        }
        int iid = mr.path("iid").asInt(0);
        if (iid <= 0) {
          continue;
        }
        JsonNode full = null;
        String head = textOrNull(mr, "sha");
        String base = textOrNull(mr.path("diff_refs"), "base_sha");
        if (head == null || base == null) {
          full = fetchMergeRequest(repo, iid);
          if (head == null) {
            head = textOrNull(full.path("diff_refs"), "head_sha");
          }
          if (base == null) {
            base = textOrNull(full.path("diff_refs"), "base_sha");
          }
        }
        if (head == null || base == null) {
          LOG.warn("gitlab MR !{} has no head/base sha; skipped from window", iid);
          continue;
        }
        out.add(
            new PullRequestRef(
                iid,
                head,
                textOrNull(mr, "merge_commit_sha"),
                "opened".equals(mr.path("state").asText(""))
                    ? PullRequestRef.STATE_OPEN
                    : PullRequestRef.STATE_CLOSED,
                updated == null ? Instant.EPOCH : updated,
                GitLabDispositionSupport.instantOrNull(mr.path("merged_at")),
                base));
      }
      if (root.size() < PAGE_SIZE || reachedOlder) {
        break;
      }
      page = nextPage(p, page);
      if (page == null) {
        break; // 异常分页头：页号无法递增，按无下一页处理
      }
    }
    return out;
  }

  private static String nextPage(GitLabHttpTransport.Page page, String currentPage) {
    // GitLab.com 可能省略部分分页头；满页且无 X-Next-Page 时按 offset 递增兜底
    return page.nextPage().isBlank() ? incrementPageOrNull(currentPage) : page.nextPage();
  }

  /** 页号递增兜底：非数字或超出 int 范围（异常分页头）按无下一页处理，返回 {@code null}，不抛 {@link NumberFormatException}。 */
  static String incrementPageOrNull(String currentPage) {
    try {
      return String.valueOf(Integer.parseInt(currentPage) + 1);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private JsonNode fetchMergeRequest(RepoRef repo, int iid) {
    return transport.getJson(repo, "/merge_requests/" + iid);
  }

  /**
   * 处置信号（06§1.1）：人类对 bot discussion 的 reply 标签 + bot Summary note 的 👍/👎。
   *
   * <p>GitLab reply 没有 `in_reply_to_id`，线程关系由 discussion 表达；reaction 走 note 的 award_emoji
   * （`thumbsup`/`thumbsdown`）。discussion `resolved` 虽在 REST 可得，但 DispositionSignal 模型无该类别，
   * 本轮不采（ADR-0011 决策 10）。
   */
  @Override
  public DispositionSignal[] fetchDispositionSignals(RepoRef repo, int prNumber) {
    String login = botUsername(repo);
    List<JsonNode> discussions =
        getPagedArray(repo, "/merge_requests/" + prNumber + "/discussions");
    List<DispositionSignal> out = new ArrayList<>();
    for (JsonNode discussion : discussions) {
      Long parentId = null;
      for (JsonNode note : discussion.path("notes")) {
        if (note.path("system").asBoolean(false)) {
          continue;
        }
        long noteId = note.path("id").asLong(0);
        if (noteId <= 0) {
          continue;
        }
        String author = note.path("author").path("username").asText("");
        if (login.equals(author)) {
          if (parentId == null) {
            parentId = noteId;
          }
          continue;
        }
        if (parentId == null) {
          continue; // 人类自建线程不是处置信号
        }
        String body = note.path("body").asText("");
        String tag = GitLabDispositionSupport.replyTag(body);
        if (tag == null) {
          continue; // 无标签回复不改变处置分类（ignored 口径据此保持噪声语义）
        }
        out.add(
            new DispositionSignal(
                DispositionSignal.KIND_REPLY_TAG,
                parentId,
                GitLabDispositionSupport.instantOrEpoch(note.path("created_at")),
                tag,
                author,
                GitLabDispositionSupport.excerpt(body)));
      }
    }
    for (JsonNode discussion : discussions) {
      for (JsonNode note : discussion.path("notes")) {
        if (note.path("system").asBoolean(false)) {
          continue;
        }
        if (!login.equals(note.path("author").path("username").asText(""))) {
          continue; // 只采 bot 的 Summary note reaction（run 满意度载体，06§1.1）
        }
        if (note.path("position").isObject()) {
          continue; // inline note 的 reaction 不参与 run 满意度
        }
        long noteId = note.path("id").asLong(0);
        if (noteId <= 0) {
          continue;
        }
        for (JsonNode award :
            getPagedArray(
                repo, "/merge_requests/" + prNumber + "/notes/" + noteId + "/award_emoji")) {
          String name = award.path("name").asText("");
          String content =
              "thumbsup".equals(name)
                  ? DispositionSignal.REACTION_UP
                  : "thumbsdown".equals(name) ? DispositionSignal.REACTION_DOWN : null;
          if (content == null) {
            continue;
          }
          out.add(
              new DispositionSignal(
                  DispositionSignal.KIND_REACTION,
                  noteId,
                  GitLabDispositionSupport.instantOrEpoch(award.path("created_at")),
                  content,
                  award.path("user").path("username").asText(""),
                  ""));
        }
      }
    }
    return out.toArray(new DispositionSignal[0]);
  }

  private List<JsonNode> getPagedArray(RepoRef repo, String pathAndQuery) {
    List<JsonNode> out = new ArrayList<>();
    String page = "1";
    boolean exhausted = false;
    // 多探一页：恰好 100 个满页（10k 条）时，第 101 页为空必须视为正常结束，而不是触顶
    for (int i = 0; i <= MAX_PAGES; i++) {
      GitLabHttpTransport.Page p = transport.getPage(repo, withPage(pathAndQuery, page));
      JsonNode root = p.body();
      if (!root.isArray() || root.isEmpty()) {
        exhausted = true;
        break;
      }
      root.forEach(out::add);
      String next = p.nextPage();
      if (next.isBlank()) {
        if (root.size() < PAGE_SIZE) {
          exhausted = true;
          break;
        }
        next = incrementPageOrNull(page);
        if (next == null) {
          exhausted = true; // 异常分页头：页号无法递增，按无下一页处理
          break;
        }
      }
      page = next;
    }
    if (!exhausted) {
      // 触顶仍是满页 = 还有数据未读：返回部分结果会让文件索引/账本"看起来完整"
      throw new org.cra.common.exception.CraIntegrationException(
          "GitLab 分页超过上限 " + (MAX_PAGES * PAGE_SIZE) + " 条，无法保证结果完整: " + pathAndQuery);
    }
    return out;
  }

  private static String withPage(String pathAndQuery, String page) {
    String sep = pathAndQuery.contains("?") ? "&" : "?";
    return pathAndQuery + sep + "per_page=" + PAGE_SIZE + "&page=" + page;
  }

  private String botUsername(RepoRef repo) {
    if (cachedUsername == null) {
      try {
        String username = transport.getAbsoluteJson("/user").path("username").asText("");
        if (!username.isBlank()) {
          cachedUsername = username;
        }
      } catch (CraIntegrationException e) {
        if (!GitLabHttpTransport.isUserUnauthorized(e)) {
          throw e;
        }
        LOG.warn("GET /user unauthorized; using configured bot username fallback if present");
      }
      if (cachedUsername == null) {
        if (botUsernameFallback == null) {
          throw new CraIntegrationException(
              "GitLab bot identity unavailable: GET /user failed and no botUsername fallback"
                  + " (CI job tokens need an explicit bot username)");
        }
        cachedUsername = botUsernameFallback;
      }
    }
    return cachedUsername;
  }

  private static String textOrNull(JsonNode node, String field) {
    if (node == null) {
      return null;
    }
    JsonNode value = node.path(field);
    return value.isMissingNode() || value.isNull() || value.asText().isBlank()
        ? null
        : value.asText();
  }
}
