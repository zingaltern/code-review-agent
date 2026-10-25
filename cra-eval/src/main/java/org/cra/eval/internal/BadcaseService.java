package org.cra.eval.internal;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.cra.common.exception.CraConfigException;
import org.cra.common.exception.CraIntegrationException;
import org.cra.common.git.CommentSource;
import org.cra.common.git.RepoRef;
import org.cra.eval.api.ExpectedFindings;
import org.cra.eval.api.FixtureMeta;
import org.cra.git.api.GitPlatformAdapter;

/**
 * {@code cra-eval badcase add} 实现（T3.5 / 06§2）：定位 run 的 finding → 生成评测集新条目 （diff + 快照从远端拉 +
 * meta/字段草案）→ 交给 annotate 流程确认后进入下次回归。
 *
 * <p>来源事实写入用例目录的 {@code badcase.json}（不走 FixtureMeta 格式变更，保持数据集 v1 向前兼容）； meta.source 固定 {@code
 * production}，note 记录 run/finding/label 以便审计。
 *
 * <p>产物落盘见 {@link BadcaseFixtureWriter}，标识/集合判定见 {@link BadcaseIdentifiers}。
 */
final class BadcaseService {

  private BadcaseService() {}

  /**
   * 输入参数（由 {@link BadcaseCommand} 解析并注入平台端口）。
   *
   * @param dataset 数据集根
   * @param runRefRaw 原始 run_ref（审计记录）
   * @param runRef 已解析的本地 run 路径
   * @param findingId finding 标识
   * @param label missed|false-positive|relabeled
   * @param toSet relabeled 的目标集合（可空；默认 bad）
   * @param caseId 自定义 case id（可空）
   * @param annotator 标注人
   * @param reason 备注理由
   * @param diffFile 离线 diff（可空）
   * @param snapshotDir 离线快照源目录（可空）
   * @param fileOverride 覆盖 finding 文件（brief 无法解析时）
   * @param lineOverride 覆盖 finding 行号
   */
  record Inputs(
      Path dataset,
      String runRefRaw,
      Path runRef,
      String findingId,
      String label,
      String toSet,
      String caseId,
      String annotator,
      String reason,
      Path diffFile,
      Path snapshotDir,
      String fileOverride,
      Integer lineOverride) {}

  /**
   * 生成结果。
   *
   * @param caseDir 用例目录
   * @param caseId 用例标识
   * @param set 所属集合
   * @param run 来源 run
   * @param finding 定位到的 finding
   * @param findingSource ledger|brief
   * @param verifierVerdict 原验证者判定（可空）
   * @param diffSource diff 来源描述
   * @param changedFiles diff 文件数
   * @param snapshot 快照结果
   */
  record Result(
      Path caseDir,
      String caseId,
      String set,
      TraceIngest.RunTrace run,
      FindingLocator.Located finding,
      String findingSource,
      String verifierVerdict,
      String diffSource,
      int changedFiles,
      SnapshotBuilder.Result snapshot) {}

