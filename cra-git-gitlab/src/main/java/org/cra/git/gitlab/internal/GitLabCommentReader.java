package org.cra.git.gitlab.internal;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.cra.common.exception.CraIntegrationException;
import org.cra.common.git.CommentLedger;
import org.cra.common.git.CommentSource;
import org.cra.common.git.DispositionSignal;
import org.cra.common.git.DispositionSupport;
import org.cra.common.git.LedgerComment;
import org.cra.common.git.LedgerMarkerParser;
import org.cra.common.git.PullRequestRef;
import org.cra.common.git.RepoRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * GitLab 账本 / 处置 / 周报列表读侧（CommentSource 实现，05§1.3、05§2.3、06§1.1、ADR-0011）。
 *
 * <p>从 {@link GitLabHttpAdapter} 拆出以控制上帝类行数（10§8）：本类只做 MR/commit/issue 评论与列表取数、标记解析与分页；发布与 diff
 * 读取仍在 adapter。分页触顶一律显式失败（含第 101 页探测），不返回部分结果。
 */
final class GitLabCommentReader implements CommentSource {

  private static final Logger LOG = LoggerFactory.getLogger(GitLabCommentReader.class);
  private static final int PAGE_SIZE = 100;
  private static final int MAX_PAGES = 100;

  private final GitLabHttpTransport transport;
  private final String botUsernameFallback;
  private final LedgerMarkerParser markerParser = new LedgerMarkerParser();
  private volatile String cachedUsername;

  GitLabCommentReader(GitLabHttpTransport transport, String botUsernameFallback) {
    this.transport = transport;
    this.botUsernameFallback =
        botUsernameFallback == null || botUsernameFallback.isBlank() ? null : botUsernameFallback;
  }

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
                DispositionSupport.instantOrEpoch(note.path("created_at")),
                inline ? GitLabJsonSupport.textOrNull(position, "head_sha") : null));
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
              DispositionSupport.instantOrEpoch(c.path("created_at")),
              commitSha));
    }
    return GitLabLedgerSupport.parse(comments, markerParser);
  }

  /**
   * issue 载体账本（M4d，05§2.5）：列出 bot 的 issue，只保留完成标记（run / finding）内 commit 与 {@code commitSha}
   * 一致者——issue 载体本身不带 PR 号，head sha 即键（05§1.3）。
   */
  @Override
  public CommentLedger fetchIssueBotComments(RepoRef repo, String commitSha) {
    return GitLabLedgerSupport.parseFiltered(
        listBotIssues(repo, null),
        markerParser,
        outcome -> GitLabLedgerSupport.marksCommit(outcome, commitSha));
  }

  @Override
  public List<LedgerComment> listBotIssues(RepoRef repo, Instant since) {
    String login = botUsername(repo);
    List<LedgerComment> out = new ArrayList<>();
    String page = "1";
    for (int i = 0; i <= MAX_PAGES; i++) {
      String query =
          "/issues?state=all&order_by=updated_at&sort=desc&author_username="
              + GitLabPayloadSupport.urlEncode(login)
              + (since == null
                  ? ""
                  : "&updated_after=" + GitLabPayloadSupport.urlEncode(since.toString()));
      GitLabHttpTransport.Page p = transport.getPage(repo, withPage(query, page));
      JsonNode root = p.body();
      if (i == MAX_PAGES) {
        if (root.isArray() && !root.isEmpty()) {
          throw new CraIntegrationException(
              "GitLab 分页超过上限 " + (MAX_PAGES * PAGE_SIZE) + " 条，无法保证列表完整: " + query);
        }
        break;
      }
      if (!root.isArray() || root.isEmpty()) {
        break;
      }
      boolean reachedOlder = false;
      for (JsonNode issue : root) {
        Instant updated = DispositionSupport.instantOrNull(issue.path("updated_at"));
        if (since != null && (updated == null || updated.isBefore(since))) {
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
                DispositionSupport.instantOrEpoch(issue.path("created_at")),
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
    for (int i = 0; i <= MAX_PAGES; i++) {
      GitLabHttpTransport.Page p =
          transport.getPage(
              repo, withPage("/merge_requests?state=all&order_by=updated_at&sort=desc", page));
      JsonNode root = p.body();
      if (i == MAX_PAGES) {
        if (root.isArray() && !root.isEmpty()) {
          throw new CraIntegrationException(
              "GitLab 分页超过上限 " + (MAX_PAGES * PAGE_SIZE) + " 条，无法保证列表完整");
        }
        break;
      }
      if (!root.isArray() || root.isEmpty()) {
        break;
      }
      boolean reachedOlder = false;
      for (JsonNode mr : root) {
        Instant updated = DispositionSupport.instantOrNull(mr.path("updated_at"));
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
        String head = GitLabJsonSupport.textOrNull(mr, "sha");
        String base = GitLabJsonSupport.textOrNull(mr.path("diff_refs"), "base_sha");
        if (head == null || base == null) {
          full = fetchMergeRequest(repo, iid);
          if (head == null) {
            head = GitLabJsonSupport.textOrNull(full.path("diff_refs"), "head_sha");
          }
          if (base == null) {
            base = GitLabJsonSupport.textOrNull(full.path("diff_refs"), "base_sha");
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
                GitLabJsonSupport.textOrNull(mr, "merge_commit_sha"),
                "opened".equals(mr.path("state").asText(""))
                    ? PullRequestRef.STATE_OPEN
                    : PullRequestRef.STATE_CLOSED,
                updated == null ? Instant.EPOCH : updated,
                DispositionSupport.instantOrNull(mr.path("merged_at")),
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
        String tag = DispositionSupport.replyTag(body);
        if (tag == null) {
          continue; // 无标签回复不改变处置分类（ignored 口径据此保持噪声语义）
        }
        out.add(
            new DispositionSignal(
                DispositionSignal.KIND_REPLY_TAG,
                parentId,
                DispositionSupport.instantOrEpoch(note.path("created_at")),
                tag,
                author,
                DispositionSupport.excerpt(body)));
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
                  DispositionSupport.instantOrEpoch(award.path("created_at")),
                  content,
                  award.path("user").path("username").asText(""),
                  ""));
        }
      }
    }
    return out.toArray(new DispositionSignal[0]);
  }

  List<JsonNode> getPagedArray(RepoRef repo, String pathAndQuery) {
    List<JsonNode> out = new ArrayList<>();
    String page = "1";
    boolean exhausted = false;
    // 多探一页：恰好 100 个满页（10k 条）时，第 101 页为空必须视为正常结束，而不是触顶
    for (int i = 0; i <= MAX_PAGES; i++) {
      GitLabHttpTransport.Page p = transport.getPage(repo, withPage(pathAndQuery, page));
      JsonNode root = p.body();
      if (i == MAX_PAGES) {
        // 探页：恰好 MAX_PAGES 个满页后第 101 页为空才算正常结束；有数据即超过上限
        if (root.isArray() && !root.isEmpty()) {
          throw new CraIntegrationException(
              "GitLab 分页超过上限 " + (MAX_PAGES * PAGE_SIZE) + " 条，无法保证结果完整: " + pathAndQuery);
        }
        exhausted = true;
        break;
      }
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
      throw new CraIntegrationException(
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
}
