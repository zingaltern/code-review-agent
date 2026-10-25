package org.cra.core.internal.pipeline;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 反锚定引用新颖性校验器（07 T2.4；03§4.2 v1.2 重定义）：机器侧 <b>质控信号</b>，输出 PASS/FAIL 入 finding 与 trace，<b>FAIL
 * 不影响晋升</b>（CONFIRMED 照常发布）——单行证据类真缺陷的正确复述必然与 finding 原文高度重叠，硬校验会系统性拒真；FAIL
 * 率按验证者/模板/模型维度聚合进周报（06§1.3）， 骤升 = 锚定回退预警，走 badcase 复盘而非自动拒真。
 *
 * <p>归一化（03§4.2 供统计口径）：行号引用抽取 {@code \bL?(\d{1,4})\b} 与 {@code :(\d{1,4})} 归一为数字 集合；代码片段按去空白行比较；重叠率
 * = 交集引用数 / rationale 引用总数。 <b>FAIL 判据</b>：rationale 存在引用且重叠率 = 1.0——即 CONFIRMED rationale
 * 未包含结论原文中未出现的 任何引用（04§4.2 输出约束的机器可判定形式）。rationale 无引用 → PASS（无法建立锚定证据）。
 */
public final class AntiAnchorValidator {

  private static final Pattern LINE_REF = Pattern.compile("\\bL?(\\d{1,4})\\b|:(\\d{1,4})");
  private static final Pattern FENCE = Pattern.compile("```+");

  /** 校验结果（anti_anchor_check 字段与 trace 事件载荷）。 */
  public record Result(Status status, double overlapRatio, int refsTotal) {}

  /** 质控结论（03§4.2：PASS | FAIL）。 */
  public enum Status {
    PASS,
    FAIL
  }

  private AntiAnchorValidator() {}

  /**
   * 校验一条 CONFIRMED 判定的引用新颖性。
   *
   * @param findingOriginalText finding 原文（evidence + description + failureMode 拼接）
   * @param rationale 验证者判定理由
   * @return PASS/FAIL + 重叠率与引用总数（trace 统计用）
   */
  public static Result check(String findingOriginalText, String rationale) {
    Set<String> originalRefs = references(findingOriginalText);
    Set<String> rationaleRefs = references(rationale);
    if (rationaleRefs.isEmpty()) {
      return new Result(Status.PASS, 0.0, 0);
    }
    long shared = rationaleRefs.stream().filter(originalRefs::contains).count();
    double ratio = (double) shared / rationaleRefs.size();
    Status status = ratio >= 1.0 ? Status.FAIL : Status.PASS;
    return new Result(status, ratio, rationaleRefs.size());
  }

  /** 归一化引用集合：行号数字 + 代码围栏内去空白行（03§4.2 归一化定义）。 */
  private static Set<String> references(String text) {
    if (text == null || text.isBlank()) {
      return Set.of();
    }
    Set<String> refs = new HashSet<>();
    Matcher m = LINE_REF.matcher(text);
    while (m.find()) {
      String digits = m.group(1) != null ? m.group(1) : m.group(2);
      if (digits != null) {
        refs.add("#" + Integer.parseInt(digits));
      }
    }
    refs.addAll(fencedLines(text));
    return refs;
  }

  /** 代码围栏内容行（去首尾空白；围栏语言标注行不算内容）。 */
  private static List<String> fencedLines(String text) {
    List<String> lines = text.lines().collect(Collectors.toList());
    List<String> content = new java.util.ArrayList<>();
    boolean inside = false;
    for (String line : lines) {
      if (FENCE.matcher(line.trim()).matches()) {
        inside = !inside;
        continue;
      }
      if (inside) {
        String stripped = line.strip();
        if (!stripped.isEmpty()) {
          content.add(stripped.toLowerCase(Locale.ROOT));
        }
      }
    }
    return content;
  }
}
