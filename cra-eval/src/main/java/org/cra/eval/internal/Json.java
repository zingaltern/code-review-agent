package org.cra.eval.internal;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Path;
import org.cra.common.exception.CraConfigException;
import org.cra.common.exception.CraIntegrationException;
import org.cra.eval.api.ExpectedFindings;
import org.cra.eval.api.FixtureMeta;

/** Jackson 持有与 JSON 读写（cra-eval 内共享； Jackson 为既有白名单依赖，经 cra-git-github 传递引入）。 */
public final class Json {

  /** 共享 mapper：fail on unknown 关闭（11§2 演进向前兼容），输出 pretty。 */
  public static final ObjectMapper MAPPER =
      new ObjectMapper()
          .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
          .enable(SerializationFeature.INDENT_OUTPUT);

  private Json() {}

  /**
   * 读 meta.json。
   *
   * @param file meta.json 路径
   * @return 元数据
   */
  public static FixtureMeta readMeta(Path file) {
    return read(file, FixtureMeta.class, "meta.json");
  }

  /**
   * 读 expected_findings.json。
   *
   * @param file 路径
   * @return 断言规格
   */
  public static ExpectedFindings readExpected(Path file) {
    return read(file, ExpectedFindings.class, "expected_findings.json");
  }

  /**
   * 通用读文件。
   *
   * @param file 路径
   * @param type 目标类型
   * @param what 出错提示用名
   * @param <T> 目标类型
   * @return 反序列化结果
   */
  public static <T> T read(Path file, Class<T> type, String what) {
    try {
      return MAPPER.readValue(file.toFile(), type);
    } catch (IOException e) {
      throw new CraConfigException(what + " 解析失败: " + file + "（" + e.getMessage() + "）");
    }
  }

  /**
   * 写 JSON 文件。
   *
   * @param file 目标路径
   * @param value 值
   */
  public static void write(Path file, Object value) {
    try {
      MAPPER.writeValue(file.toFile(), value);
    } catch (IOException e) {
      throw new CraIntegrationException("JSON 写入失败: " + file, e);
    }
  }

  /**
   * 序列化为紧凑字符串。
   *
   * @param value 值
   * @return JSON 文本
   */
  public static String toCompactString(Object value) {
    try {
      return new ObjectMapper().writeValueAsString(value);
    } catch (IOException e) {
      throw new CraIntegrationException("JSON 序列化失败", e);
    }
  }
}
