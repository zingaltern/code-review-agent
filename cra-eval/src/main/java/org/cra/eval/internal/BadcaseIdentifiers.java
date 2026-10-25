package org.cra.eval.internal;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.cra.common.exception.CraConfigException;
import org.cra.common.git.RepoRef;
import org.cra.common.text.Texts;
import org.cra.eval.api.FixtureMeta;

/** badcase 用例标识与集合判定（T3.5）：set 归类、case_id 生成/清洗、原 run 验证者判定提取。 */
final class BadcaseIdentifiers {

  private BadcaseIdentifiers() {}

  static String setOf(String label, String toSet) {
    if ("false-positive".equals(label)) {
      return FixtureMeta.SET_CLEAN;
    }
    if ("missed".equals(label)) {
      return FixtureMeta.SET_BAD;
    }
    String set = toSet == null || toSet.isBlank() ? FixtureMeta.SET_BAD : toSet;
    if (!FixtureMeta.SET_BAD.equals(set) && !FixtureMeta.SET_CLEAN.equals(set)) {
      throw new CraConfigException("relabeled 的 --to-set 非法: " + set + "（bad|clean）");
    }
    return set;
  }

  static String defaultCaseId(
      String label, RepoRef repo, TraceIngest.RunTrace run, String findingId) {
    String repoName = repo == null ? sanitize(run.runId()) : repo.repo();
    String id = findingId.replaceAll("[^A-Za-z0-9]", "");
    String shortId = id.length() <= 10 ? id : id.substring(id.length() - 10);
    return sanitize(
            "badcase-" + label + "-" + repoName + "-" + Math.max(run.pr(), 1) + "-" + shortId)
        .toLowerCase(Locale.ROOT);
  }

  static String sanitize(String raw) {
    String value = raw.replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("^-+|-+$", "");
    if (value.isBlank()) {
      throw new CraConfigException("无法生成合法 case_id（原始值: " + raw + "），请用 --case-id 指定");
    }
    return value;
  }

  static String verifierVerdict(TraceIngest.RunTrace run, String findingId) {
    Optional<TraceIngest.Promotion> promotion =
        run.promotions().stream().filter(p -> findingId.equals(p.findingId())).findFirst();
    return promotion.map(TraceIngest.Promotion::verifierVerdict).orElse("");
  }

  static String abbreviate(String sha) {
    return Texts.shortSha(sha, 8, "?");
  }

  /** 校验 label/finding_id/dataset 必填项（用户输入边界）。 */
  static void validate(BadcaseService.Inputs inputs) {
    if (!List.of("missed", "false-positive", "relabeled").contains(inputs.label())) {
      throw new CraConfigException(
          "--label 非法: " + inputs.label() + "（missed|false-positive|relabeled）");
    }
    if (inputs.findingId() == null || inputs.findingId().isBlank()) {
      throw new CraConfigException("缺少 finding_id");
    }
    if (inputs.dataset() == null) {
      throw new CraConfigException("缺少 --dataset <dir>");
    }
  }
}
