package org.cra.eval.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.zip.GZIPInputStream;
import org.cra.common.exception.CraConfigException;
import org.cra.common.exception.CraIntegrationException;
import org.cra.eval.api.FixtureMeta;

/**
 * 快照缓存与解包（06§3）：repo_snapshot.tgz **不入 git**（体积与许可），落本地缓存目录（.gitignore）；以 meta.snapshot.sha256
 * 保证缓存一致性。快照缺失时给出再生成指引，不静默降级。
 */
public final class SnapshotStore {

  private SnapshotStore() {}

  /**
   * 解包快照到缓存目录（幂等：同 sha256 复用）。
   *
   * @param caseDir 用例目录（含 tgz）
   * @param meta 用例元数据
   * @param cacheDir 缓存根目录
   * @return 解包后的快照根目录（即解包 target 本身，tar 内无额外一层前缀时亦成立）
   */
  public static Path unpack(Path caseDir, FixtureMeta meta, Path cacheDir) {
    Path tgz = caseDir.resolve(meta.snapshot().file());
    if (!Files.isRegularFile(tgz)) {
      throw new CraConfigException(
          "快照缺失: " + tgz + " —— repo_snapshot.tgz 不入 git，需先由生成工具再生成（见 cra-eval/dataset/README.md）");
    }
    String actualSha = sha256(tgz);
    if (meta.snapshot().sha256() != null && !meta.snapshot().sha256().equalsIgnoreCase(actualSha)) {
      throw new CraConfigException(
          "快照 sha256 不一致: meta="
              + meta.snapshot().sha256()
              + " actual="
              + actualSha
              + "（快照与 meta 不匹配，请再生成）");
    }
    Path snapshotsRoot = cacheDir.resolve("snapshots").toAbsolutePath().normalize();
    Path target = snapshotsRoot.resolve(meta.caseId()).normalize();
    if (!target.startsWith(snapshotsRoot) || target.equals(snapshotsRoot)) {
      // meta.json 来自数据集（外部输入）：case_id 必须落在缓存根内，否则 deleteRecursively 会删到树外
      throw new CraConfigException("case_id 非法（路径逃逸缓存目录）: " + meta.caseId());
    }
    Path marker = target.resolve(".cra-eval-snapshot-sha256");
    if (Files.isDirectory(target) && isMarker(marker, actualSha)) {
      return target;
    }
    try {
      if (Files.exists(target)) {
        deleteRecursively(target);
      }
      Files.createDirectories(target);
      TarReader.Result result;
      try (InputStream in = new GZIPInputStream(Files.newInputStream(tgz))) {
        result = TarReader.untar(in, target);
      }
      if (result.files() == 0) {
        throw new CraConfigException("快照为空（tar 内无常规文件，可能生成失败或被截断）: " + tgz);
      }
      Files.writeString(marker, actualSha, StandardCharsets.UTF_8);
      return target;
    } catch (IOException e) {
      throw new CraIntegrationException("快照解包失败: " + tgz, e);
    }
  }

  private static boolean isMarker(Path marker, String sha) {
    try {
      return sha.equalsIgnoreCase(Files.readString(marker, StandardCharsets.UTF_8).trim());
    } catch (IOException e) {
      return false;
    }
  }

  /**
   * 计算文件 sha256（十六进制小写）。
   *
   * @param file 目标文件
   * @return sha256
   */
  public static String sha256(Path file) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (InputStream in = Files.newInputStream(file)) {
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) {
          digest.update(buf, 0, n);
        }
      }
      StringBuilder sb = new StringBuilder();
      for (byte b : digest.digest()) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException | IOException e) {
      throw new CraIntegrationException("sha256 计算失败: " + file, e);
    }
  }

  private static void deleteRecursively(Path root) throws IOException {
    if (!Files.exists(root)) {
      return;
    }
    try (var walk = Files.walk(root)) {
      walk.sorted(java.util.Comparator.reverseOrder())
          .forEach(
              p -> {
                try {
                  Files.delete(p);
                } catch (IOException e) {
                  throw new CraIntegrationException("缓存清理失败: " + p, e);
                }
              });
    }
  }
}
