package org.cra.git.api;

import java.util.List;
import java.util.Optional;
import org.cra.common.git.CommentLedger;
import org.cra.common.git.DiffResult;
import org.cra.common.git.DispositionSignal;
import org.cra.common.git.FileContent;
import org.cra.common.git.PostCommitReviewCommand;
import org.cra.common.git.PostIssueCommand;
import org.cra.common.git.PostReviewCommand;
import org.cra.common.git.PrReviewResult;
import org.cra.common.git.RepoRef;

/**
 * Git 平台适配 SPI（05§2.1；11§4：只加 default 方法，入参出参只允许 cra-common 纯 POJO）。 平台实现见 cra-git-github；blame
 * 不在本接口——走 {@link LocalRepoContext}（05§2.1）。
 */
public interface GitPlatformAdapter {

  /**
   * 拉取两 sha 间完整变更集（GitHub：compare API，02§2 步骤 2）。
   *
   * @param repo 仓库引用
   * @param baseSha 基线 sha
   * @param headSha 目标 sha
   * @return 变更集（文件 + hunk 区间 + commit 摘要）
   */
  DiffResult fetchDiff(RepoRef repo, String baseSha, String headSha);

  /**
   * 读取指定 sha 下单文件内容（GitHub：contents API raw）。
   *
   * @param repo 仓库引用
   * @param sha commit sha
   * @param path 文件路径
   * @return 文件内容（二进制时 text 为 null）
   */
  FileContent readFile(RepoRef repo, String sha, String path);

  /**
   * 列出指定 sha 下匹配 glob 的文件路径（编排器消费：调用点索引扫描域 ADR-0015 + 测试文件清单采集 v1.3.2；语义单一事实源 = {@link
   * org.cra.common.context.PathGlobs}，05§2.1）。
   *
   * @param repo 仓库引用
   * @param sha commit sha
   * @param pathGlob glob 模式（双星号加斜杠匹配零或多级目录，裸双星号跨段，单星号单段，问号单段通配；详见 {@link
   *     org.cra.common.context.PathGlobs#compile}）
   * @return 匹配的文件路径列表
   */
  List<String> listFiles(RepoRef repo, String sha, String pathGlob);

  /**
   * 发布评审（05§2.2：先 inline review → 再 Summary issue comment；整包 422 降级为逐条提交， 仍失败的
   * anchor_status=SUMMARY_FALLBACK）。
   *
   * @param cmd 发布命令（inline 评论 + Summary 正文）
   * @return 逐条结果与两类评论 id
   */
  PrReviewResult postReview(PostReviewCommand cmd);

  /**
   * 拉取某 PR 上 bot 自身评论并解析账本标记（02§2 步骤 1；inline 与 issue 端点合并，05§2.3）。
   *
   * @param repo 仓库引用
   * @param prNumber PR 编号
   * @return 账本快照
   */
  CommentLedger fetchBotComments(RepoRef repo, int prNumber);

  /**
   * 编辑 Summary issue comment 正文（05§2.2/§2.3：PATCH /repos/{o}/{r}/issues/comments/{id}，issue comment
   * 可编辑而 PR review body 不可）。两个消费方：过期收尾（02§5 加取代前缀）与幂等闸命中后的 Summary 处置。平台不支持评论编辑时以
   * UnsupportedOperationException 拒绝（不可得语义，11§4 第 5 条）， 调用方按降级处置。
   *
   * @param repo 仓库引用
   * @param prNumber PR 编号（URL 路径含 PR 便于校验）
   * @param commentId Summary issue comment id
   * @param body 编辑后的完整正文（整包替换语义，非增量）
   */
  default void updateSummaryComment(RepoRef repo, int prNumber, long commentId, String body) {
    throw new UnsupportedOperationException("comment editing unsupported by this adapter");
  }

  /**
   * 拉取处置信号（06§1.1 周报数据面；M0 不采，返回空数组——不可得语义，11§4 第 5 条）。
   *
   * @param repo 仓库引用
   * @param prNumber PR 编号
   * @return 处置信号数组
   */
  DispositionSignal[] fetchDispositionSignals(RepoRef repo, int prNumber);

  /**
   * 发布 commit comment 载体（M4c push 直推形态，05§2.4；SPI 演进新增 default 方法，11§4 第 1 条）。
   *
   * <p>commit comment 无行级锚点，全部 finding 以详情列表进入正文，文末带 run 完成标记。默认以 {@link
   * UnsupportedOperationException} 拒绝（"不可得"语义，11§4 第 5 条）——平台不支持 push 形态时组合根不得进入该模式， 不允许静默丢弃发布。
   *
   * @param cmd commit comment 发布命令
   * @return 成功时返回 commit comment id；平台未返回 id（异常形态）为空
   */
  default Optional<Long> postCommitReview(PostCommitReviewCommand cmd) {
    throw new UnsupportedOperationException("commit review carrier unsupported by this adapter");
  }

  /**
   * 发布 issue 载体（M4d 夜间存量评审，05§2.5；SPI 演进新增 default 方法，11§4 第 1 条）。
   *
   * <p>每条被评审 PR 一个 issue：正文含 Summary 与全部 finding 详情，文末带 run 完成标记。默认拒绝（不可得语义， 11§4 第 5 条），理由同 {@link
   * #postCommitReview}。
   *
   * @param cmd issue 发布命令
   * @return 成功时返回 issue 编号；平台未返回编号（异常形态）为空
   */
  default Optional<Long> postIssueReview(PostIssueCommand cmd) {
    throw new UnsupportedOperationException("issue review carrier unsupported by this adapter");
  }
}
