package org.cra.core.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.cra.agents.api.AgentBrief;
import org.cra.agents.api.AgentBudget;
import org.cra.agents.api.AgentRole;
import org.cra.agents.api.SubAgent;
import org.cra.agents.api.ToolWhitelist;
import org.cra.agents.api.VerificationBatchInput;
import org.cra.agents.api.VerifierAgentDefinition;
import org.cra.agents.internal.TemplateLoader;
import org.cra.common.context.ReviewPolicy;
import org.cra.common.finding.AnchorStatus;
import org.cra.common.finding.CandidateFinding;
import org.cra.common.finding.FindingCategory;
import org.cra.common.finding.Severity;
import org.cra.common.finding.Side;
import org.cra.common.git.DiffFile;
import org.cra.common.git.DiffResult;
import org.cra.core.api.PipelineOptions;
import org.cra.core.internal.pipeline.FindingAggregator;
import org.cra.core.internal.pipeline.PreExistingBlameChecker;
import org.cra.core.internal.pipeline.RunBudgetTracker;
import org.cra.core.internal.pipeline.SemanticContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 验证波批次计数（评审 #5/#15）：每个已提交批次恰计一次，成功/超时/失败同口径。 */
class VerificationWaveTest {

  @TempDir Path runRoot;

  @Test
  void countsEachSuccessfulBatchOnce() {
    ExecutorService pool = Executors.newFixedThreadPool(4);
    try {
      VerificationWave wave =
          new VerificationWave(
              new VerifierAgentDefinition(new TemplateLoader()),
              new org.cra.core.internal.AgentRunner(new ScriptedGateway()),
              new TraceWriter(runRoot, "run_batches_ok"),
              new RunBudgetTracker(PipelineOptions.defaults()),
              pool,
              path -> Optional.empty(),
              new CitedEvidenceResolver(null),
              null,
              SemanticContext.disabled(),
              5_000L);
      List<FindingAggregator.Scored> candidates = new ArrayList<>();
      for (int i = 0; i < 3; i++) {
        candidates.add(candidate("f_000" + i, "src/F" + i + ".java"));
      }
      VerificationWave.WaveResult result =
          wave.verify(
              candidates,
              ReviewPolicy.empty(),
              Map.of(),
              diff("src/F0.java", "src/F1.java", "src/F2.java"),
              "run_batches_ok");
      assertThat(result.outcomes()).hasSize(3);
      assertThat(result.batches()).as("3 个成功批次不得双计").isEqualTo(3);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void countsTimedOutAndFailedBatches() {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      VerificationWave wave =
          new VerificationWave(
              brokenVerifier(),
              new org.cra.core.internal.AgentRunner(new ScriptedGateway()),
              new TraceWriter(runRoot, "run_batches_bad"),
              new RunBudgetTracker(PipelineOptions.defaults()),
              pool,
              path -> Optional.empty(),
              new CitedEvidenceResolver(null),
              null,
              SemanticContext.disabled(),
              200L);
      List<FindingAggregator.Scored> candidates =
          List.of(candidate("f_0100", "src/Fast.java"), candidate("f_0101", "src/Slow.java"));
      VerificationWave.WaveResult result =
          wave.verify(
              candidates,
              ReviewPolicy.empty(),
              Map.of(),
              diff("src/Fast.java", "src/Slow.java"),
              "run_batches_bad");
      assertThat(result.batches()).as("超时/失败批同样计入").isEqualTo(2);
      assertThat(result.degradations())
          .anyMatch(d -> d.startsWith("verifier-batch-failed"))
          .contains("verifier-timeout");
    } finally {
      pool.shutdownNow();
    }
  }

  /** 快文件抛异常（ExecutionException）、慢文件阻塞到超时。 */
  private static SubAgent<VerificationBatchInput> brokenVerifier() {
    AgentRole role = new AgentRole("verifier", "verifier");
    AgentBudget budget = new AgentBudget(12_000, 1_500, 2, 240_000);
    return new SubAgent<>() {
      @Override
      public AgentRole role() {
        return role;
      }

      @Override
      public AgentBrief buildBrief(VerificationBatchInput input) {
        if (input.file().contains("Slow")) {
          try {
            Thread.sleep(60_000);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
          }
        }
        throw new IllegalStateException("boom");
      }

      @Override
      public ToolWhitelist tools() {
        return ToolWhitelist.NONE;
      }

      @Override
      public AgentBudget budget() {
        return budget;
      }
    };
  }

  private static FindingAggregator.Scored candidate(String id, String file) {
    CandidateFinding finding =
        new CandidateFinding(
            id,
            "run_wave",
            FindingCategory.BUG,
            "t",
            "d",
            "f",
            file,
            1,
            Side.RIGHT,
            "e",
            null,
            Severity.IMPORTANT,
            false,
            0.5,
            "bug-hunter/dataflow",
            "m");
    return new FindingAggregator.Scored(
        finding, "fp-" + id, AnchorStatus.INLINE, PreExistingBlameChecker.Verdict.UNKNOWN);
  }

  private static DiffResult diff(String... files) {
    List<DiffFile> diffs = new ArrayList<>();
    for (String file : files) {
      diffs.add(new DiffFile(file, null, "modified", "@@ -1 +1 @@\n+x\n", List.of()));
    }
    return new DiffResult("base", "head", diffs, List.of());
  }
}
