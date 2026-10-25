package org.cra.eval.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.cra.common.exception.CraConfigException;
import org.cra.common.exception.CraException;
import org.cra.common.exception.CraIntegrationException;
import org.cra.common.git.CommentLedger;
import org.cra.common.git.CommentSource;
import org.cra.common.git.LedgerComment;
import org.cra.common.git.LedgerFindingMarker;
import org.cra.common.git.RepoRef;

/**
 * badcase finding 定位（T3.5）：先查账本（已发布的 finding 有完整正文），再回退 run briefs （被 REFUTED/截断的候选不在账本里，但验证 brief
 * 保留了 `### id=…` 段）。
 *
 * <p>两处都定位不到时显式失败——不凭空生成 expected（badcase 的价值在"真实发生过的 finding"）。
 */
final class FindingLocator {

  private static final Pattern BRIEF_HEADER =
      Pattern.compile("(?m)^### id=(\\S+) category=(\\S+) severity=(\\S+)\\s*$");
  private static final Pattern BRIEF_FILE = Pattern.compile("文件全文(?:不可得)?（([^，）]+)(?:，head 侧)?）");
  private static final Pattern EVIDENCE_LINE = Pattern.compile("L(\\d+)(?:[-–]\\d+)?");
  private static final Pattern PATH_LINE = Pattern.compile("([\\w./-]+\\.[A-Za-z0-9]+):(\\d+)");
  private static final Pattern BODY_TITLE =
      Pattern.compile(
          "(?m)^\\*\\*(?:\\[UNVERIFIED\\])?\\[([A-Z]+)\\] (.+?)\\*\\* — `([^`]+):(\\d+)`$");

  private FindingLocator() {}

  /** 定位结果。 */
  record Located(
      String findingId,
      String source,
      String file,
      int line,
      String category,
      String severity,
      String title,
      String description,
      String failureMode,
      String evidence,
      String suggestion) {

    Located {
      source = source == null ? "" : source;
      file = file == null ? "" : file;
      category = category == null || category.isBlank() ? "bug" : category;
      severity = severity == null ? "" : severity;
      title = title == null ? "" : title;
      description = description == null ? "" : description;
      failureMode = failureMode == null ? "" : failureMode;
      evidence = evidence == null ? "" : evidence;
      suggestion = suggestion == null ? "" : suggestion;
    }
  }

  /**
   * 定位 finding。
   *
   * @param runDir run 目录（含 briefs/）
   * @param run run trace
   * @param findingId 目标 finding
   * @param comments 账本端口（可空：离线/无 token）
   * @param repo 仓库（可空）
   * @param warnings 回退/失败原因收集
   * @return 定位结果
   */
  static Located locate(
      Path runDir,
      TraceIngest.RunTrace run,
      String findingId,
      CommentSource comments,
      RepoRef repo,
      List<String> warnings) {
    if (comments != null && repo != null && run.pr() > 0) {
      try {
        Located fromLedger = fromLedger(comments, repo, run.pr(), findingId);
        if (fromLedger != null) {
          return fromLedger;
        }
      } catch (CraException e) {
        warnings.add("账本定位失败，回退 trace brief：" + e.getMessage());
      }
    }
    Located fromBrief = fromBriefs(runDir, findingId);
    if (fromBrief != null) {
      return fromBrief;
    }
    throw new CraConfigException(
        "run "
            + run.runId()
            + " 中未定位到 finding_id="
            + findingId
            + "（查过账本与 "
            + runDir.resolve("briefs")
            + "/*.md）——请确认 id 来自该 run 的 trace/报告");
  }

  // ---------- 账本 ----------

  private static Located fromLedger(
      CommentSource comments, RepoRef repo, int prNumber, String findingId) {
    CommentLedger ledger = comments.fetchBotComments(repo, prNumber);
    LedgerFindingMarker marker =
        ledger.findingMarkers().stream()
            .filter(m -> findingId.equals(m.findingId()))
            .findFirst()
            .orElse(null);
    if (marker == null) {
      return null;
    }
    LedgerComment comment =
        ledger.comments().stream()
            .filter(c -> c.id() == marker.commentId())
            .findFirst()
            .orElse(null);
    if (comment == null) {
      return null;
    }
    String body = unescape(comment.body());
    Matcher title = BODY_TITLE.matcher(body);
    boolean titleFound = title.find();
    String severity = "";
    String titleText = "";
    String file = marker.file();
    int line = marker.line();
    if (titleFound) {
      severity = title.group(1).toLowerCase(java.util.Locale.ROOT);
      titleText = title.group(2).strip();
      if (!title.group(3).isBlank()) {
        file = title.group(3);
      }
      line = Integer.parseInt(title.group(4));
    }
    String afterTitle = titleFound ? body.substring(title.end()) : body;
    String failure = between(afterTitle, "**失败模式**：", "**证据**：");
    String description = between(afterTitle, "", "**失败模式**：").strip();
    String evidence = fenced(afterTitle, "**证据**：");
    String suggestion = fenced(afterTitle, "**建议**：");
    return new Located(
        findingId,
        "ledger",
        file,
        line,
        marker.category(),
        severity,
        titleText,
        description,
        failure,
        evidence,
        suggestion);
  }

