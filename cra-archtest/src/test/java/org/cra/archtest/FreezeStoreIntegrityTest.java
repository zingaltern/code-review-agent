package org.cra.archtest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * 10§7 第 2/3 条伴生测试：freeze store 是纯文本，每条被冻结违规必须手工注记 {@code # {issue: <GitHub issue URL>, expires:
 * <YYYY-MM-DD>}}——无 issue 或无到期日的行直接红； 到期日 ≤ 当天且违规未消除 → 红（30 天复审）。store 文件不存在 = 当前无豁免，测试通过。
 */
class FreezeStoreIntegrityTest {

  private static final Pattern ISSUE_LINE =
      Pattern.compile(
          "\\{issue:\\s*(?<issue>https://\\S+),\\s*expires:\\s*(?<date>\\d{4}-\\d{2}-\\d{2})\\}");

  @Test
  void everyFrozenViolationHasIssueAndUnexpiredDate() throws IOException {
    Path store =
        Path.of(System.getProperty("user.dir")).resolve("src/test/resources/archunit-freeze-store");
    if (!Files.isRegularFile(store)) {
      return; // 无豁免登记，无事可查
    }
    List<String> lines = Files.readAllLines(store, StandardCharsets.UTF_8);
    for (int i = 0; i < lines.size(); i++) {
      String line = lines.get(i);
      if (line.isBlank() || line.strip().startsWith("#")) {
        continue;
      }
      Matcher m = ISSUE_LINE.matcher(line);
      if (!m.find()) {
        throw new AssertionError(
            String.format(
                "freeze store 第 %d 行缺 {issue: …, expires: …} 注记（10§7 第 2 条）：%s", i + 1, line));
      }
      LocalDate expires;
      try {
        expires = LocalDate.parse(m.group("date"));
      } catch (DateTimeParseException e) {
        throw new AssertionError(String.format("freeze store 第 %d 行 expires 日期非法：%s", i + 1, line));
      }
      if (!expires.isAfter(LocalDate.now())) {
        throw new AssertionError(
            String.format(
                "freeze store 第 %d 行豁免已到期（%s），违规未消除即红（10§7 第 3 条 30 天复审）：%s",
                i + 1, expires, line));
      }
    }
  }
}
