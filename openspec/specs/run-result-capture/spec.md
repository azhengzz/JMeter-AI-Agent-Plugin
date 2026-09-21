# run-result-capture Specification

## Purpose

测试运行结果的采集与读取——一个在 GUI 启动时注册的全局生命周期钩子，确保**任何** GUI 内发起的 JMeter 运行（用户点击 Run 按钮，或 Agent 调用 `run_test`）都被同一个内存结果收集器采集。覆盖采集时机与 EDT 线程约束、`reset()` 语义、运行来源（provenance）记录与暴露、与 `run_test` 的双重注入去重、预监听器健壮性、收集器节点不泄漏进 `.jmx`、特性开关门控，以及读取工具对 GUI 运行返回实时数据。

## Requirements

### Requirement: 采集用户在 GUI 发起的测试运行

系统 SHALL 在 GUI 初始化时注册一个针对 JMeter `Start` 命令类（`org.apache.jmeter.gui.action.Start`）的 ActionRouter **预**监听器（`addPreActionListener`）。当用户通过 JMeter GUI 发起本地测试运行时，该监听器 SHALL 在 `Start.doAction` 读取并克隆测试树之前，将 `AgentResultCollector` 注入测试计划树并 `reset()`，使本次运行的 sample 被采集。该监听器 SHALL 仅对启动类动作（`ACTION_START`、`ACTION_START_NO_TIMERS`、`RUN_TG`、`RUN_TG_NO_TIMERS`）执行注入，对 `ACTION_STOP`、`ACTION_SHUTDOWN`、`VALIDATE_TG` 及其余动作不做任何变更。远程/分布式运行（`RemoteStart`）与无界面/CLI 运行明确不在范围内。

#### Scenario: 用户点击 Run 按钮，结果被采集
- **WHEN** 用户在 JMeter GUI 点击 Run（触发 `ACTION_START`）且测试计划含线程组
- **THEN** `Start.class` 预监听器在 `Start.doAction` 之前把 `AgentResultCollector` 注入测试计划树并 `reset()`
- **AND** 该运行的 sample 被采集进收集器的静态状态
- **AND** 事后 `get_test_results` 返回本次运行的采样数据

#### Scenario: Run Thread Group（RUN_TG）路径同样被采集
- **WHEN** 用户对选中的线程组点击 Run（触发 `RUN_TG` 或 `RUN_TG_NO_TIMERS`）
- **THEN** 预监听器同样注入并 `reset()` 收集器
- **AND** 被选中线程组的 sample 被采集

#### Scenario: 预监听器对停止/关停动作无副作用
- **WHEN** `ACTION_STOP` 或 `ACTION_SHUTDOWN` 被派发（`Start` 同样处理这些动作）
- **THEN** 预监听器不调用 `reset()`、不注入收集器、不变更任何已采集结果

#### Scenario: 注入时机早于树克隆
- **WHEN** 任意启动类动作被派发
- **THEN** 收集器节点在 `Start.startEngine` 执行 `cloneTree` 之前已存在于 GUI 树模型中
- **AND** 克隆后交给引擎的测试树包含该收集器节点，故 `TestCompiler` 将其编译为 `SampleListener`

#### Scenario: JMeter 默认 save-before-run 不破坏采集
- **WHEN** 计划已保存为 `.jmx` 且 JMeter 默认 `save_automatically_before_run=true`，用户点击 Run（或 Agent 调 `run_test`）
- **THEN** `Start.doAction` 内 `popupShouldSave` 同步触发的 SAVE 先经 Save 预监听器剥离收集器（写出干净 `.jmx`），随后 Save 后监听器在 `startEngine` 之前重新注入收集器
- **AND** `startEngine` 克隆的树包含收集器，sample 被正常采集（不因 save-before-run 而落空）
- **AND** 该保证对 `run_test`（AGENT）在任何开关取值下都成立

### Requirement: 运行来源（provenance）记录与暴露

