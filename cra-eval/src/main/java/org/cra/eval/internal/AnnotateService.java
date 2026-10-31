package org.cra.eval.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.cra.common.exception.CraConfigException;
import org.cra.common.exception.CraIntegrationException;
import org.cra.eval.api.ExpectedFinding;
import org.cra.eval.api.ExpectedFindings;
import org.cra.eval.api.FixtureMeta;
import org.cra.eval.api.NegativeBait;

/**
 * 半自动标注（06§3 {@code cra-eval annotate}）：patch hunk 预填 expected 草稿 + 人工确认断言； relabel/recheck 通道格式写入
 * （净化工序②预留，06§3）。
 */
public final class AnnotateService {

  private static final Pattern HUNK_HEADER =
      Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@", Pattern.MULTILINE);

  private AnnotateService() {}

  /**
   * 从 diff 预填 expected 草稿（每 hunk 一条：右侧行起点、类别默认 bug、关键词取新增行标识符）。
   *
   * @param caseDir 用例目录（读 diff.patch）
   * @param diffText diff 文本（null 则读 caseDir/diff.patch）
   * @param annotator 标注人
   * @return 草稿文件路径（expected_findings.draft.json）
   */
  public static Path prefill(Path caseDir, String diffText, String annotator) {
    if (diffText == null) {
      try {
        diffText = Files.readString(caseDir.resolve("diff.patch"), StandardCharsets.UTF_8);
      } catch (IOException e) {
        throw new CraIntegrationException("diff.patch 读取失败: " + caseDir, e);
      }
    }
    List<ExpectedFinding> expected = new ArrayList<>();
    for (DiffSplitter.PatchedFile file : DiffSplitter.split(diffText)) {
      if (file.patch() == null || file.isTestFile()) {
        continue; // 二进制与测试 hunk（净化①已过滤的部分）不进断言预填
      }
      for (Hunk hunk : hunks(file.patch())) {
        expected.add(
            new ExpectedFinding(
                file.newPath() != null ? file.newPath() : file.oldPath(),
                hunk.rightStart,
                "bug",
                keywords(hunk.addedLines),
                "hunk 预填（右行 " + hunk.rightStart + "），人工确认断言关键词与类别"));
      }
    }
    ExpectedFindings draft = new ExpectedFindings(1, expected, List.<NegativeBait>of());
    return writeDraft(caseDir, draft);
  }

  /**
   * 写入 expected 草稿（prefill 与 badcase add 共用：draft → 人工确认 → confirm）。
   *
   * @param caseDir 用例目录
   * @param draft 草稿内容
   * @return 草稿文件路径（expected_findings.draft.json）
   */
  public static Path writeDraft(Path caseDir, ExpectedFindings draft) {
    Path out = caseDir.resolve("expected_findings.draft.json");
    Json.write(out, draft);
    return out;
  }

