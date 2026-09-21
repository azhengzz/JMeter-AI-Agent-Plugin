# Design: concurrency-safe-tool-batching

## Context

工具并发执行路径当前不可达：`AgentLoop:240` 硬编码 `concurrentTools(false)`；`agent.tools.concurrent.enabled` 在 `AgentConfig` 读取但零调用方（死配置）。multi-instance design.md「D4 三次校正」已完成 nanobot 对照分析并预留了移植计划（两件套），本变更是该计划的落地。

Nanobot 参考事实（已核实源码，`D:\WorkHome\git\github\nanobot-zheng\nanobot`）：

- **无用户级开关**：`config/`、`apps/`、`cli/` 全库零命中 `concurrent_tools` 配置；`agent/loop.py:824` 无条件硬编码 `concurrent_tools=True`。唯一"开关"是 `AgentRunSpec` dataclass 字段默认 `False`（API 级默认，生产调用方覆盖）。
- **安全性来源**：`agent/tools/base.py:167` `concurrency_safe` 默认 `read_only and not exclusive`——只读且非独占才可并行；`agent/runner.py:1553` `_partition_tool_batches` 按调用序把连续安全调用并为并行批、不安全调用切单例批，批间顺序、批内 `asyncio.gather` 并行。
- **上下文传播**：`contextvars.ContextVar`（RequestContext/file_states/workspace_scope，token 式 bind/reset）靠 asyncio.Task 创建时自动拷贝 context 进入并发工具——Java 17 无等价物，直译为派发点手动搬运（`ToolRegistry.executeAsyncWithEvent` 已有 AgentRunContext 搬运先例，`runner` 侧 spec 显式传参走我们既有的 ThreadLocal set/clear）。
- **不可移植差异**：nanobot 协程同事件循环线程、无线程跳变；我们的阻塞工具必须跳池线程，故单例批必须**内联调用线程**执行（而非再派发），保住 ThreadLocal 可见性。

## Goals / Non-Goals

**Goals:**

- 消灭死代码/死配置/硬编码三重债：删 `agent.tools.concurrent.enabled`、`AgentConfig.isConcurrentToolsEnabled()`、`AgentRunSpec.concurrentTools`。
- 落地 nanobot `concurrency_safe` 分批纪律：安全工具并行收益（典型只读多连发轮次时延从"求和"降为"取最大"），不安全工具行为与现状完全一致。
- `DelegationGuard`/`AgentRunContext` 在并行路径下可见性成立（D4 三次校正两件套之二）。

**Non-Goals:**

- 不做用户级/全局级并发开关（对齐 nanobot，分类即边界）。
- 不引入 `read_only`+`exclusive` 双属性派生（nanobot 的 `concurrency_safe = read_only && !exclusive` 在我们没有第二个消费方，单旗标足够）。
- 不改并行批内超时模型（沿用既有 per-tool `orTimeout`）、不改 `failOnToolError` 语义、不做工具优先级调度变更（既有批内优先级排序保留）。
- 不为 `delegate_to_instance`/`spawn`/变更类工具开启并行（保持默认 false）。

## Decisions

### D1. 删开关而非接线：对齐 Nanobot「分类即安全边界」

**选择**：彻底移除开关链（配置→AgentConfig→spec 字段→AgentLoop 硬编码），并发常开、分批兜底。
**替代**：把 `agent.tools.concurrent.enabled` 接线到 spec——保留开关则用户关掉后安全工具退化为串行（收益消失），打开后行为又取决于各工具分类，开关语义含糊；且 nanobot 无此开关，参考实现的经验是"门槛在分类不在开关"。
**风险控制**：全部工具默认不安全 ⇒ 移除开关后默认行为与旧串行路径逐位一致（见 spec「全不安全工具时与旧串行行为一致」场景），不存在"开关一删行为突变"。

### D2. 单例批内联调用线程，不派发执行器

**选择**：`AgentRunner.executeToolCalls` 分批后，`size==1` 的批沿用现行串行路径（`toolRegistry.executeWithEvent` 内联）；`size>1` 的安全批才走 `executeAsyncWithEvents(...).join()`。
**理由**：(a) 单例批内联 ⇒ `DelegationGuard`/`AgentRunContext` ThreadLocal 天然可见，D4 三次校正论证的"结构性保证"；(b) 与旧行为逐位一致（现状串行路径即内联）；(c) 避免为单个工具付线程跳变 + 上下文搬运成本。
**替代**：全部批都派执行器再搬运上下文——多一次线程跳变与搬运，收益为零。

### D3. 分批逻辑放 `AgentRunner.executeToolCalls`，不放 `ToolRegistry`

**选择**：分批（按调用序切批）在 AgentRunner，批内执行复用 `ToolRegistry.executeAsyncWithEvents`（保留其批内优先级排序与结果原序返回）。
**理由**：对齐 nanobot（`_partition_tool_batches` 在 runner）；`ToolRegistry` 保持"执行给定调用列表"的职责，公开 API 零变化（既有测试不破）。

