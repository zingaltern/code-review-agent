package org.cra.core.internal.pipeline;

import java.util.ArrayList;
import java.util.List;
import org.cra.common.run.Depth;
import org.cra.core.api.PipelineOptions;

/**
 * 深度档位解析（02§4.3 档位表 + 04§2.2 大 PR 降档；T1.5/T1.6）。M1 reviewer 仍单角色—— 深度参数化落点为窗口大小、条件全文与切片开关；大 diff
 * 降档与 hard-cap 裁剪叠加在任何档位之上并在覆盖声明中如实说明。
 */
public final class DepthPolicy {

  private DepthPolicy() {}

  /**
   * 解析生效档位。
   *
   * @param requested 请求档位
   * @param totalPatchLines 变更 diff 总行数
   * @param options 编排参数
   * @return 深度计划
   */
  public static DepthPlan resolve(Depth requested, int totalPatchLines, PipelineOptions options) {
    List<String> notes = new ArrayList<>();
    Depth effective = requested;
    int window = 30;
    boolean fullText = true;
    int fullFileMax = 200;
    switch (requested) {
      case QUICK -> fullText = false;
      case STANDARD -> {
        if (totalPatchLines < 300) {
          effective = Depth.QUICK;
          fullText = false;
          notes.add("变更 <300 行，默认小 diff 自动 quick 档（02§4.3）");
        }
      }
      case DEEP -> {
        window = 60;
        fullFileMax = 400;
      }
    }
    if (totalPatchLines > options.largeDiffLines()) {
      window = 15;
      fullText = false;
      notes.add(
          "大 diff（%d 行 > %d）降为 Quick 质量：窗口 ±15、不注入全文（02§4.4）"
              .formatted(totalPatchLines, options.largeDiffLines()));
    }
    boolean priorityOnly = false;
    int perspectiveDrop = 0;
    if (totalPatchLines > options.largeDiffLines()) {
      perspectiveDrop = 1;
      notes.add("大 diff 降档：视角降 1（04§2.2）");
    }
    if (totalPatchLines > options.hardCapLines()) {
      priorityOnly = true;
      notes.add(
          "超大 diff（%d 行 > %d）仅评审 priority_paths（02§4.4）"
              .formatted(totalPatchLines, options.hardCapLines()));
    }
    return new DepthPlan(
        effective, window, fullText, fullFileMax, priorityOnly, notes, perspectiveDrop);
  }
}
