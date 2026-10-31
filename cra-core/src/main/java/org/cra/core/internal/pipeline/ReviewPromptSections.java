package org.cra.core.internal.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.cra.common.context.PathGlobs;
import org.cra.common.context.ReviewPolicy;
import org.cra.common.git.DiffFile;
import org.cra.common.git.FileContent;

/**
 * 专角色输入附加段（03§6 v1.2）：只负责把上下文渲染成 prompt 段落，不做派发与预算记账； 信任属性由调用方与模板定界（04§3.3）。
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

  /** TestReviewer 测试清单条目上限（有界注入；其余计数声明，防大 PR 吃穿 16k 输入预算，02§4.4）。 */
  private static final int TEST_LIST_MAX_ENTRIES = 60;

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

  /**
   * TestReviewer 的测试文件清单附加段（全 PR 级输入）。
   *
   * <p>测试文件默认不进评审 chunk（02§4.4），但"本次改了什么测试"是"缺测试覆盖"结论的正向证据面：计划内测试文件
   * （开关打开时）与按策略跳过的测试文件都要列出，否则该角色的核心主张在默认策略下失去输入（03§3）。
   *
   * @param plan 评审计划（含测试文件策略跳过的路径）
   * @return 附加段
   */
  static String testExtraSection(ChunkPlanner.Plan plan) {
    StringBuilder sb = new StringBuilder();
    sb.append("### 本次变更的测试相关文件\n");
    List<String> lines = new ArrayList<>();
    for (ChunkPlanner.Chunk chunk : plan.chunks()) {
      for (DiffFile file : chunk.files()) {
        if (ReviewRoutingPolicy.isTestPath(file.path())) {
          lines.add("- " + file.path() + "（" + file.status() + "）");
        }
      }
    }
    for (String path : plan.skippedTestPaths()) {
      lines.add("- " + path + "（按策略不评审，仅作测试覆盖判断依据）");
    }
    if (lines.isEmpty()) {
      sb.append("-（本次变更未触及测试文件）\n");
      return sb.toString();
    }
    int shown = Math.min(lines.size(), TEST_LIST_MAX_ENTRIES);
    for (int i = 0; i < shown; i++) {
      sb.append(lines.get(i)).append('\n');
    }
    if (lines.size() > shown) {
      // 大 PR（AI coding 时代常见数百个测试文件）：清单必须有界，否则单段吃穿 TestReviewer 的 16k 输入预算
      sb.append("…等 ")
          .append(lines.size() - shown)
          .append(" 个（共 ")
          .append(lines.size())
          .append(" 个测试文件，超出清单上限 ")
          .append(TEST_LIST_MAX_ENTRIES)
          .append("；未列出的测试文件仍可经 followup_file 补查）\n");
    }
    return sb.toString();
  }
}
