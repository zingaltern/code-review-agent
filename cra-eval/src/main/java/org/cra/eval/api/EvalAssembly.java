package org.cra.eval.api;

/** cra-eval 装配工厂（10§4 模式：组合入口只触达 api 工厂；当前无内部装配参数，占位对齐模块结构模板）。 */
public final class EvalAssembly {

  private EvalAssembly() {}

  /** 数据集格式版本（11§2 演进扩展点读取入口）。 */
  public static int datasetFormatVersion() {
    return FixtureMeta.FORMAT_VERSION;
  }
}
