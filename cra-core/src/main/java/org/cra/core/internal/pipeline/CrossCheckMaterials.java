package org.cra.core.internal.pipeline;

import java.util.ArrayList;
import java.util.List;
import org.cra.common.context.ReviewPolicy;
import org.cra.common.context.TokenMath;
import org.cra.common.event.ProductEvent;
import org.cra.common.git.CommitHistoryEntry;
import org.cra.common.git.DiffFile;
import org.cra.common.text.Texts;

/**
 * CrossChecker brief 材料组装（03§3.1 模式 A/B；03§6 预算行 = ≤32k 输入，材料分项不变：diff 精简视图 ~8k + 变更史/事件史 合计 ≤4k +
 * 产品段 ≤2k）。
 *
 * <ul>
 *   <li><b>diff 精简视图</b>：仅 hunk 头 + 增删行（不注入上下文行）——用于推导隐含假设，不用于找错；
 *   <li><b>变更史摘要</b>：上下游路径集近 N 周 commit（sha / 时间 / message 首行），commit message 属不可信（04§3.3）， 由模板
 *       UNTRUSTED 定界；
 *   <li><b>产品事件史</b>（M4f-full，ADR-0012）：与变更路径/commit/关键词相关的工单/事故复盘摘要，事件摘要属不可信， 与变更史共享 ≤4k 预算（事件优先占
 *       ≤2k，其余留给 commit）；
 *   <li><b>产品段</b>：REVIEW.md Product context / Known incidents（模式 A）与 Sniff tests（模式 B），base 分支读取 +
 *       owner 审批信任模型（04§3.2），可信内容不定界。
 * </ul>
 *
 * <p>全部裁剪确定性（按 token 预算截尾 + 显式截断声明），同输入同输出可重放（04§2.1: 字符/3.5 估算）。
 */
public final class CrossCheckMaterials {

  /** diff 精简视图 token 上限（03§6：~8k）。 */
  public static final long DIFF_TOKEN_CAP = 8_000;

  /** 变更史摘要 token 上限（03§6：≤4k；与事件史共享）。 */
  public static final long HISTORY_TOKEN_CAP = 4_000;

  /** 产品事件史 token 上限（03§6：变更史/事件史合计 ≤4k 内，事件优先占 ≤2k）。 */
  public static final long EVENT_TOKEN_CAP = 2_000;

  /** 产品段 token 上限（03§6：≤2k）。 */
  public static final long CONTEXT_TOKEN_CAP = 2_000;

  /** 单条 commit message 展示上限（字符；超出截断，摘要不追求全文）。 */
  private static final int COMMIT_MESSAGE_MAX_CHARS = 240;

  /** 单条事件摘要展示上限（字符；完整摘要留在 raw_json，不进 brief）。 */
  private static final int EVENT_SUMMARY_MAX_CHARS = 240;

  /** 单条事件 URL 展示上限（字符）。 */
  private static final int EVENT_URL_MAX_CHARS = 120;

  private CrossCheckMaterials() {}

  /**
   * 组装结果。
   *
   * @param diffDigest 精简 diff（hunk 头 + 增删行）
   * @param historyText 变更史摘要（不可信）
   * @param trustedSections Product context / Known incidents / Sniff tests（可信；按启用模式过滤）
   * @param notes 裁剪/降级声明（进 Summary 覆盖声明）
   */
  public record Materials(
      String diffDigest, String historyText, String trustedSections, List<String> notes) {

    public Materials {
      notes = List.copyOf(notes == null ? List.of() : notes);
    }
  }

  /**
   * 组装 CrossChecker 材料。
   *
   * @param files 变更文件（whole-PR 范围；skip/hard-cap 已在规划期裁剪）
   * @param history 上下游路径集近 N 周 commit（可为空）
   * @param weeks 回溯周数（摘要标题用）
   * @param policy base 分支 REVIEW.md 策略
   * @param assumptionMode 模式 A 是否启用
   * @param sniffMode 模式 B 是否启用
   * @return 材料
   */
  public static Materials build(
      List<DiffFile> files,
      List<CommitHistoryEntry> history,
      int weeks,
      ReviewPolicy policy,
      boolean assumptionMode,
      boolean sniffMode) {
    return build(files, history, weeks, policy, assumptionMode, sniffMode, List.of());
  }

