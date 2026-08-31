## Why

当前插件硬编码单一会话键 `"jmeter-ai-chat"`（`AiChatPanel.java:52` 与 `IpcServer.java:63` 各自定义一次），同一台机器上所有 JMeter 实例读写同一个 `{workspace}/sessions/jmeter-ai-chat.jsonl`，并发多实例时聊天上下文相互覆盖、彼此干扰。同时，关闭 JMeter 不触发任何记忆整合——全仓唯一的 JVM shutdown hook 在 `IpcServer.java:188`，只清理 IPC 端口文件；`AgentLoop.shutdown()`/`SessionManager.shutdown()` 存在却从未挂到 JMeter 退出路径，未整合的会话内容无法沉淀为长期记忆。

但用户开多实例的真实场景是**为完成同一任务**并行编辑/调试多个 jmx：既需要会话上下文按实例隔离，又需要实例间彼此感知（谁开着哪个 jmx）、并能委派任务让持有目标 jmx 的实例代为执行、回传结果——即"隔离会话上下文，但不彻底隔离实例"。现状既无法隔离（共享会话文件），也无法协作（实例间互不可见、不可委派）。

## What Changes

- **每实例独立会话文件**：会话键从硬编码常量改为启动期生成的进程级唯一实例标识（`instanceId`），每个 JMeter 实例启动即拥有独立 `sessions/{instanceId}.jsonl`，消除跨实例上下文污染。每次启动即新建独立会话文件。
- **统一会话键来源**：合并 `AiChatPanel.CHAT_SESSION_KEY` 与 `IpcServer.AGENT_SESSION_KEY`（`/agent` 处理器默认用它）两处重复定义，统一指向单一进程级实例标识源（消除 GUI 与 IPC 会话键漂移）。
- **关闭即记忆整合（交互式）**：用户主动关闭 JMeter 时弹出对话框，告知待整合消息数 N 并询问是否深度提炼；**始终静默**把会话归档进共享 `HISTORY.md`（跨实例共享桥，N>0 时无论选"是/否"都执行），深度提炼（写 `MEMORY.md`）由用户在对话框中选择——选"是"则展示进度、完成后告知用户。非用户退出（崩溃/Restart）经 JVM shutdown hook 兜底静默归档（无对话框）。
- **扩展既有实例注册表**：`InstanceRegistry` 已支持按 PID 共存（`port-{pid}.json`，含 `{pid,port,token,startedAt,bind}`，原子写 + TCP/PID 双确认清理）。扩展 `InstanceInfo` 增加 `instanceId` 与 `jmxPath`（当前打开的 .jmx）；任一实例经 `listInstances()` 即可知道"还有哪些实例、各自开着哪个 jmx"。
- **跨实例任务委派（复用既有 IPC 传输）**：实例 A 驱动实例 B 在结构上与"CLI 驱动 GUI"等价——A 读取 B 的 `port-{pid}.json` 取其端口与 token，经既有 `POST /agent`（或 `/tool`）端点把任务投递到 B 的 `AgentLoop`/工具集；B 持有目标 jmx 故能执行（运行测试、改树、读取），结果回传 A，A 把结果回合内注入主代理，复用既有回合内合流语义。新增"寻址到持有 jmx X 的实例"语义与超时/取消处理。
- **jmx 切换感知**：用户在 GUI 打开/切换/关闭 .jmx 时，经既有 `ActionRouter` 监听（`Open`/`Save`/`Close`/`New` 类动作）更新本实例注册项的 `jmxPath`（或经 `/info` 端点按需读取 `GuiPackage` 的当前计划文件）。

## Capabilities

### New Capabilities
- `per-instance-session`: 单实例本地生命周期——每实例独立会话文件（隔离上下文）+ 关闭时把会话归档进共享记忆（隔离→共享桥梁）。覆盖实例标识派生、会话文件命名与生命周期、GUI 与 IPC 会话键统一、退出时始终静默归档 `HISTORY.md` 与交互式深度提炼对话框（用户 gating `MEMORY.md`）及其可靠性。
- `instance-coordination`: 跨实例协作层——实例注册表（存在性 + 当前打开 jmx 广播）与基于既有 IPC 通道的跨实例任务委派 RPC（委派到持有目标 jmx 的实例、回合内结果回注）。覆盖注册表生命周期、jmx 字段维护、委派寻址与执行、结果反馈、无目标/超时/失败处理。

### Modified Capabilities

无。既有 `async-subagent` 为进程内子代理，`instance-coordination` 是与之正交的跨进程传输层，不改动其需求；`run-result-capture` 的采集语义不变，仅被跨实例委派的接收侧复用。

## Impact

- **会话层**：`SessionManager`（会话键从常量 `"jmeter-ai-chat"` 改为 `instanceId`）、`Session`、`AiChatPanel.java:52`（`CHAT_SESSION_KEY`）、`AgentLoopFactory`（实例标识注入与生命周期）。
- **记忆层**：`MemoryConsolidator` / `MemoryStore`（新增关闭期同步归档入口与深度提炼路径）、`AgentRunner`（与关闭回调衔接）。
- **GUI/对话框**：新增关闭整合模态对话框（EDT + `SwingWorker` 进度回传），于退出前询问用户是否深度提炼。
- **IPC 层**：`InstanceRegistry` / `InstanceInfo`（扩展 `instanceId` + `jmxPath` 字段）、`IpcServer.java`（`/agent` 处理器改用 `instanceId` 会话键、新增 `/info` 或扩展 `/health` 暴露当前 jmx）、`port-{pid}.json` 格式扩展。
- **委派客户端**：复用 `JmeterCli` 既有 HTTP 投递逻辑（`postIpc`）抽出为进程内可复用客户端，供 `delegate_to_instance` 工具调用其他实例。
- **生命周期**：`SelectionInitCommand`（启动登记实例 + 生成 `instanceId` + 注册关闭回调 + 维护 `jmxPath` 的动作监听）；关闭回调主路径为 `ActionRouter` 对 `ExitCommand` 的前置动作监听（EDT，驱动整合对话框），`Runtime.addShutdownHook` 作非用户退出兜底。
- **工具层**：新增 `list_instances` / `delegate_to_instance` 工具（注册到主 `ToolRegistry`）。
- **配置**：新增 `agent.session.per-instance`、`agent.memory.consolidate-on-exit`、`agent.instance.coordination.enabled` 等开关；`jmeter-ai-sample.properties` 补样例。注意 IPC 现默认关闭（`jmeter.ai.ipc.enabled=false`），跨实例协作依赖其开启。
- **风险**：注册表/端口文件的共享目录并发读写、委派 RPC 的超时与线程模型（不得阻塞 EDT 或主迭代线程，复用 `/agent` 既有 `CompletableFuture`+超时取消）、关闭回调可靠性（JMeter 可能不经确认直接 `System.exit`）、孤立会话文件与注册表项的清理。
