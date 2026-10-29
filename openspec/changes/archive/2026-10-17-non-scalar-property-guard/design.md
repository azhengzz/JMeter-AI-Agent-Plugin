# Design

## Context

见 proposal.md（动机、复现案例、敞口评估）。本设计回答「护栏放哪、查什么、怎么报错、怎么不影响既有路径」。全部代码锚点已在本仓库与 jmeter-5.6.3 源码核实（详见 `TODO/non-scalar-property-guard.md` 第 1–6 节）。

当前写入路径结构（`SchemaBasedPropertyHandler.setProperties`，`:50-64`）：

```java
ComponentSchema.PropertyDefinition propDef = schema != null ? schema.getProperty(propName) : null;
if (propDef != null) {
    handleBySchemaType(element, propName, propValue, properties, propDef);  // schema 认识
} else {
    handleSimpleProperty(element, propName, propValue);                     // 兜底（危险区）
}
```

JMeter `AbstractTestElement.setProperty(JMeterProperty)` 按名字直接 `propMap.put`，零类型检查；结构属性被标量覆盖后 GUI `configure()` 硬转型失败。

## Goals / Non-Goals

**Goals**
- 兜底分支上的结构化属性覆盖被拒绝，元素保持原值，Agent 收到可自纠的教学式错误。
- fail-fast：一次调用任一违规即整批拒绝，无半写状态。
- create / update / batch_update 一致防护（共用写入路径）。

**Non-Goals**
- 不处理反射 setter 静默丢弃（TODO 文档第 7 节，独立缺口另行决策）。
- 不为无 schema 组件补 schema；不改 `${}` 豁免；不改 schema 驱动的结构化写入。

## Decisions

### D1 护栏落点：`setProperties` 的 else 分支（方案 B）

写入循环前增加一次**预扫描**：遍历本次全部属性，凡「propDef == null 且 `element.getProperty(propName)` 为结构化类型」者记为违规；存在违规则整批拒绝、不进入写入循环。

- 选 B 而非 A（`handleSimpleProperty` 入口）：B 的作用域**精确等于危险区**（唯一不经 schema 校验约束的分支）；A 还覆盖 `:78`/`:97`/`:494`/`:578` 四个调用点，其中嵌套/数组项路径（`:494`/`:578`）目标是新建元素、理论上无碰撞但需额外证明义务——B 直接绕开该证明。
- 选 B 而非 C（工具层统一出口）：工具层拿不到属性语义，要么重复遍历要么回调 handler，改动最重收益最小。
- 预扫描而非逐属性即拦：保证 fail-fast 无半写（若逐属性拦，前面的属性已写入、后面才拒绝，留下部分应用的中间态，与「错误即重试」的 Agent 交互模型不匹配）。

### D2 结构化类型判定：四种全查

```java
private static boolean isStructuredProperty(JMeterProperty p) {
    return p instanceof CollectionProperty
        || p instanceof MapProperty
        || p instanceof TestElementProperty
        || p instanceof ObjectProperty;
}
```

- **含 `ObjectProperty`，是对参考实现（jmeter-ai `isNonScalar`，仅查三种）的有意增强**：本站 `ResultCollector.saveConfig` 正是 ObjectProperty（构造器 `:167` 预置），无 schema visualizer 众多；若不查，该复现照样打穿。
- 误伤风险为零：本站合法的 ObjectProperty 写入（`:402`/`:536`）走结构化装配路径，不经过被护栏的 else 分支。
- `NullProperty` 与各标量类型不命中 → 放行（向后兼容，见 D5）。

### D3 违规上报：`setProperties` 返回违规清单，工具层转 ToolResult.error

`setProperties` 现为 `void`。改为返回 `List<PropertyGuardViolation>`（新嵌套记录类：属性名 + 当前结构化类别描述）；空清单 = 全部写入成功（既有语义不变）。

三个调用工具（Create/Update/BatchUpdate）在调用后检查清单：非空 → `ToolResult.error`（**硬错误，已确认**——对齐参考实现与既有 validation 错误的交互模型，Agent 对 error 的遵从度远高于 warning；fail-fast 已保证无半写，硬错误无副作用残留）。

