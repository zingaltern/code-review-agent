package org.cra.server.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** API token 过滤器（/api/v1/** Bearer；fail closed）。 */
class ApiTokenFilterTest {

  private final ApiTokenFilter filter = new ApiTokenFilter("api-token");

  @Test
  void allowsValidBearer() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/reports/attest");
    request.addHeader("Authorization", "Bearer api-token");
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();
    filter.doFilter(request, response, chain);
    assertThat(chain.getRequest()).isNotNull();
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void rejectsMissingOrWrongToken() throws Exception {
    MockHttpServletRequest missing = new MockHttpServletRequest("POST", "/api/v1/reports/attest");
    MockHttpServletResponse missingResponse = new MockHttpServletResponse();
    filter.doFilter(missing, missingResponse, new MockFilterChain());
    assertThat(missingResponse.getStatus()).isEqualTo(401);

    MockHttpServletRequest wrong = new MockHttpServletRequest("POST", "/api/v1/reports/attest");
    wrong.addHeader("Authorization", "Bearer wrong");
    MockHttpServletResponse wrongResponse = new MockHttpServletResponse();
    filter.doFilter(wrong, wrongResponse, new MockFilterChain());
    assertThat(wrongResponse.getStatus()).isEqualTo(401);
  }

  @Test
  void ignoresNonApiPaths() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/webhooks/github");
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();
    filter.doFilter(request, response, chain);
    assertThat(chain.getRequest()).isNotNull();
  }

  @Test
  void filtersApiPathsUnderContextPath() throws Exception {
    // server.servlet.context-path 下 requestURI 含前缀：必须仍保护 /api/v1（不能整体跳过过滤）
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/cra/api/v1/reports/attest");
    request.setContextPath("/cra");
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, new MockFilterChain());
    assertThat(response.getStatus()).isEqualTo(401);
  }

  @Test
  void acceptsLowerCaseBearerScheme() throws Exception {
    // HTTP auth scheme 大小写不敏感（RFC 7235）
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/reports/attest");
    request.addHeader("Authorization", "bearer api-token");
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();
    filter.doFilter(request, response, chain);
    assertThat(chain.getRequest()).isNotNull();
  }
}
