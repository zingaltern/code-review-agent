package org.cra.eval.internal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.cra.common.exception.CraConfigException;
import org.cra.common.exception.CraIntegrationException;
import org.cra.common.git.FileContent;
import org.cra.common.git.RepoRef;
import org.cra.git.api.GitPlatformAdapter;

/**
 * badcase 用例快照（T3.5）：从远端 head 树（tree API + contents raw）或本地目录生成 {@code repo_snapshot.tgz}，sha256 回填
 * meta（与 {@link SnapshotStore} 校验口径一致）。
 *
 * <p>远端模式下二进制文件无法经 FileContent 取得原始字节（adapter 契约只有 text/binary），跳过并在结果中计数、 meta.note 声明——不伪造内容。
 */
final class SnapshotBuilder {

  private SnapshotBuilder() {}

  /** 生成结果。 */
  record Result(Path tgz, String sha256, int files, int skippedBinary) {}

  /**
   * 从远端 head 树生成快照。
   *
   * @param caseDir 用例目录
   * @param repo 仓库
   * @param headSha head
   * @param adapter 平台适配器
   * @return 结果
   */
  static Result fromRemote(Path caseDir, RepoRef repo, String headSha, GitPlatformAdapter adapter) {
    List<String> paths = adapter.listFiles(repo, headSha, "**");
    Map<String, byte[]> files = new TreeMap<>();
    int skippedBinary = 0;
    for (String path : paths) {
      FileContent content = adapter.readFile(repo, headSha, path);
      if (content.binary() || content.text() == null) {
        skippedBinary++;
        continue;
      }
      files.put(path, content.text().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    if (files.isEmpty()) {
      throw new CraConfigException("远端 head 树无文本文件，拒绝生成空快照: " + repo.owner() + "/" + repo.repo());
    }
    Path tgz = caseDir.resolve("repo_snapshot.tgz");
    int written = TarWriter.writeTgz(tgz, files);
    return new Result(tgz, SnapshotStore.sha256(tgz), written, skippedBinary);
  }

  /**
   * 从本地目录树生成快照（离线/测试路径）。
   *
   * @param caseDir 用例目录
   * @param tree 源目录
   * @return 结果
   */
  static Result fromLocalDir(Path caseDir, Path tree) {
    if (!Files.isDirectory(tree)) {
      throw new CraConfigException("--snapshot-dir 不存在: " + tree);
    }
    Map<String, byte[]> files = new TreeMap<>();
    try (Stream<Path> walk = Files.walk(tree)) {
      List<Path> regular =
          walk.filter(Files::isRegularFile)
              .filter(p -> !ignored(tree.relativize(p).toString().replace('\\', '/')))
              .sorted()
              .toList();
      for (Path file : regular) {
        String rel = tree.relativize(file).toString().replace('\\', '/');
        files.put(rel, Files.readAllBytes(file));
      }
    } catch (IOException e) {
      throw new CraIntegrationException("本地快照目录读取失败: " + tree, e);
    }
    if (files.isEmpty()) {
      throw new CraConfigException("--snapshot-dir 无文件，拒绝生成空快照: " + tree);
    }
    Path tgz = caseDir.resolve("repo_snapshot.tgz");
    int written = TarWriter.writeTgz(tgz, files);
    return new Result(tgz, SnapshotStore.sha256(tgz), written, 0);
  }

  private static boolean ignored(String rel) {
    return rel.startsWith(".git/")
        || rel.equals(".git")
        || rel.equals(".DS_Store")
        || rel.startsWith("__MACOSX/")
        || rel.startsWith("target/")
        || rel.contains("/target/");
  }
}
