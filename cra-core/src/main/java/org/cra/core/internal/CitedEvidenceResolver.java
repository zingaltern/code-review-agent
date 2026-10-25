package org.cra.core.internal;

import java.util.Optional;
import org.cra.git.api.LocalRepoContext;

/**
 * 被引矛盾证据解析（03§4.2 v1.3.1：context 类 finding 引用的 commit diff 节选，编排器预取内联， 每 commit ≤1k token）。经本地 jgit
 * 读取（{@link LocalRepoContext#commitFilePatch}）。
 *
 * <p>best effort：本地仓库不可得 / commit 不可解析 / 文件不在该 commit 内 → 空串（组装器降级为 finding 自带证据原文）。引用格式的权威定义随
 * CrossChecker（M4f）落定，M2 仅要求 7–40 位 hex 引用可解析。
 */
final class CitedEvidenceResolver {

  private final LocalRepoContext localRepoContext;

  /**
   * @param localRepoContext 本地仓库上下文（null = 纯 API 形态，全部降级为空）
   */
  CitedEvidenceResolver(LocalRepoContext localRepoContext) {
    this.localRepoContext = localRepoContext;
  }

  /**
   * 取被引 commit 对文件的 unified patch。
   *
   * @param sha 被引 commit（完整或缩写）
   * @param path commit 内文件路径
   * @return patch 文本；不可得为空串
   */
  String commitPatch(String sha, String path) {
    if (localRepoContext == null) {
      return "";
    }
    try {
      Optional<String> patch = localRepoContext.commitFilePatch(sha, path);
      return patch.orElse("");
    } catch (RuntimeException e) {
      return "";
    }
  }
}
