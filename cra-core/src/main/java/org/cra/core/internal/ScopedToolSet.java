package org.cra.core.internal;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.cra.common.git.FileContent;

/**
 * 受限只读工具集（T2.1，03§2.3）：read_file(scoped) 与 grep_pattern(scoped) 的执行端。 作用域 = 编排器预取并裁剪到 brief
 * 白名单的文件集——路径不在集合内即 越界；无网络、无写盘、无 shell（隔离边界实现层硬编码，不靠 prompt，04§5 反模式 8）。
 */
final class ScopedToolSet {

  /** 单次工具输出字符上限（防单次工具结果撑爆下一轮输入预算，04§1 预算裁决）。 */
  static final int MAX_OUTPUT_CHARS = 12_000;

  /** read_file 单次返回行数上限。 */
  static final int MAX_READ_LINES = 400;

  /** grep_pattern 单次返回匹配行数上限。 */
  static final int MAX_GREP_MATCHES = 50;

  private final Map<String, FileContent> scopedFiles;

  /**
   * @param scopedFiles 白名单作用域内的文件全文（编排器预取；二进制文件 text 为 null）
   */
  ScopedToolSet(Map<String, FileContent> scopedFiles) {
    // TreeMap 保证 grep 遍历路径序稳定（可重放，03§2.1）
    this.scopedFiles = new TreeMap<>(scopedFiles == null ? Map.of() : scopedFiles);
  }

  /**
   * 白名单作用域内的路径集合。
   *
   * @return 路径集合
   */
  java.util.Set<String> scopedPaths() {
    return scopedFiles.keySet();
  }

  /** 工具执行结果：{@code allowed=false} 表示白名单/作用域违规（调用方须终止该代理）。 */
  record ToolResult(boolean allowed, String output) {}

  /**
   * 执行一次白名单内工具。
   *
   * @param tool 工具名（read_file | grep_pattern）
   * @param args 工具参数 JSON
   * @return 结果；allowed=false 为隔离违规，allowed=true 且 output 为参数错误提示时供模型自行修正
   */
  ToolResult execute(String tool, JsonNode args) {
    return switch (tool == null ? "" : tool) {
      case "read_file" -> readFile(args);
      case "grep_pattern" -> grepPattern(args);
      default -> new ToolResult(false, "未声明的工具: " + tool);
    };
  }

  private ToolResult readFile(JsonNode args) {
    JsonNode pathNode = args.get("path");
    if (pathNode == null || pathNode.asText().isBlank()) {
      return new ToolResult(true, "工具参数非法：read_file 缺 path。请修正参数，或直接输出最终结果 JSON。");
    }
    String path = pathNode.asText();
    FileContent file = scopedFiles.get(path);
    if (file == null) {
      return new ToolResult(false, "路径越界（不在本代理白名单作用域内）: " + path);
    }
    if (file.binary() || file.text() == null) {
      return new ToolResult(true, "文件不可读（二进制或内容不可得）: " + path);
    }
    List<String> lines = List.of(file.text().split("\n", -1));
    int start = clampLine(args, "start_line", 1);
    int end = Math.min(clampLine(args, "end_line", lines.size()), lines.size());
    if (start > end) {
      return new ToolResult(true, "行区间非法（start > end）: " + start + "-" + end);
    }
    int cappedEnd = Math.min(end, start + MAX_READ_LINES - 1);
    StringBuilder out = new StringBuilder();
    for (int i = start; i <= cappedEnd; i++) {
      out.append("L").append(i).append(": ").append(lines.get(i - 1)).append("\n");
    }
    if (cappedEnd < end) {
      out.append("（已截断：单次最多返回 ").append(MAX_READ_LINES).append(" 行，可继续请求后续区间）\n");
    }
    return new ToolResult(true, truncate(path + "（L" + start + "-L" + cappedEnd + "）\n" + out));
  }

  private ToolResult grepPattern(JsonNode args) {
    JsonNode patternNode = args.get("pattern");
    if (patternNode == null || patternNode.asText().isBlank()) {
      return new ToolResult(true, "工具参数非法：grep_pattern 缺 pattern。请修正参数，或直接输出最终结果 JSON。");
    }
    Pattern pattern;
    try {
      pattern = Pattern.compile(patternNode.asText());
    } catch (PatternSyntaxException e) {
      return new ToolResult(true, "正则非法: " + e.getMessage());
    }
    List<String> matches = new ArrayList<>();
    boolean truncated = false;
    for (Map.Entry<String, FileContent> entry : scopedFiles.entrySet()) {
      FileContent file = entry.getValue();
      if (file.binary() || file.text() == null) {
        continue;
      }
      String[] lines = file.text().split("\n", -1);
      for (int i = 0; i < lines.length; i++) {
        if (pattern.matcher(lines[i]).find()) {
          if (matches.size() >= MAX_GREP_MATCHES) {
            truncated = true;
            break;
          }
          matches.add(entry.getKey() + ":L" + (i + 1) + ": " + lines[i].strip());
        }
      }
      if (truncated) {
        break;
      }
    }
    if (matches.isEmpty()) {
      return new ToolResult(true, "无匹配（作用域内 " + scopedFiles.size() + " 个文件）: " + pattern.pattern());
    }
    String note = truncated ? "\n（已截断：单次最多返回 " + MAX_GREP_MATCHES + " 行匹配）" : "";
    return new ToolResult(true, truncate(String.join("\n", matches) + note));
  }

  private static int clampLine(JsonNode args, String field, int defaultValue) {
    JsonNode node = args.get(field);
    if (node == null || !node.canConvertToInt() || node.asInt() < 1) {
      return defaultValue;
    }
    return node.asInt();
  }

  private static String truncate(String output) {
    if (output.length() <= MAX_OUTPUT_CHARS) {
      return output;
    }
    return output.substring(0, MAX_OUTPUT_CHARS) + "\n（工具结果超出 " + MAX_OUTPUT_CHARS + " 字符上限，已截断）";
  }
}
