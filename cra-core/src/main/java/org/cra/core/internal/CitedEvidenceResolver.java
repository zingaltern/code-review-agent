package org.cra.core.internal;

import java.util.List;
import java.util.Optional;
import org.cra.common.finding.CandidateFinding;
import org.cra.common.finding.ContextCitation;
import org.cra.git.api.LocalRepoContext;

/**
 * 被引矛盾证据解析（03§4.2 v1.3.1：context 类 finding 引用的 commit diff 节选，编排器预取内联， 每 commit ≤1k
 * token）。引用格式的权威定义见 {@link ContextCitation}（M4f 落定），经本地 jgit 读取（{@link
 * LocalRepoContext#commitFilePatch}）。
 *
 * <p>best effort：本地仓库不可得 / commit 不可解析 / 文件不在该 commit 内 → 空串（组装器降级为 finding 自带证据原文）。
 */
final class CitedEvidenceResolver {

  private final LocalRepoContext localRepoContext;

  /**
   * @param localRepoContext 本地仓库上下文（null = 纯 API 形态，全部降级为空）
   */
  CitedEvidenceResolver(LocalRepoContext localRepoContext) {
    this.localRepoContext = localRepoContext;
  }

  /**
   * 一条 finding 的全部机器可解析引用（commit + REVIEW.md 条目锚）。
   *
   * @param finding 候选结论
   * @return 引用集合
   */
  Citation citationOf(CandidateFinding finding) {
    String text = finding.evidence() + "\n" + finding.description();
    return new Citation(
        ContextCitation.commitRefs(text, finding.file()), ContextCitation.reviewMdRefs(text));
  }

  /**
   * 取被引 commit 对文件的 unified patch。
   *
   * @param sha 被引 commit（完整或缩写）
   * @param path commit 内文件路径
   * @return patch 文本；不可得为空串
   */
  String commitPatch(String sha, String path) {
    if (localRepoContext == null) {
      return "";
    }
    try {
      Optional<String> patch = localRepoContext.commitFilePatch(sha, path);
      return patch.orElse("");
    } catch (RuntimeException e) {
      return "";
    }
  }

  /**
   * 引用集合。
   *
   * @param commits 被引 commit（结构化引用优先，裸 hex 兼容）
   * @param reviewMdRefs 被引 REVIEW.md 条目（段名规范化）
   */
  record Citation(
      List<ContextCitation.CommitRef> commits, List<ContextCitation.ReviewMdRef> reviewMdRefs) {

    Citation {
      commits = List.copyOf(commits == null ? List.of() : commits);
      reviewMdRefs = List.copyOf(reviewMdRefs == null ? List.of() : reviewMdRefs);
    }

    /**
     * 是否无任何引用。
     *
     * @return true 若无引用
     */
    boolean isEmpty() {
      return commits.isEmpty() && reviewMdRefs.isEmpty();
    }
  }
}
