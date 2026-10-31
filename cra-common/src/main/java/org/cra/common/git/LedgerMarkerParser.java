package org.cra.common.git;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 账本标记解析器（05§1.3 五规则，finding 级与 run 级同套规则；08§5 场景 3 的承重类）：产出 {@link LedgerFindingMarker}/ {@link
 * LedgerRunMarker} 的解析侧同包类型。
 *
 * <ul>
 *   <li>规则 1 仅认末位：只解析 body 末尾、且位于代码围栏之外的 cra: 标记；正文中间/围栏内的 同类标记一律忽略并计 LEDGER_SUSPECT；
 *   <li>规则 2 严格格式：v= 必须为已知版本、字段集合与顺序合法、哈希为规整十六进制、status 仅 DONE/DEGRADED；任一不满足 → 忽略 +
 *       LEDGER_SUSPECT；
 *   <li>规则 4b 一致性：标记 file 与评论正文 file:line 不一致 → 忽略并告警。
 * </ul>
 *
 * <p>发布侧双向防护（规则 3，正文 {@code <!--} 转义）由发布器负责；兜底分层（规则 5）由 消费方依 {@link
 * CommentLedger#hasAnyValidMarker()} 判定。实现侧不抛异常， 一切非法输入归约为"无标记 + 可疑计数"。
 *
 * <p>单一事实源（ADR-0016）：格式被 05§1.3 冻结的前提下，GitHub/GitLab 两个适配器曾各持逐字节相同的复制件， 2026-10-30 单日两轮双写修复
 * （围栏计数、CRLF 归一）证明双写漂移风险真实，故解析器下沉 common； 08§7 的 100% 分支覆盖门禁随迁至 cra-common（类级白名单不变）。
 */
public final class LedgerMarkerParser {

  /** 已知标记版本（05§1.3；升级须同步发布器与解析器）。 */
  public static final String MARKER_VERSION = "2.1";

  private static final Pattern MARKER_START = Pattern.compile("<!--\\s*cra:");
  private static final Set<String> CATEGORIES =
      Set.of("bug", "security", "test", "convention", "performance", "context");
  private static final List<String> FINDING_FIELDS =
      List.of("v", "fp", "file", "cat", "id", "run", "commit", "line");
  private static final List<String> RUN_FIELDS =
      List.of("v", "run", "commit", "status", "findings", "suppressed");

  /**
   * 解析结果：有效标记 + 可疑原因列表（LEDGER_SUSPECT 逐条入 trace）。
   *
   * @param marker 末位且合法的标记；非法/无标记为空
   * @param suspects 可疑原因列表（与 marker 可同时非空：末位合法但正文中间另有伪造标记）
   */
  public record ParseOutcome(Optional<MarkerParseResult> marker, List<SuspectReason> suspects) {

    public ParseOutcome {
      suspects = List.copyOf(suspects);
    }
  }

  /** 解析出的有效标记。 */
  public sealed interface MarkerParseResult {
    /**
     * finding 级标记字段。
     *
     * @param fingerprint 指纹 v2（规整十六进制）
     * @param file 文件路径（须与评论正文一致，规则 4b）
     * @param line 锚行号（正整数）
     * @param category 类别小写（05§1.1 枚举）
     * @param findingId finding 标识（{@code f_} 前缀）
     * @param runId run 标识（{@code run_} 前缀）
     * @param commitSha 发布时的 head sha（7–40 位十六进制）
     */
    record FindingMark(
        String fingerprint,
        String file,
        int line,
        String category,
        String findingId,
        String runId,
        String commitSha)
        implements MarkerParseResult {}

    /**
     * run 完成级标记字段。
     *
     * @param runId run 标识（{@code run_} 前缀）
     * @param commitSha 发布时的 head sha（7–40 位十六进制）
     * @param status 完成状态（仅 DONE/DEGRADED）
     * @param findings 本 run 发布的 finding 数
     * @param suppressed 压制数
     */
    record RunMark(String runId, String commitSha, String status, int findings, int suppressed)
        implements MarkerParseResult {}
  }

  /** 可疑原因（05§1.3 规则 1/2/4b 的细分，供 trace 与告警归因）。 */
  public enum SuspectReason {
    /** 标记出现在正文中间（不在 body 末尾） */
    MID_BODY,
    /** 标记位于代码围栏之内 */
    INSIDE_FENCE,
    /** 字段缺 '=' 或为空 */
    MALFORMED_FIELD,
    /** 类型既非 finding 也非 run */
    UNKNOWN_TYPE,
    /** v 不是已知版本 */
    BAD_VERSION,
    /** 字段集合或顺序不合法 */
    BAD_FIELD_ORDER,
    /** 字段值非法（哈希/类别/前缀/整数/状态） */
    BAD_VALUE,
    /** 标记 file 与正文 file:line 不一致 */
    FILE_MISMATCH
  }

  /**
   * 解析一条评论 body。
   *
   * @param body 评论原文
   * @return 有效标记（末位且合法）+ 可疑计数明细
   */
  public ParseOutcome parse(String body) {
    List<SuspectReason> suspects = new ArrayList<>();
    if (body == null || body.isBlank()) {
      return new ParseOutcome(Optional.empty(), suspects);
    }
    List<Integer> starts = new ArrayList<>();
    Matcher m = MARKER_START.matcher(body);
    while (m.find()) {
      starts.add(m.start());
    }
    if (starts.isEmpty()) {
      return new ParseOutcome(Optional.empty(), suspects);
    }
    String trimmed = body.stripTrailing();
    int endIndex = endMarkerIndex(trimmed, starts);
    // 末位之外的 cra: 标记全部计可疑（规则 1：正文中间一律忽略）
    for (int start : starts) {
      if (start != endIndex) {
        suspects.add(SuspectReason.MID_BODY);
      }
    }
    if (endIndex < 0) {
      return new ParseOutcome(Optional.empty(), suspects);
    }
    String tail = trimmed.substring(endIndex);
    if (fenceCount(trimmed.substring(0, endIndex)) % 2 == 1) {
      suspects.add(SuspectReason.INSIDE_FENCE);
      return new ParseOutcome(Optional.empty(), suspects);
    }
    // endIndex 处已命中 "<!--\s*cra:" 且 trimmed 以 "-->" 结尾，标记体可确定性截取
    String inner = tail.substring("<!--".length(), tail.length() - "-->".length());
    String afterCra = inner.substring(inner.indexOf("cra:") + "cra:".length());
    // 规则 4b 的"正文"= 标记之前的 body（标记自身恒含 file 值，不能参与一致性校验）
    return parseFields(afterCra, trimmed.substring(0, endIndex), suspects);
  }

  private int endMarkerIndex(String trimmed, List<Integer> starts) {
    if (!trimmed.endsWith("-->")) {
      return -1;
    }
    // 末位标记 = 最后一个 "<!-- cra:" 出现处
    return starts.get(starts.size() - 1);
  }

  /**
   * 统计围栏定界符：一串 ≥3 的连续反引号计为一个（CommonMark），且仅当该行以 ≤3 空格缩进开头时才构成围栏—— 行中/行内代码出现的 ``` 不切换奇偶，连续反引号串也不按 3
   * 个一组重复计数。
   */
  private static int fenceCount(String text) {
    int count = 0;
    // CRLF/CR 统一归一为 LF（GitHub/GitLab 评论可能含 \r\n；CR-only 属历史格式）
    String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
    for (String line : normalized.split("\n", -1)) {
      int lead = 0;
      while (lead < 3 && lead < line.length() && line.charAt(lead) == ' ') {
        lead++;
      }
      int ticks = 0;
      while (lead + ticks < line.length() && line.charAt(lead + ticks) == '`') {
        ticks++;
      }
      if (ticks >= 3) {
        count++;
      }
    }
    return count;
  }

  private ParseOutcome parseFields(String inner, String fullBody, List<SuspectReason> suspects) {
    List<String[]> fields = new ArrayList<>();
    for (String segment : inner.replace("\r", " ").replace("\n", " ").split("\\|")) {
      String trimmedSegment = segment.trim();
      int eq = trimmedSegment.indexOf('=');
      if (eq <= 0) {
        suspects.add(SuspectReason.MALFORMED_FIELD);
        return new ParseOutcome(Optional.empty(), suspects);
      }
      fields.add(
          new String[] {
            trimmedSegment.substring(0, eq).trim(), trimmedSegment.substring(eq + 1).trim()
          });
    }
    // split 恒产出 ≥1 段；首键必为 v（标记格式约定），不设防御分支（100% 分支门禁，08§7）
    String firstKey = fields.get(0)[0];
    if (!"v".equals(firstKey)) {
      suspects.add(SuspectReason.BAD_FIELD_ORDER);
      return new ParseOutcome(Optional.empty(), suspects);
    }
    Map<String, String> byKey = new LinkedHashMap<>();
    List<String> keys = fields.stream().map(f -> f[0]).toList();
    for (String[] f : fields) {
      byKey.put(f[0], f[1]);
    }
    if (!MARKER_VERSION.equals(byKey.get("v"))) {
      suspects.add(SuspectReason.BAD_VERSION);
      return new ParseOutcome(Optional.empty(), suspects);
    }
    List<String> expected;
    if (keys.size() > 1 && "fp".equals(keys.get(1))) {
      expected = FINDING_FIELDS;
    } else if (keys.size() > 1 && "run".equals(keys.get(1))) {
      expected = RUN_FIELDS;
    } else {
      suspects.add(SuspectReason.UNKNOWN_TYPE);
      return new ParseOutcome(Optional.empty(), suspects);
    }
    if (!keys.equals(expected)) {
      suspects.add(SuspectReason.BAD_FIELD_ORDER);
      return new ParseOutcome(Optional.empty(), suspects);
    }
    if (expected == FINDING_FIELDS) {
      return parseFinding(byKey, fullBody, suspects);
    }
    return parseRun(byKey, suspects);
  }

  private ParseOutcome parseFinding(
      Map<String, String> byKey, String fullBody, List<SuspectReason> suspects) {
    // 字段顺序校验已保证全部键存在，值只会为空串不会为 null（可达性不变量）
    String fp = byKey.get("fp");
    String file = byKey.get("file");
    String cat = byKey.get("cat");
    String id = byKey.get("id");
    String run = byKey.get("run");
    String commit = byKey.get("commit");
    Integer line = parseIntOrNull(byKey.get("line"));
    if (!isHex(fp, 1, 64)
        || file.isBlank()
        || !CATEGORIES.contains(cat)
        || !id.startsWith("f_")
        || !run.startsWith("run_")
        || !isHex(commit, 7, 40)
        || line == null
        || line <= 0) {
      suspects.add(SuspectReason.BAD_VALUE);
      return new ParseOutcome(Optional.empty(), suspects);
    }
    // 规则 4b：标记 file 须与正文一致（正文含 file:line 形态或裸路径）
    if (!fullBody.contains(file)) {
      suspects.add(SuspectReason.FILE_MISMATCH);
      return new ParseOutcome(Optional.empty(), suspects);
    }
    return new ParseOutcome(
        Optional.of(new MarkerParseResult.FindingMark(fp, file, line, cat, id, run, commit)),
        suspects);
  }

  private ParseOutcome parseRun(Map<String, String> byKey, List<SuspectReason> suspects) {
    String run = byKey.get("run");
    String commit = byKey.get("commit");
    String status = byKey.get("status");
    Integer findings = parseIntOrNull(byKey.get("findings"));
    Integer suppressed = parseIntOrNull(byKey.get("suppressed"));
    if (run.isBlank()
        || !isHex(commit, 7, 40)
        || (!"DONE".equals(status) && !"DEGRADED".equals(status))
        || findings == null
        || suppressed == null) {
      // parseIntOrNull 只接受 \d+，不会产出负数，无需 <0 分支（100% 分支门禁，08§7）
      suspects.add(SuspectReason.BAD_VALUE);
      return new ParseOutcome(Optional.empty(), suspects);
    }
    return new ParseOutcome(
        Optional.of(new MarkerParseResult.RunMark(run, commit, status, findings, suppressed)),
        suspects);
  }

  private static boolean isHex(String value, int minLength, int maxLength) {
    return value.length() >= minLength && value.length() <= maxLength && value.matches("[0-9a-f]+");
  }

  private static Integer parseIntOrNull(String value) {
    if (!value.matches("\\d+")) {
      return null;
    }
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      // 超出 int 范围的纯数字串（如 13 位以上）
      return null;
    }
  }
}
