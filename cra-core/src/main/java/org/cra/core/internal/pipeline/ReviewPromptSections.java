package org.cra.core.internal.pipeline;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.cra.common.context.PathGlobs;
import org.cra.common.context.ReviewPolicy;
import org.cra.common.git.DiffFile;
import org.cra.common.git.FileContent;

/**
 * 专角色输入附加段（03§6 v1.2）：只负责把可信上下文渲染成 prompt 段落，不做派发与预算记账。
 *
 * <p>从 {@link ReviewStage} 拆出：Prompt 段落属于上下文组装职责，与扇出调度解耦后 可独立演进与测试。
 */
final class ReviewPromptSections {

  /** Security 敏感路径判定（注入 chunk 内文件全文，03§6 v1.2）。 */
  private static final Pattern SENSITIVE_PATH =
      Pattern.compile("(?i)(auth|crypto|secret|token|password|credential|jwt|security|keystore)");

  /** 依赖清单文件（依赖风险核对，03§3 Security 输入重点）。 */
  private static final Set<String> DEPENDENCY_MANIFESTS =
      Set.of("pom.xml", "build.gradle", "build.gradle.kts", "gradle.lockfile", "settings.gradle");

  /** Security 附加段字符上限（≈8k token，04§2.1 字符/3.5）。 */
  private static final int EXTRA_SECTION_MAX_CHARS = 28_000;

  private ReviewPromptSections() {}

  /**
   * Security 附加段（03§6 v1.2）：chunk 内敏感路径/priority 命中文件的全文 + 依赖清单变更注记， 字符封顶（预算内注入）。
   *
   * @param chunk 评审 chunk
   * @param headFiles head 侧预取全文
   * @param policy 评审策略（priority_paths）
   * @return 附加段（无命中返回空串）
   */
  static String securityExtraSection(
      ChunkPlanner.Chunk chunk, Map<String, FileContent> headFiles, ReviewPolicy policy) {
    StringBuilder out = new StringBuilder();
    for (DiffFile file : chunk.files()) {
      boolean manifest = DEPENDENCY_MANIFESTS.contains(file.path());
      boolean sensitive =
          SENSITIVE_PATH.matcher(file.path()).find()
              || PathGlobs.matchesAny(policy.priorityPaths(), file.path());
      if (manifest) {
        out.append("【依赖清单变更】").append(file.path()).append("（重点核对引入组件与版本放宽）\n");
      }
      if (sensitive) {
        FileContent content = headFiles.get(file.path());
        if (content != null && !content.binary() && content.text() != null) {
          out.append("### 敏感文件全文：")
              .append(file.path())
              .append("\n")
              .append(content.text())
              .append("\n");
        }
      }
      if (out.length() >= EXTRA_SECTION_MAX_CHARS) {
        out.append("（附加段已达预算上限，后续文件仅注入 diff）\n");
        break;
      }
    }
    if (out.length() > EXTRA_SECTION_MAX_CHARS) {
      out.setLength(EXTRA_SECTION_MAX_CHARS);
      out.append("\n（附加段截断至预算上限）\n");
    }
    return out.toString();
  }

  /** TestReviewer 的测试文件清单附加段（全 PR 级输入）。 */
  static String testExtraSection(ChunkPlanner.Plan plan) {
    StringBuilder sb = new StringBuilder();
    sb.append("### 本次变更的测试相关文件\n");
    boolean any = false;
    for (ChunkPlanner.Chunk chunk : plan.chunks()) {
      for (DiffFile file : chunk.files()) {
        if (ReviewRoutingPolicy.isTestPath(file.path())) {
          any = true;
          sb.append("- ").append(file.path()).append("（").append(file.status()).append("）\n");
        }
      }
    }
    if (!any) {
      sb.append("-（本次变更未触及 src/test/ 路径）\n");
    }
    return sb.toString();
  }
}
