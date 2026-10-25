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
}
