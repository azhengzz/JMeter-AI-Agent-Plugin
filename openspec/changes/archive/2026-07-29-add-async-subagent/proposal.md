## Why

JMeter agent 当前只有单条主循环，遇到"先深度分析/检索、再综合"的复杂任务时，主代理上下文会被大量中间工具结果撑爆、token 浪费严重、聚焦度下降。Nanobot 用「异步子代理（SubAgent）」解决：主代理通过 `spawn` 把独立子任务委派给一个后台子代理（隔离的工具集、隔离的上下文、独立迭代循环），子代理完成后把**结果摘要**回合内回注主代理——主代理上下文保持干净，复杂任务得以分解。我们需要把这套机制移植到 JMeter agent，采用 Nanobot 的「异步派发 + 回合合流」混合模型。

## What Changes

- **新增异步子代理机制**：主代理 LLM 调用 `spawn` 工具派发子任务；spawn 立即返回（非阻塞），子代理在专用线程池后台运行独立 AgentRunner 循环；完成后将结果摘要作为注入消息回合内回注主代理，由主代理自然转述给用户。
- **回合合流（turn-confluence）**：主代理在 6 个注入检查点，若该会话仍有子代理在跑且无就绪消息，则**阻塞等待**（带 ~120s 超时，上限 300s）子代理结果，确保结果在同一回合被消费而非触发竞争性新任务。
- **工具 scope 机制**：`Tool` 接口新增 `default Set<String> getScopes()`（默认 `{core}`）；子代理工具集只 INCLUDE 含 `subagent` 标签的工具。子代理默认**只读分析型**工具集，排除改树/执行/编排工具。
- **防递归**：`spawn` 与 `subagent_status` 工具 scope 为 `{core}`，子代理工具集天然不含 → 从源头杜绝"子代理派生子代理"的无界递归。
- **彻底会话隔离**：子代理用临时内存 Session（`persistSession=false`）、独立 sessionKey（`subagent:<taskId>`）、不参与记忆整合——零字节写入主会话 jsonl，不污染主代理上下文。
- **可观测性**：主代理可通过 `subagent_status` 工具主动查询运行中/近期完成的子代理状态（phase/iteration/tool_events/usage）；日志记录子代理工具调用；终端用户**只看到完成态结果摘要**，无实时进度泄露。
- **跨池取消**：主代理 Stop / 会话切换 / IPC 超时取消主 run 时，级联取消该会话所有运行中子代理。

## Capabilities

### New Capabilities
- `async-subagent`: 异步子代理的完整生命周期——spawn 非阻塞派发、专用执行器、隔离工具集（scope 过滤）、回合内结果合流（阻塞式注入）、彻底会话隔离、状态查询与跨池取消。覆盖行为契约：何时阻塞、何时退化新回合、隔离边界、防递归保证、并发上限。

### Modified Capabilities
<!-- 无既有 spec（openspec/specs/ 为空）。本变更为首次引入 spec。 -->

## Impact

- **新增 7 个类 + 1 个模板**：`agent.subagent.SubagentManager/ SubagentStatus/ SubagentHook/ ResultSink`、`agent.run.AgentRunContext`、`agent.tools.subagent.SpawnTool/ SubagentStatusTool`、`resources/templates/subagent/subagent_announce.md`。
- **修改 8 个文件（全部 additive，零破坏主路径）**：
  - `Tool.java`（+`getScopes()` default）、`AgentRunSpec.java`（+`persistSession`/`runExecutor` 字段 + build 校验）、`AgentRunner.java`（3 个 persistSession 分支点 + runExecutor + AgentRunContext set/clear）、`InjectionManager.java`（+`drainBlocking`）、`ToolRegistry.java`（异步包装器重放 AgentRunContext）、`AgentLoop.java`（持有 SubagentManager + offerInjection seam + drainInjected + cancelActiveTask 级联取消 + shutdown）、`AgentLoopFactory.java`（接线 + 特性开关）、`BuiltinCommands.java`（`/new` 先取消再清空）。
- **执行器拓扑**：新增专用 fixed 线程池跑子代理（与 `agent-loop` 单线程、`tool-executor` cached 池、ForkJoinPool.commonPool、EDT 均隔离）；死锁可证伪（阻塞线程 T_main_loop ≠ 完成 future 的 T_subagent）。
- **GUI 安全**：子代理只读 + 主代理可能改树，均经 EDT 序列化（`EdtRunner.invokeAndWait`），无数据竞争，仅可接受的快照不一致。
- **配置**：新增 6 个 `agent.subagent.*` 属性；默认 `agent.subagent.enabled=false`（特性开关，渐进启用）。
- **依赖**：无新外部依赖；复用现有 `AiService`/`ContextBuilder`/`Session`/`InjectionManager`/`AgentHook`。