对每次被采集的运行，系统 SHALL 记录其来源为 `USER`（GUI 发起）或 `AGENT`（`run_test` 工具发起），并在 `get_test_status` 的输出中暴露该来源。来源判别 SHALL 基于 ACTION_START 事件的 `source`——`RunTestTool` 实例即 `AGENT`，其余即 `USER`——且 MUST 不依赖任何可跨运行残留的共享可变标志。

#### Scenario: 用户运行标记为 USER
- **WHEN** 用户在 GUI 发起运行
- **THEN** 收集器记录 `lastProvenance = USER`
- **AND** `get_test_status` 输出包含 `Started by: USER`

#### Scenario: Agent 运行标记为 AGENT
- **WHEN** `RunTestTool` 发起运行（构造 `new ActionEvent(this, ...)`）
- **THEN** 收集器记录 `lastProvenance = AGENT`
- **AND** `get_test_status` 输出包含 `Started by: AGENT`

#### Scenario: provenance 跨 EDT 派发仍正确
- **WHEN** `RunTestTool` 的 EDT 块（E1）与预监听器所在的 EDT 块（E2）为两个先后派发
- **THEN** 来源判别仍基于事件 `source` 正确得出（无 set/clear 标志的泄漏窗口）

### Requirement: Agent 运行采集零回归（含特性开关关闭）

`RunTestTool` SHALL 保留其现有收集器注入路径，仅将 `reset()` 调用改为以 `AGENT` 来源重置；`RunTestTool` MUST NOT 依赖全局预监听器来完成 Agent 运行的采集。当 `agent.runcapture.enabled=false` 时，`run_test` 发起的运行 SHALL 仍被完整采集。

#### Scenario: 开关关闭时 run_test 仍采集
- **WHEN** `agent.runcapture.enabled=false` 且 Agent 调用 `run_test`（action=start）
- **THEN** `RunTestTool` 自行注入收集器并 `reset(AGENT)`
- **AND** 该运行被采集，`get_test_results` 返回非空结果

#### Scenario: RunTestTool 不读取特性开关
- **WHEN** `agent.runcapture.enabled` 取任意值且 Agent 调用 `run_test`
- **THEN** `RunTestTool.doStart` 的注入与启动行为不随开关取值变化

### Requirement: 每条启动恰一个注入器（防双重注入）

当全局预监听器已注册时，对同一次启动 MUST 恰有一个注入器：若该启动由 `RunTestTool` 发起（`AGENT`），预监听器 SHALL 完全跳过注入（`RunTestTool` 已注入）；若由用户发起（`USER`），预监听器 SHALL 执行注入。任何被采集的运行 SHALL 在编译后的引擎树中恰有一个 `AgentResultCollector` 节点，sample 不得被重复计数。

#### Scenario: Agent 运行不产生双重注入
- **WHEN** 开关开启，Agent 调用 `run_test`
- **THEN** 预监听器识别来源为 `AGENT` 后直接返回，不注入
- **AND** 引擎编译后的树中恰一个收集器节点，sample 计数无翻倍

#### Scenario: 用户运行由预监听器注入
- **WHEN** 开关开启，用户在 GUI 发起运行
- **THEN** 预监听器识别来源为 `USER` 并执行注入
- **AND** 引擎编译后的树中恰一个收集器节点

### Requirement: 预监听器健壮性（永不阻断测试启动）

全局预监听器 SHALL 捕获并吞掉其体内的一切异常（`Throwable`，记录日志后返回），MUST NOT 向 `ActionRouter` 抛出任何异常——否则 `ActionRouter` 会跳过 `Start.doAction` 导致测试无法启动。预监听器 SHALL 在已有测试运行中（`JMeterContextService.getTestStartTime()>0` 或 `AgentResultCollector.isTestRunning()` 为真）时，对 USER 路径跳过 `reset()` 与注入，以免实时清零进行中的计数器。

