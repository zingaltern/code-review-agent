package org.cra.store.api;

import java.nio.file.Path;
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

  /**
   * 创建本地 SQLite 账本（02§5 本地形态；M2 本地 pre-PR 模式组合根使用）。
   *
   * <p>注意：JDBC 驱动（org.xerial:sqlite-jdbc）由运行时 classpath 提供，cra-store 自身只依赖
   * java.sql——本地模式装配时组合根须确保驱动在依赖清单（M1 由协调者统一合入，见 feat/m1-ledger 汇报）。
   *
   * @param repoOwner 仓库属主
   * @param repoName 仓库名
   * @param dbFile 数据库文件路径（如 ~/.cra/ledger.db）
   * @return ReviewLedger
   */
  public static ReviewLedger createSqliteLedger(String repoOwner, String repoName, Path dbFile) {
    return new org.cra.store.internal.sqlite.SqliteLedger(repoOwner, repoName, dbFile);
  }
}
