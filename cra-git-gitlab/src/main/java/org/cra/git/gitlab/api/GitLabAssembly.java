package org.cra.git.gitlab.api;

import java.net.http.HttpClient;
import org.cra.git.gitlab.internal.GitLabHttpAdapter;

/** cra-git-gitlab 装配工厂（10§4：api 包只放工厂，internal 实现跨模块不可见）。 */
public final class GitLabAssembly {

  /** GitLab.com API 根（自建实例通过重载显式传入，05§2.3 端点可注入）。 */
  public static final String DEFAULT_API_BASE = "https://gitlab.com/api/v4";

  private GitLabAssembly() {}

  /**
   * 创建 GitLab 组件（REST v4；apiBase 可注入测试地址）。
   *
   * @param apiBase API 根地址（null/空白取 {@link #DEFAULT_API_BASE}）
   * @param token GitLab access token（PRIVATE-TOKEN；只从环境变量读，05§6）
   * @return 平台适配器 + CommentSource（同一实现，ADR-0005）
   */
  public static GitLabComponents createComponents(String apiBase, String token) {
    return createComponents(apiBase, token, null);
  }

  /**
   * 创建 GitLab 组件（带 bot 身份回退）。
   *
   * <p>GitLab CI job token 通常不能读 `GET /user`，此时必须显式给出 bot 用户名（如 project access token 对应的 bot
   * 用户），否则适配器拒绝把任何评论当账本（ADR-0009 决策 3）。
   *
   * @param apiBase API 根地址（null/空白取 {@link #DEFAULT_API_BASE}）
   * @param token GitLab access token
   * @param botUsernameFallback `/user` 不可读时使用的 bot 用户名（可空）
   * @return 平台适配器 + CommentSource
   */
  public static GitLabComponents createComponents(
      String apiBase, String token, String botUsernameFallback) {
    String base = apiBase == null || apiBase.isBlank() ? DEFAULT_API_BASE : apiBase;
    GitLabHttpAdapter adapter =
        new GitLabHttpAdapter(
            base,
            token,
            HttpClient.newBuilder().build(),
            d -> {
              try {
                Thread.sleep(d);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            },
            botUsernameFallback);
    return new GitLabComponents(adapter, adapter);
  }
}
