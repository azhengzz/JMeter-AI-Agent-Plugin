# Implementation Tasks

## 1. 护栏核心（D1 + D2 + D3 前半）

- [x] 1.1 在 `SchemaBasedPropertyHandler` 新增静态判定 `isStructuredProperty(JMeterProperty)`：命中 `CollectionProperty` / `MapProperty` / `TestElementProperty` / `ObjectProperty` 四种返回 true（NullProperty 与标量 false）。→ verify: 编译通过。
- [x] 1.2 新增违规记录类型（属性名 + 结构化类别描述），并将 `setProperties` 返回值由 `void` 改为违规清单：进入写入循环**前**先做一次预扫描——凡「schema 查不到该属性（propDef == null）且 `element.getProperty(propName)` 命中 1.1 判定」记为违规；清单非空则直接 return（不执行任何写入）。→ verify: 单元测试 2.x 的 TestPlan 复现用例由"覆盖成功"翻转为"返回违规且属性未变"。
- [x] 1.3 同步修复 `setProperties` 既有调用点因签名变化产生的编译错误（本任务只改调用不接错误消费，消费在 3.x）。→ verify: `mvn clean test-compile` 通过（用 clean 排除 stale class）。

## 2. 护栏行为测试（D5 负空间 + 复现矩阵）

- [x] 2.1 在 `SchemaBasedPropertyHandlerTest` 新增护栏用例组：① TestPlan + `{"user_defined_variables": "abc"}` → 返回违规、属性仍为 TestElementProperty（原容器子项数不变）；② 同调用混入合法属性（如 `comment`）→ 整批拒绝、`comment` 未写入（fail-fast）。→ verify: 断言通过。
- [x] 2.2 ③ `new ResultCollector()`（模拟无 schema 监听器）+ `{"saveConfig": "x"}` → 返回违规、`saveConfig` 仍为 ObjectProperty（ObjectProperty 纳入生效）；④ `new CookieManager()` + `{"CookieManager.cookies": "x"}`（schema 存在时该属性走结构化路径不受影响；另测 schema 传 null 模拟无 schema 时命中 CollectionProperty 违规）。→ verify: 断言通过。
- [x] 2.3 负空间回归：⑤ 未知标量属性（`{"customNote": "hello"}` 于无 schema 元素）→ 照常写入 StringProperty；⑥ schema 驱动的结构化写入（既有 Case 4 cookies 长整型、Case 5 main_controller 等**全部既有用例不改断言原样通过**）；⑦ `${}` 变量字符串写入数值 schema 字段 → 照常落 StringProperty（与 `${}` 豁免正交）。→ verify: 全部通过。

## 3. 工具层接线（D3 后半 + D4）

- [x] 3.1 `CreateJMeterElementTool` / `UpdateJMeterElementTool` / `BatchUpdateJMeterElementTool` 消费违规清单：非空 → `ToolResult.error`，消息按 design D4 教学式模板（属性名列表 + 结构化类别 + schema 结构化形态/GUI 手工两路指引 + "No properties were modified in this call"）；空清单 → 既有成功路径完全不变。**batch_update 语义（已确认）**：全部目标元素的护栏预扫描先于任何写入完成，任一元素撞结构属性即整调用拒绝（对齐既有 Step 4 validation 的 up-front fail-fast，不产生部分元素已更新的混合状态）。→ verify: 编译通过；既有工具级测试（如有）不回归。
- [x] 3.2 错误消息单测：对 D4 模板做断言（含属性名、结构化类别描述、两条指引、"No properties were modified"），可用 handler 级违规清单 + 消息构建函数直接断言。→ verify: 断言通过。

## 4. 回归与文档

- [x] 4.1 全量回归：`mvn clean test`（不与 Workflow 并行）。→ verify: 除既有环境 flaky（单独复跑分流传）外无新增失败；`SchemaBasedPropertyHandlerTest` 全绿。
- [x] 4.2 更新 `TODO/non-scalar-property-guard.md`：第 1–6 节标注已实施（引用本 change），第 7 节保留为独立缺口；`TODO/TODO.md` 对应条目摘要更新（①已实施、②遗留）。→ verify: 文档状态与实际一致。
- [x] 4.3 AGENTS.md 工具层 `SchemaBasedPropertyHandler` 条目补一句护栏说明（未知属性兜底拒绝覆盖结构化属性，教学式错误）。→ verify: 与实现描述一致。
