package org.cra.llm.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.cra.llm.api.LlmRequest;
import org.cra.llm.api.ResponseMode;
import org.junit.jupiter.api.Test;

/** OpenAI 兼容编解码单测（chat+JSON 双模式的请求形状与响应解析，07 T0.2）。 */
class OpenAiCodecTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void jsonModeCarriesResponseFormat() throws Exception {
    String body =
        OpenAiCodec.encodeRequest(
            "m1", new LlmRequest("sys", "user", ResponseMode.JSON, 500, "general-reviewer"));
    JsonNode root = MAPPER.readTree(body);
    assertThat(root.path("response_format").path("type").asText()).isEqualTo("json_object");
    assertThat(root.path("max_tokens").asInt()).isEqualTo(500);
    assertThat(root.path("messages").get(0).path("role").asText()).isEqualTo("system");
    assertThat(root.path("messages").get(1).path("content").asText()).isEqualTo("user");
    assertThat(root.path("model").asText()).isEqualTo("m1");
  }

  @Test
  void textModeOmitsResponseFormat() throws Exception {
    String body =
        OpenAiCodec.encodeRequest(
            "m1", new LlmRequest("sys", "user", ResponseMode.TEXT, null, "x"));
    JsonNode root = MAPPER.readTree(body);
    assertThat(root.has("response_format")).isFalse();
    assertThat(root.has("max_tokens")).isFalse();
  }

  @Test
  void decodeReadsContentAndUsage() throws Exception {
    OpenAiCodec.ParsedResponse parsed =
        OpenAiCodec.decodeResponse(
            """
        {"choices":[{"message":{"content":"hello"}}],
         "usage":{"prompt_tokens":18200,"completion_tokens":1500}}""");
    assertThat(parsed.content()).isEqualTo("hello");
    assertThat(parsed.tokensIn()).isEqualTo(18200);
    assertThat(parsed.tokensOut()).isEqualTo(1500);
  }

  @Test
  void decodeEstimatesWhenUsageMissing() throws Exception {
    String content = "a".repeat(35);
    OpenAiCodec.ParsedResponse parsed =
        OpenAiCodec.decodeResponse(
            "{\"choices\":[{\"message\":{\"content\":\"" + content + "\"}}]}");
    assertThat(parsed.tokensIn()).isGreaterThan(0);
    assertThat(parsed.tokensOut()).isEqualTo(10); // 35/3.5
  }

  @Test
  void decodeRejectsMalformedBody() {
    assertThatThrownBy(() -> OpenAiCodec.decodeResponse("{\"nope\":1}"))
        .isInstanceOf(java.io.IOException.class)
        .hasMessageContaining("no choices");
  }
}
