package org.cra.common.semantic;

/**
 * 符号种类（语义索引第一版：类型 / 方法 / 字段——ADR-0014）。
 *
 * <p>词法/结构抽取得到的定义粒度，不做类型绑定与跨语言泛化（Java-only，ADR-0014 局限声明）。
 */
public enum SymbolKind {
  /** 类型定义：class / interface / enum / record / @interface。 */
  TYPE,
  /** 方法定义（含构造器；record 隐式访问器不在索引内）。 */
  METHOD,
  /** 字段定义（含 enum 常量；record 组件与局部变量不在索引内）。 */
  FIELD
}
