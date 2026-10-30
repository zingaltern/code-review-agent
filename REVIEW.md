# REVIEW.md — 本仓库的评审策略（04§3.1）

> 本文件由评审流水线从 base 分支读取并解析；`## ` 二级标题为策略段，段内 `- ` 列表项为条目。

## Skip paths

- src/test/**
- **/src/test/**
- **/*Test.java
- **/*Tests.java

## Conventions

- 变更文件为测试代码（`src/test/**`、`*Test.java`、`*Tests.java`）时不在评审范围内：单测正确性由 `mvn verify` 与测试自身保障，评审只为生产代码与文档投入 token（owner 裁决 2026-10-30）。
- 生产代码"缺少测试覆盖"类结论仍按正常口径评审（该主张指向生产文件，不受 Skip paths 影响）。