  /**
   * 执行 badcase add。
   *
   * @param inputs 参数
   * @param adapter 平台适配器（离线 diff+快照时可为空）
   * @param comments 账本端口（可空：无 token 时只用 brief 定位）
   * @return 结果
   */
  static Result add(Inputs inputs, GitPlatformAdapter adapter, CommentSource comments) {
    BadcaseIdentifiers.validate(inputs);
    Path traceFile = TraceIngest.locateTrace(inputs.runRef());
    TraceIngest.RunTrace run = TraceIngest.parse(traceFile);
    RepoRef repo = run.repo() == null || run.repo().isBlank() ? null : RepoRef.parse(run.repo());
    List<String> warnings = new ArrayList<>();
    FindingLocator.Located located =
        FindingLocator.locate(
            traceFile.getParent(), run, inputs.findingId(), comments, repo, warnings);
    String file = inputs.fileOverride() == null ? located.file() : inputs.fileOverride();
    int line = inputs.lineOverride() == null ? located.line() : inputs.lineOverride();
    if (file.isBlank() || line <= 0) {
      throw new CraConfigException(
          "finding 定位缺 file/line（source=" + located.source() + "），请用 --file/--line 显式指定后再固化");
    }
    String set = BadcaseIdentifiers.setOf(inputs.label(), inputs.toSet());
    String caseId =
        inputs.caseId() == null
            ? BadcaseIdentifiers.defaultCaseId(inputs.label(), repo, run, inputs.findingId())
            : BadcaseIdentifiers.sanitize(inputs.caseId());
    Path caseDir = inputs.dataset().resolve(set).resolve(caseId);
    if (Files.exists(caseDir)) {
      throw new CraConfigException("用例目录已存在（不覆盖，避免误丢人工标注）: " + caseDir);
    }
    String diffText = BadcaseFixtureWriter.diff(inputs, adapter, repo, run);
    int changedFiles = DiffSplitter.split(diffText).size();
    if (changedFiles == 0) {
      throw new CraConfigException("diff 无法拆出文件（确认 base/head 正确）: " + run.runId());
    }
    String verdict = BadcaseIdentifiers.verifierVerdict(run, inputs.findingId());
    try {
      Files.createDirectories(caseDir);
      Files.writeString(caseDir.resolve("diff.patch"), diffText, StandardCharsets.UTF_8);
      SnapshotBuilder.Result snapshot =
          BadcaseFixtureWriter.snapshot(inputs, adapter, repo, run, caseDir);
      ExpectedFindings draft =
          "false-positive".equals(inputs.label())
              ? new ExpectedFindings(1, List.of(), List.of())
              : new ExpectedFindings(
                  1,
                  List.of(
                      FindingLocator.toExpected(
                          new FindingLocator.Located(
                              located.findingId(),
                              located.source(),
                              file,
                              line,
                              located.category(),
                              located.severity(),
                              located.title(),
                              located.description(),
                              located.failureMode(),
                              located.evidence(),
                              located.suggestion()),
                          "badcase 草稿（label="
                              + inputs.label()
                              + "；确认位置/类别/关键词后 annotate confirm）")),
                  List.of());
      AnnotateService.writeDraft(caseDir, draft);
      FixtureMeta meta =
          BadcaseFixtureWriter.meta(inputs, repo, run, caseId, set, snapshot, changedFiles);
      Json.write(caseDir.resolve("meta.json"), meta);
      Json.write(
          caseDir.resolve("badcase.json"),
          new Provenance(
              inputs.label(),
              caseId,
              set,
              inputs.runRefRaw(),
              run.runId(),
              inputs.findingId(),
              repo == null ? "" : repo.owner() + "/" + repo.repo(),
              run.pr(),
              run.baseSha(),
              run.headSha(),
              verdict,
              located.source(),
              Instant.now().toString(),
              inputs.annotator(),
              inputs.reason()));
      if (!warnings.isEmpty()) {
        ReportWriter.printLines(warnings.stream().map(w -> "⚠ " + w).toList());
      }
      return new Result(
          caseDir,
          caseId,
          set,
          run,
          new FindingLocator.Located(
              located.findingId(),
              located.source(),
              file,
              line,
              located.category(),
              located.severity(),
              located.title(),
              located.description(),
              located.failureMode(),
              located.evidence(),
              located.suggestion()),
          located.source(),
          verdict,
          inputs.diffFile() == null
              ? "compare "
                  + BadcaseIdentifiers.abbreviate(run.baseSha())
                  + ".."
                  + BadcaseIdentifiers.abbreviate(run.headSha())
              : "--diff-file",
          changedFiles,
          snapshot);
    } catch (IOException e) {
      BadcaseFixtureWriter.cleanup(caseDir, e);
      throw new CraIntegrationException("badcase 用例写入失败: " + caseDir, e);
    } catch (RuntimeException e) {
      BadcaseFixtureWriter.cleanup(caseDir, e);
      throw e;
    }
  }

  /**
   * badcase 来源事实（badcase.json；06§2 回溯入口）。
   *
   * @param label missed|false-positive|relabeled
   * @param caseId 用例标识
   * @param set 所属集合
   * @param runRef 原始 run 引用（artifact:// 或本地路径）
   * @param runId run 标识
   * @param findingId 来源 finding
   * @param repo 仓库
   * @param pr PR 编号
   * @param baseSha base
   * @param headSha head
   * @param verifierVerdict 当时验证者判定（可空）
   * @param findingSource ledger|brief
   * @param addedAt 固化时间
   * @param addedBy 操作人
   * @param reason 理由
   */
  record Provenance(
      @JsonProperty("label") String label,
      @JsonProperty("case_id") String caseId,
      @JsonProperty("set") String set,
      @JsonProperty("run_ref") String runRef,
      @JsonProperty("run_id") String runId,
      @JsonProperty("finding_id") String findingId,
      @JsonProperty("repo") String repo,
      @JsonProperty("pr") int pr,
      @JsonProperty("base_sha") String baseSha,
      @JsonProperty("head_sha") String headSha,
      @JsonProperty("verifier_verdict") String verifierVerdict,
      @JsonProperty("finding_source") String findingSource,
      @JsonProperty("added_at") String addedAt,
      @JsonProperty("added_by") String addedBy,
      @JsonProperty("reason") String reason) {}
}
