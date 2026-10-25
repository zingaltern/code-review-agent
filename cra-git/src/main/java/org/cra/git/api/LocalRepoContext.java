package org.cra.git.api;

import java.util.Optional;
import org.cra.common.git.BlameResult;

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
}
