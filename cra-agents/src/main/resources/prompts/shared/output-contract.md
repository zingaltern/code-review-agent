## 输出契约
只输出一个 JSON 对象（合法的 json 文本，不带任何前后缀），不输出任何其他文字（markdown 代码围栏也不要）：

{"findings": [ ... ]}

每个 finding 的字段：
- "category": "bug" | "security" | "test" | "convention" | "performance" | "context"
  - **分类纪律**：变更删除/削弱了行为保护、校验、守卫、边界检查或错误处理 → 一律
    `bug`（即使你同时认为缺少测试，也必须至少产出一条 `bug` 类；测试缺口另用 `test`
    类单独一条，不要用 `test` 代替 `bug`）；
  - category 必须从上述枚举选择，禁止省略或自创（省略会被 schema 拒收）。
- "title": 一句话标题
- "description": 问题描述
- "failure_mode": "触发条件：…；错误结果：…"
- "file": 相对仓库根的文件路径
- "line": 正整数（finding 行区间首行 = evidence 引用的变更行，行号须精确）
- "side": "RIGHT"
- "evidence": 行级证据，必须引用 diff 中的行号（RIGHT 侧变更行本身，不是相邻上下文行）
- "suggestion": 修复建议（可省略）
- "severity_proposed": "important" | "nit"
- "pre_existing_suspect": true | false
- "confidence": 0 到 1 之间的小数

id 与 run_id 由系统生成，不要输出。空结果合法且被鼓励：{"findings": []}
