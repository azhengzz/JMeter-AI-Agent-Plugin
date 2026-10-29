# Proposal

## Why

写属性路径的「未知属性兜底分支」会把值按字面写入：当属性名恰命中元素上既有的**结构化属性**（Collection/Map/TestElement/ObjectProperty）时，JMeter 的 `setProperty` 按名字直接覆盖、零类型检查，结构属性被字符串整体替换，GUI 面板 `configure()` 类型转换失败——**面板打不开，只能手工编辑 jmx 才能恢复**。已核实可达复现：无 schema 的 `TestPlan` + `update_jmeter_element` 传 `{"user_defined_variables": "abc"}`（TestElementProperty 被覆盖）、无 schema 监听器传 `{"saveConfig": "x"}`（ObjectProperty 被覆盖）。敞口集中在无 schema 组件（注册于 legacy-elements.yaml，占多数），因其不经 `validateUnknownParameters` 拦截。详细论证见 `TODO/non-scalar-property-guard.md` 第 1–6 节；参考实现为 jmeter-ai（Feather Wand）的 `JMeterTreeMutator.isNonScalar` 护栏。

## What Changes

- 在 `SchemaBasedPropertyHandler.setProperties` 的**未知属性分支**（schema 不认识该属性时）前置一道运行时类型护栏：写入前检查目标元素当前该属性是否为结构化类型（`CollectionProperty`/`MapProperty`/`TestElementProperty`/`ObjectProperty`），是则**拒绝写入**并回结构化错误，属性保持原值（讨论定案：落点选方案 B——作用域精确等于危险区、零误伤）。
- **`ObjectProperty` 纳入护栏**（对参考实现的有意增强——jmeter-ai 只查三种，但本站 `ResultCollector.saveConfig` 正是 ObjectProperty 且无 schema visualizer 众多）。
- 护栏采用**先扫描后写入**（fail-fast）：本次调用中任一未知属性撞上结构化属性即整批中止，不产生半写状态；违规清单经工具层转为 `ToolResult.error`，**错误消息为教学版**（指明属性名、当前结构化类型、正确入参形态或 GUI 手工编辑的退路）。
- 覆盖 create/update/batch_update 三条链路（共用 `setProperties` 写入路径，一次防护全量生效；create 场景下元素构造器预置的结构属性如 `ResultCollector.saveConfig` 同样受保护）。
- schema 认识的属性（结构化路径与标量路径）与嵌套/数组项装配路径（目标为新建元素）**行为不变**；标量属性的兜底写入（无 schema 组件上的未知标量属性）**行为不变**（向后兼容）。

## Capabilities

### New Capabilities
- `non-scalar-property-guard`: 写属性路径的非标量覆盖防护——未知属性兜底分支拒绝以标量值覆盖既有结构化属性（Collection/Map/TestElement/Object Property），fail-fast 整批拒绝并回教学式结构化错误；覆盖 create/update/batch_update；schema 驱动的合法结构化写入与标量兜底写入不受影响。

### Modified Capabilities
<!-- 无：既有 specs 下无写属性行为相关能力（写路径此前无 spec 覆盖） -->

## Impact

- **代码**：`SchemaBasedPropertyHandler.java`（`setProperties` 增加护栏预扫描 + 违规上报机制，约 +60 行）；`CreateJMeterElementTool.java` / `UpdateJMeterElementTool.java` / `BatchUpdateJMeterElementTool.java`（消费违规清单 → `ToolResult.error`，各 ~5 行）。
- **测试**：扩展 `SchemaBasedPropertyHandlerTest`（护栏矩阵：TestPlan UDV 复现、ResultCollector saveConfig、标量兜底不变、schema 结构化写入不误伤、嵌套/数组项路径不误伤）。
- **文档**：实施后更新 `TODO/non-scalar-property-guard.md` 状态（第 1–6 节标记已实施；第 7 节反射 setter 静默丢弃为独立缺口，不随本变更处理）；AGENTS.md 工具层 `SchemaBasedPropertyHandler` 条目补一句护栏说明。
- **外部依赖**：无新增。
- **用户可见影响**：此前会**静默破坏测试计划**（面板打不开）的写入现在被拒绝并给出可自纠的错误；其余写入行为无任何变化。
- **非目标**：不处理第 7 节反射 setter 静默丢弃（定性为错误不透明、影响面 1 个 GUI 无入口字段，另行决策）；不为无 schema 组件补 schema；不改动 `${}` 变量引用豁免。
