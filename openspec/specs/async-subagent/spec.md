# async-subagent Specification

## Purpose

异步子代理（SubAgent）的完整生命周期：主代理通过 `spawn` 工具非阻塞派发只读分析子任务给后台子代理（隔离的工具集、隔离的上下文、独立迭代循环、专用线程池），子代理完成后将结果摘要作为注入消息回合内回注主代理。覆盖行为契约：何时阻塞等待、何时退化为非阻塞、会话隔离边界、防递归保证、并发上限、可观测性与跨池取消。

## Requirements

### Requirement: 非阻塞 spawn 派发

系统 SHALL 提供名为 `spawn` 的工具供主代理 LLM 调用，用于把一个独立子任务委派给后台子代理。`spawn` MUST 立即返回（非阻塞），仅回执 taskId 与"完成后通知"提示；子代理的实际执行 MUST 在专用线程池异步进行，不得占用主代理迭代线程。每主会话同时运行的子代理数量 MUST 不超过 `agent.subagent.max.concurrent`（默认 1）。

#### Scenario: spawn 立即返回 taskId，子代理后台运行
- **WHEN** 主代理 LLM 在工具调用中调用 `spawn(task=...)`
- **THEN** `spawn` 工具在不等待子代理完成的情况下返回成功，回执包含 taskId
- **AND** 子代理在 `SubagentManager` 的专用线程池上异步启动独立 AgentRunner 循环

#### Scenario: 达到并发上限时拒绝派发
- **WHEN** 主会话已有 `agent.subagent.max.concurrent` 个子代理在运行，主代理再次调用 `spawn`
- **THEN** `spawn` 工具同步返回错误结果（非阻塞），提示并发上限已达，不创建新子代理

#### Scenario: 特性关闭时不注册 spawn 工具
- **WHEN** `agent.subagent.enabled=false`
- **THEN** `spawn` 与 `subagent_status` 工具不被注册到主 ToolRegistry，主代理 LLM 看不到它们

### Requirement: 回合内结果合流（阻塞式注入）

当主代理迭代到达注入检查点时，若该会话有就绪的注入消息，系统 MUST 先非阻塞消费它们（用户消息优先）；若没有就绪消息但本会话仍有子代理在运行，系统 MUST 在该检查点阻塞等待子代理结果（最长 `agent.subagent.drain.timeout.seconds`，默认 120s），使结果在同一主代理回合内被消费。子代理结果 MUST 作为 user 角色消息注入主会话的当前回合。

#### Scenario: 子代理在跑时主代理阻塞等待结果
- **WHEN** 主代理到达注入检查点，注入队列为空，且 `getRunningCountBySession(sessionKey) > 0`
- **THEN** 主代理阻塞（最长 drain 超时）直到至少一条子代理结果到达，随后将其作为 user 消息注入并继续本回合

#### Scenario: 无子代理在跑时立即返回（不阻塞）
- **WHEN** 主代理到达注入检查点，注入队列为空，且 `getRunningCountBySession(sessionKey) == 0`
- **THEN** 注入回调立即返回空列表，主代理不阻塞

#### Scenario: drain 超时后退化为非阻塞
- **WHEN** 阻塞等待超过 `agent.subagent.drain.timeout.seconds` 仍无结果
- **THEN** 回调记录告警日志、返回空列表
- **AND** 该 pending 条目被移除，使后续检查点不再因同一等待反复阻塞

#### Scenario: 阻塞被 Stop 中断时干净退出
- **WHEN** 用户在主代理阻塞等待子代理期间点击 Stop（`cancelActiveTask`）
- **THEN** 阻塞 drain 捕获 `InterruptedException`、复位中断标志、返回空列表
- **AND** 主代理循环在下一个 `isAborted` 检查点干净退出，不抛未捕获异常

### Requirement: 工具 scope 隔离与防递归

`Tool` 接口 SHALL 提供 `getScopes()` 默认返回 `{core}`。子代理的工具集 MUST 只包含 `getScopes()` 含 `"subagent"` 标签的工具（INCLUDE 语义，非 exclude-non-core）。`spawn` 与 `subagent_status` 工具 MUST 声明 scope 为 `{core}`（不含 `subagent`），从而被排除在子代理工具集之外——从源头杜绝子代理派生子代理的无界递归。

#### Scenario: 子代理工具集只含只读分析工具
- **WHEN** `SubagentManager` 构建子代理 ToolRegistry
- **THEN** 注册的工具仅限 `getScopes()` 含 `"subagent"` 的工具（如 get_test_plan_tree、parse_jmx_file、find_element、read_file 等）
- **AND** 不含任何改树/执行工具（create/update/delete_jmeter_element、run_test、write_file 等）

#### Scenario: spawn 与 subagent_status 不在子代理工具集
- **WHEN** 子代理 ToolRegistry 构建
- **THEN** `spawn` 与 `subagent_status` 均不在其中（scope={core}）
- **AND** 子代理 LLM 收到的工具定义列表不包含这两个工具，子代理无法递归派发或自省

### Requirement: 彻底会话隔离

子代理 MUST 不向主会话 jsonl 写入任何字节，也不参与主会话的记忆整合。子代理的 `AgentRunSpec` MUST 使用 `sessionKey="subagent:<taskId>"` 与 `persistSession=false`；`AgentRunner.run` 在 `persistSession=false` 时 MUST 跳过所有 `SessionManager` 调用与记忆整合，使用临时内存 `Session`。`SubagentManager` MUST 直接调用 `agentRunner.run(spec)`，不得经由 `AgentLoop.processMessage` 派发。

