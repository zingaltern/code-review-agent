package org.cra.eval.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Objects;
import org.cra.common.text.Texts;

/**
 * 用例元数据（06§3 fixture 规格的 meta.json，评测数据集格式 v1，11§2 演进扩展点——字段变更须 bump {@code format_version}
 * 并保证旧集可加载）。
 *
 * @param formatVersion 格式版本，当前恒为 1
 * @param caseId 用例标识（如 bad-Gson-1 / clean-Mockito-3abf001 / adv-inj-01）
 * @param set 所属集合：{@code bad}（Defects4J buggy 侧缺陷引入 diff，期望 findings 非空）、 {@code clean}（宿主项目良性
 *     commit，期望 findings 为空）、{@code adversarial}（微型 fixture）
 * @param adversarialSubtype 对抗集子类：injection（藏指令）/ hallucination（幻觉诱导）/ refusal（拒真陷阱）/
 *     context（v1.3.1 第 4 类：隐式假设与外部证据矛盾，06§1.2）；非对抗集为 null
 * @param source 来源：defects4j | synthetic | production（T3.5 badcase 固化；来源细节见用例目录 badcase.json）
 * @param project Defects4J 项目 id（如 Gson），synthetic 为 null
 * @param bugId Defects4J bug 编号，无则为 null
 * @param reportId 上游 issue/PR 标识（如 gson#40），可空
 * @param baseSha ReviewRequest.baseSha——bad/clean 固定侧（fixed 或 parent）commit
 * @param headSha ReviewRequest.headSha——buggy（或良性 commit）侧
 * @param snapshot 快照文件描述（tgz 不入 git，见 .gitignore 与 README 再生成说明）
 * @param reviewRequest 驱动流水线用的请求参数
 * @param filteredTestFiles 净化工序①：bad 集 diff 中被 hunk 级过滤掉的测试文件（src/test/**、*Test.java 等）
 * @param annotation 标注状态（draft → annotated；半自动标注 cra-eval annotate 产出）
 * @param recheck 复核通道（净化工序②格式预留，06§3：误标样本打 relabeled 挪集、当次统计作废）
 * @param note 备注可空
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FixtureMeta(
    @JsonProperty("format_version") int formatVersion,
    @JsonProperty("case_id") String caseId,
    @JsonProperty("set") String set,
    @JsonProperty("adversarial_subtype") String adversarialSubtype,
    @JsonProperty("source") String source,
    @JsonProperty("project") String project,
    @JsonProperty("bug_id") Integer bugId,
    @JsonProperty("report_id") String reportId,
    @JsonProperty("base_sha") String baseSha,
    @JsonProperty("head_sha") String headSha,
    @JsonProperty("snapshot") Snapshot snapshot,
    @JsonProperty("review_request") ReviewRequestSpec reviewRequest,
    @JsonProperty("filtered_test_files") List<String> filteredTestFiles,
    @JsonProperty("annotation") Annotation annotation,
    @JsonProperty("recheck") Recheck recheck,
    @JsonProperty("note") String note) {

  /** 数据集格式当前版本（11§2：格式变更 bump 版本字段并保证旧集可加载）。 */
  public static final int FORMAT_VERSION = 1;

  public static final String SET_BAD = "bad";
  public static final String SET_CLEAN = "clean";
  public static final String SET_ADVERSARIAL = "adversarial";

  public FixtureMeta {
    if (formatVersion != FORMAT_VERSION) {
      throw new IllegalArgumentException(
          "meta format_version=" + formatVersion + " 不受支持（当前 " + FORMAT_VERSION + "）");
    }
    Texts.requireNonBlank(caseId, "case_id");
    Texts.requireNonBlank(set, "set");
    if (!SET_BAD.equals(set) && !SET_CLEAN.equals(set) && !SET_ADVERSARIAL.equals(set)) {
      throw new IllegalArgumentException("非法 set: " + set + "（bad|clean|adversarial）");
    }
    if (SET_ADVERSARIAL.equals(set)) {
      Texts.requireNonBlank(adversarialSubtype, "adversarial_subtype");
      if (!"injection".equals(adversarialSubtype)
          && !"hallucination".equals(adversarialSubtype)
          && !"refusal".equals(adversarialSubtype)
          && !"context".equals(adversarialSubtype)) {
        throw new IllegalArgumentException(
            "非法 adversarial_subtype: "
                + adversarialSubtype
                + "（injection|hallucination|refusal|context）");
      }
    }
    Texts.requireNonBlank(baseSha, "base_sha");
    Texts.requireNonBlank(headSha, "head_sha");
    Objects.requireNonNull(snapshot, "snapshot");
    Objects.requireNonNull(reviewRequest, "review_request");
    filteredTestFiles = List.copyOf(filteredTestFiles == null ? List.of() : filteredTestFiles);
  }

  /**
   * 快照文件描述。
   *
   * @param file 文件名（相对用例目录，通常 repo_snapshot.tgz）
   * @param sha256 快照 sha256（加载器校验缓存一致性；可空则跳过校验）
   * @param cropRoot 快照裁剪根（缺陷相关模块目录，06§3）
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Snapshot(
      @JsonProperty("file") String file,
      @JsonProperty("sha256") String sha256,
      @JsonProperty("crop_root") String cropRoot) {

    public Snapshot {
      Texts.requireNonBlank(file, "snapshot.file");
      sha256 = sha256 == null || sha256.isBlank() ? null : sha256;
      cropRoot = cropRoot == null ? "" : cropRoot;
    }
  }

  /**
   * 驱动流水线的请求参数。
   *
   * @param prNumber 合成 PR 号（流水线仅要求正整数，黑盒驱动用）
   * @param repoOwner 合成属主
   * @param repoName 合成仓库名（默认即 case_id）
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ReviewRequestSpec(
      @JsonProperty("pr_number") int prNumber,
      @JsonProperty("repo_owner") String repoOwner,
      @JsonProperty("repo_name") String repoName) {

    public ReviewRequestSpec {
      if (prNumber <= 0) {
        throw new IllegalArgumentException("pr_number must be positive: " + prNumber);
      }
      Texts.requireNonBlank(repoOwner, "review_request.repo_owner");
      Texts.requireNonBlank(repoName, "review_request.repo_name");
    }
  }

  /**
   * 标注状态。
   *
   * @param status draft | annotated | recheck-pending | relabeled
   * @param annotator 标注人（agent 名或人名）
   * @param annotatedAt 标注日期（ISO，如 2026-10-25）
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Annotation(
      @JsonProperty("status") String status,
      @JsonProperty("annotator") String annotator,
      @JsonProperty("annotated_at") String annotatedAt) {

    public Annotation {
      status = status == null || status.isBlank() ? "draft" : status;
      annotator = annotator == null ? "" : annotator;
      annotatedAt = annotatedAt == null ? "" : annotatedAt;
    }
  }

  /**
   * 复核通道（净化工序②，格式预留：M1 只落格式与 annotate 写入，复核闭环随 T3.2）。
   *
   * @param status pending | confirmed | relabeled
   * @param relabeledTo 改标去向集合（status=relabeled 时必填）
   * @param reason 改标/复核理由，可空
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Recheck(
      @JsonProperty("status") String status,
      @JsonProperty("relabeled_to") String relabeledTo,
      @JsonProperty("reason") String reason) {

    public Recheck {
      status = status == null || status.isBlank() ? "pending" : status;
      relabeledTo = relabeledTo == null ? "" : relabeledTo;
      reason = reason == null ? "" : reason;
    }
  }
}
