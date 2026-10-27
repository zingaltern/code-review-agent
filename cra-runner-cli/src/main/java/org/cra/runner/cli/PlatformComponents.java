package org.cra.runner.cli;

import org.cra.common.git.CommentSource;
import org.cra.git.api.GitPlatformAdapter;
import org.cra.git.github.api.GitHubAssembly;
import org.cra.git.github.api.GitHubComponents;
import org.cra.git.gitlab.api.GitLabAssembly;
import org.cra.git.gitlab.api.GitLabComponents;

/**
 * 平台组件集（ADR-0012）：按配置装配 GitHub 或 GitLab 的 adapter + CommentSource，组合根与 sweep 共用。
 *
 * <p>两个实现模块互不依赖（11§4 第 4 条）；本类位于组合根，按平台选择其一，未选中的平台不构造、不读取其 token。
 *
 * @param adapter Git 平台适配器
 * @param commentSource 账本/处置取数端口（05§2.1）
 */
record PlatformComponents(GitPlatformAdapter adapter, CommentSource commentSource) {

  /**
   * 按配置装配平台组件。
   *
   * @param config 运行配置
   * @return 平台组件集
   */
  static PlatformComponents create(CraConfig config) {
    if (config.isGitlab()) {
      GitLabComponents components =
          GitLabAssembly.createComponents(
              config.gitlab().baseUrl(), config.gitlab().token(), config.gitlab().botUsername());
      return new PlatformComponents(components.adapter(), components.commentSource());
    }
    GitHubComponents components =
        GitHubAssembly.createComponents(config.githubApi(), config.githubToken());
    return new PlatformComponents(components.adapter(), components.commentSource());
  }
}