#### Scenario: 子代理不写主会话 jsonl
- **WHEN** 一个子代理成功完成（未被取消）
- **THEN** 主会话 jsonl 文件与 `SessionManager.sessions` map 位级不变
- **AND** 不产生 `subagent_*.jsonl` 残留文件，`SessionManager.getActiveSessionCount()` 不变

#### Scenario: 子代理不参与记忆整合
- **WHEN** 子代理运行
- **THEN** 子代理的 pre-run 与 post-run `maybeConsolidate` 均被跳过（`persistSession=false` 守卫）
- **AND** 共享 `MemoryStore` 的历史与长期记忆不被写入子代理衍生内容

#### Scenario: SubagentManager 直接调 agentRunner.run
- **WHEN** `SubagentManager.spawn` 执行子代理
- **THEN** 子代理经由 `AgentRunner.run(spec)` 直接执行，不经 `AgentLoop.processMessage`
- **AND** 不触发 `AgentLoop.processMessage` 内不受 `persistSession` 约束的 `getOrCreate` 调用

### Requirement: 可观测性

系统 SHALL 提供 `subagent_status` 工具（scope={core}）供主代理主动查询运行中与近期完成的子代理状态（phase、iteration、tool_events、usage、error、stop_reason）。`SubagentStatus` MUST 线程安全（volatile 标量 + 不可变快照），由 `SubagentHook` 在子代理线程单写、由查询工具在 tool-executor 线程读。子代理的工具调用 MUST 记录到日志。终端用户 MUST 只看到子代理的完成态结果摘要（经主代理自然转述），不得看到子代理的实时进度或内部工具调用细节。

#### Scenario: 主代理主动查询子代理状态
- **WHEN** 主代理调用 `subagent_status`
- **THEN** 返回该会话子代理的 phase/iteration/tool_events/usage/error 等可读摘要（移植 Nanobot self.py 格式）
- **AND** 完成态状态在保留窗口内可查：超 `agent.subagent.status.retention.seconds`（默认 60s）被回收，每会话超出 `agent.subagent.status.max.completed`（默认 10）按最旧淘汰

#### Scenario: 终端用户只看到完成态摘要
- **WHEN** 子代理完成、结果回合内回注主代理
- **THEN** announce 文本作为注入 user 消息被主代理消费，主代理输出自然语言转述作为回合最终响应
- **AND** announce 不产生面向用户的 `ProgressUpdate`，用户看不到子代理的中间工具调用或实时进度

#### Scenario: 子代理工具调用进日志
- **WHEN** 子代理执行任一工具调用
- **THEN** `SubagentHook.beforeExecuteTools` 在 DEBUG 日志记录工具名与参数

### Requirement: 跨池取消

主代理取消（Stop 按钮、IPC 超时、`/new` 会话重置）MUST 级联取消该会话所有运行中的子代理。`AgentLoop.cancelActiveTask(sessionKey)` MUST 在设置主 abortFlag 与主 interrupt **之前**先调用 `subagentManager.cancelBySession(sessionKey)`，使阻塞在 drain 上的主线程在亚秒级解阻塞。`cancelBySession` MUST 为每个子代理设置其独立 abortFlag 并 interrupt 其独立 AgentRunner 线程。

#### Scenario: Stop 主 run 级联取消子代理
- **WHEN** 用户点击 Stop（`cancelActiveTask`）
- **THEN** `subagentManager.cancelBySession` 先于主 interrupt 被调用
- **AND** 该会话所有运行中子代理被设 abortFlag + interrupt，停止燃烧 token

#### Scenario: 主代理 Stop 精准命中主线程（不误伤子代理）
- **WHEN** 主代理与子代理并发运行，用户点击 Stop
- **THEN** 主代理 interrupt 命中主迭代线程，子代理 interrupt 命中各自线程
- **AND** 因每个 spawn 使用独立 `AgentRunner` 实例，`runningThread` 字段不被并发踩踏

#### Scenario: /new 先取消再清空
- **WHEN** 用户在子代理运行期间输入 `/new`
- **THEN** `cmdNew` 先调用 `cancelActiveTask(sessionKey)`（级联取消子代理与主 run）
- **AND** 随后才执行 `session.clear()`，清空生效于干净状态

### Requirement: per-spawn 独立 AgentRunner 实例

`SubagentManager.spawn` MUST 为每次派发创建独立的 `AgentRunner` 实例（不复用主代理的共享 AgentRunner），确保各自的 `volatile runningThread` 字段独立、`interrupt()` 精准命中对应线程。主代理的共享 `AgentRunner` MUST 不被子代理运行覆写 `runningThread`。

#### Scenario: 每次 spawn 创建独立 AgentRunner
- **WHEN** `SubagentManager.spawn` 派发子代理
- **THEN** 为该子代理 new 一个独立 `AgentRunner` 实例运行 spec
- **AND** 主代理继续使用 `AgentLoop` 的共享 AgentRunner，两者 `runningThread` 互不影响

#### Scenario: 会话亲和路由结果回主会话
- **WHEN** 子代理完成、announce 结果
- **THEN** 结果经 spawn 时捕获的主 sessionKey 路由回主会话注入队列（不路由到其它会话）
- **AND** 仅当当前活跃主 run 与派发它的 run 为同一 turn 时才 offer，否则保留 terminal 状态供查询
