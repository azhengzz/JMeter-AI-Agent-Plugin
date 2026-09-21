# Tasks: concurrency-safe-tool-batching

## 1. 死代码/死配置/硬编码清除

- [x] 1.1 `AgentRunSpec`：删除 `concurrentTools` 字段、getter、builder 默认值与 `concurrentTools(boolean)` 方法
- [x] 1.2 `AgentLoop:240`：删除 `.concurrentTools(false)` 行
- [x] 1.3 `AgentConfig`：删除 `concurrentToolsEnabled` 字段、构造器读取行、`:98` 日志行、`isConcurrentToolsEnabled()` getter
- [x] 1.4 配置文档：删 `jmeter-ai-sample.properties:294`、README.md:321 与 README_en.md:324 表行
- [x] 1.5 `mvn clean test-compile` 验证清除后全仓库编译通过（无残留引用）

## 2. concurrency_safe 分类机制

- [x] 2.1 `Tool` 接口新增 `default boolean isConcurrencySafe() { return false; }`，javadoc 注明"只读、无副作用、可与其他安全工具并行；覆盖即声明白名单准入"
- [x] 2.2 首批 13 个只读工具覆盖返回 `true`：`GetTestPlanTreeTool`、`FindElementTool`、`GetSelectedElementTool`、`QueryElementPropertiesTool`、`GetScriptInfoTool`、`ParseJmxFileTool`、`ReadFileTool`、`ListDirTool`、`GetTestStatusTool`、`GetTestResultsTool`、`WebFetchTool`、`WebSearchTool`、`ListInstancesTool`
- [x] 2.3 确认 `delegate_to_instance`、`spawn`/subagent 工具、`run_test` 及全部变更类工具保持默认 `false`（grep 审查无意外覆盖）

## 3. 分批执行（AgentRunner）

- [x] 3.1 `AgentRunner.executeToolCalls`：删除 `spec.isConcurrentTools()` 分支，改为按调用序分批——连续 `isConcurrencySafe()` 调用并为一个批，非安全调用各自单例批
- [x] 3.2 批执行：`size>1` 批经 `toolRegistry.executeAsyncWithEvents(batch, toolTimeoutMs).join()`；`size==1` 批沿用现行内联 `executeWithEvent` 串行路径（ThreadLocal 可见性不变）
- [x] 3.3 结果/事件跨批按原始调用顺序拼接；既有 `failOnToolError`、hook 时序语义不变
- [x] 3.4 更新方法 javadoc：分批纪律、nanobot 对照（`_partition_tool_batches`）

## 4. 运行上下文搬运（ToolRegistry）

- [x] 4.1 `ToolRegistry.executeAsyncWithEvent`：在既有 `AgentRunContext` 搬运块同构追加 `DelegationGuard`——调用线程 `isActive()` 捕获、任务内 `begin()`、finally 先 `end()` 后 `AgentRunContext.clear()`
- [x] 4.2 `DelegationGuard` javadoc 更新：删除"并发路径当前不可达"段落，改述单例批内联 + 派发点搬运双通道保证

## 5. 测试

- [x] 5.1 新增分批测试：连续安全调用并行（latch/时序断言）、非安全调用分割批次（互斥断言）、混合批次结果原序返回
- [x] 5.2 新增守卫测试：并行批内工具观察到 `DelegationGuard.isActive()==true`；工具结束后池线程无 `AgentRunContext`/`DelegationGuard` 残留
- [x] 5.3 回归：既有 ToolRegistry/AgentRunner 相关测试全绿；`mvn clean test` 全量通过

## 6. 文档同步

- [x] 6.1 CLAUDE.md：工具层描述补"concurrency_safe 分批并发（对齐 Nanobot，无用户开关）"；移除对 `agent.tools.concurrent.enabled` 的任何引用
- [x] 6.2 multi-instance design.md「D4 三次校正」追加一句：两件套已由 `concurrency-safe-tool-batching` 变更落地（链接变更名）
