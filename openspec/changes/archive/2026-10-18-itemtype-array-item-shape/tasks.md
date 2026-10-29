# Tasks

## 1. 模型与加载器

- [x] 1.1 `ComponentSchema.PropertyDefinition` 增加 `itemType` 字段 + getter + `hasItemType()` +
  双载 setter（PropertyType 载与经 `PropertyType.fromString` 的 String 载，镜像 innerItemType
  :295-301）。验证：`mvn clean test-compile` 通过
- [x] 1.2 `ComponentSchemaLoader.parsePropertyDefinition` 解析 `itemType` 键（:215-218 区），并加
  五条 lint 告警（镜像 :230-236 风格）：① 非 Array 上声明；② itemType 原始串严格枚举校验（直接
  valueOf try/catch + 限标量子集，不经 fromString 兜底）；③ Array 无任何元素声明却带 default；
  ④ Array itemProperties-only；⑤ itemType 与 className / itemClass+itemProperties 共存。
  验证：新增加载器测试断言合法声明被解析、五类错误形态各出告警（错误形态以模型态断言：itemType
  不被设置；告警为日志级 lint）
- [x] 1.3 SchemaLoaderTest 等既有 validation 测试回归（四个测试类均为 main() runner，`mvn test`
  不收集，用 `mvn clean test-compile` + 新 JUnit 用例覆盖）。验证：全绿

## 2. 存量声明补齐

- [x] 2.1 三个 schema 文件补 `itemType: String`：ResponseAssertion 的 `Asserion.test_strings`、
  ModuleController 的 `ModuleController.node_path`、UserParameters 的 `UserParameters.names`。
  验证：重跑分支扫描脚本，「generic 且无 itemType」= 0（三个属性均带 String）
- [x] 2.2 `ComponentValidatorQuirkNameTest` fixture 的 `Asserion.test_strings` 条目同步补
  `itemType: String`（否则不变式打破其 4 个断言）。验证：该测试 7/7 绿

## 3. 校验器

- [x] 3.1 新增 `acceptsVariableExpressions(PropertyType)` 重载（数值类型 true），现有
  PropertyDefinition 版委派之。验证：`ComponentValidatorVariableTest` 17 例全绿（既有行为不变）
- [x] 3.2 `validateParameterTypes`（:379-423，validate/validateUpdate 两路共用）加 item 级校验：
  `hasItemType` → 逐项 `isValidType(item, itemType)` + 数值型 `${...}` 豁免（与标量路径 :399-400
  同构，无数字宽容）；违规错误点名属性与元素序号并附 flat-list 示例指引。
  验证：新增用例 V1（`List<Map>` 拒 + 指引）、V2（`List<String>` 过）、V3（`List<Number>` 对
  itemType:String 拒）、V4（混合拒）、V6（数值 itemType + `${var}` 过）
- [x] 3.3 同方法加不变式：Array 三种元素声明（itemType | className | itemClass+itemProperties）
  皆无且有 supplied 值 → 「item shape not declared」教学式硬错（schema 缺口口径）。
  验证：新增用例 V5（fixture 无声明 + 传值 → invalid）

## 4. 写入防御

- [x] 4.1 `handleGenericCollectionProperty` 签名加 propDef，四个调用点（:235/:280/:700/:743）同步；
  `hasItemType` 且任一元素为 Map/Collection → 整属性跳过不写 + log.warn。
  验证：SchemaBasedPropertyHandlerTest 新增 H1（real modulecontroller schema + `List<Map>` →
  属性未写入；对照 `List<String>` 正常写入——responseassertion 类不在测试类路径，用同分支的
  node_path 等价覆盖）与 H2（node_path 的 default "[]" 路径行为与现状一致：空集合）

## 5. 文档

- [x] 5.1 SCHEMA-GUIDE 三处同步：§6.2 Array 专用字段表加 itemType 行（含互斥说明）、§11 模板
  决策树 ARRAY 分支补 itemType 判断、§11 GUI 翻译表「字符串列表」行补 itemType 关键字。
  验证：人工核对三处与实现一致
- [x] 5.2 TODO/TODO.md 加完成条目（指向 TODO/itemtype-array-item-shape.md 计划与本变更）。
  验证：条目存在且状态准确

## 6. 回归与验收

- [x] 6.1 全量 `mvn clean test`（838 基线 + 新增用例），回归清单显式含 ComponentValidatorQuirkNameTest /
  ComponentValidatorVariableTest / SchemaBasedPropertyHandlerTest。验证：0 failures
  （实测 857 / 0 / 11 skipped：+8 loader、+9 校验器、+2 handler）
- [x] 6.2 验收对照 spec 场景：复现案例（responseassertion + `[{TestElement.name=…}]`）经校验被拒
  且树无残留；`["123"]` 正常创建（GUI 模式表显示 123）。验证：CLI 或单测级复演通过
  （单测级：v1_objectItemsRejectedWithGuidance 钉 List<Map> 拒 + 指引；校验先于 EDT 写入故无树残留
  （guard 变更 M 系列已证该序）；V2/H1-good 钉 `["123"]` 写入。GUI 级显示确认待用户重部署后进行）