#### Scenario: 注入过程异常被吞，启动不受影响
- **WHEN** 预监听器在注入过程中抛出异常（例如找不到 TestPlan 节点）
- **THEN** 异常被捕获并记录为日志，预监听器正常返回
- **AND** `Start.doAction` 照常执行，测试照常启动

#### Scenario: 已有运行中收到启动动作时跳过
- **WHEN** `getTestStartTime()>0`（已有运行）时收到一个启动类动作
- **THEN** 预监听器跳过 `reset()` 与注入
- **AND** 进行中运行的已采集计数器不被实时清零

### Requirement: 收集器节点不泄漏进 .jmx 文件

系统 SHALL 注册一个针对 JMeter `Save` 命令类（`org.apache.jmeter.gui.action.Save`）的预监听器，在 `Save.doAction` 读取测试树之前移除任何 `AgentResultCollector` 节点。该剥离 SHALL 覆盖 `SAVE`、`SAVE_AS`、`SAVE_ALL_AS`、`SAVE_AS_TEST_FRAGMENT`，且 MUST NOT 受 `agent.runcapture.enabled` 开关影响（防泄漏优先于开关）。

#### Scenario: 运行中保存不写入收集器节点
- **WHEN** 测试运行进行中（收集器节点存在于 GUI 树），用户保存测试计划
- **THEN** `Save.class` 预监听器在保存前移除收集器节点
- **AND** 保存的 `.jmx` 文件不含 `__agent_result_collector__`

#### Scenario: 无收集器节点时保存为 no-op
- **WHEN** GUI 树中无收集器节点时用户保存
- **THEN** `Save.class` 预监听器不产生副作用

#### Scenario: 开关关闭仍剥离
- **WHEN** `agent.runcapture.enabled=false` 且用户保存
- **THEN** `Save.class` 预监听器仍被注册并执行剥离

### Requirement: 特性开关门控全局采集注册

特性开关 `agent.runcapture.enabled`（默认 `true`）SHALL 仅门控 `Start.class` 预监听器的注册，不门控 `Save.class` 剥离监听器、也不影响 `RunTestTool`。预监听器的注册 SHALL 恰发生一次——经插件 GUI 初始化（`SelectionInitCommand` 处理 `ADD_ALL` 时的一次性守卫）完成。

#### Scenario: 开关关闭不注册 Start 预监听器
- **WHEN** `agent.runcapture.enabled=false` 且 GUI 完成 `ADD_ALL` 初始化
- **THEN** `Start.class` 预监听器不被注册，GUI 发起的运行不被采集
- **AND** `Save.class` 剥离监听器仍被注册

#### Scenario: 注册恰发生一次
- **WHEN** `ADD_ALL` 被多次派发（例如关闭文件后重新初始化）
- **THEN** 预监听器仅注册一次（一次性守卫 + 单例实例去重）
- **AND** 单次启动不会触发监听器多次执行

### Requirement: 读取工具对 GUI 运行返回实时数据

`get_test_status` 与 `get_test_results` SHALL 对用户在 GUI 发起的运行返回与 Agent 发起运行相同结构的实时采集数据。当不存在任何被采集的运行结果时，工具 SHALL 明确报告"无可采集的运行结果"，MUST NOT 返回陈旧数据。

#### Scenario: 用户运行后读取到实时数据
- **WHEN** 用户在 GUI 发起运行，随后 Agent 调用 `get_test_status` 或 `get_test_results`
- **THEN** 工具返回本次运行的实时统计/采样
- **AND** `get_test_status` 输出 `Started by: USER`

#### Scenario: 无被采集运行时明确报告
- **WHEN** 不存在任何被采集的运行（如本次会话仅存在 GUI 运行但开关关闭、或尚无运行）
- **THEN** 读取工具返回明确的"无可采集运行结果"提示
- **AND** 不返回来自上一轮的陈旧数据
