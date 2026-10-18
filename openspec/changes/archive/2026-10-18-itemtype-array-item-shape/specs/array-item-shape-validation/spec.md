# Spec Delta

## Purpose

Array 属性元素形态的声明式校验：通过 schema 键 itemType 声明数组元素标量类型，写入工具据此逐项校验，
杜绝「错误形态静默字符串化」（如对象数组的 toString 字面量被当作模式串写入断言）。

## ADDED Requirements

### Requirement: itemType 声明数组元素标量类型

系统 SHALL 支持在组件 schema 中为 Array 类型属性声明 `itemType`，值域为标量类型子集
（String/Integer/Long/Float/Double/Boolean/Number）。`itemType` SHALL 与容器声明（`className`）和
表行声明（`itemClass`+`itemProperties`）互斥；加载器 SHALL 对以下 schema 作者错误发出告警：
在非 Array 属性上声明 itemType、itemType 原始值不属于标量子集（拼写错误可检出）、itemType 与
className 或 itemClass+itemProperties 同时声明、Array 属性仅声明 itemProperties 而无 itemClass、
Array 属性未声明任何元素形态却声明了 default。

#### Scenario: 声明被解析并生效
- **WHEN** schema 为某 Array 属性声明 `itemType: String`
- **THEN** 该属性参与逐项校验与写入防御，行为按本规范各要求执行

#### Scenario: 互斥与非法值告警
- **WHEN** schema 在同一 Array 属性上同时声明 itemType 与 className（或 itemType 值拼写错误）
- **THEN** 加载器记录告警日志，不中断加载

### Requirement: 数组元素逐项类型校验

当 Array 属性声明了 itemType 且调用（create/update/batch）提供了该属性的值时，系统 SHALL 对每个
元素按声明的标量类型校验，严格性与同类型标量属性完全一致：itemType 为 String 时元素必须是字符串
（JSON 数字被拒并指引传字符串）；itemType 为数值类型时元素接受 `${...}` 变量引用字符串（与数值
标量字段的豁免同构）。任一元素违规 SHALL 使该次调用校验失败，错误 SHALL 点名属性与元素序号，
并以期望形态示例指引（如 flat list of strings, e.g. ["200", "201"]）。

#### Scenario: 对象数组被拒并给出指引
- **WHEN** 对 `Asserion.test_strings`（itemType: String）传入 `[{name: "123"}]` 形态的值
- **THEN** 调用被拒，错误指明该属性期望扁平字符串列表并附示例，测试计划无变更

#### Scenario: 扁平字符串照常通过
- **WHEN** 对同一属性传入 `["123"]`
- **THEN** 校验通过，写入后 GUI 模式表显示 `123`，运行时断言按预期生效

#### Scenario: 数字元素按标量同构拒绝
- **WHEN** 对 itemType: String 的属性传入 `[123]`（JSON 数字）
- **THEN** 调用被拒并指引传字符串——与 `type: String` 标量属性收数字即拒的既有行为一致

#### Scenario: 数值 itemType 继承变量豁免
- **WHEN** 对 itemType 为数值类型的属性传入 `["${threads}"]`
- **THEN** 校验通过

### Requirement: 数组必须声明元素形态

对未声明任何元素形态（无 itemType、无 className、无 itemClass+itemProperties）的 Array 属性，
当调用提供了该属性的 supplied 值时，系统 SHALL 以教学式错误拒绝，指明该属性的元素形态未在组件
schema 中声明（属 schema 缺口）。schema default 经默认值注入路径应用，SHALL 不受此不变式影响
（保持既有行为）。

#### Scenario: 未声明形态且传值被拒
- **WHEN** 某组件 schema 的 Array 属性未声明任何元素形态，调用传入该属性的值
- **THEN** 调用被拒，错误说明元素形态未声明，测试计划无变更

#### Scenario: default 注入路径不受影响
- **WHEN** 创建组件时未提供某带字符串列表 default 的属性
- **THEN** 默认值照常应用，行为与本变更之前一致

### Requirement: 写入路径拒绝非标量元素字符串化

属性写入路径 SHALL 提供纵深防御：对已声明 itemType 的 Array 属性，写入时若发现任一元素为对象或
嵌套集合，SHALL 跳过对该属性的整次写入（元素属性保持调用前状态），而不是将元素字符串化落盘。

#### Scenario: 绕过校验的直写不落垃圾
- **WHEN** 未经校验直接对已声明 itemType 的属性执行写入，值含对象元素
- **THEN** 该属性未被写入，目标元素保持调用前状态

### Requirement: 存量字符串集合属性声明补齐

以下存量属性 SHALL 声明 `itemType: String`，且其既有正确用法行为不变：
`Asserion.test_strings`（ResponseAssertion）、`ModuleController.node_path`（ModuleController）、
`UserParameters.names`（UserParameters）。

#### Scenario: 存量正确用法回归
- **WHEN** 分别以 `["200", "201"]`、`["Test Plan", "Thread Group"]`、`["username", "password"]`
  形态设置三个属性
- **THEN** 均照常成功写入，行为与本变更之前完全一致
