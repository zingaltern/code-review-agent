package org.cra.core.internal.pipeline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.cra.common.context.PathGlobs;
import org.cra.common.context.ReviewPolicy;
import org.cra.common.git.DiffFile;

/**
 * 评审计划：变更文件分 chunk（04§2.2：按模块分组、2–3 文件/chunk）+ skip/priority 范围裁决（04§3.1）。 skip
 * 命中的文件整体不进评审；hard-cap 触发时仅保留 priority_paths 命中文件（未配置 priority 时如实降级为全量并声明）。
 */
public final class ChunkPlanner {

  /** 单 chunk 文件数上限（04§2.2 算术示例 2–3 文件/chunk）。 */
  static final int MAX_FILES_PER_CHUNK = 3;

  private ChunkPlanner() {}

  /**
   * 评审范围规划结果。
   *
   * @param chunks 分块列表（保持 diff 文件顺序）
   * @param skippedPaths 被策略 skip 的文件路径
   * @param notes 范围裁决说明（未配置 priority 降级等）
   */
  public record Plan(List<Chunk> chunks, List<String> skippedPaths, List<String> notes) {

    public Plan {
      chunks = List.copyOf(chunks == null ? List.of() : chunks);
      skippedPaths = List.copyOf(skippedPaths == null ? List.of() : skippedPaths);
      notes = List.copyOf(notes == null ? List.of() : notes);
    }
  }

  /**
   * 单个评审 chunk。
   *
   * @param key 模块分组键（路径前两段）
   * @param files 该 chunk 的变更文件
   */
  public record Chunk(String key, List<DiffFile> files) {

    public Chunk {
      files = List.copyOf(files == null ? List.of() : files);
    }
  }

  /**
   * 规划评审范围与分块。
   *
   * @param files 变更文件（diff 顺序）
   * @param policy base 分支评审策略
   * @param priorityOnly true 时仅评审 priority_paths（hard-cap，02§4.4）
   * @return 规划结果
   */
  public static Plan plan(List<DiffFile> files, ReviewPolicy policy, boolean priorityOnly) {
    List<String> skipped = new ArrayList<>();
    List<String> notes = new ArrayList<>();
    List<DiffFile> scoped = new ArrayList<>();
    for (DiffFile file : files) {
      if (PathGlobs.matchesAny(policy.skipPaths(), file.path())) {
        skipped.add(file.path());
      } else {
        scoped.add(file);
      }
    }
    if (priorityOnly) {
      if (policy.priorityPaths().isEmpty()) {
        notes.add("hard-cap 触发但未配置 priority_paths，按全部变更文件评审并在覆盖声明（02§4.4）");
      } else {
        List<DiffFile> filtered = new ArrayList<>();
        for (DiffFile file : scoped) {
          if (PathGlobs.matchesAny(policy.priorityPaths(), file.path())) {
            filtered.add(file);
          } else {
            skipped.add(file.path() + "（超出 priority_paths，hard-cap 裁剪）");
          }
        }
        scoped = filtered;
      }
    }
    return new Plan(groupByModule(scoped), skipped, notes);
  }

  private static List<Chunk> groupByModule(List<DiffFile> files) {
    Map<String, List<DiffFile>> groups = new LinkedHashMap<>();
    for (DiffFile file : files) {
      groups.computeIfAbsent(moduleKey(file.path()), k -> new ArrayList<>()).add(file);
    }
    List<Chunk> chunks = new ArrayList<>();
    for (Map.Entry<String, List<DiffFile>> entry : groups.entrySet()) {
      List<DiffFile> group = entry.getValue();
      for (int from = 0; from < group.size(); from += MAX_FILES_PER_CHUNK) {
        chunks.add(
            new Chunk(
                entry.getKey(),
                group.subList(from, Math.min(group.size(), from + MAX_FILES_PER_CHUNK))));
      }
    }
    return List.copyOf(chunks);
  }

  private static String moduleKey(String path) {
    String[] segments = path.split("/");
    if (segments.length <= 1) {
      return "(root)";
    }
    return segments.length == 2 ? segments[0] : segments[0] + "/" + segments[1];
  }
}