  /**
   * 人工确认收尾：draft → expected_findings.json + meta 标注状态推进。
   *
   * @param caseDir 用例目录
   * @param annotator 标注人
   * @return 正式 expected 文件路径
   */
  public static Path confirm(Path caseDir, String annotator) {
    Path draft = caseDir.resolve("expected_findings.draft.json");
    Path finalFile = caseDir.resolve("expected_findings.json");
    if (!Files.isRegularFile(draft)) {
      throw new CraConfigException("草稿不存在（先跑 annotate prefill）: " + draft);
    }
    // 先校验再转正：手工编辑坏的草稿不能以"已确认"身份进入正式断言（下次 run 才炸）
    Json.readExpected(draft);
    try {
      Files.move(draft, finalFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      throw new CraIntegrationException("draft 提交失败: " + draft, e);
    }
    updateMeta(
        caseDir,
        meta ->
            new FixtureMeta(
                meta.formatVersion(),
                meta.caseId(),
                meta.set(),
                meta.adversarialSubtype(),
                meta.source(),
                meta.project(),
                meta.bugId(),
                meta.reportId(),
                meta.baseSha(),
                meta.headSha(),
                meta.snapshot(),
                meta.reviewRequest(),
                meta.filteredTestFiles(),
                new FixtureMeta.Annotation("annotated", annotator, today()),
                meta.recheck(),
                meta.note()));
    return finalFile;
  }

  /**
   * 改标（净化工序②复核通道格式预留，06§3：误标样本打 relabeled、样本挪集、当次统计作废重跑）。
   *
   * @param caseDir 用例目录
   * @param toSet 改标去向（bad|clean）
   * @param annotator 操作人
   * @param reason 理由
   */
  public static void relabel(Path caseDir, String toSet, String annotator, String reason) {
    if (!"bad".equals(toSet) && !"clean".equals(toSet)) {
      throw new CraConfigException("relabel 目标集合非法: " + toSet + "（bad|clean）");
    }
    updateMeta(
        caseDir,
        meta ->
            new FixtureMeta(
                meta.formatVersion(),
                meta.caseId(),
                toSet,
                meta.adversarialSubtype(),
                meta.source(),
                meta.project(),
                meta.bugId(),
                meta.reportId(),
                meta.baseSha(),
                meta.headSha(),
                meta.snapshot(),
                meta.reviewRequest(),
                meta.filteredTestFiles(),
                new FixtureMeta.Annotation("relabeled", annotator, today()),
                new FixtureMeta.Recheck("relabeled", toSet, reason),
                meta.note() == null ? "" : meta.note() + " | relabel→" + toSet + ": " + reason));
  }

  private static void updateMeta(Path caseDir, java.util.function.UnaryOperator<FixtureMeta> op) {
    Path metaFile = caseDir.resolve("meta.json");
    FixtureMeta updated = op.apply(Json.readMeta(metaFile));
    Json.write(metaFile, updated);
  }

  private static String today() {
    return java.time.LocalDate.now(java.time.ZoneId.systemDefault()).toString();
  }

  // ---------- hunk 解析与关键词预填 ----------

  /** 单 hunk 预填信息。 */
  record Hunk(int rightStart, List<String> addedLines) {}

  /**
   * 提取 patch 的 hunk（右行起点 + 新增行）。
   *
   * @param patch hunk 全文
   * @return hunk 列表
   */
  static List<Hunk> hunks(String patch) {
    List<Hunk> out = new ArrayList<>();
    Matcher header = HUNK_HEADER.matcher(patch);
    List<Integer> starts = new ArrayList<>();
    while (header.find()) {
      starts.add(header.start());
      out.add(new Hunk(Integer.parseInt(header.group(3)), new ArrayList<>()));
    }
    for (int i = 0; i < out.size(); i++) {
      int from = starts.get(i);
      int to = i + 1 < starts.size() ? starts.get(i + 1) : patch.length();
      String body = patch.substring(from, to);
      for (String line : body.split("\n")) {
        if (line.startsWith("@@")) {
          continue;
        }
        if (line.startsWith("+")) {
          out.get(i).addedLines().add(line.substring(1));
        }
        // "-" 与上下文行只影响行号推进；预填取 hunk 右行起点，无需逐行计数
      }
    }
    return out;
  }

  /**
   * 关键词预填：新增行中的标识符 token（≥4 字符，去重取前 5，保序）。
   *
   * @param addedLines 新增行
   * @return 关键词列表
   */
  static List<String> keywords(List<String> addedLines) {
    Set<String> tokens = new LinkedHashSet<>();
    Pattern ident = Pattern.compile("[A-Za-z_]\\w{3,}");
    for (String line : addedLines) {
      Matcher m = ident.matcher(line);
      while (m.find() && tokens.size() < 5) {
        String token = m.group();
        if (!JAVA_NOISE.contains(token)) {
          tokens.add(token);
        }
      }
    }
    return new ArrayList<>(tokens);
  }

  private static final Set<String> JAVA_NOISE =
      Set.of(
          "public",
          "private",
          "protected",
          "static",
          "final",
          "return",
          "void",
          "class",
          "interface",
          "import",
          "package",
          "throws",
          "throw",
          "this",
          "super",
          "null",
          "true",
          "false",
          "while",
          "break",
          "catch",
          "finally",
          "extends",
          "implements",
          "instanceof",
          "String",
          "Object",
          "Long",
          "Integer",
          "Boolean",
          "int",
          "long",
          "boolean",
          "char",
          "double",
          "float",
          "byte",
          "short",
          "else",
          "case",
          "switch",
          "default",
          "new",
          "enum",
          "assert",
          "abstract",
          "synchronized",
          "transient",
          "volatile",
          "native",
          "strictfp");
}
