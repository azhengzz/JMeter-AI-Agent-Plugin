## Why

Agent 当前只能读取**它自己发起**的测试结果：`run_test` 在启动前往测试树注入 `AgentResultCollector`（`RunTestTool.doStart` 唯一做这件事的地方），`get_test_status` / `get_test_results` 再从该收集器的静态字段读取。但用户日常是直接点 JMeter 工具栏的绿色 Run 按钮跑测试——这条路径完全不经过 `run_test`，没有收集器被注入、`reset()` 不会被调用，结果工具只能返回陈旧或空数据。用户随时想"就刚才在 GUI 跑出来的结果问 AI"成了不可能的事（要么用 Agent 重跑，要么手工把结果贴给 AI）。**结果采集应当跟随"测试"本身，而不是跟随"谁按下了启动"。**

## What Changes

- **采集所有 GUI 运行（不限于 Agent 运行）**：新增一个插件级测试生命周期钩子，在 GUI 初始化时注册一次，监听 JMeter 的"测试启动"动作；无论运行由用户（Run 按钮）还是 Agent（`run_test`）触发，都确保 `AgentResultCollector` 被注入测试树并 `reset()`——把当前写死在 `RunTestTool.doStart` 里的注入逻辑上提为唯一的注入点。
- **保持 Agent 运行行为不变**：`run_test` 仍正常工作；统一注入点后 Agent 自己发起的运行同样被覆盖，且消除"工具与钩子双重注入"的冲突风险。
- **运行来源（provenance）可追溯**：采集结果记录"谁启动的运行"（用户 / Agent）与起止时间，使 Agent 能回答诸如"展示我刚才在 GUI 跑的那次的结果"。
- **读取工具契约不变**：`get_test_status` / `get_test_results` 维持现有静态读取契约，现在对 GUI 运行也能返回实时数据；无需新增读取工具。
- **保留性兜底**：若用户手动从树里删掉收集器节点、或运行在钩子未注册时发生，读取工具优雅降级（明确报告"无可采集的运行结果"而非返回陈旧数据）。
- **明确排除（Non-Goal）**：不采集非 GUI 运行（headless / CLI `jmeter -n`）的结果——插件仅在 GUI 模式加载，该场景不触及。

## Capabilities

### New Capabilities

- `run-result-capture`: 测试运行结果的采集与读取——一个在 GUI 启动时注册的全局生命周期钩子，确保**任何** GUI 内发起的 JMeter 运行（用户点击 Run 按钮，或 Agent 调用 `run_test`）都被同一个内存结果收集器采集；覆盖注入时机与 EDT 线程约束、`reset()` 语义、运行来源记录、与 `run_test` 的去重、读取工具对 GUI 运行的可用性、以及无收集器时的降级。

### Modified Capabilities

<!-- openspec/specs/ 当前仅有 async-subagent，与本变更无需求交集。本变更为首次引入执行/结果相关 spec。 -->

## Impact

- **复用现有收集器**：`AgentResultCollector`（`agent/tools/jmeter/execution/AgentResultCollector.java`）的静态状态、`SampleListener`/`TestStateListener`/`NoThreadClone`/`Remoteable` 实现保持不变；可能新增"运行来源"字段与对应的 `reset(provenance)` 重载。
- **重构 `RunTestTool`**（`RunTestTool.java:139-187`）：注入收集器 + `reset()` 的逻辑上提为共享入口；`run_test` 改为委托给钩子/共享注入器，避免双重注入，`ACTION_START` 触发逻辑保留。
- **新增生命周期钩子组件**：镜像现有 `SelectionInitCommand`（监听 `ActionNames.ADD_ALL` 安装选择追踪器）的注册模式，新增一个监听"测试启动"动作的预监听器，在 JMeter 编译测试树前注入收集器并 `reset()`。
- **注册点**：在插件唯一入口 `AiMenuCreator`（或与 `SelectionInitCommand` 同处的 GUI 初始化点）登记该钩子。
- **读取工具**：`GetTestStatusTool` / `GetTestResultsTool` 无契约改动，自动获得 GUI 运行数据；仅在"无采集运行"时调整文案。
- **线程模型**：注入与 `ACTION_START` 派发均在 EDT（与现有 `RunTestTool` 一致）；读取工具经 EDT/静态读，沿用现有约定。
- **依赖**：无新外部依赖；仅复用 JMeter `ActionRouter` / `ActionNames` / `GuiPackage` / `JMeterContextService` SPI（`SelectionInitCommand` 已证明可行）。
- **配置**：拟新增特性开关（默认开启），允许用户在出现边缘问题时关闭全局采集，回退到仅 Agent 运行被采集。
