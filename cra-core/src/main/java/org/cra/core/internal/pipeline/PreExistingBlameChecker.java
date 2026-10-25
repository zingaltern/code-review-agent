package org.cra.core.internal.pipeline;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.cra.common.finding.CandidateFinding;
import org.cra.common.git.BlameResult;
import org.cra.git.api.LocalRepoContext;

/**
 * pre-existing 嫌疑的 jgit blame 复核（07 T1.7，05§2：GitHub 无 blame API，走本地仓库）。 锚行最后修改 commit 在本 PR commit
 * 集内 → INTRODUCED_IN_PR（嫌疑不成立）；不在 → CONFIRMED_PRE_EXISTING（Summary Pre-existing 分组，不计 nit 预算）。
 * 无本地仓库（localRepoRoot 为空）或单条 blame 失败 → UNKNOWN：不标注，Summary 如实声明（05§2.1 降级语义，不假装有该能力）。
 */
public final class PreExistingBlameChecker {

  /** 单条嫌疑的复核结论。 */
  public enum Verdict {
    /** blame 落在 PR commit 之外：变更前已存在，确证 */
    CONFIRMED_PRE_EXISTING,
    /** blame 落在 PR commit 之内：本 PR 触碰该行，嫌疑不成立 */
    INTRODUCED_IN_PR,
    /** 复核不可得：不标注 */
    UNKNOWN
  }

  /**
   * 复核结果。
   *
   * @param verdicts finding id → 结论（仅含 pre_existing_suspect 条目）
   * @param notes 降级声明（无本地仓库/blame 失败计数）
   */
  public record Outcome(Map<String, Verdict> verdicts, List<String> notes) {

    public Outcome {
      verdicts = verdicts == null ? Map.of() : Map.copyOf(verdicts);
      notes = List.copyOf(notes == null ? List.of() : notes);
    }

    /**
     * 查询结论（非嫌疑条目默认 UNKNOWN）。
     *
     * @param findingId finding 标识
     * @return 结论
     */
    public Verdict verdictOf(String findingId) {
      return verdicts.getOrDefault(findingId, Verdict.UNKNOWN);
    }
  }

  private final LocalRepoContext localRepo;

  /**
   * @param localRepo 本地仓库上下文（null = 纯 API 形态，全部降级 UNKNOWN）
   */
  public PreExistingBlameChecker(LocalRepoContext localRepo) {
    this.localRepo = localRepo;
  }

  /**
   * 复核全部 pre_existing_suspect 条目。
   *
   * @param findings 候选（去重后）
   * @param prCommitShas 本 PR 变更集内 commit sha 全集
   * @param headSha 评审 head
   * @return 复核结果
   */
  public Outcome check(List<CandidateFinding> findings, Set<String> prCommitShas, String headSha) {
    Set<String> shas = prCommitShas == null ? Set.of() : new LinkedHashSet<>(prCommitShas);
    List<CandidateFinding> suspects =
        findings.stream().filter(CandidateFinding::preExistingSuspect).toList();
    Map<String, Verdict> verdicts = new LinkedHashMap<>();
    if (suspects.isEmpty()) {
      return new Outcome(verdicts, List.of());
    }
    if (localRepo == null || localRepo.localRepoRoot().isEmpty()) {
      suspects.forEach(f -> verdicts.put(f.id(), Verdict.UNKNOWN));
      return new Outcome(
          verdicts, List.of("pre-existing 复核不可得：无本地仓库，" + suspects.size() + " 条嫌疑未标注（05§2.1）"));
    }
    int failed = 0;
    for (CandidateFinding suspect : suspects) {
      try {
        BlameResult blame = localRepo.blame(suspect.file(), suspect.line(), headSha);
        verdicts.put(
            suspect.id(),
            shas.contains(blame.commitSha())
                ? Verdict.INTRODUCED_IN_PR
                : Verdict.CONFIRMED_PRE_EXISTING);
      } catch (RuntimeException e) {
        // 边界内降级：单条 blame 失败转状态，不炸穿 run（09§4）
        verdicts.put(suspect.id(), Verdict.UNKNOWN);
        failed++;
      }
    }
    List<String> notes =
        failed == 0
            ? List.of()
            : List.of("pre-existing 复核 blame 失败 " + failed + " 条，按 UNKNOWN 不标注");
    return new Outcome(verdicts, notes);
  }
}
