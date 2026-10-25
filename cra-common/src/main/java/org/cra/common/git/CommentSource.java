package org.cra.common.git;

import java.time.Instant;
import java.util.List;

/**
 * 账本与处置采集的取数端口（05§2.1 v1 基线新增，ADR-0005）。
 *
 * <p>消费方：cra-store 的 GitHub 评论账本实现（构造器注入，只依赖 common 类型）与 cra-eval report 周报；提供方：cra-git-github（适配既有
 * adapter 方法）；装配：组合根 （cra-runner-cli / eval 的 Main）。每新增平台须同步提供本端口实现（11§2）。
 */
public interface CommentSource {

  /**
   * 枚举窗口内（updated_at ≥ since）的 PR（06§1.1 周报需要先知道账本覆盖哪些 PR，05§2.1）。
   *
   * <p>默认实现返回空列表 = "平台不提供 PR 列表"（11§4 第 5 条不可得语义）：周报据此声明该维度不可采， 不以异常伪装支持。
   *
   * @param repo 仓库引用
   * @param since 窗口起点（含）
   * @return PR 条目（平台不提供时为空列表）
   */
  default List<PullRequestRef> listPullRequests(RepoRef repo, Instant since) {
    return List.of();
  }

  /**
   * 拉取某 PR 上 bot 自身发布的全部评论并解析账本标记（与 GitPlatformAdapter.fetchBotComments 同名方法同语义；inline 与 issue
   * 评论分属不同端点，合并去重，05§2.3）。
   *
   * @param repo 仓库引用
   * @param prNumber PR 编号
   * @return 账本快照（评论原文 + 解析出的标记 + 可疑计数）
   */
  CommentLedger fetchBotComments(RepoRef repo, int prNumber);

  /**
   * 拉取处置信号（06§1.1 周报数据面）。信号不可得的平台返回空数组并声明"不采" （11§4 第 5 条：不可得语义，禁止抛异常假装支持）。
   *
   * <p>REST v3 下可读的两类：人类对 bot inline 评论的 reply 标签（双端点合并的一部分，05§2.3）与 Summary issue comment 的 👍/👎
   * reaction。resolve 状态为 GraphQL-only，不采（06§1.1）。
   *
   * @param repo 仓库引用
   * @param prNumber PR 编号
   * @return 处置信号数组
   */
  DispositionSignal[] fetchDispositionSignals(RepoRef repo, int prNumber);
}