  /**
   * 组装 CrossChecker 材料（M4f-full：追加产品事件史）。
   *
   * @param events 与变更路径/commit/关键词相关的产品事件（模式 A 未启用或未命中时为空）
   * @return 材料
   */
  public static Materials build(
      List<DiffFile> files,
      List<CommitHistoryEntry> history,
      int weeks,
      ReviewPolicy policy,
      boolean assumptionMode,
      boolean sniffMode,
      List<ProductEvent> events) {
    List<String> notes = new ArrayList<>();
    String diff = capTokens(diffDigest(files), DIFF_TOKEN_CAP, "diff 精简视图", notes);
    String eventsText =
        assumptionMode ? capTokens(eventsText(events, weeks), EVENT_TOKEN_CAP, "事件史摘要", notes) : "";
    long eventTokens = TokenMath.estimateTokens(eventsText);
    long historyBudget = Math.max(1_000, HISTORY_TOKEN_CAP - eventTokens);
    String historyText = capTokens(historyText(history, weeks), historyBudget, "变更史摘要", notes);
    String historyAndEvents = joinSections(historyText, eventsText);
    String sections =
        capTokens(
            trustedSections(policy, assumptionMode, sniffMode), CONTEXT_TOKEN_CAP, "产品段", notes);
    return new Materials(diff, historyAndEvents, sections, notes);
  }

  /** diff 精简视图：文件头 + hunk 头 + 增删行；上下文行不注入（推导假设不需要整段代码）。 */
  static String diffDigest(List<DiffFile> files) {
    StringBuilder out = new StringBuilder();
    for (DiffFile file : files) {
      if (file.patch() == null || file.patch().isBlank()) {
        continue;
      }
      out.append("--- a/").append(file.path()).append("\n+++ b/").append(file.path()).append('\n');
      for (String line : file.patch().split("\n", -1)) {
        if (line.startsWith("@@")
            || line.startsWith("+")
            || line.startsWith("-")
            || line.startsWith("\\ No newline")) {
          out.append(line).append('\n');
        }
      }
    }
    return out.toString().stripTrailing();
  }

  /** 变更史摘要：标题 + 每条 commit 的短 sha / 时间 / message 首行（时间倒序由 SPI 保证）。 */
  static String historyText(List<CommitHistoryEntry> history, int weeks) {
    if (history == null || history.isEmpty()) {
      return "";
    }
    StringBuilder out = new StringBuilder();
    out.append("### 近 ")
        .append(weeks)
        .append(" 周内上下游路径集的 commit（共 ")
        .append(history.size())
        .append(" 条，时间倒序）\n");
    for (CommitHistoryEntry entry : history) {
      String message = entry.message().strip();
      int newline = message.indexOf('\n');
      if (newline >= 0) {
        message = message.substring(0, newline);
      }
      if (message.length() > COMMIT_MESSAGE_MAX_CHARS) {
        message = message.substring(0, COMMIT_MESSAGE_MAX_CHARS) + "…";
      }
      out.append("- ")
          .append(entry.committedAt())
          .append(' ')
          .append(shortSha(entry.sha()))
          .append(' ')
          .append(message)
          .append('\n');
    }
    return out.toString().stripTrailing();
  }

