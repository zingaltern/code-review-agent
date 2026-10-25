## 输出契约
只输出一个 JSON 对象，不输出任何其他文字（markdown 代码围栏也不要）：

{"findings": [ ... ]}

每个 finding 的字段：
- "category": "bug" | "security" | "test" | "convention" | "performance" | "context"
- "title": 一句话标题
- "description": 问题描述
- "failure_mode": "触发条件：…；错误结果：…"
- "file": 相对仓库根的文件路径
- "line": 正整数（finding 行区间首行）
- "side": "RIGHT"
- "evidence": 行级证据，必须引用 diff 中的行号
- "suggestion": 修复建议（可省略）
- "severity_proposed": "important" | "nit"
- "pre_existing_suspect": true | false
- "confidence": 0 到 1 之间的小数

id 与 run_id 由系统生成，不要输出。空结果合法且被鼓励：{"findings": []}
