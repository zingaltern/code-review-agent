package org.cra.store.api;

import org.cra.common.git.CommentSource;
import org.cra.common.git.RepoRef;

/** cra-store 装配工厂（10§4）。 */
public final class StoreAssembly {

  private StoreAssembly() {}

  /**
   * 创建 GitHub 评论账本（ADR-0005：构造器注入 CommentSource 取数，账本实现只依赖 common 类型）。
   *
   * @param source 账本取数端口（cra-git-github 提供）
   * @param repo 账本绑定的仓库（实例按仓库创建，组合根每 run 装配）
   * @return ReviewLedger
   */
  public static ReviewLedger createGitHubLedger(CommentSource source, RepoRef repo) {
    return new org.cra.store.internal.GitHubCommentLedger(source, repo);
  }
}
