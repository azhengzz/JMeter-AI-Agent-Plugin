# Spec Delta

## Purpose

让 Agent 在用户要求时把 GUI 中的活测试计划落盘（存回关联文件或另存到指定路径），并为 goal 完成时由模型按指引发起的保存提供同一套保存语义与保护规则。

## ADDED Requirements

### Requirement: save_test_plan 工具的保存与另存语义

系统 SHALL 提供 `save_test_plan(filePath?, overwrite?)` 工具，把 GUI 中的活测试计划写入磁盘，落盘行为与用户按 Ctrl+S 等价。未提供 `filePath` 时，工具 SHALL 把计划存回其关联文件——即此后 Ctrl+S、运行前自动保存、跨实例发现的"打开的 jmx"所指的那个文件。提供 `filePath` 时，工具 SHALL 执行另存为：把计划写入该路径，并把计划的文件关联改为该路径。工具 MUST NOT 提供「只写副本、不改关联」的导出模式。成功时工具返回 SHALL 包含保存后的文件路径（`filePath`）、文件名（`fileName`）、模式（`mode`，存回为 `save`、另存为 `save_as`）与文件字节数（`bytes`），MUST NOT 回显测试计划树。

#### Scenario: 存回关联文件
- **WHEN** 计划已关联 `D:/plans/login.jmx`，Agent 调用 `save_test_plan` 且不传 `filePath`
- **THEN** `D:/plans/login.jmx` 被当前测试计划覆写，返回 `mode` 为 `save` 及该路径与文件字节数
- **AND** 计划的文件关联保持为 `D:/plans/login.jmx`

#### Scenario: 另存为新路径并改关联
- **WHEN** 计划已关联 `D:/plans/login.jmx`，Agent 调用 `save_test_plan` 传 `filePath` 为尚不存在的 `D:/plans/login-v2.jmx`
- **THEN** 该路径被写入当前测试计划，返回 `mode` 为 `save_as`
- **AND** 计划的文件关联变为 `D:/plans/login-v2.jmx`——此后无参保存、运行前自动保存与跨实例发现的 jmx 路径均指向新文件
- **AND** `D:/plans/login.jmx` 内容不变

#### Scenario: 计划未改动时存回仍然重写
- **WHEN** 计划与其关联文件内容一致，Agent 调用 `save_test_plan` 且不传 `filePath`
- **THEN** 关联文件照常被重写并返回成功，与 Ctrl+S 一致，不做"无改动则跳过"

### Requirement: 仅在用户明确要求时调用

`save_test_plan` 的工具描述 SHALL 写明：仅当用户明确要求保存（当场要求，或事先要求改完后保存）时调用，模型不得自行决定保存——**唯一例外**是 goal 回合：验证通过后、调用 `update_goal(complete)` 之前，模型 SHALL 自己调 `save_test_plan` 存回计划（见 goal-autonomous-loop）。在 goal 回合（`/goal` 触发的回合或 goal 活跃期间的回合）与 IPC 委派回合中调用本工具 SHALL 与普通回合同等可用，MUST NOT 设置硬阻断。

#### Scenario: goal 回合内按用户点名的路径另存
- **WHEN** goal 活跃期间用户明确要求把计划另存到指定路径，Agent 调用 `save_test_plan` 传该 `filePath`
- **THEN** 另存照常执行，不因回合处于 goal 模式而被拒

#### Scenario: 工具描述承载调用纪律
- **WHEN** 读取 `save_test_plan` 的工具描述
- **THEN** 描述写明仅在用户明确要求时调用，并说明 goal 验证通过后由模型自己调用保存

### Requirement: 保存前的保护规则

下列规则任一触发时，工具 SHALL 返回说明原因与下一步的错误，MUST NOT 写盘，MUST NOT 弹出任何对话框——文件选择框、覆盖确认框等在自主回合中无人应答，会卡死 GUI 线程：

- **未保存计划**：计划没有关联文件且未提供 `filePath`。工具 MUST NOT 猜测路径、使用默认目录或自动命名。
- **`filePath` 形态**：必须是绝对路径、以 `.jmx` 结尾（不自动补扩展名）、父目录已存在（不自动创建）。
- **覆盖保护**：提供了 `filePath` 且该路径上已有文件时，必须同时传 `overwrite: true`。只要提供了 `filePath` 就一律按另存处理，目标恰为当前关联文件时同样要求 `overwrite`。不传 `filePath` 的存回不受此限，计划自己的文件直接覆写。
- **autosave 保护**：未提供 `filePath`，且计划的关联文件位于 workspace 的 `autosave/` 目录下——回合快照或运行自动存档被打开后即处于此状态。此时 SHALL 指引改用 `filePath` 另存到正式位置。显式提供 `filePath` 的保存不受此限。

#### Scenario: 从未保存的计划不猜路径
- **WHEN** 计划从未保存过（无关联文件），Agent 调用 `save_test_plan` 且不传 `filePath`
- **THEN** 返回错误，说明计划从未保存并指引提供 `filePath`
- **AND** 不弹文件选择框、不产生任何文件

