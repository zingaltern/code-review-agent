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
   * 创建无 PR 形态的 GitHub 载体账本（M4c push = commit comment；M4d 夜间 = issue，05§1.3 键控扩展）。
   *
   * @param source 账本取数端口（cra-git-github 提供 fetchCommitBotComments / fetchIssueBotComments）
   * @param repo 账本绑定的仓库
   * @param mode 载体形态（COMMIT | ISSUE；PULL_REQUEST 走两参重载）
   * @return ReviewLedger
   */
  public static ReviewLedger createGitHubLedger(
      CommentSource source, RepoRef repo, org.cra.common.run.ReviewMode mode) {
    if (mode == org.cra.common.run.ReviewMode.PULL_REQUEST) {
      return createGitHubLedger(source, repo);
    }
    return new org.cra.store.internal.GitHubCarrierLedger(source, repo, mode);
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

  /**
   * 创建 PostgreSQL 账本（02§5 服务形态；M4b webhook 常驻服务）。
   *
   * <p>schema 为 {@code db/postgres/ledger.sql} 的手写幂等 DDL，由本工厂在构造后立即执行（重复调用安全）。连接池由组合根提供： server 用
   * Spring Boot 的 HikariCP，测试用 PGSimpleDataSource；cra-store 自身只依赖 java.sql。
   *
   * @param repoOwner 仓库属主（webhook 可来自多仓库，组合根按 job 的仓库创建实例）
   * @param repoName 仓库名
   * @param dataSource 连接池
   * @return ReviewLedger
   */
  public static ReviewLedger createPostgresLedger(
      String repoOwner, String repoName, javax.sql.DataSource dataSource) {
    org.cra.store.internal.postgres.PostgresLedger ledger =
        new org.cra.store.internal.postgres.PostgresLedger(repoOwner, repoName, dataSource);
    ledger.ensureSchema();
    return ledger;
  }

  /**
   * 只执行 PostgreSQL 五表的幂等 DDL（服务启动时 fail-fast；不创建账本实例）。
   *
   * @param dataSource 连接池
   */
  public static void ensurePostgresSchema(javax.sql.DataSource dataSource) {
    new org.cra.store.internal.postgres.PostgresLedger("schema", "schema", dataSource)
        .ensureSchema();
  }
}
