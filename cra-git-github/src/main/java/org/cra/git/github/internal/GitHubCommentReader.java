package org.cra.git.github.internal;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.http.HttpRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.cra.common.exception.CraIntegrationException;
import org.cra.common.git.CommentLedger;
import org.cra.common.git.CommentSource;
import org.cra.common.git.DispositionSignal;
import org.cra.common.git.DispositionSupport;
import org.cra.common.git.LedgerComment;
import org.cra.common.git.LedgerFindingMarker;
import org.cra.common.git.LedgerMarkerParser;
import org.cra.common.git.LedgerMarkerParser.MarkerParseResult;
import org.cra.common.git.LedgerMarkerParser.ParseOutcome;
import org.cra.common.git.LedgerRunMarker;
import org.cra.common.git.PullRequestRef;
import org.cra.common.git.RepoRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * GitHub 账本 / 处置 / 周报列表读侧（CommentSource 实现，05§1.3、05§2.3、06§1.1）。
 *
 * <p>从 {@link GitHubHttpAdapter} 拆出以控制上帝类行数（10§8）：本类只做 bot 评论与 issue/PR 列表的取数与标记解析，发布与 diff 读取仍在
 * adapter；分页触顶一律显式失败，不返回部分结果。
 */
final class GitHubCommentReader implements CommentSource {

  private static final Logger LOG = LoggerFactory.getLogger(GitHubCommentReader.class);

  private static final String CI_BOT_LOGIN = "github-actions[bot]";
  private static final int PAGE_SIZE = 100;
  private static final int MAX_PAGES = 100;

  /** 分页硬上限（100×100 = 10k 条）：触顶即显式失败，禁止把部分结果冒充"全部评论"。 */
  private static final int MAX_ITEMS = PAGE_SIZE * MAX_PAGES;

  private final GitHubHttpTransport transport;
  private final LedgerMarkerParser markerParser = new LedgerMarkerParser();
  private volatile String cachedLogin;

  GitHubCommentReader(GitHubHttpTransport transport) {
    this.transport = transport;
  }

  @Override
  public CommentLedger fetchBotComments(RepoRef repo, int prNumber) {
    String login = botLogin(repo);
    List<LedgerComment> comments = new ArrayList<>();
    List<LedgerFindingMarker> findings = new ArrayList<>();
    List<LedgerRunMarker> runs = new ArrayList<>();
    collectPaged(
        repo, "/pulls/" + prNumber + "/comments", login, LedgerComment.KIND_INLINE, comments);
    collectPaged(
        repo, "/issues/" + prNumber + "/comments", login, LedgerComment.KIND_ISSUE, comments);
    int suspectCount = parseMarkers(comments, findings, runs);
    return new CommentLedger(comments, findings, runs, suspectCount);
  }

