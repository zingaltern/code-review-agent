package org.cra.core.internal.pipeline;

import java.util.ArrayList;
import java.util.List;
import org.cra.common.context.ReviewPolicy;
import org.cra.common.context.TokenMath;
import org.cra.common.git.CommitHistoryEntry;
import org.cra.common.git.DiffFile;

/**
 * CrossChecker brief 材料组装（03§3.1 模式 A/B；03§6 预算行 = ≤16k 输入：diff 精简视图 ~8k + 变更史摘要 ≤4k + 产品段 ≤2k）。
 *
 * <ul>
 *   <li><b>diff 精简视图</b>：仅 hunk 头 + 增删行（不注入上下文行）——用于推导隐含假设，不用于找错；
 *   <li><b>变更史摘要</b>：上下游路径集近 N 周 commit（sha / 时间 / message 首行），commit message 属不可信（04§3.3）， 由模板
 *       UNTRUSTED 定界；
 *   <li><b>产品段</b>：REVIEW.md Product context / Known incidents（模式 A）与 Sniff tests（模式 B），base 分支读取 +
 *       owner 审批信任模型（04§3.2），可信内容不定界。
 * </ul>
 *
 * <p>全部裁剪确定性（按 token 预算截尾 + 显式截断声明），同输入同输出可重放（04§2.1: 字符/3.5 估算）。
 */
public final class CrossCheckMaterials {

  /** diff 精简视图 token 上限（03§6：~8k）。 */
  public static final long DIFF_TOKEN_CAP = 8_000;

  /** 变更史摘要 token 上限（03§6：≤4k）。 */
  public static final long HISTORY_TOKEN_CAP = 4_000;

  /** 产品段 token 上限（03§6：≤2k）。 */
  public static final long CONTEXT_TOKEN_CAP = 2_000;

  /** 单条 commit message 展示上限（字符；超出截断，摘要不追求全文）。 */
  private static final int COMMIT_MESSAGE_MAX_CHARS = 240;

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
    List<String> notes = new ArrayList<>();
    String diff = capTokens(diffDigest(files), DIFF_TOKEN_CAP, "diff 精简视图", notes);
    String historyText = capTokens(historyText(history, weeks), HISTORY_TOKEN_CAP, "变更史摘要", notes);
    String sections =
        capTokens(
            trustedSections(policy, assumptionMode, sniffMode), CONTEXT_TOKEN_CAP, "产品段", notes);
    return new Materials(diff, historyText, sections, notes);
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

  /** 产品段（可信）：按模式过滤；全空返回显式占位（模板据此跳过相应模式）。 */
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
    return sha == null ? "" : sha.substring(0, Math.min(7, sha.length()));
  }
}
