package org.cra.eval.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 账本标记解析（eval 观测专用，05§1.3 格式）。与 cra-git-github 的账本解析器职责不同：eval 只做**观测**（读取已发布正文里的 finding/run
 * 标记用于断言），不做幂等/防污染判定，因此采取宽松解析并如实记录不可解析情况。
 */
public final class EvalMarkerParser {

  private static final Pattern MARKER =
      Pattern.compile("<!--\\s*cra:v=([0-9.]+)\\s*\\|([^>]*?)-->");

  private EvalMarkerParser() {}

  /** 解析出的 finding 标记字段（05§1.3 finding 级标记）。 */
  public record FindingMarker(
      String fingerprint,
      String file,
      String category,
      int line,
      String findingId,
      String runId,
      String commitSha) {}

  /** 解析出的 run 完成标记字段（05§1.3 run 级标记；status ∈ DONE|DEGRADED）。 */
  public record RunMarker(String runId, String commitSha, String status, int findings) {}

  /**
   * 提取正文中全部 finding 级标记（每条 inline 评论恰一个；宽松解析不校验哈希）。
   *
   * @param body 评论正文
   * @return 标记列表（可能为空）
   */
  public static List<FindingMarker> parseFindingMarkers(String body) {
    List<FindingMarker> out = new ArrayList<>();
    if (body == null) {
      return out;
    }
    for (Map<String, String> fields : allMarkerFields(body)) {
      String file = fields.get("file");
      String cat = fields.get("cat");
      String line = fields.get("line");
      if (file == null || cat == null || line == null || !line.matches("\\d{1,9}")) {
        continue;
      }
      out.add(
          new FindingMarker(
              fields.getOrDefault("fp", ""),
              file,
              cat,
              Integer.parseInt(line),
              fields.getOrDefault("id", ""),
              fields.getOrDefault("run", ""),
              fields.getOrDefault("commit", "")));
    }
    return out;
  }

  /**
   * 提取最后一个 run 完成标记（Summary 正文末尾，05§1.3 规则 1"仅认末位"的宽松对应）。
   *
   * @param body Summary 正文
   * @return 标记；无完成标记时为 null（= run 不完整或失败）
   */
  public static RunMarker parseRunMarker(String body) {
    if (body == null) {
      return null;
    }
    List<Map<String, String>> markers = allMarkerFields(body);
    for (int i = markers.size() - 1; i >= 0; i--) {
      Map<String, String> fields = markers.get(i);
      String status = fields.get("status");
      if (!"DONE".equals(status) && !"DEGRADED".equals(status)) {
        continue;
      }
      String runId = fields.get("run");
      if (runId == null) {
        continue;
      }
      String findings = fields.getOrDefault("findings", "-1");
      int findingsCount = findings.matches("\\d{1,9}") ? Integer.parseInt(findings) : -1;
      return new RunMarker(runId, fields.getOrDefault("commit", ""), status, findingsCount);
    }
    return null;
  }

  // ---------- 内部 ----------

  private static List<Map<String, String>> allMarkerFields(String body) {
    List<Map<String, String>> out = new ArrayList<>();
    Matcher m = MARKER.matcher(body);
    while (m.find()) {
      Map<String, String> fields = new HashMap<>();
      for (String part : m.group(2).split("\\|")) {
        int eq = part.indexOf('=');
        if (eq <= 0) {
          continue;
        }
        fields.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
      }
      out.add(fields);
    }
    return out;
  }
}