### D4. `Tool.isConcurrencySafe()` 接口 default 方法，默认 false

**选择**：`default boolean isConcurrencySafe() { return false; }`，安全工具显式覆盖。
**理由**：default 方法不破坏既有实现类（172 个组件工具零改动）；默认不安全 = 白名单准入，新增工具需显式声明才可并行。等价于 nanobot `read_only && !exclusive` 的保守缺省（两者默认均"不可并行"）。
**首批准入**（全部只读，13 个）：`GetTestPlanTreeTool`、`FindElementTool`、`GetSelectedElementTool`、`QueryElementPropertiesTool`（4 个 JMeter 树读，见 Risks 的 C1）、`GetScriptInfoTool`、`ParseJmxFileTool`（2 个静态/文件读）、`ReadFileTool`、`ListDirTool`（2 个文件读）、`GetTestStatusTool`、`GetTestResultsTool`（2 个执行状态读）、`WebFetchTool`、`WebSearchTool`（2 个 Web 读）、`ListInstancesTool`（IPC 只读发现）。排除：`delegate_to_instance`（阻塞+有副作用）、`run_test`（启动状态变更）、`get_log_panel_content`（已 EDT 封装读，标了无收益且徒增 EDT 竞争）、一切树/文件/元素变更工具、`spawn`（异步子代理生命周期）。

### D5. 守卫搬运：`executeAsyncWithEvent` 派发点 capture/set/clear

**选择**：在既有 `AgentRunContext` 搬运块（`ToolRegistry.executeAsyncWithEvent`）同构追加 `DelegationGuard`：调用线程 `isActive()` 捕获 → 工具任务内 `begin()` → finally `end()`。
**理由**：并行路径真实可达后这是活代码（安全批内的工具跑池线程）；即使未来某安全工具内部再触发委派判断也守恒。单例批内联不经过此路径，靠 D2 结构性可见。

### D6. JMeter 树并发读安全：依赖既有 EDT 封装 + 批内无写

**分析**：安全批内全部只读、无树写；JMeter 工具对 `GuiPackage`/树模型的访问本就按 EDT 模型封装（见记忆 jmeter-gui-edt-threading），并发读经 EDT 串行化后与今日单工具执行的竞态面一致（用户同时在 GUI 编辑的竞态今天已存在，非本变更引入）。`GetTestStatus/GetTestResults` 读运行态收集器（volatile/快照），并发读安全。

## Risks / Trade-offs

- [安全工具被误标（写操作伪装成读）→ 批内真数据竞争] → 缓解：白名单准入 + default false；PR 审查规则"覆盖 `isConcurrencySafe` 的工具必须只读"；`delegate_to_instance`/`spawn` 明确排除。
- [池线程上 `DelegationGuard.begin/end` 与 AgentRunContext 清理顺序错位 → 残留/泄漏] → 缓解：同一 finally 块内先 `end()` 后 `AgentRunContext.clear()`；新增"池化线程无残留"场景测试。
- [并行批占满工具执行器 → 慢工具（WebFetch）拖批] → 缓解：per-tool `orTimeout` 不变，批内最慢者封顶；执行器既有容量与超时机制不变。
- [删除 `AgentRunSpec.concurrentTools` 是源不兼容变更] → 影响面：全仓库仅 `AgentLoop:240` 调用 builder 方法、无测试引用；插件无外部 API 消费者。可接受。
- [用户配置残留 `agent.tools.concurrent.enabled` 行] → 无代码读取，静默忽略（spec 场景已定义）；README/sample 同步删除引导。
- [树读工具非 EDT 读 `DefaultTreeModel`（JMeterTreeModel extends DefaultTreeModel）与用户 EDT 编辑竞态（C1）] → **存量问题、非本变更引入**：串行时代的树读工具同样跑在 run 载体线程（`executeWithEvent` 内联路径，非 EDT），与用户在 EDT 上的拖拽/删节点从来无互斥——本变更仅把树读放进并发批，使重叠窗口略为加宽（一轮内多个树读同时遍历同一棵树）。后果为间歇性 `ConcurrentModificationException`/越界/半吊子快照，且被各工具的 try/catch 兜成 error 而非 kill JVM。**本轮处置：记录、不动白名单**（摘除白名单并不消除该竞态——单例批仍内联在 run 载体线程，仍非 EDT）。根治需另行变更：给树读工具加 `invokeAndWait` EDT 封装，使读序列化到 EDT 与用户写互斥（这是唯一正确解，与并发机制正交）。

## Migration Plan

1. 代码落地（本变更 tasks）→ `mvn clean test` 全量。
2. 回滚策略：单 commit revert 即可恢复旧串行路径（所有删除集中、新增代码路径默认不触发）。
3. 文档同步：README 双语表删行、`jmeter-ai-sample.properties` 删行、CLAUDE.md 工具层描述更新、multi-instance design.md D4 三次校正追加"已由 concurrency-safe-tool-batching 落地"指向。

## Open Questions

（无——nanobot 参考实现已消除主要设计不确定性；首批准入清单可在 code review 时增删，不影响机制。）
