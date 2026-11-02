# tool-concurrency Specification

## Purpose

工具并发执行的资格分类与分批调度——为每个工具提供 `isConcurrencySafe()` 分类（默认 `false`），仅只读、无副作用的安全工具有资格并行执行。按调用原始顺序把连续的 safe 调用合并为并行批、非 safe 调用各自构成单例串行批，批间顺序、批内并行；结果与事件按原始调用顺序返回。覆盖批内超时/错误隔离、运行期上下文（`AgentRunContext` 与 `DelegationGuard`）跨执行线程搬运，以及移除未接线的并发开关与死代码（对齐 Nanobot 无用户级开关）。

## Requirements

### Requirement: 工具按 concurrency_safe 分类决定并行资格

系统 SHALL 为每个工具提供 `isConcurrencySafe()` 分类（默认 `false`），仅返回 `true` 的工具有资格进入并行批执行。分类为安全即声明该工具只读、无副作用、可与其他安全工具同时执行。

#### Scenario: 默认不安全
- **WHEN** 工具未覆盖 `isConcurrencySafe()`
- **THEN** 该工具返回 `false`，永远不会与其他工具并行执行

#### Scenario: 只读工具准入
- **WHEN** 工具（如 `get_test_plan_tree`、`read_file`、`web_search`、`list_instances`）覆盖返回 `true`
- **THEN** 该工具有资格与同批其他安全工具并行执行

#### Scenario: 阻塞/副作用工具永不并行
- **WHEN** 被委派回合内模型调用 `delegate_to_instance`（未覆盖分类，默认 `false`）等多个工具
- **THEN** `delegate_to_instance` 必须以单例批内联串行执行，不得与其他工具并行

### Requirement: 分批纪律——安全并行批 + 不安全单例串行批

系统 SHALL 按调用原始顺序分批：连续的 concurrency-safe 工具调用合并为一个并行批，非安全调用各自构成单例批；批间顺序执行、批内并行执行。结果与事件 SHALL 按原始调用顺序返回，与执行顺序无关。

#### Scenario: 连续安全调用并行
- **WHEN** 单轮 LLM 返回 [readA(安全), readB(安全), readC(安全)]
- **THEN** 三者合并为一个并行批同时执行，总耗时接近最慢者而非三者之和

#### Scenario: 安全调用被非安全调用分割
- **WHEN** 单轮返回 [readA(安全), createB(不安全), readC(安全)]
- **THEN** 分批为 [readA]、[createB]、[readC] 三批顺序执行，任何两个工具不同时运行

#### Scenario: 结果顺序稳定
- **WHEN** 并行批内工具完成顺序与调用顺序不同
- **THEN** 返回的 ToolResult 与 ToolEvent 列表仍按原始调用顺序排列

### Requirement: 单例批工具的 GUI 线程等待有界

单例批（非并发安全）工具经统一 EDT 执行咽喉等待 JMeter GUI 事件线程时，等待 SHALL 有界：上限经 `agent.tools.edt.wait.timeout.ms` 配置（默认 120000ms，下限钳 1000ms，无"0=关闭"语义）。

等待超时或被中断（如 Stop）时，系统 SHALL 仅放弃调用方等待并返回结构化错误，错误文案 SHALL：声明操作**结局未知**（可能仍在排队、执行中、或已完成——调用方放弃等待不等于操作未执行）、引导先经 `get_test_plan_tree` 等不依赖 EDT 派发的只读工具复查现状后再行动、明确禁止盲目重试写操作（重试可能叠加在迟到的原操作之上）。

已投递到 EDT 的任务在任何情况下 MUST NOT 被撤回或中断，SHALL 在 GUI 线程恢复后完整执行（与既有 Stop 中断后的孤儿任务语义同构）。工具对 EDT 执行错误 SHALL 可见——任何调用点 MUST NOT 把 EDT 错误吞没为成功形态结果（**例外**：源码注释明示为有意的制备性 best-effort 步骤——如批量工具主块前的 `deselectCurrentNode` warn-only 取消选中——不改变树结构，吞没为告警可接受）。