#### Scenario: filePath 形态不合法被拒
- **WHEN** Agent 调用 `save_test_plan`，`filePath` 为相对路径、不以 `.jmx` 结尾、或父目录不存在
- **THEN** 返回错误并指出违反的那条约束，不写盘、不创建目录

#### Scenario: 另存覆盖已有文件需显式确认
- **WHEN** Agent 调用 `save_test_plan` 传 `filePath` 指向已存在的文件，但未传 `overwrite: true`
- **THEN** 返回错误并说明需 `overwrite: true`，该文件内容不变

#### Scenario: 带 overwrite 另存覆盖已有文件
- **WHEN** Agent 调用 `save_test_plan` 传 `filePath` 指向已存在的文件并传 `overwrite: true`
- **THEN** 该文件被当前测试计划覆写，文件关联改为该路径

#### Scenario: 关联指向 autosave 时无参保存被拒
- **WHEN** Agent 用 `open_jmx_file` 打开 `{workspace}/autosave/` 下的一份回合快照（关联随之指向该快照），随后调用 `save_test_plan` 且不传 `filePath`
- **THEN** 返回错误，说明关联文件是 autosave 快照、应改用 `filePath` 另存到正式位置
- **AND** 该快照内容不变

#### Scenario: 关联指向 autosave 时显式另存放行
- **WHEN** 承上一场景，Agent 调用 `save_test_plan` 传 `filePath` 为正式位置的 `.jmx`
- **THEN** 另存成功，文件关联离开 autosave 目录

### Requirement: 落盘与 Ctrl+S 等价

`save_test_plan` 的写盘 SHALL 走 JMeter 自身的保存动作，因而与用户按 Ctrl+S 共享同一组副作用：JMeter 的保存备份策略照常生效，计划有未保存改动时按 JMeter 属性在备份目录留存旧文件；保存后计划的"未保存"状态被清除，关闭或退出时不再因这些改动提示保存；插件注入的结果收集器节点 MUST NOT 被写入 `.jmx`；另存后跨实例发现看到的 jmx 路径同步为新路径。测试运行期间调用保存 SHALL 被允许，与 Ctrl+S 一致。另存为 MUST NOT 进入 JMeter 的文件选择框路径。系统 MUST NOT 检测或拦截磁盘文件被外部程序修改的情况，JMeter 自身不检测。

#### Scenario: 保存清除未保存状态
- **WHEN** Agent 修改了测试计划后调用 `save_test_plan` 存回关联文件，随后用户关闭 JMeter
- **THEN** JMeter 不再因这些改动弹出"是否保存"的确认

#### Scenario: 保存的文件不含结果收集器节点
- **WHEN** 测试运行期间（结果收集器节点存在于 GUI 树中）Agent 调用 `save_test_plan`
- **THEN** 写出的 `.jmx` 不包含插件的结果收集器节点，运行中的测试不受影响

#### Scenario: 另存后跨实例发现看到新路径
- **WHEN** 实例 A 的 Agent 调用 `save_test_plan` 另存到新路径，随后实例 B 列出本机实例
- **THEN** 实例 A 登记的"打开的 jmx"为新路径

### Requirement: 保存结果如实回传

JMeter 的保存动作在失败时不向调用方回传错误：异常被动作路由吞掉，只弹出一个错误对话框。因此工具 MUST NOT 以"已派发保存"当作成功。派发前 SHALL 做目标可写的前置检查——目标已存在则须可写，否则父目录须可写。派发后 SHALL 以"保存动作正常返回"作为成功信号，并复核目标文件确实存在。前置检查不通过、保存动作未正常返回、或目标文件不存在时，工具 SHALL 返回失败并说明目标路径，MUST NOT 报告成功。

#### Scenario: 目标不可写时前置拒绝
- **WHEN** Agent 另存到父目录不可写的路径，或存回一个只读的关联文件
- **THEN** 返回失败并说明目标路径，不派发保存动作

#### Scenario: 写盘失败不报成功
- **WHEN** 保存动作在写盘时失败
- **THEN** 工具返回失败并说明目标路径，不返回含 `bytes` 的成功结果

### Requirement: goal 完成保存复用同一套保存语义

goal 完成时由模型按指引发起的保存（触发条件见 goal-autonomous-loop）SHALL 就是一次普通的 `save_test_plan` 调用（不传 `filePath`），适用本能力的全部保护规则与落盘副作用，包括未保存计划与 autosave 保护导致的拒绝。工具结果（成功路径或拒绝原因）直接返回给模型，模型据此决定是否在 complete 前重试或向用户询问路径。

#### Scenario: 关联指向 autosave 时 goal 保存被拒
- **WHEN** 计划的关联文件位于 `autosave/` 目录下，goal 验证通过后模型调 `save_test_plan`
- **THEN** 保存被 autosave 保护拒绝，模型收到「未保存 + 应另存到正式位置」的工具错误
- **AND** 模型据此向用户询问保存路径或选择另存，而非由宿主静默处理
