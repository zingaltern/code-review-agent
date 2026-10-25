package org.cra.eval.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.cra.common.exception.CraConfigException;
import org.cra.common.exception.CraIntegrationException;
import org.cra.eval.api.ExpectedFindings;
import org.cra.eval.api.FixtureMeta;

/**
 * 数据集加载器（06§3 / 11§2）：读 meta.json + expected_findings.json + diff.patch，解包快照。 构造 ReviewRequest
 * 所需字段随 {@link LoadedCase} 交给 runner——驱动流水线走 CLI 黑盒（10§2）。
 */
public final class DatasetLoader {

  private DatasetLoader() {}

  /** 加载完成的用例。 */
  public record LoadedCase(
      Path caseDir,
      FixtureMeta meta,
      ExpectedFindings expected,
      String diffPatch,
      Path snapshotRoot) {

    /** 缓存目录（解包快照所在）。 */
    public Path cacheDir() {
      return snapshotRoot.getParent().getParent();
    }
  }

  /**
   * 加载单个用例。
   *
   * @param caseDir 用例目录
   * @param cacheDir 缓存根目录（快照解包处）
   * @return 加载结果
   */
  public static LoadedCase load(Path caseDir, Path cacheDir) {
    if (!Files.isDirectory(caseDir)) {
      throw new CraConfigException("用例目录不存在: " + caseDir);
    }
    FixtureMeta meta = Json.readMeta(caseDir.resolve("meta.json"));
    ExpectedFindings expected = Json.readExpected(caseDir.resolve("expected_findings.json"));
    Path snapshotRoot = SnapshotStore.unpack(caseDir, meta, cacheDir);
    String diff;
    try {
      diff = Files.readString(caseDir.resolve("diff.patch"), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new CraIntegrationException("diff.patch 读取失败: " + caseDir, e);
    }
    if (diff.isBlank()) {
      throw new CraConfigException("diff.patch 为空: " + caseDir);
    }
    return new LoadedCase(caseDir, meta, expected, diff, snapshotRoot);
  }

  /**
   * 枚举数据集下全部用例目录（含 meta.json 的目录，按目录名排序）。
   *
   * @param datasetRoot 数据集根（其下按 set 分目录）
   * @param setFilter 集合过滤（null = 全部；支持 bad/clean/adversarial 或对抗子类 adv-injection 等）
   * @return 用例目录列表
   */
  public static List<Path> listCases(Path datasetRoot, String setFilter) {
    if (!Files.isDirectory(datasetRoot)) {
      throw new CraConfigException("数据集目录不存在: " + datasetRoot);
    }
    try (var walk = Files.walk(datasetRoot, 3)) {
      return walk.filter(p -> !p.equals(datasetRoot))
          .filter(p -> Files.isRegularFile(p.resolve("meta.json")))
          .filter(p -> matchesFilter(p, setFilter))
          .sorted()
          .toList();
    } catch (IOException e) {
      throw new CraIntegrationException("数据集枚举失败: " + datasetRoot, e);
    }
  }

  private static boolean matchesFilter(Path caseDir, String setFilter) {
    if (setFilter == null || "all".equals(setFilter)) {
      return true;
    }
    FixtureMeta meta = Json.readMeta(caseDir.resolve("meta.json"));
    if ("bad".equals(setFilter) || "clean".equals(setFilter) || "adversarial".equals(setFilter)) {
      return setFilter.equals(meta.set());
    }
    if (setFilter.startsWith("adv-")) {
      return FixtureMeta.SET_ADVERSARIAL.equals(meta.set())
          && setFilter.substring(4).equals(meta.adversarialSubtype());
    }
    throw new CraConfigException(
        "非法 --set: "
            + setFilter
            + "（all|bad|clean|adversarial|adv-injection|adv-hallucination|adv-refusal）");
  }
}