#### Scenario: GUI 线程长期不派发事件时等待有界
- **WHEN** GUI 事件线程被长任务占用超过配置上限，Agent 调用写树工具（如 create_element）
- **THEN** 工具在上限到达时返回含"结局未知"与复查指引的结构化错误，回合继续推进而非无限停滞
- **AND** jmeter 日志留有 EDT 等待超时记录

#### Scenario: Stop 中断等待后 EDT 任务不撤回
- **WHEN** 工具正在等待 EDT 执行期间用户按下 Stop（运行线程被中断）
- **THEN** 工具返回中止/结局未知语义的错误，不抛出无文案的裸中断
- **AND** 已投递到 EDT 的任务不被取消，GUI 线程恢复后照常完整执行

#### Scenario: 超时错误不得被吞没为成功
- **WHEN** 批量更新工具（batch_update）的 EDT 执行等待超时
- **THEN** 工具返回超时错误结果给模型，MUST NOT 以"Updated 0 of N elements"成功形态掩盖错误

#### Scenario: 正常路径行为不变
- **WHEN** GUI 线程健康，工具的 EDT 操作毫秒级完成
- **THEN** 工具结果、类加载器切换与 `setProperties + refreshCurrentGui` 同块原子性与既有行为完全一致

### Requirement: 并行批内超时与错误互不传染

并行批内每个工具 SHALL 独立享有超时约束；单工具超时或失败 SHALL 仅产生该工具的错误结果/事件，不中断同批其他工具，也不改变 AgentRunner 既有 `failOnToolError` 语义。

并行批工具超时时，系统 SHALL best-effort 中断该工具仍在执行的工作（该路径仅 concurrency-safe 只读工具可达；中断 MUST NOT 波及 EDT 上已排队或执行中的任务）；工具若不响应中断则照常跑完，其超时错误结果不变。

#### Scenario: 批内单工具超时
- **WHEN** 并行批中 readA 正常完成、readB 超过工具超时上限
- **THEN** readA 返回正常结果，readB 返回超时错误结果，批整体不抛异常

#### Scenario: 超时后泄漏的工作被尽力取消
- **WHEN** 并行批中 readB 超时且其池线程仍在阻塞等待（如等待 GUI 线程或慢 IO）
- **THEN** 该池线程被中断以尽早退出，readB 的超时错误结果与同批其他工具均不受影响
- **AND** 若该工具内部已向 GUI 线程排队任务，该任务不受中断影响、稍后照常执行

### Requirement: 运行上下文跨执行线程搬运

系统 SHALL 在工具被派发到池化执行线程时搬运运行期上下文：`AgentRunContext`（既有）与 `DelegationGuard`（新增）在派发点于调用线程捕获、工具线程内置位、finally 清除。被委派回合内并行执行的安全工具 SHALL 能观察到深度守卫状态。

#### Scenario: 并行工具内守卫可见
- **WHEN** 被委派回合（`DelegationGuard` 已置位）中一个安全工具与其他安全工具并行执行且该工具内部读取守卫状态
- **THEN** 该工具在其执行线程上观察到 `DelegationGuard.isActive() == true`

#### Scenario: 池化线程无残留
- **WHEN** 任一工具执行完毕（含异常路径）
- **THEN** 其执行线程上的 `AgentRunContext` 与 `DelegationGuard` 均被清除，不泄漏给该池化线程上的后续任务

### Requirement: 移除未接线的并发开关与死代码

系统 SHALL NOT 保留未接线的并发开关：`agent.tools.concurrent.enabled` 配置项、`AgentConfig.isConcurrentToolsEnabled()`、`AgentRunSpec.concurrentTools` 字段及其 builder 方法 SHALL 全部移除。工具并发行为 SHALL 完全由 `concurrency_safe` 分批纪律决定，不提供用户级开关（对齐 Nanobot：loop 无条件 `concurrent_tools=True`，无配置项）。

#### Scenario: 死配置移除后行为不变
- **WHEN** 用户配置文件中仍残留 `agent.tools.concurrent.enabled=true`（历史遗留）
- **THEN** 该行被忽略（无任何代码读取），工具并发行为仅由各工具的安全分类决定

#### Scenario: 全不安全工具时与旧串行行为一致
- **WHEN** 某轮 LLM 返回的工具调用全部未覆盖安全分类（默认 false）
- **THEN** 全部以单例批内联串行执行，行为与移除开关前的串行路径一致