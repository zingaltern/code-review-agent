package org.cra.store.internal;

import java.util.List;
import java.util.Optional;
import org.cra.common.git.CommentLedger;
import org.cra.common.git.CommentSource;
import org.cra.common.git.LedgerFindingMarker;
import org.cra.common.git.LedgerRunMarker;
import org.cra.common.git.RepoRef;
import org.cra.store.api.ReviewLedger;

/**
 * GitHub 评论账本（ADR-0005、02§5：bot 自身发布的 PR 评论即事实源）。只读视图—— 写入由发布动作内嵌标记完成（05§1.3 规则 3），本类不写评论（11§8 反模式
 * 3）。 解析与兜底分层由取数端口返回的 {@link CommentLedger} 承载（05§1.3 规则 5）。
 */
public class GitHubCommentLedger implements ReviewLedger {

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
}
