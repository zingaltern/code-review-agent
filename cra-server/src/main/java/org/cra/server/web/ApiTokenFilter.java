package org.cra.server.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * /api/v1/** 的 Bearer token 保护（报告签发等管理端点；webhook 走 HMAC 验签，不经本过滤器）。
 *
 * <p>fail closed：token 缺失/不匹配一律 401；比较用常数时间。
 */
public final class ApiTokenFilter extends OncePerRequestFilter {

  private static final String PREFIX = "Bearer ";

  private final byte[] token;

  /**
   * @param token 服务 API token（CRA_SERVER_API_TOKEN）
   */
  public ApiTokenFilter(String token) {
    if (token == null || token.isBlank()) {
      throw new IllegalArgumentException("API token 不能为空");
    }
    this.token = token.getBytes(StandardCharsets.UTF_8);
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().startsWith("/api/v1/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String header = request.getHeader("Authorization");
    if (header == null || !header.startsWith(PREFIX)) {
      unauthorized(response);
      return;
    }
    byte[] provided = header.substring(PREFIX.length()).getBytes(StandardCharsets.UTF_8);
    if (!MessageDigest.isEqual(token, provided)) {
      unauthorized(response);
      return;
    }
    filterChain.doFilter(request, response);
  }

  private static void unauthorized(HttpServletResponse response) throws IOException {
    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    response.setContentType("application/json");
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.getWriter().write("{\"status\":\"unauthorized\"}");
  }
}
