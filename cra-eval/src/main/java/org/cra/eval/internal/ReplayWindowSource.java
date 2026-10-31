package org.cra.eval.internal;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.cra.common.exception.CraConfigException;
import org.cra.common.exception.CraIntegrationException;
import org.cra.common.git.CommentLedger;
import org.cra.common.git.DispositionSignal;
import org.cra.common.git.LedgerComment;
import org.cra.common.git.LedgerFindingMarker;
import org.cra.common.git.LedgerRunMarker;
import org.cra.common.git.PullRequestRef;
import org.cra.common.git.RepoRef;

/**
 * 离线账本回放源（测试与脱敏样例）：读 fixture 目录下的 {@code prs/*.json}，产出与 {@link GitHubWindowSource}
 * 完全同形的取数结果。不做任何网络访问，也不假装"数据来自 GitHub"——报告 data_source 标为 {@code ledger-fixture}。
 *
 * <p>fixture 形态见 {@code cra-eval/src/test/resources/report/README.md}。
 */
final class ReplayWindowSource implements ReportWindowSource {

  private final Path fixtureDir;

  ReplayWindowSource(Path fixtureDir) {
    this.fixtureDir = fixtureDir;
  }

  @Override
  public String label() {
    return "ledger-fixture";
  }

  @Override
  public WindowData.Window load(RepoRef repo, Instant since) {
    Path prsDir = fixtureDir.resolve("prs");
    if (!Files.isDirectory(prsDir)) {
      throw new CraConfigException("账本回放目录缺少 prs/: " + prsDir);
    }
    List<String> notes = new ArrayList<>();
    JsonNode meta = readJsonOrNull(fixtureDir.resolve("window.json"));
    if (meta != null) {
      meta.path("notes").forEach(n -> notes.add(n.asText()));
      if (meta.hasNonNull("repo")
          && !meta.path("repo").asText().equals(repo.owner() + "/" + repo.repo())) {
        notes.add(
            "回放 fixture 的 repo="
                + meta.path("repo").asText()
                + " 与 --repo 不一致（以 --repo 为准，fixture 仅作数据面）");
      }
    }
    List<Path> files;
    try (Stream<Path> walk = Files.list(prsDir)) {
      files = walk.filter(p -> p.toString().endsWith(".json")).sorted().toList();
    } catch (IOException e) {
      throw new CraIntegrationException("账本回放目录读取失败: " + prsDir, e);
    }
    List<WindowData.PrWindow> prs = new ArrayList<>();
    for (Path file : files) {
      JsonNode root = readJson(file);
      PullRequestRef pull =
          new PullRequestRef(
              root.path("number").asInt(),
              root.path("head_sha").asText(""),
              textOrNull(root, "merge_commit_sha"),
              root.path("state").asText(PullRequestRef.STATE_CLOSED),
              Instant.parse(root.path("updated_at").asText()),
              root.hasNonNull("merged_at") ? Instant.parse(root.path("merged_at").asText()) : null);
      if (pull.updatedAt().isBefore(since)) {
        continue;
      }
      prs.add(new WindowData.PrWindow(pull, ledger(root), signals(root), touches(root)));
    }
    return new WindowData.Window(repo, label(), prs, List.of(), List.of(), notes);
  }

  private static CommentLedger ledger(JsonNode root) {
    List<LedgerComment> comments =
        StreamSupport.stream(root.path("comments").spliterator(), false)
            .map(
                c ->
                    new LedgerComment(
                        c.path("id").asLong(),
                        c.path("kind").asText(LedgerComment.KIND_INLINE),
                        c.path("author").asText(""),
                        c.path("body").asText(""),
                        c.hasNonNull("created_at")
                            ? Instant.parse(c.path("created_at").asText())
                            : Instant.EPOCH,
                        textOrNull(c, "commit_id")))
            .toList();
    List<LedgerFindingMarker> findings =
        StreamSupport.stream(root.path("finding_markers").spliterator(), false)
            .map(
                m ->
                    new LedgerFindingMarker(
                        m.path("fingerprint").asText(),
                        m.path("file").asText(),
                        m.path("line").asInt(),
                        m.path("category").asText(),
                        m.path("finding_id").asText(),
                        m.path("run_id").asText(),
                        m.path("commit_sha").asText(),
                        m.path("comment_id").asLong()))
            .toList();
    List<LedgerRunMarker> runs =
        StreamSupport.stream(root.path("run_markers").spliterator(), false)
            .map(
                m ->
                    new LedgerRunMarker(
                        m.path("run_id").asText(),
                        m.path("commit_sha").asText(),
                        m.path("status").asText(LedgerRunMarker.STATUS_DONE),
                        m.path("findings").asInt(),
                        m.path("suppressed").asInt(),
                        m.path("comment_id").asLong()))
            .toList();
    return new CommentLedger(comments, findings, runs, root.path("suspect_count").asInt(0));
  }

  private static List<DispositionSignal> signals(JsonNode root) {
    return StreamSupport.stream(root.path("signals").spliterator(), false)
        .map(
            s ->
                new DispositionSignal(
                    s.path("kind").asText(),
                    s.path("comment_id").asLong(),
                    Instant.parse(s.path("occurred_at").asText()),
                    s.path("detail").asText(""),
                    s.path("actor").asText(""),
                    s.path("excerpt").asText("")))
        .toList();
  }

  private static Map<String, WindowData.TouchFacts> touches(JsonNode root) {
    Map<String, Set<String>> files = new HashMap<>();
    Map<String, Set<WindowData.LineKey>> lines = new HashMap<>();
    for (JsonNode t : root.path("touches")) {
      String commit = t.path("commit").asText();
      if (t.path("whole_file").asBoolean(false)) {
        files.computeIfAbsent(commit, k -> new HashSet<>()).add(t.path("file").asText());
      } else {
        lines
            .computeIfAbsent(commit, k -> new HashSet<>())
            .add(new WindowData.LineKey(t.path("file").asText(), t.path("line").asInt()));
      }
    }
    Map<String, WindowData.TouchFacts> out = new HashMap<>();
    Set<String> commits = new HashSet<>(files.keySet());
    commits.addAll(lines.keySet());
    for (String commit : commits) {
      out.put(
          commit,
          new WindowData.TouchFacts(
              files.getOrDefault(commit, Set.of()), lines.getOrDefault(commit, Set.of())));
    }
    return out;
  }

  private static String textOrNull(JsonNode node, String field) {
    return node.hasNonNull(field) && !node.path(field).asText().isBlank()
        ? node.path(field).asText()
        : null;
  }

  private static JsonNode readJson(Path file) {
    try {
      return Json.MAPPER.readTree(file.toFile());
    } catch (IOException e) {
      throw new CraConfigException("回放 fixture 解析失败: " + file + "（" + e.getMessage() + "）");
    }
  }

  private static JsonNode readJsonOrNull(Path file) {
    return Files.isRegularFile(file) ? readJson(file) : null;
  }
}
