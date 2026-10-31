package org.cra.server.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * /api/v1/** 的 Bearer token 保护（报告签发等管理端点；webhook 走平台验签——GitHub HMAC / GitLab token——不经本过滤器）。
 *
 * <p>fail closed：token 缺失/不匹配一律 401；两侧先 SHA-256 再常数时间比较（不泄露 token 长度，与 GitLab webhook 验签一致）。
 */
public final class ApiTokenFilter extends OncePerRequestFilter {

  private static final String PREFIX = "Bearer ";

  private final byte[] tokenDigest;

  /**
   * @param token 服务 API token（CRA_SERVER_API_TOKEN）
   */
  public ApiTokenFilter(String token) {
    if (token == null || token.isBlank()) {
      throw new IllegalArgumentException("API token 不能为空");
    }
    this.tokenDigest = sha256(token.getBytes(StandardCharsets.UTF_8));
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();
    String contextPath = request.getContextPath();
    if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
      path = path.substring(contextPath.length());
    }
    return !path.startsWith("/api/v1/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String header = request.getHeader("Authorization");
    if (header == null
        || header.length() <= PREFIX.length()
        || !header.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
      unauthorized(response);
      return;
    }
    byte[] providedDigest =
        sha256(header.substring(PREFIX.length()).getBytes(StandardCharsets.UTF_8));
    if (!MessageDigest.isEqual(tokenDigest, providedDigest)) {
      unauthorized(response);
      return;
    }
    filterChain.doFilter(request, response);
  }

  private static byte[] sha256(byte[] value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 不可用", e);
    }
  }

  private static void unauthorized(HttpServletResponse response) throws IOException {
    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    response.setContentType("application/json");
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.getWriter().write("{\"status\":\"unauthorized\"}");
  }
}