  private static String between(String text, String from, String to) {
    int start = from.isEmpty() ? 0 : text.indexOf(from);
    if (start < 0) {
      return "";
    }
    start += from.length();
    int end = text.indexOf(to, start);
    if (end < 0) {
      end = text.length();
    }
    return text.substring(start, end).strip();
  }

  private static String fenced(String text, String label) {
    int start = text.indexOf(label);
    if (start < 0) {
      return "";
    }
    int fence = text.indexOf("```", start);
    if (fence < 0) {
      return "";
    }
    int contentStart = text.indexOf('\n', fence);
    if (contentStart < 0) {
      return "";
    }
    int end = text.indexOf("\n```", contentStart);
    if (end < 0) {
      end = text.length();
    }
    return text.substring(contentStart + 1, end).strip();
  }

  private static String unescape(String body) {
    return body.replace("&lt;!--", "<!--").replace("&gt;", ">");
  }

  // ---------- briefs ----------

  private static Located fromBriefs(Path runDir, String findingId) {
    Path briefs = runDir.resolve("briefs");
    if (!Files.isDirectory(briefs)) {
      return null;
    }
    List<Path> files;
    try (Stream<Path> walk = Files.list(briefs)) {
      files = walk.filter(p -> p.toString().endsWith(".md")).sorted().toList();
    } catch (IOException e) {
      throw new CraIntegrationException("briefs 目录读取失败: " + briefs, e);
    }
    for (Path file : files) {
      String text;
      try {
        text = Files.readString(file, StandardCharsets.UTF_8);
      } catch (IOException e) {
        throw new CraIntegrationException("brief 读取失败: " + file, e);
      }
      Located located = parseBrief(text, findingId);
      if (located != null) {
        return located;
      }
    }
    return null;
  }

  private static Located parseBrief(String text, String findingId) {
    Matcher header = BRIEF_HEADER.matcher(text);
    int sectionStart = -1;
    String category = "";
    String severity = "";
    while (header.find()) {
      if (header.group(1).equals(findingId)) {
        sectionStart = header.end();
        category = header.group(2);
        severity = header.group(3);
        break;
      }
    }
    if (sectionStart < 0) {
      return null;
    }
    Matcher next = BRIEF_HEADER.matcher(text);
    int sectionEnd = text.length();
    while (next.find()) {
      if (next.start() > sectionStart) {
        sectionEnd = next.start();
        break;
      }
    }
    String section = text.substring(sectionStart, sectionEnd);
    int nextHeading = section.indexOf("\n## ");
    if (nextHeading >= 0) {
      section = section.substring(0, nextHeading);
    }
    Map<String, String> fields = parseFields(section);
    String evidence = fields.getOrDefault("证据", "");
    Matcher fileMatcher = BRIEF_FILE.matcher(text);
    String file = fileMatcher.find() ? fileMatcher.group(1).strip() : "";
    int line = 0;
    Matcher evLine = EVIDENCE_LINE.matcher(evidence);
    if (evLine.find()) {
      line = Integer.parseInt(evLine.group(1));
    }
    Matcher pathLine = PATH_LINE.matcher(evidence);
    if (line <= 0 && pathLine.find()) {
      line = Integer.parseInt(pathLine.group(2));
      if (file.isBlank()) {
        file = pathLine.group(1);
      }
    }
    return new Located(
        findingId,
        "brief",
        file,
        line,
        category,
        severity,
        fields.getOrDefault("标题", ""),
        "",
        fields.getOrDefault("失败模式", ""),
        evidence,
        "");
  }

  private static Map<String, String> parseFields(String section) {
    Map<String, String> fields = new LinkedHashMap<>();
    String current = null;
    StringBuilder value = new StringBuilder();
    for (String line : section.split("\n", -1)) {
      String key = null;
      String rest = null;
      for (String label : List.of("标题", "失败模式", "证据")) {
        if (line.startsWith(label + "：")) {
          key = label;
          rest = line.substring(label.length() + 1);
          break;
        }
      }
      if (key != null) {
        if (current != null) {
          fields.put(current, value.toString().strip());
        }
        current = key;
        value = new StringBuilder(rest);
      } else if (current != null) {
        value.append('\n').append(line);
      }
    }
    if (current != null) {
      fields.put(current, value.toString().strip());
    }
    return fields;
  }

  /**
   * 从定位结果生成 expected 断言（badcase 草稿；人工确认后生效）。
   *
   * @param located 定位结果
   * @param note 备注
   * @return expected 条目
   */
  static org.cra.eval.api.ExpectedFinding toExpected(Located located, String note) {
    List<String> keywords =
        AnnotateService.keywords(
            List.of(
                located.title(), located.failureMode(), located.description(), located.evidence()));
    String effectiveNote = keywords.isEmpty() ? note + " ⚠ 关键词为空：annotate confirm 前必须补齐" : note;
    return new org.cra.eval.api.ExpectedFinding(
        located.file(), located.line(), located.category(), keywords, effectiveNote);
  }
}
