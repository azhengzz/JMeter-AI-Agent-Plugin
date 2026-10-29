# Design

## Context

写入路径的 ARRAY 分派三分支（容器 / 表行 / 通用集合）：无任何元素声明的属性落通用集合路径，对每个
元素无条件 `toString()`（SchemaBasedPropertyHandler `handleGenericCollectionProperty`）。全量扫描
（两路对抗复核一致）：25 个顶层 Array 属性 = 容器 20（yaml `testClass` 键被 loader 映射进 className）+
表行 2 + 通用 3（`Asserion.test_strings` / `ModuleController.node_path` / `UserParameters.names`，
全深度 585 属性节点中深层 Array 声明为 0）。三者皆为扁平字符串集合，文档无 Map 形态示例。

既有先例：模型已有 `innerItemType`（ARRAY_2D 内层标量类型，setter 双载 String→`PropertyType.fromString`），
loader 已有 schema 一致性 lint 先例（Array+className 无 itemProperties → warn）。完整审计记录见
`TODO/itemtype-array-item-shape.md`（计划 v2.1，含两路对抗审查的发现与吸收）。

## Goals / Non-Goals

**Goals:**
- 元素形态由 schema 声明驱动（单一事实源），校验严格性与标量路径完全同构
- 堵住「错误形态静默字符串化」，并让未来漏声明的 Array 在有值传入时显式失败而非静默落盘

**Non-Goals:**
- 不动 ARRAY_2D（innerItemType 既有机制）、容器 20 / 表行 2 属性
- 不处理 componentValidator 为 null 的降级环境（该路径 schema 同源为 null，走的是标量兜底而非通用
  集合路径，记为已知另案）
- 不改时间戳条目名漂移；不在 handler 做类型转换

## Decisions

**D1：新键 `itemType`，不复用 `itemClass`。**
`itemClass` 的数据契约是「TestElement bean 命名字段行」（输入 `[{name:…}]` / 反射装配 /
TestElementProperty 存储）；字符串集合是「裸标量」（输入 `["123"]` / 无字段可设 / StringProperty 存储）。
三层全不同，三个独立死点实证：TestBean 表分支对 String 抛 ClassCastException 后回退到同一条垃圾路径；
非 Map 条目被丢弃导致清空；行被包成 TestElementProperty 而读侧要标量。`itemType` 与 `innerItemType`
命名同族，setter 双载直接镜像。

**D2：item 级校验与标量路径同构，严格性一致（用户裁定）。**
逐项 `isValidType(item, itemType)` + 数值型 `${...}` 豁免。需要新增
`acceptsVariableExpressions(PropertyType)` 重载——现有 PropertyDefinition 版 switch 的是
propDef.getType()，item 级恒为 ARRAY 恒 false；旧签名委派新签名，既有 17 个豁免测试不受影响。
无数字宽容：`type: String` 标量收 JSON 数字即拒（isValidType `instanceof String`），itemType:String
数组同样拒——以标量既有行为为准绳（2026-10-18 裁定）。

**D3：不变式与分派谓词逐字对齐，只管 supplied 值。**
合法形态 = `className` | `itemClass`+`itemProperties` | `itemType` 三者之一（itemProperties 单独
不算——进不了表行分支，属 latent 不一致，lint 拦截）。`applyDefaultValues` 在校验之后注入 default、
直达写入（node_path 的 `default: "[]"` 即此路径），不扩大打击面；loader lint 对「无声明却带 default」
告警。

**D4：写入防御定位为纵深防御，而非旁路环境的主防线。**
审查发现 componentValidator==null 时 schema 同源为 null → propDef==null → 走标量兜底的 toString，
根本不经过通用集合路径；通用路径的预扫描（元素为 Map/Collection → 整属性跳过）防的是绕过校验直调
写入层的路径（测试 / 未来工具）。预扫描只在 hasItemType 时生效，四条回退调用路径不受影响。

**D5：lint 对 itemType 原始串做严格枚举校验。**
`PropertyType.fromString` 对无法识别的字符串静默兜底为 STRING，拼写错误与合法声明不可区分；lint
须直接对原始 YAML 值做枚举名匹配（限标量子集）。

## Risks / Trade-offs

- [未来 schema 作者漏声明 itemType → 该属性有值即硬错，不可写] → 这是不变式的立意（显式失败优于
  静默垃圾）；错误消息指明是 schema 缺口；loader lint 提前在加载期告警
- [`ComponentValidatorQuirkNameTest` 的 fixture 是无声明形态，不变式会打破其 4 个断言] → 实施时
  同步给 fixture 补 `itemType: String` 一行，并纳入回归清单
- [fromString 拼写盲区同样存在于 innerItemType（既有问题）] → 本变更只修 itemType 的 lint；
  innerItemType 同盲区记录不改（非目标）
- [存量会话若依赖错误形态写入（理论上已产出的垃圾元素）] → 不迁移存量；验收含 GUI 侧确认新写入正确

## Migration Plan

纯增量：模型加字段、loader 加解析（旧 schema 无 itemType 完全兼容）、validator/handler 加分支。
三个存量 schema 与一个测试 fixture 补声明。部署侧：schema yaml 变更随 `mvn install` 复制到
`{JMETER_HOME}/bin/jmeter-agent/skills/`，运行中的 GUI 需重部署后生效。回滚 = revert 提交，无数据迁移。

## Open Questions

（无——两轮对抗审查已消解计划层未知；实施期如遇 specs 未覆盖的形态，回到变更补充 delta）
