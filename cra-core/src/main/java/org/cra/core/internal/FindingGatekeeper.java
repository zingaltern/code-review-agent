package org.cra.core.internal;

import static org.cra.core.internal.TraceEvents.ANCHOR_CHECK;
import static org.cra.core.internal.TraceEvents.BLAME_CHECK;
import static org.cra.core.internal.TraceEvents.DEDUP;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.cra.common.finding.AnchorStatus;
import org.cra.common.finding.CandidateFinding;
import org.cra.common.git.CommitSummary;
import org.cra.common.git.DiffResult;
import org.cra.common.git.LedgerFindingMarker;
import org.cra.common.run.ReviewRequest;
import org.cra.core.internal.pipeline.FindingAggregator;
import org.cra.core.internal.pipeline.PreExistingBlameChecker;
import org.cra.git.api.LocalRepoContext;

/**
 * 候选门禁（02§4.1/05§2.2）：指纹去重（exact → ±5 滑窗 → possible_duplicate）→ 位置锚定 → pre-existing blame 复核。
 *
 * <p>从 {@link ReviewPipeline} 拆出：门禁只产出“可进入验证的候选 + 去重计数 + blame 结论”，不参与晋升与发布。
 */
final class FindingGatekeeper {

  private final PipelineDataLoader dataLoader;
  private final LocalRepoContext localRepoContext;

  FindingGatekeeper(PipelineDataLoader dataLoader, LocalRepoContext localRepoContext) {
    this.dataLoader = dataLoader;
    this.localRepoContext = localRepoContext;
  }

  Outcome gate(
      ReviewRequest request,
      DiffResult diff,
      List<CandidateFinding> mergedFindings,
      List<LedgerFindingMarker> publishedMarkers,
      Map<String, Optional<String>> contentCache,
      TraceWriter trace) {
    List<FindingAggregator.Scored> candidates = new ArrayList<>();
    int skippedDuplicate = 0;
    for (CandidateFinding finding : mergedFindings) {
      List<String> lines =
          dataLoader
              .contentOf(finding.file(), request.headSha(), request, contentCache)
              .map(text -> List.of(text.split("\n", -1)))
              .orElse(List.of(""));
      FingerprintDeduper.Decision decision =
          FingerprintDeduper.evaluate(
              finding.file(),
              ReviewBodyRenderer.categoryOf(finding),
              finding.line(),
              lines,
              publishedMarkers,
              marker -> dataLoader.oldAnchorHashOf(marker, request, contentCache));
      String matchValue =
          switch (decision.match()) {
            case EXACT -> "exact";
            case SLIDING_WINDOW -> "sliding-window";
            case NONE -> decision.possibleDuplicate() ? "possible_duplicate" : "none";
          };
      trace.emit(DEDUP, Map.of("finding_id", finding.id(), "match", matchValue));
      if (decision.match() != FingerprintDeduper.Match.NONE) {
        skippedDuplicate++;
        continue;
      }
      AnchorStatus anchor = PipelineDataLoader.anchorStatus(diff, finding);
      trace.emit(ANCHOR_CHECK, Map.of("finding_id", finding.id(), "status", anchor.name()));
      candidates.add(
          new FindingAggregator.Scored(
              finding, decision.fingerprint(), anchor, PreExistingBlameChecker.Verdict.UNKNOWN));
    }

    // T1.7：pre-existing 嫌疑 jgit blame 复核（05§2；无本地仓库降级声明）
    PreExistingBlameChecker blameChecker = new PreExistingBlameChecker(localRepoContext);
    Set<String> prCommitShas =
        diff.commits().stream().map(CommitSummary::sha).collect(Collectors.toSet());
    PreExistingBlameChecker.Outcome blameOutcome =
        blameChecker.check(
            candidates.stream().map(FindingAggregator.Scored::finding).toList(),
            prCommitShas,
            request.headSha());
    blameOutcome.notes().forEach(n -> trace.emit(BLAME_CHECK, Map.of("note", n)));
    List<FindingAggregator.Scored> checked =
        candidates.stream()
            .map(
                s ->
                    new FindingAggregator.Scored(
                        s.finding(),
                        s.fingerprint(),
                        s.anchor(),
                        blameOutcome.verdictOf(s.finding().id())))
            .toList();
    return new Outcome(checked, skippedDuplicate, blameOutcome);
  }

  record Outcome(
      List<FindingAggregator.Scored> candidates,
      int skippedDuplicate,
      PreExistingBlameChecker.Outcome blameOutcome) {}
}
