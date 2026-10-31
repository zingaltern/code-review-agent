package org.cra.store.internal;

import java.util.List;
import java.util.Optional;
import org.cra.common.git.CommentLedger;
import org.cra.common.git.CommentSource;
import org.cra.common.git.LedgerComment;
import org.cra.common.git.LedgerFindingMarker;
import org.cra.common.git.LedgerRunMarker;
import org.cra.common.git.RepoRef;
import org.cra.common.git.ReviewKey;
import org.cra.common.run.ReviewMode;
import org.cra.store.api.ReviewLedger;

/**
 * GitHub 评论账本（ADR-0005、02§5：bot 自身发布的 PR 评论即事实源）。读侧视图—— 写入由发布动作内嵌标记完成（05§1.3 规则 3），本类不写评论（11§8 反模式
 * 3）；写侧 default 方法在此保持无操作（{@link ReviewLedger} 约定）。 解析与兜底分层由取数端口返回的 {@link CommentLedger} 承载（05§1.3
 * 规则 5）。
 */
public final class GitHubCommentLedger implements ReviewLedger {

  private final CommentSource source;
  private final RepoRef repo;

  /**
   * @param source 账本取数端口（CommentSource，构造器注入）
   * @param repo 绑定仓库
   */
  public GitHubCommentLedger(CommentSource source, RepoRef repo) {
    this.source = source;
    this.repo = repo;
  }

  @Override
  public Optional<LedgerRunMarker> findCompletion(int prNumber, String commitSha) {
    CommentLedger ledger = source.fetchBotComments(repo, prNumber);
    // 同 commit 重跑时取首条完成标记即可（闸语义只判存在性）
    return ledger.runMarkers().stream()
        .filter(LedgerRunMarker::isCompletion)
        .filter(m -> m.commitSha().equalsIgnoreCase(commitSha))
        .findFirst();
  }

  @Override
  public List<LedgerFindingMarker> publishedFindings(int prNumber) {
    return source.fetchBotComments(repo, prNumber).findingMarkers();
  }

  @Override
  public List<LedgerRunMarker> completedRuns(int prNumber) {
    return LedgerMarkerOrder.completionMarkers(source.fetchBotComments(repo, prNumber));
  }

  @Override
  public int suspectMarkerCount(int prNumber) {
    return source.fetchBotComments(repo, prNumber).suspectCount();
  }

  @Override
  public Optional<LedgerComment> comment(int prNumber, long commentId) {
    return source.fetchBotComments(repo, prNumber).comments().stream()
        .filter(c -> c.id() == commentId)
        .findFirst();
  }

  // ---------- 键控扩展（M4c/M4d，05§1.3）：PR 形态只接受 PULL_REQUEST 键 ----------

  @Override
  public Optional<LedgerRunMarker> findCompletion(ReviewKey key) {
    return findCompletion(requirePullRequest(key), key.commitSha());
  }

  @Override
  public List<LedgerFindingMarker> publishedFindings(ReviewKey key) {
    return publishedFindings(requirePullRequest(key));
  }

  @Override
  public List<LedgerRunMarker> completedRuns(ReviewKey key) {
    return completedRuns(requirePullRequest(key));
  }

  @Override
  public int suspectMarkerCount(ReviewKey key) {
    return suspectMarkerCount(requirePullRequest(key));
  }

  @Override
  public Optional<LedgerComment> comment(ReviewKey key, long commentId) {
    return comment(requirePullRequest(key), commentId);
  }

  private int requirePullRequest(ReviewKey key) {
    if (key.mode() != ReviewMode.PULL_REQUEST) {
      throw new IllegalArgumentException(
          "PR 评论账本只接受 PULL_REQUEST 键: " + key.mode() + "（无 PR 形态用 GitHubCarrierLedger）");
    }
    if (!repo.owner().equalsIgnoreCase(key.repoOwner())
        || !repo.repo().equalsIgnoreCase(key.repoName())) {
      throw new IllegalArgumentException(
          "账本仓库不匹配：ledger=" + repo + " key=" + key.repoOwner() + "/" + key.repoName());
    }
    return key.prNumber();
  }
}
