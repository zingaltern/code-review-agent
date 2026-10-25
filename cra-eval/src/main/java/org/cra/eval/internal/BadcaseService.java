package org.cra.eval.internal;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
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
    validate(inputs);
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
    String set = setOf(inputs.label(), inputs.toSet());
    String caseId =
        inputs.caseId() == null
            ? defaultCaseId(inputs.label(), repo, run, inputs.findingId())
            : sanitize(inputs.caseId());
    Path caseDir = inputs.dataset().resolve(set).resolve(caseId);
    if (Files.exists(caseDir)) {
      throw new CraConfigException("用例目录已存在（不覆盖，避免误丢人工标注）: " + caseDir);
    }
    String diffText = diff(inputs, adapter, repo, run);
    int changedFiles = DiffSplitter.split(diffText).size();
    if (changedFiles == 0) {
      throw new CraConfigException("diff 无法拆出文件（确认 base/head 正确）: " + run.runId());
    }
    String verdict = verifierVerdict(run, inputs.findingId());
    try {
      Files.createDirectories(caseDir);
      Files.writeString(caseDir.resolve("diff.patch"), diffText, StandardCharsets.UTF_8);
      SnapshotBuilder.Result snapshot = snapshot(inputs, adapter, repo, run, caseDir);
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
      FixtureMeta meta = meta(inputs, repo, run, caseId, set, snapshot, changedFiles);
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
              ? "compare " + abbreviate(run.baseSha()) + ".." + abbreviate(run.headSha())
              : "--diff-file",
          changedFiles,
          snapshot);
    } catch (IOException e) {
      cleanup(caseDir, e);
      throw new CraIntegrationException("badcase 用例写入失败: " + caseDir, e);
    } catch (RuntimeException e) {
      cleanup(caseDir, e);
      throw e;
    }
  }

  private static void validate(Inputs inputs) {
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

  private static String setOf(String label, String toSet) {
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

  private static String diff(
      Inputs inputs, GitPlatformAdapter adapter, RepoRef repo, TraceIngest.RunTrace run) {
    if (inputs.diffFile() != null) {
      try {
        String text = Files.readString(inputs.diffFile(), StandardCharsets.UTF_8);
        if (text.isBlank()) {
          throw new CraConfigException("--diff-file 为空: " + inputs.diffFile());
        }
        return text;
      } catch (IOException e) {
        throw new CraConfigException("--diff-file 读取失败: " + inputs.diffFile());
      }
    }
    requireRemote(adapter, repo, run, "diff");
    if (run.baseSha().isBlank() || run.headSha().isBlank()) {
      throw new CraConfigException("run trace 缺 base/head sha，无法拉 diff: " + run.runId());
    }
    return DiffRenderer.render(adapter.fetchDiff(repo, run.baseSha(), run.headSha()));
  }

  private static SnapshotBuilder.Result snapshot(
      Inputs inputs,
      GitPlatformAdapter adapter,
      RepoRef repo,
      TraceIngest.RunTrace run,
      Path caseDir) {
    if (inputs.snapshotDir() != null) {
      return SnapshotBuilder.fromLocalDir(caseDir, inputs.snapshotDir());
    }
    requireRemote(adapter, repo, run, "快照");
    if (run.headSha().isBlank()) {
      throw new CraConfigException("run trace 缺 head sha，无法拉快照: " + run.runId());
    }
    return SnapshotBuilder.fromRemote(caseDir, repo, run.headSha(), adapter);
  }

  private static void requireRemote(
      GitPlatformAdapter adapter, RepoRef repo, TraceIngest.RunTrace run, String what) {
    if (adapter == null || repo == null) {
      throw new CraConfigException(
          "生成 "
              + what
              + " 需要 GitHub token（--token/GITHUB_TOKEN）或离线输入（"
              + ("diff".equals(what) ? "--diff-file" : "--snapshot-dir")
              + "）；run repo="
              + run.repo());
    }
  }

  private static FixtureMeta meta(
      Inputs inputs,
      RepoRef repo,
      TraceIngest.RunTrace run,
      String caseId,
      String set,
      SnapshotBuilder.Result snapshot,
      int changedFiles) {
    String owner = repo == null ? "local" : repo.owner();
    String name = repo == null ? sanitize(run.runId()) : repo.repo();
    String note =
        "badcase "
            + inputs.label()
            + " run="
            + run.runId()
            + " finding="
            + inputs.findingId()
            + " diff_files="
            + changedFiles
            + " snapshot_files="
            + snapshot.files()
            + (snapshot.skippedBinary() > 0 ? " binary_skipped=" + snapshot.skippedBinary() : "")
            + (inputs.reason() == null || inputs.reason().isBlank() ? "" : " | " + inputs.reason());
    return new FixtureMeta(
        FixtureMeta.FORMAT_VERSION,
        caseId,
        set,
        null,
        "production",
        null,
        null,
        repo == null ? "" : owner + "/" + name + "#" + run.pr(),
        run.baseSha(),
        run.headSha(),
        new FixtureMeta.Snapshot("repo_snapshot.tgz", snapshot.sha256(), "."),
        new FixtureMeta.ReviewRequestSpec(Math.max(run.pr(), 1), owner, name),
        List.of(),
        new FixtureMeta.Annotation(
            "draft", inputs.annotator(), java.time.LocalDate.now().toString()),
        new FixtureMeta.Recheck("pending", "", ""),
        note);
  }

  private static String verifierVerdict(TraceIngest.RunTrace run, String findingId) {
    Optional<TraceIngest.Promotion> promotion =
        run.promotions().stream().filter(p -> findingId.equals(p.findingId())).findFirst();
    return promotion.map(TraceIngest.Promotion::verifierVerdict).orElse("");
  }

  private static String defaultCaseId(
      String label, RepoRef repo, TraceIngest.RunTrace run, String findingId) {
    String repoName = repo == null ? sanitize(run.runId()) : repo.repo();
    String id = findingId.replaceAll("[^A-Za-z0-9]", "");
    String shortId = id.length() <= 10 ? id : id.substring(id.length() - 10);
    return sanitize(
            "badcase-" + label + "-" + repoName + "-" + Math.max(run.pr(), 1) + "-" + shortId)
        .toLowerCase(Locale.ROOT);
  }

  private static String sanitize(String raw) {
    String value = raw.replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("^-+|-+$", "");
    if (value.isBlank()) {
      throw new CraConfigException("无法生成合法 case_id（原始值: " + raw + "），请用 --case-id 指定");
    }
    return value;
  }

  private static String abbreviate(String sha) {
    if (sha == null || sha.isBlank()) {
      return "?";
    }
    return sha.length() <= 8 ? sha : sha.substring(0, 8);
  }

  /** 半成品清理：清理失败挂到原始异常（suppressed），不掩盖主错误。 */
  private static void cleanup(Path root, Throwable primary) {
    if (root == null || !Files.exists(root)) {
      return;
    }
    try (var walk = Files.walk(root)) {
      for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
        Files.delete(path);
      }
    } catch (IOException e) {
      primary.addSuppressed(e);
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
