package org.cra.git.api;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.cra.common.git.BlameResult;
import org.cra.common.git.CommitHistoryEntry;

/**
 * 本地仓库上下文（05§2.1 v1.1）：blame 走本地 jgit——GitHub REST/GraphQL 均无行级 blame 端点； CI workflow 本就 checkout
 * 仓库，本地模式天然有仓库。M0 仅有接口（jgit blame 随 T1.7 落地）。
 */
public interface LocalRepoContext {

  /**
   * 行级 blame。
   *
   * @param path 文件路径
   * @param line 1 基行号
   * @param sha commit sha
   * @return blame 结果
   */
  BlameResult blame(String path, int line, String sha);

  /**
   * 本地仓库根（CI checkout / 本地模式存在；纯 API 模式为空——为空是合法形态，pre-existing 检测降级为不标注 + Summary 声明，05§2.1）。
   *
   * @return 仓库根目录，不可得时 empty
   */
  Optional<String> localRepoRoot();

  /**
   * 被引 commit 对指定文件的 diff 节选（对首父的 unified patch；03§4.2 v1.3.1 context 类验证 brief 的
   * 分型组装材料——被引矛盾证据原文，编排器预取内联）。 SPI 演进新增 default 方法（11§4 第 1 条）；M2 仅 VerifierAgent 的 context 类 brief
   * 消费（CrossChecker M4f 才产出 context 类 finding）。
   *
   * <p>best effort 语义：commit/文件不可得、无首父（根 commit）、仓库不可得均返回 empty——调用方降级为 finding 自带证据原文，不抛异常不炸 run。
   *
   * @param sha 被引 commit（完整或缩写 sha）
   * @param path 该 commit 内的文件路径（diff 按此路径过滤）
   * @return unified patch 节选；不可得为 empty
   */
  default Optional<String> commitFilePatch(String sha, String path) {
    return Optional.empty();
  }

  /**
   * 路径集近期变更史（03§3.1 CrossChecker 模式 A 的额外上下文输入 b：上下游路径集在近 N 周内的 commit 摘要）。
   *
   * <p>时间窗口以 {@code headSha} 的 committer time 为锚回溯（不以运行时钟为准——同输入同输出，可重放）；返回 提交时间倒序、仅命中 {@code
   * paths} 中任一文件的 commit（对首父的 diff 过滤，rename 的新旧路径均参与匹配）。 SPI 演进新增 default 方法（11§4 第 1 条）：
   * 默认不可得返回空列表——无本地仓库是合法形态，CrossChecker 变更史输入降级为如实声明（同 blame 模式，05§2.1）， 产品背景段（REVIEW.md）检查照常执行。
   *
   * <p>best effort 语义：head 不可解析 / 非仓库 / 读取失败均返回空列表，不抛异常不炸 run。
   *
   * @param paths 上下游路径集（相对仓库根；空集直接返回空列表）
   * @param weeks 回溯周数（≤0 视为不可得）
   * @param headSha 窗口锚点 commit（完整或缩写 sha）
   * @return commit 摘要列表（时间倒序；不可得为空列表）
   */
  default List<CommitHistoryEntry> recentCommits(Set<String> paths, int weeks, String headSha) {
    return List.of();
  }
}