**batch_update 粒度（已确认）**：batch 以同一组属性应用到 N 个元素，护栏对**全部目标元素的预扫描须先于任何写入完成**——任一元素撞结构属性 → 整个工具调用拒绝，不产生"部分元素已更新"的混合状态。语义对齐该工具既有 Step 4 validation 的 up-front fail-fast（`BatchUpdateJMeterElementTool.java:141-145`）。

`handleBySchemaType` 等内部方法不改签名——预扫描在 `setProperties` 顶层完成，命中即 return，不进入写入。

### D4 错误消息：教学式模板（按 schema 有无分支）

> **修订**（M6 手工测试发现）：初版第一条指引「If the component has a schema, pass a structured value instead」会把 agent 引入重试死胡同——护栏只在 schema **不认识**该属性时触发，此时"传结构化形态"永远不可行动（无 schema 时写入路径只会 `toString()` 污染，有 schema 但属性未收录时也不存在该属性的结构化表单）。修订为按 schema 有无分支，且**不再建议结构化重试**。

```
Property '<name>' on '<elementName> (<Class>)' currently holds <kind>
and cannot be overwritten with a plain scalar — this would corrupt the element and
break its GUI panel.
【组件有 schema（属性未被收录）】
This property is not defined in the component's schema, so there is no structured form the tools can apply.
  - Check the exact property name in the component reference — a similarly named schema-defined property may exist.
  - Or edit this property manually in the JMeter GUI.
【组件无 schema】
This component has no parameter schema, so this property cannot be set via tools.
  - Edit this property manually in the JMeter GUI.
Refused properties: [<names>]. No properties were modified in this call.
```

`<kind>` 取四选一的友好描述（`a collection` / `a map` / `a nested test element` / `a configuration object`）。违规记录携带 `componentSchemaKnown` 标志（扫描时 `schema != null` 即真），消息按其分支；同一次调用的违规必然同分支（单一元素+schema，batch 受同类型约束）。

### D5 不受影响的路径（护栏的负空间）

| 路径 | 为何不受影响 |
|---|---|
| schema 结构化写入（Object/Array → `handleBySchemaType`） | propDef != null，不进预扫描的违规判定 |
| schema 标量写入 | 同上 |
| 未知**标量**属性兜底写入 | `getProperty(name)` 为标量/NullProperty，不命中 D2 |
| 嵌套对象 / 数组项装配（`:494`/`:578` 等） | 不经过 `setProperties` 的 else 分支（在 handleBySchemaType 内部） |
| `${}` 变量引用写入 | 属标量写入；与护栏正交 |

## Risks / Trade-offs

- **行为变化面**：唯一被改变的是「此前会静默破坏测试计划」的写入——从破坏变为拒绝。无已知合法用例依赖该破坏行为。
- **误伤兜底**：若某无 schema 组件**本就期望**用字符串覆盖结构属性（未发现此类用例；JMeter 自身 GUI 从不这么写），会被拒——错误消息的 GUI 退路覆盖该情况。
- **fail-fast 拒绝整批**：一次调用混合合法属性与违规属性时，合法属性也被拒（未写入）。代价是 Agent 需去掉违规项重传；收益是无半写状态、语义干净。与既有 validation（写入前整批校验）的交互模型一致。

## Migration Plan

无迁移：纯增量护栏，不改任何既有成功路径的输出。实施后更新 `TODO/non-scalar-property-guard.md`（第 1–6 节标记已实施）与 AGENTS.md 的 `SchemaBasedPropertyHandler` 条目（补一句护栏说明）。

## Open Questions

无——四个待决问题已在讨论中定案（落点 B / 纳入 ObjectProperty / 教学式错误 / create 随共用路径自动覆盖）；实施前三项补充确认亦已定案：① fail-fast 整批拒绝 + 硬错误；② batch_update 对齐既有 up-front 模式（整调用拒绝）；③ 第 7 节（反射 setter 静默丢弃）排除在外，留 `TODO/non-scalar-property-guard.md` 由用户后续定 A/B。