  /**
   * 产品事件史摘要（ADR-0012）：标题 + 每条事件的 id / 时间 / 类型 / 服务 / 租户 / 路径 / 摘要 / commit 引用 / 来源链接。
   *
   * <p>事件摘要与 commit message 同属不可信输入（04§3.3），由模板 UNTRUSTED 定界；引用格式 {@code event <event_id>} 供验证层预取。
   *
   * @param events 事件（时间倒序由检索端口保证）
   * @param weeks 回溯周数（标题用）
   * @return 摘要文本（空列表 -> 空串）
   */
  static String eventsText(List<ProductEvent> events, int weeks) {
    if (events == null || events.isEmpty()) {
      return "";
    }
    StringBuilder out = new StringBuilder();
    out.append("### 近 ")
        .append(weeks)
        .append(" 周相关产品事件史（工单/事故复盘，共 ")
        .append(events.size())
        .append(" 条，时间倒序；事件摘要属不可信输入）\n");
    for (ProductEvent event : events) {
      out.append("- event ").append(event.eventId()).append(' ').append(event.occurredAt());
      if (event.type() != null) {
        out.append(" type=").append(event.type());
      }
      if (event.service() != null) {
        out.append(" service=").append(capText(event.service(), 60));
      }
      if (event.tenant() != null) {
        out.append(" tenant=").append(capText(event.tenant(), 60));
      }
      if (!event.paths().isEmpty()) {
        out.append(" paths=").append(capText(String.join(",", event.paths()), 160));
      }
      out.append(" — ").append(capText(oneLine(event.summary()), EVENT_SUMMARY_MAX_CHARS));
      if (!event.commitRefs().isEmpty()) {
        out.append("（commit_refs: ")
            .append(
                String.join(
                    ",", event.commitRefs().subList(0, Math.min(5, event.commitRefs().size()))))
            .append('）');
      }
      if (event.sourceUrl() != null) {
        out.append("（source: ").append(capText(event.sourceUrl(), EVENT_URL_MAX_CHARS)).append('）');
      }
      out.append('\n');
    }
    return out.toString().stripTrailing();
  }

  private static String joinSections(String first, String second) {
    if (first == null || first.isBlank()) {
      return second == null ? "" : second;
    }
    if (second == null || second.isBlank()) {
      return first;
    }
    return first + "\n\n" + second;
  }

  private static String oneLine(String value) {
    return value == null ? "" : value.replaceAll("\\s+", " ").strip();
  }

  private static String capText(String value, int maxChars) {
    if (value == null) {
      return "";
    }
    return value.length() <= maxChars ? value : value.substring(0, maxChars) + "…";
  }

  static String trustedSections(ReviewPolicy policy, boolean assumptionMode, boolean sniffMode) {
    StringBuilder out = new StringBuilder();
    if (assumptionMode) {
      appendSection(out, "Product context（产品背景与隐含假设）", policy.productContext());
      appendSection(out, "Known incidents（历史事故/复盘）", policy.knownIncidents());
    }
    if (sniffMode) {
      appendSection(out, "Sniff tests（经验性嗅探检查）", policy.sniffTests());
    }
    return out.toString().stripTrailing();
  }

  private static void appendSection(StringBuilder out, String title, List<String> items) {
    if (items == null || items.isEmpty()) {
      return;
    }
    out.append("### ").append(title).append('\n');
    for (String item : items) {
      out.append("- ").append(item).append('\n');
    }
    out.append('\n');
  }

  /**
   * 按 token 预算截断（保留头部行，确定性）；截断事实入 notes（覆盖声明可见）。
   *
   * @param text 原文
   * @param cap token 上限
   * @param label 段名（声明用）
   * @param notes 声明收集
   * @return 截断后文本
   */
  static String capTokens(String text, long cap, String label, List<String> notes) {
    if (text == null || text.isBlank()) {
      return "";
    }
    if (TokenMath.estimateTokens(text) <= cap) {
      return text;
    }
    StringBuilder out = new StringBuilder();
    long used = 0;
    for (String line : text.split("\n", -1)) {
      long cost = TokenMath.estimateTokens(line) + 1;
      if (used + cost > cap) {
        break;
      }
      out.append(line).append('\n');
      used += cost;
    }
    notes.add(label + "超预算截断（上限 " + cap + " token，03§6 CrossChecker 预算行）");
    return out.toString().stripTrailing();
  }

  private static String shortSha(String sha) {
    return Texts.shortSha(sha, 7, "");
  }
}
