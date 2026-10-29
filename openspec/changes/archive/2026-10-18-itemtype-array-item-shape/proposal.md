# Proposal

## Why

无元素形态声明的 Array 属性（如 `Asserion.test_strings`）落入通用集合写入路径后，对每个元素无条件
`toString()`：Agent 传 `List<Map>` 时，Map 的 toString 字面量（如 `{TestElement.name=123, ...}`）被
静默当作一条模式串写进断言——工具报成功、GUI 显示垃圾值、运行时断言恒失败。校验层当前不查数组元素
内容（`isValidType` 对 ARRAY 仅查 Iterable），这是一个「错误形态静默字符串化」的透明性缺口。

## What Changes

- 新增 schema 键 `itemType`（值域 = PropertyType 标量子集），声明 Array 属性的元素标量类型；
  命名与既有 `innerItemType`（ARRAY_2D）同族
- validator 对声明了 itemType 的 Array 属性逐项校验，与标量 type 校验**完全同构**：严格性一致
  （itemType:String 拒绝 JSON 数字，同 `type: String` 标量行为）、数值类型继承 `${...}` 变量引用豁免
  （新增 `acceptsVariableExpressions(PropertyType)` 重载）
- 新增不变式：1D Array 属性必须声明元素形态（`itemType` | `className` | `itemClass`+`itemProperties`
  三者之一，与写入路径分派谓词逐字对齐），皆无且该属性有 supplied 值 → 教学式硬错
  （只管 supplied 值；`applyDefaultValues` 注入的 schema default 为既有路径，不扩大打击面）
- 写入路径纵深防御：通用集合写入发现元素为 Map/Collection 且已声明 itemType 时整属性跳过不写
- 三个存量无声明属性补 `itemType: String`：`Asserion.test_strings`、`ModuleController.node_path`、
  `UserParameters.names`（全量扫描确认 generic 分支仅此三者，皆为扁平字符串集合，零误伤）
- schema 加载器新增五条 lint（非 Array 上声明、原始串严格枚举校验、无声明却带 default、
  itemProperties-only、与 className/itemClass 共存）
- 文档同步三处：SCHEMA-GUIDE §6.2 字段表、§11 决策树、§11 GUI 翻译表

## Capabilities

### New Capabilities
- `array-item-shape-validation`: Array 属性元素形态的声明式校验——itemType 键语义、逐项类型校验、
  无声明不变式、写入路径纵深防御与 schema lint

### Modified Capabilities
（无——`non-scalar-property-guard` 锚定「未知属性兜底分支的非标量覆盖」，本案是独立机制，不动其需求）

## Impact

- `ComponentSchema`（PropertyDefinition 增字段）、`ComponentSchemaLoader`（解析 + lint）、
  `ComponentValidator`（项级校验 + 不变式 + 豁免重载）、`SchemaBasedPropertyHandler`
  （handleGenericCollectionProperty 签名与预扫描，四个调用点）
- 3 个 schema yaml（ResponseAssertion / ModuleController / UserParameters）与
  `ComponentValidatorQuirkNameTest` fixture（无声明形态会被不变式打破，同步补声明）
- SCHEMA-GUIDE.md 文档；测试新增 validator/handler 两组用例
- 不影响：container 20 / testbean-table 2 个 Array 属性、3 个 ARRAY_2D 属性（innerItemType 既有机制）、
  数值字段 `${}` 豁免既有行为（ComponentValidatorVariableTest 17 例不受影响）
