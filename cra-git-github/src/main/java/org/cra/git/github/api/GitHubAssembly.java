package org.cra.git.github.api;

import java.net.http.HttpClient;
import org.cra.git.github.internal.GitHubHttpAdapter;

/** cra-git-github 装配工厂（10§4：api 包只放工厂，internal 实现跨模块不可见）。 */
public final class GitHubAssembly {

  private GitHubAssembly() {}

  /**
   * 创建 GitHub 组件（REST v3；apiBase 可注入测试地址）。
   *
   * @param apiBase API 根地址（生产用 https://api.github.com）
   * @param token GITHUB_TOKEN（只从环境变量读，05§6）
   * @return 平台适配器 + CommentSource（同一实现，ADR-0005）
   */
  public static GitHubComponents createComponents(String apiBase, String token) {
    GitHubHttpAdapter adapter =
        new GitHubHttpAdapter(
            apiBase,
            token,
            HttpClient.newBuilder().build(),
            d -> {
              try {
                Thread.sleep(d);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            });
    return new GitHubComponents(adapter, adapter);
  }
}
