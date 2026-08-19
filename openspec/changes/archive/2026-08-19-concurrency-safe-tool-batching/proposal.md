# Proposal: concurrency-safe-tool-batching

## Why

工具并发路径当前是死代码：`AgentLoop` 构建 spec 时硬编码 `concurrentTools(false)`，而 `agent.tools.concurrent.enabled` 配置读了但从未接线（`AgentConfig.isConcurrentToolsEnabled()` 零调用方）。既浪费了 LLM 多工具调用时并行执行只读工具的时延收益，又留下"死配置 + 硬编码"的双重技术债（见 multi-instance design.md D4 三次校正）。Nanobot 参考实现给出了成熟方案：并发常开（loop 无条件 `concurrent_tools=True`，无用户级开关），安全性完全由 `concurrency_safe` 分批纪律（只读工具才并行、其余单例批串行）承担。

## What Changes

- **删除并发开关，拒绝死代码**：移除 `agent.tools.concurrent.enabled` 配置（AgentConfig 字段/getter/日志、`jmeter-ai-sample.properties`、README 双语表格）与 `AgentRunSpec.concurrentTools` 字段及 builder 方法（`AgentLoop:240` 硬编码行随之删除）。对齐 Nanobot：无用户级开关，分批纪律自身即安全边界。
- **新增 `Tool.isConcurrencySafe()` 分类**（接口 default 方法，默认 `false`）：等价于 Nanobot `concurrency_safe`（其默认 `read_only && !exclusive`）的 Java 单旗标简化。
- **Nanobot 式分批执行**：`AgentRunner.executeToolCalls` 改为先按调用序分批——连续的 concurrency-safe 调用并为一个并行批（经 `executeAsyncWithEvents`），非安全调用各自成单例批**内联串行**执行（保留在 run 载体线程上，ThreadLocal 可见性不变）。
- **首批安全工具准入**（全部只读）：JMeter 树查询 4 个（GetTestPlanTree/FindElement/GetSelectedElement/QueryElementProperties）、文件读取 2 个（ReadFile/ListDir）、执行状态 2 个（GetTestStatus/GetTestResults）、Web 2 个（WebFetch/WebSearch）、IPC 1 个（ListInstances）。`delegate_to_instance`（阻塞/有副作用）与所有变更类工具保持默认不安全。
- **DelegationGuard 随 AgentRunContext 搬运**：`ToolRegistry.executeAsyncWithEvent` 异步派发处 capture/set/clear 守卫（D4 三次校正规划的防御纵深，并行路径真实可达后由死转活），跨实例委派深度 1 守卫在并发模式下同样成立。

## Capabilities

### New Capabilities
- `tool-concurrency`: 工具调用并发执行机制——concurrency_safe 分类、分批纪律（安全并行批 + 不安全单例串行批）、批内超时与结果序、运行上下文（AgentRunContext/DelegationGuard）跨执行线程搬运。

### Modified Capabilities
（无——`agent.tools.concurrent.enabled` 为从未生效的死配置，删除它不构成 spec 级行为变更）

## Impact

- **代码**：`Tool` 接口（+default 方法）、`AgentRunner.executeToolCalls`（分批逻辑替换 concurrent 分支）、`ToolRegistry.executeAsyncWithEvent`（守卫搬运）、`AgentRunSpec`/`AgentConfig`/`AgentLoop`（死代码删除）、13 个只读工具（覆盖 `isConcurrencySafe`）。
- **线程模型**：只读工具可能并行执行——JMeter 树/GUI 的并发读依赖既有 EDT 封装（读工具内部已按 EDT 模型处理）；单例批仍内联 run 线程，`DelegationGuard`/`AgentRunContext` 可见性与串行时代一致。
- **文档**：README 双语配置表删 1 行、CLAUDE.md 工具层描述、multi-instance design.md D4 三次校正的"将来接线时"计划由本变更落地（追加指向）。
- **测试**：新增分批/守卫可见性测试；全量回归。
