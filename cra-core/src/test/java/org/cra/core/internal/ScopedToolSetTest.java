package org.cra.core.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.cra.common.git.FileContent;
import org.junit.jupiter.api.Test;

/** T2.1 受限工具集行为测试（03§2.3：read_file/grep_pattern scoped；作用域外即违规；输出截断防预算击穿）。 */
class ScopedToolSetTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static ScopedToolSet set() {
    return new ScopedToolSet(
        java.util.Map.of(
            "src/Foo.java",
            FileContent.ofText("src/Foo.java", "package demo;\npublic class Foo {\n  int x;\n}\n"),
            "src/Bar.java",
            FileContent.ofText("src/Bar.java", "final class Bar {\n  // TODO secret\n}\n")));
  }

  private static ScopedToolSet.ToolResult exec(ScopedToolSet tools, String json) throws Exception {
    return tools.execute("read_file", MAPPER.readTree(json));
  }

  @Test
  void readFileReturnsNumberedSlice() throws Exception {
    ScopedToolSet.ToolResult result =
        exec(set(), "{\"path\": \"src/Foo.java\", \"start_line\": 2, \"end_line\": 3}");
    assertThat(result.allowed()).isTrue();
    assertThat(result.output()).contains("L2: public class Foo {");
    assertThat(result.output()).contains("L3:   int x;");
    assertThat(result.output()).doesNotContain("L1: package demo;");
  }

  @Test
  void readFileDefaultsToWholeFile() throws Exception {
    ScopedToolSet.ToolResult result = exec(set(), "{\"path\": \"src/Foo.java\"}");
    assertThat(result.allowed()).isTrue();
    assertThat(result.output()).contains("L1: package demo;");
    assertThat(result.output()).contains("L4: }");
  }

  @Test
  void readFileOutsideScopeIsViolation() throws Exception {
    ScopedToolSet.ToolResult result = exec(set(), "{\"path\": \"../etc/passwd\"}");
    assertThat(result.allowed()).isFalse();
    assertThat(result.output()).contains("越界");
  }

  @Test
  void readFileMissingPathIsArgErrorNotViolation() throws Exception {
    ScopedToolSet.ToolResult result = exec(set(), "{}");
    assertThat(result.allowed()).isTrue();
    assertThat(result.output()).contains("参数非法");
  }

  @Test
  void grepMatchesAcrossScopedFilesWithLineNumbers() throws Exception {
    ScopedToolSet.ToolResult result =
        set().execute("grep_pattern", MAPPER.readTree("{\"pattern\": \"TODO|class \"}"));
    assertThat(result.allowed()).isTrue();
    // TreeMap 路径序：Bar 在前
    assertThat(result.output()).contains("src/Bar.java:L1: final class Bar {");
    assertThat(result.output()).contains("src/Bar.java:L2: // TODO secret");
    assertThat(result.output()).contains("src/Foo.java:L2: public class Foo {");
  }

  @Test
  void grepNoMatchIsInformativeNotViolation() throws Exception {
    ScopedToolSet.ToolResult result =
        set().execute("grep_pattern", MAPPER.readTree("{\"pattern\": \"zzz-not-there\"}"));
    assertThat(result.allowed()).isTrue();
    assertThat(result.output()).contains("无匹配");
  }

  @Test
  void grepBadRegexIsArgError() throws Exception {
    ScopedToolSet.ToolResult result =
        set().execute("grep_pattern", MAPPER.readTree("{\"pattern\": \"([bad\"}"));
    assertThat(result.allowed()).isTrue();
    assertThat(result.output()).contains("正则非法");
  }

  @Test
  void undeclaredToolIsViolation() throws Exception {
    ScopedToolSet.ToolResult result = set().execute("write_file", MAPPER.readTree("{}"));
    assertThat(result.allowed()).isFalse();
  }

  @Test
  void readIsCappedAtMaxLines() throws Exception {
    StringBuilder big = new StringBuilder();
    for (int i = 1; i <= 1_000; i++) {
      big.append("line ").append(i).append("\n");
    }
    ScopedToolSet tools =
        new ScopedToolSet(
            java.util.Map.of("big.txt", FileContent.ofText("big.txt", big.toString())));
    ScopedToolSet.ToolResult result = exec(tools, "{\"path\": \"big.txt\"}");
    assertThat(result.allowed()).isTrue();
    assertThat(result.output()).contains("已截断");
    assertThat(result.output()).contains("L400:");
    assertThat(result.output()).doesNotContain("L401:");
  }
}
