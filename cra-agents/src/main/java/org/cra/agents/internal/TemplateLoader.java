package org.cra.agents.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.cra.agents.api.TemplateSupport;

/**
 * 模板装载器（04§4）：classpath 资源 + {@code {>path}} 包含解析 + {@code {var}} 严格替换 + 内容哈希版本。零外部依赖（未引模板引擎——新依赖须走
 * PR 说明与白名单，09§7）。
 */
public final class TemplateLoader implements TemplateSupport {

  private static final Pattern INCLUDE = Pattern.compile("\\{>([\\w./-]+)}");
  private static final Pattern VAR = Pattern.compile("\\{\\{(\\w+)}}");

  private final Map<String, String> cache = new HashMap<>();

  @Override
  public String render(String templatePath, Map<String, String> vars) {
    String raw = resolveIncludes(templatePath, 0);
    Matcher m = VAR.matcher(raw);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      String key = m.group(1);
      String value = vars.get(key);
      if (value == null) {
        throw new IllegalArgumentException("模板 " + templatePath + " 缺变量: " + key);
      }
      m.appendReplacement(out, Matcher.quoteReplacement(value));
    }
    m.appendTail(out);
    // 严格替换已保证模板内声明的占位符全部展开；渲染结果中的 {{…}} 属不可信内容本身，不误报
    return out.toString();
  }

  @Override
  public String versionOf(String templatePath) {
    String raw = resolveIncludes(templatePath, 0);
    String name = templatePath.substring(templatePath.lastIndexOf('/') + 1);
    if (name.endsWith(".md")) {
      name = name.substring(0, name.length() - ".md".length());
    }
    return name + "@" + shortSha(raw);
  }

  private String resolveIncludes(String path, int depth) {
    if (depth > 4) {
      throw new IllegalStateException("模板包含深度超限: " + path);
    }
    String raw = load(path);
    Matcher m = INCLUDE.matcher(raw);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      String included = resolveIncludes(m.group(1), depth + 1);
      m.appendReplacement(out, Matcher.quoteReplacement(included));
    }
    m.appendTail(out);
    return out.toString();
  }

  private String load(String path) {
    return cache.computeIfAbsent(
        path,
        p -> {
          try (InputStream in =
              Thread.currentThread().getContextClassLoader().getResourceAsStream(p)) {
            if (in == null) {
              throw new IllegalArgumentException("模板资源不存在: " + p);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
          } catch (IOException e) {
            throw new IllegalStateException("模板资源读取失败: " + p, e);
          }
        });
  }

  private static String shortSha(String content) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < 4; i++) {
        sb.append(String.format("%02x", hash[i]));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
