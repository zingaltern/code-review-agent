package org.cra.core.internal.pipeline;

import java.util.Locale;
import org.cra.common.finding.FindingCategory;
import org.cra.core.internal.VerdictJsonCodec;
import org.cra.core.internal.VerdictJsonCodec.Refutation;
import org.cra.core.internal.VerdictJsonCodec.VerifierVerdict;

/**
 * REFUTED 反证机械校验（ADR-0014 §2.1）：bug 类候选的 REFUTED 必须携带可核查反证， 否则降级为 INSUFFICIENT_EVIDENCE 交 Judge 仲裁。
 *
 * <p>校验只做"证据形态"判断（确定性、可重放），不做语义裁决：
 *
 * <ol>
 *   <li>缺 {@code refutation} 或字段不全 → MISSING；
 *   <li>{@code kind=CITATION_MISMATCH}（仅引述/锚点不符，不构成对失败模式的否定）→ CITATION_MISMATCH；
 *   <li>{@code quote} 归一化后不能在本次简报可见上下文（代码上下文 + 被引证据 + 补查材料）中逐字定位 →
 *       QUOTE_UNRESOLVED——"对简报外代码的推理"因此天然不满足可核查性；
 *   <li>{@code quote} 归一化后过短（<8 字符，如 "null"）→ QUOTE_TOO_SHORT（无鉴别力）。
 * </ol>
 *
 * <p>非 bug 类或非 REFUTED 判定不适用（NOT_APPLICABLE）；security 类 REFUTED 仍走既有抽查保险丝。
 */
public final class RefuteEvidenceGate {

  /** 归一化后 quote 的最小鉴别长度。 */
  static final int MIN_QUOTE_CHARS = 8;

  /** 校验状态。 */
  public enum Status {
    /** 不适用（非 bug 类或非 REFUTED） */
    NOT_APPLICABLE,
    /** 反证可核查，REFUTED 成立 */
    OK,
    /** 反证不可核查，判定降级为 INSUFFICIENT_EVIDENCE */
    DOWNGRADE
  }

  /** 降级原因（OK/NOT_APPLICABLE 时为 NONE）。 */
  public enum Reason {
    NONE,
    /** 缺 refutation 对象或字段不全（含 kind 非法） */
    MISSING,
    /** 仅引述/锚点不符——不是对失败模式的否定 */
    CITATION_MISMATCH,
    /** quote 不能在简报可见上下文中逐字定位 */
    QUOTE_UNRESOLVED,
    /** quote 过短，无鉴别力 */
    QUOTE_TOO_SHORT,
    /** anchor 缺失 */
    ANCHOR_MISSING
  }

  /** 校验结果。 */
  public record Result(Status status, Reason reason) {

    /** 不适用（非 bug 类或非 REFUTED）。 */
    public static Result notApplicable() {
      return new Result(Status.NOT_APPLICABLE, Reason.NONE);
    }

    public boolean downgraded() {
      return status == Status.DOWNGRADE;
    }
  }

  private RefuteEvidenceGate() {}

  /**
   * 校验一条判定。
   *
   * @param category finding 类别（仅 bug 类强制）
   * @param verdict 验证者判定
   * @param refutation 结构化反证（可空）
   * @param visibleContext 本次简报可见的不可信上下文（codeContext + citedEvidence + followup）
   * @return 校验结果
   */
  public static Result check(
      FindingCategory category,
      VerifierVerdict verdict,
      Refutation refutation,
      String visibleContext) {
    if (category != FindingCategory.BUG || verdict != VerifierVerdict.REFUTED) {
      return new Result(Status.NOT_APPLICABLE, Reason.NONE);
    }
    if (refutation == null || refutation.malformed() || refutation.kind() == null) {
      return new Result(Status.DOWNGRADE, Reason.MISSING);
    }
    if (refutation.anchor() == null || refutation.anchor().isBlank()) {
      return new Result(Status.DOWNGRADE, Reason.ANCHOR_MISSING);
    }
    if (refutation.kind() == VerdictJsonCodec.RefutationKind.CITATION_MISMATCH) {
      return new Result(Status.DOWNGRADE, Reason.CITATION_MISMATCH);
    }
    String quote = normalize(refutation.quote());
    if (quote.length() < MIN_QUOTE_CHARS) {
      return new Result(Status.DOWNGRADE, Reason.QUOTE_TOO_SHORT);
    }
    if (!normalize(visibleContext).contains(quote)) {
      return new Result(Status.DOWNGRADE, Reason.QUOTE_UNRESOLVED);
    }
    return new Result(Status.OK, Reason.NONE);
  }

  /**
   * 归一化：去除全部空白与反引号，大小写不敏感（模型引用代码时的换行/缩进/围栏差异不构成反证失效； 但改写/概括仍无法定位）。
   *
   * @param text 原文
   * @return 归一化文本（小写）
   */
  static String normalize(String text) {
    if (text == null) {
      return "";
    }
    StringBuilder sb = new StringBuilder(text.length());
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (Character.isWhitespace(c) || c == '`' || c == '\uFEFF') {
        continue;
      }
      sb.append(c);
    }
    return sb.toString().toLowerCase(Locale.ROOT);
  }
}
