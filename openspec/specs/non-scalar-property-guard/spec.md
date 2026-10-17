# non-scalar-property-guard Specification

## Purpose

写属性路径的非标量覆盖防护：元素属性写入工具（create/update/batch）在「属性定义未知」的兜底分支上，拒绝以标量值覆盖既有结构化属性，防止测试计划被静默破坏（GUI 面板无法打开）。

## Requirements

### Requirement: 兜底写入拒绝覆盖既有结构化属性

当组件属性定义不可知（组件无 schema，或属性不在 schema 中）而走兜底写入时，系统 SHALL 在写入前检查目标元素当前该属性名下的值；若其为结构化属性（集合、映射、嵌套测试元素、配置对象四种之一），系统 SHALL 拒绝本次对该属性的写入，且该属性的值 SHALL 保持不变。

#### Scenario: 覆盖嵌套元素属性被拒
- **WHEN** 对无 schema 的测试计划元素（TestPlan）调用属性更新，传入 `{"user_defined_variables": "abc"}`
- **THEN** 该写入被拒绝，`user_defined_variables` 仍为原嵌套元素属性（User Defined Variables 容器），测试计划面板可正常打开

#### Scenario: 覆盖配置对象属性被拒
- **WHEN** 对无 schema 的监听器组件（如 Aggregate Report / Summary Report）创建或更新时，传入 `{"saveConfig": "x"}`
- **THEN** 该写入被拒绝，`saveConfig` 仍为原配置对象属性（SampleSaveConfiguration），监听器面板可正常打开

#### Scenario: 未知标量属性写入不受影响
- **WHEN** 对无 schema 组件传入一个既有值为标量（或不存在）的未知属性，如 `{"customNote": "hello"}`
- **THEN** 写入照常按既有兜底行为执行（字符串落字符串属性），不被护栏拦截

### Requirement: 拒绝为整批 fail-fast 且不产生半写状态

同一次属性写入调用中，若任一兜底属性命中结构化属性，系统 SHALL 在执行任何写入前完成检查并整批拒绝该次调用的全部属性写入；元素属性 SHALL 保持调用前状态，不出现部分属性已写入的中间态。

#### Scenario: 混合属性整批拒绝
- **WHEN** 一次更新同时传入 `{"comment": "ok", "user_defined_variables": "abc"}`（后者撞结构化属性）
- **THEN** 整批拒绝，`comment` 也未被写入，元素保持调用前状态

### Requirement: 拒绝错误为教学式结构化错误

拒绝时系统 SHALL 返回结构化错误，内容包含：被拒属性名、该属性当前的结构化类别、以及后续指引。指引 SHALL 按组件 schema 有无分支：组件无 schema 时明示该属性无法经工具设置、指引 JMeter GUI 手工编辑；组件有 schema 但属性未被收录时，指引核对组件参考中的确切属性名（可能存在同名近似的 schema 属性）或 GUI 手工编辑。错误 SHALL 经工具结果返回给调用方（Agent）以支持自纠；消息 SHALL NOT 建议以结构化形态重传该属性（护栏触发即意味着该属性无工具可用的结构化表单，此类建议会诱导无效重试）。

#### Scenario: 错误消息引导自纠
- **WHEN** 写入因撞上结构化属性被拒
- **THEN** 工具返回错误结果，消息指明属性名与结构化类别，并按 schema 有无给出对应指引；不出现仅"failed"式的无信息错误，也不出现"传结构化值重试"类不可行动的建议

### Requirement: 防护覆盖创建、更新与批量更新

护栏 SHALL 对元素创建、单元素更新、批量更新三条链路一致生效（三者共用同一属性写入路径）；创建场景下目标元素构造期预置的结构化属性（如监听器构造器写入的 saveConfig）SHALL 同样受保护。

#### Scenario: 创建路径同样受保护
- **WHEN** 创建一个无 schema 的监听器组件并传入 `{"saveConfig": "x"}`
- **THEN** 创建被拒绝（或该次属性写入整批拒绝），不产出 saveConfig 被字符串覆盖的元素

### Requirement: schema 驱动路径与装配路径不受影响

属性定义已知时的写入（schema 结构化路径与标量路径）以及嵌套对象/集合项的装配写入，SHALL 保持既有行为，不被护栏拦截或改变。

#### Scenario: schema 结构化写入不误伤
- **WHEN** 按 schema 以结构化入参更新已知组件的集合属性（如 HTTP 信息头管理器的 headers 列表）或嵌套对象属性（如线程组的循环控制器）
- **THEN** 写入照常成功，行为与本变更之前完全一致

#### Scenario: 嵌套与集合项装配不误伤
- **WHEN** 创建组件时装配嵌套对象子属性或集合项条目（目标为新建的子元素/条目）
- **THEN** 装配照常成功，不被护栏拦截