  /** 评论原文 → 两类账本标记（suspect 计数一并返回；inline/issue/commit 三个载体共用）。 */
  private int parseMarkers(
      List<LedgerComment> comments,
      List<LedgerFindingMarker> findings,
      List<LedgerRunMarker> runs) {
    int suspects = 0;
    for (LedgerComment c : comments) {
      ParseOutcome outcome = markerParser.parse(c.body());
      suspects += outcome.suspects().size();
      if (outcome.marker().isEmpty()) {
        continue;
      }
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
    return suspects;
  }

  private void collectPaged(
      RepoRef repo, String path, String login, String kind, List<LedgerComment> out) {
    // 与 GitLab 同语义：恰好 MAX_PAGES 个满页后探第 MAX_PAGES+1 页，空=恰好 10k 正常结束，非空=超限
    for (int page = 1; page <= MAX_PAGES + 1; page++) {
      JsonNode root = transport.getJson(repo, path + "?per_page=" + PAGE_SIZE + "&page=" + page);
      if (page == MAX_PAGES + 1) {
        if (root.isArray() && !root.isEmpty()) {
          throw new CraIntegrationException("评论分页超过上限 " + MAX_ITEMS + " 条，无法保证账本完整: " + path);
        }
        return;
      }
      if (!root.isArray() || root.isEmpty()) {
        return;
      }
      for (JsonNode c : root) {
        if (!login.equals(c.path("user").path("login").asText())) {
          continue; // 只认 bot 自身评论（账本语义，02§5）
        }
        out.add(toLedgerComment(c, login, kind));
      }
      if (root.size() < PAGE_SIZE) {
        return;
      }
    }
  }

  /**
   * GitHub 评论节点 → 账本评论（commit_id 缺失/空白归一为 null）。
   *
   * @param c 评论节点
   * @param login bot 用户名
   * @param kind 评论载体类型
   * @return 账本评论
   */
  private static LedgerComment toLedgerComment(JsonNode c, String login, String kind) {
    return new LedgerComment(
        c.path("id").asLong(),
        kind,
        login,
        c.path("body").asText(""),
        DispositionSupport.instantOrEpoch(c.path("created_at")),
        c.path("commit_id").isMissingNode() || c.path("commit_id").isNull()
            ? null
            : c.path("commit_id").asText());
  }

  @Override
  public CommentLedger fetchCommitBotComments(RepoRef repo, String commitSha) {
    String login = botLogin(repo);
    List<LedgerComment> comments = new ArrayList<>();
    List<LedgerFindingMarker> findings = new ArrayList<>();
    List<LedgerRunMarker> runs = new ArrayList<>();
    collectPaged(
        repo,
        "/commits/" + GitHubPayloadSupport.urlEncode(commitSha) + "/comments",
        login,
        LedgerComment.KIND_COMMIT,
        comments);
    int suspectCount = parseMarkers(comments, findings, runs);
    return new CommentLedger(comments, findings, runs, suspectCount);
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
    boolean done = false;
    for (int page = 1; page <= MAX_PAGES + 1 && !done; page++) {
      JsonNode root =
          transport.getJson(
              repo,
              "/issues?state=all&sort=updated&direction=desc&per_page="
                  + PAGE_SIZE
                  + "&page="
                  + page
                  + sinceQuery);
      if (page == MAX_PAGES + 1) {
        if (root.isArray() && !root.isEmpty()) {
          throw new CraIntegrationException("issue 分页超过上限 " + MAX_ITEMS + " 条，无法保证列表完整");
        }
      } else if (!root.isArray() || root.isEmpty()) {
        done = true;
      } else {
        boolean reachedOlder = collectIssuesPage(root, login, since, out);
        done = root.size() < PAGE_SIZE || reachedOlder;
      }
    }
    return out;
  }

  /**
   * 处理一页 issue：只收 bot 发布、窗口内、非 PR 条目；返回是否已越过窗口下界。
   *
   * @param root 页数组
   * @param login bot 用户名
   * @param since 窗口下界（null = 不限）
   * @param out 输出
   * @return true = 本页出现早于窗口的条目（后续页只会更早）
   */
  private static boolean collectIssuesPage(
      JsonNode root, String login, Instant since, List<LedgerComment> out) {
    boolean reachedOlder = false;
    for (JsonNode issue : root) {
      // /issues 端点同时返回 PR，PR 条目不是夜间 issue 载体（也不参与窗口下界判定）
      if (!issue.path("pull_request").isMissingNode()) {
        continue;
      }
      Instant updated = DispositionSupport.instantOrNull(issue.path("updated_at"));
      if (since != null && (updated == null || updated.isBefore(since))) {
        if (updated != null) {
          // 按 updated desc 排序：本页出现早于窗口的条目，后续页只会更早
          reachedOlder = true;
        }
      } else if (login.equals(issue.path("user").path("login").asText())
          && issue.path("number").asLong(0) > 0) {
        out.add(
            new LedgerComment(
                issue.path("number").asLong(0),
                LedgerComment.KIND_ISSUE,
                login,
                issue.path("body").asText(""),
                DispositionSupport.instantOrEpoch(issue.path("created_at")),
                null));
      }
    }
    return reachedOlder;
  }

  private static boolean marksCommit(ParseOutcome outcome, String commitSha) {
    if (outcome.marker().isEmpty()) {
      return false;
    }
    MarkerParseResult parsed = outcome.marker().orElseThrow();
    if (parsed instanceof MarkerParseResult.RunMark r) {
      return r.commitSha().equalsIgnoreCase(commitSha);
    }
    return ((MarkerParseResult.FindingMark) parsed).commitSha().equalsIgnoreCase(commitSha);
  }

  @Override
  public List<PullRequestRef> listPullRequests(RepoRef repo, Instant since) {
    List<PullRequestRef> out = new ArrayList<>();
    boolean done = false;
    for (int page = 1; page <= MAX_PAGES + 1 && !done; page++) {
      JsonNode root =
          transport.getJson(
              repo,
              "/pulls?state=all&sort=updated&direction=desc&per_page="
                  + PAGE_SIZE
                  + "&page="
                  + page);
      if (page == MAX_PAGES + 1) {
        if (root.isArray() && !root.isEmpty()) {
          throw new CraIntegrationException("PR 分页超过上限 " + MAX_ITEMS + " 条，无法保证列表完整");
        }
      } else if (!root.isArray() || root.isEmpty()) {
        done = true;
      } else {
        boolean reachedOlder = collectPullRequestsPage(root, since, out);
        done = root.size() < PAGE_SIZE || reachedOlder;
      }
    }
    return out;
  }

  /**
   * 处理一页 PR：窗口外的 PR 跳过（整页越过下界则停止翻页），缺 head sha 的 PR 如实告警跳过。
   *
   * @param root 页数组
   * @param since 窗口下界（null = 不限）
   * @param out 输出
   * @return true = 本页出现早于窗口的条目（后续页只会更早）
   */
  private static boolean collectPullRequestsPage(
      JsonNode root, Instant since, List<PullRequestRef> out) {
    boolean reachedOlder = false;
    for (JsonNode pr : root) {
      Instant updated = DispositionSupport.instantOrNull(pr.path("updated_at"));
      if (since != null && (updated == null || updated.isBefore(since))) {
        if (updated != null) {
          // 按 updated desc 排序：本页出现早于窗口的条目，后续页只会更早
          reachedOlder = true;
        }
        continue;
      }
      String headSha = pr.path("head").path("sha").asText(null);
      if (headSha == null || headSha.isBlank()) {
        // 单个 PR 数据残缺不中止整轮列表（对齐 GitLabCommentReader 的 warn+跳过；05§2.5 如实降级）
        LOG.warn("github PR #{} has no head sha; skipped from window", pr.path("number").asInt());
        continue;
      }
      out.add(
          new PullRequestRef(
              pr.path("number").asInt(),
              headSha,
              pr.path("merge_commit_sha").isNull() ? null : pr.path("merge_commit_sha").asText(),
              pr.path("state").asText(PullRequestRef.STATE_CLOSED),
              updated == null ? Instant.EPOCH : updated,
              DispositionSupport.instantOrNull(pr.path("merged_at")),
              pr.path("base").path("sha").asText(null)));
    }
    return reachedOlder;
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
    Set<Long> botCommentIds =
        reviewComments.stream()
            .filter(c -> login.equals(c.path("user").path("login").asText()))
            .map(c -> c.path("id").asLong())
            .collect(Collectors.toSet());
    List<DispositionSignal> out = new ArrayList<>();
    collectReplySignals(reviewComments, botCommentIds, out);
    collectIssueReactions(repo, prNumber, login, out);
    return out.toArray(new DispositionSignal[0]);
  }

  /**
   * 采集人类对 bot inline 评论的 reply 标签（06§1.1）。
   *
   * @param reviewComments PR 全量 inline 评论
   * @param botCommentIds bot 评论 id 集
   * @param out 信号输出
   */
  private static void collectReplySignals(
      List<JsonNode> reviewComments, Set<Long> botCommentIds, List<DispositionSignal> out) {
    for (JsonNode c : reviewComments) {
      long parent = c.path("in_reply_to_id").asLong(0);
      String body = c.path("body").asText("");
      // 只认对 bot 评论的回复；PR 内人类互相回复不是处置信号
      String tag =
          parent > 0 && botCommentIds.contains(parent) ? DispositionSupport.replyTag(body) : null;
      if (tag == null) {
        continue; // 无标签回复不改变处置分类（ignored 口径据此保持噪声语义）
      }
      out.add(
          new DispositionSignal(
              DispositionSignal.KIND_REPLY_TAG,
              parent,
              DispositionSupport.instantOrEpoch(c.path("created_at")),
              tag,
              c.path("user").path("login").asText(""),
              DispositionSupport.excerpt(body)));
    }
  }

  /**
   * 采集 bot Summary 评论的 👍/👎 reaction（06§1.1）。
   *
   * @param repo 仓库
   * @param prNumber PR 号
   * @param login bot 用户名
   * @param out 信号输出
   */
  private void collectIssueReactions(
      RepoRef repo, int prNumber, String login, List<DispositionSignal> out) {
    for (JsonNode c : getPaged(repo, "/issues/" + prNumber + "/comments")) {
      long commentId = c.path("id").asLong(0);
      if (!login.equals(c.path("user").path("login").asText("")) || commentId <= 0) {
        continue; // 只采 bot 的 Summary 评论 reaction（run 满意度载体，06§1.1）
      }
      collectReactionSignals(repo, commentId, out);
    }
  }

  /**
   * 采集单条评论的 reaction 信号（只识别 +1/-1）。
   *
   * @param repo 仓库
   * @param commentId 评论 id
   * @param out 信号输出
   */
  private void collectReactionSignals(RepoRef repo, long commentId, List<DispositionSignal> out) {
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
              DispositionSupport.instantOrEpoch(r.path("created_at")),
              content,
              r.path("user").path("login").asText(""),
              ""));
    }
  }

  private List<JsonNode> getPaged(RepoRef repo, String path) {
    List<JsonNode> out = new ArrayList<>();
    for (int page = 1; page <= MAX_PAGES + 1; page++) {
      JsonNode root = transport.getJson(repo, path + "?per_page=" + PAGE_SIZE + "&page=" + page);
      if (page == MAX_PAGES + 1) {
        // 探页：恰好 10k 条时第 101 页为空才算完整；有数据说明还有未读条目
        if (root.isArray() && !root.isEmpty()) {
          throw new CraIntegrationException("分页超过上限 " + MAX_ITEMS + " 条，无法保证结果完整: " + path);
        }
        break;
      }
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
        String login =
            transport
                .parseJson(transport.execute(request, "/user").body(), "/user")
                .path("login")
                .asText("");
        if (login.isBlank()) {
          // 空 login 会让账本过滤把所有评论都排除（空账本会被误判为可重发），宁可失败
          throw new CraIntegrationException(
              "GitHub bot identity unavailable: empty login from /user");
        }
        cachedLogin = login;
      } catch (CraIntegrationException e) {
        if (!GitHubHttpTransport.isStatusOnPath(e, 403, "/user")) {
          throw e;
        }
        // Actions 集成 token（GITHUB_TOKEN）是 App 语义，/user 恒 403——CI 内 bot 身份即
        // 工作流 bot（05§2.3 钉死：CI 内为 github-actions[bot]）
        cachedLogin = CI_BOT_LOGIN;
      }
    }
    return cachedLogin;
  }
}
