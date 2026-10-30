package org.cra.eval.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.cra.common.exception.CraConfigException;
import org.cra.eval.api.EvalCli;
import org.junit.jupiter.api.Test;

/** 参数校验边界（05§3.1）：参数故障退出码 2，不得静默接受无效组合。 */
class ArgGuardsTest {

  @Test
  void gateRejectsSha256OutWithoutJsonOut() {
    int code =
        EvalCli.execute(
            new String[] {"gate", "--report", "missing.json", "--sha256-out", "h.txt"}, Map.of());
    assertThat(code).isEqualTo(2);
  }

  @Test
  void sinceOverflowIsConfigErrorNotNumberFormatException() {
    assertThatThrownBy(() -> ReportCommand.DurationSpec.parse("99999999999999999999d"))
        .isInstanceOf(CraConfigException.class);
    assertThatThrownBy(() -> ReportCommand.DurationSpec.parse("9223372036854775807w"))
        .isInstanceOf(CraConfigException.class);
  }
}
