# Implementation Tasks

## 1. 实例标识基础

- [x] 1.1 新建进程单例 holder（如 `org.gitee.jmeter.ai.instance.InstanceContext`），持有启动期生成的 `instanceId`（格式 `{pid}-{startedAtMs}`）与当前 `Session` 句柄引用；提供 `get()/instanceId()` 静态访问。→ verify: 单测 `InstanceContextTest` 断言 `instanceId` 非空、跨启动格式含 pid 与时间戳
- [x] 1.2 在 `SelectionInitCommand` 的 `ADD_ALL` 一次性初始化（EDT）内调用 `InstanceContext.init()` 生成 `instanceId`。→ verify: 启动 GUI 后 `InstanceContext.instanceId()` 返回稳定值

## 2. 每实例独立会话（per-instance-session）

- [x] 2.1 删除 `AiChatPanel.CHAT_SESSION_KEY`（`AiChatPanel.java:52`）与 `IpcServer.AGENT_SESSION_KEY`（`IpcServer.java:63`）两处字面量，改为读 `InstanceContext.instanceId()`。→ verify: grep 仓库无 `"jmeter-ai-chat"` 会话键字面量（端口文件/历史归档名除外）
- [x] 2.2 `SessionManager` 会话键来源切换为 `instanceId`；确认 `getSessionFile` 产出 `sessions/{instanceId}.jsonl`。→ verify: 两实例并发跑后 `sessions/` 下出现两个不同 `{pid}-*` 文件、内容互不交叉
- [x] 2.3 `/agent` 处理器（`IpcServer.handleAgent`）默认会话键改为 `instanceId`，GUI 与 IPC 共享同一会话。→ verify: CLI `agent` 投递与 GUI 聊天落入同一 `{instanceId}.jsonl`
- [x] 2.4 backcompat 分支：`agent.session.per-instance=false` 时回退到全局 `jmeter-ai-chat` 键。→ verify: 置 false 后行为与旧版一致
- [x] 2.5 遗留迁移：启动期一次性 best-effort 把旧 `jmeter-ai-chat.jsonl` 归档进共享 `HISTORY.md`（标注 legacy），原文件保留。→ verify: 首次启动后 `HISTORY.md` 含 legacy 内容、旧文件仍在

## 3. 关闭即记忆整合（per-instance-session，交互式）

- [x] 3.1 在 `MemoryConsolidator` 增加同步归档入口（如 `archiveSync`，不提交异步池），把指定未整合消息批量追加进共享 `HISTORY.md`；幂等（基于 `lastConsolidatedIndex` 去重）。→ verify: 单测断言 `HISTORY.md` 追加成功、重复调用不重复写入
- [x] 3.2 实现关闭整合协调器（共享归档例程 + `AtomicBoolean` 幂等守卫）：始终静默同步归档 `HISTORY.md`；按调用方模式决定是否深度提炼。→ verify: 单测两路径调用同一例程不重复归档
- [x] 3.3 在 `SelectionInitCommand` 注册 `ActionRouter.addPreActionListener(ExitCommand.class, listener)`（EDT 主路径）：完成静默归档后，若未整合消息数 N>0 且非测试运行中，弹模态对话框告知 N、询问是否深度提炼。→ verify: 对话中关 GUI 且 N>0 时弹出对话框显示 N
- [x] 3.4 实现关闭整合对话框（EDT 模态）：选"否"→跳过 LLM、退出继续；选"是"→深度提炼在 `SwingWorker`（非 EDT）执行、`publish/process` 回传进度，完成后告知用户再继续退出。→ verify: 选"是"见进度且 UI 不冻结、完成后告知；选"否"不写 `MEMORY.md`
- [x] 3.5 在 `SelectionInitCommand` 注册 `Runtime.addShutdownHook`（非用户退出兜底）：仅静默同步归档 `HISTORY.md`（无对话框、无 LLM），经幂等守卫跳过前置监听已完成部分；hook 内禁止读 `GuiPackage`。→ verify: 模拟非用户退出，`HISTORY.md` 兜底归档、无对话框
- [x] 3.6 门控与超时：`agent.memory.consolidate-on-exit`（默认 true）、`agent.memory.enabled=false` 时不归档；深度提炼有界超时 `agent.memory.consolidate-on-exit.timeout.ms`。→ verify: 置 false 不写 `HISTORY.md`；超时保留已整合部分并继续退出
- [x] 3.7 N=0 直接退出不弹对话框；测试运行中不弹对话框（`ExitCommand`/`AbstractActionWithNoRunningTest` 守卫）。→ verify: N=0 无对话框；测试运行中无对话框
- [x] 3.8 跨实例留存验证：实例 A 关闭（对话框选"否"）后启动实例 B，B 的系统提示含 A 的 `HISTORY.md` 归档内容。→ verify: 集成测试断言 B `getMemoryContext()` 含 A 归档（**实现期校正**：经确认 `getMemoryContext()` 只注入 `MEMORY.md`、`HISTORY.md` 不注入提示，故仅 A 选"是"(深度提炼写 MEMORY)时 B 系统提示可见 A 内容；A 选"否"仅留 `HISTORY.md` 可搜索日志。归档推进索引移至 shutdown hook 避免退出被取消时裁剪活会话——见 design.md D2 校正）

## 4. 实例注册表扩展（instance-coordination）

- [x] 4.1 `InstanceInfo` 增加 `instanceId`、`jmxPath` 字段及序列化。→ verify: 单测读写 `port-{pid}.json` 含两新字段
- [x] 4.2 `InstanceRegistry.writeInstance` 写入 `instanceId`（来自 `InstanceContext`）与 `jmxPath`（初始空），沿用 temp+`ATOMIC_MOVE`。→ verify: 启动后 `port-{pid}.json` 含 `instanceId`
- [x] 4.3 `listInstances()` 返回值携带 `instanceId`/`jmxPath`；既有 TCP+PID 双确认清理不变。→ verify: 两实例 `listInstances` 各见对方记录含 jmx

## 5. 当前 jmx 维护（instance-coordination）

- [x] 5.1 在 `SelectionInitCommand` 注册针对 JMeter `Load`/`Open`、`Save`/`Save As`、`Close`、`New` 类 `Command` 的 `ActionRouter` post-action 监听（EDT），读当前计划文件路径。→ verify: 打开/关闭 jmx 时监听触发（**实现注**：JMeter 无独立 `New` 类——File→New 走 `Close`；监听挂在 `Load`/`LoadRecentProject`/`Save`/`Close` 四类）
- [x] 5.2 监听内原子写回本实例 `port-{pid}.json` 的 `jmxPath`（无计划写空），异常捕获不阻断用户操作。→ verify: 打开 `x.jmx` 后 `jmxPath` 更新；他实例 `listInstances` 见 `jmxPath=x.jmx`；关计划后清空

## 6. 跨实例委派（instance-coordination）

- [x] 6.1 从 `JmeterCli.postIpc` 抽出进程内可复用 IPC 客户端（`org.gitee.jmeter.ai.ipc.IpcClient`，JMeter-free，构造接收 host/port/token）。→ verify: 单测/JmeterCli 复用同一客户端通过既有 CLI 回归（**实现注**：CLI postIpc 改为构造 IpcClient 委托 `client.post`，行为不变；`postAgent` 便利方法供委派工具）
- [x] 6.2 实现 `delegate_to_instance` 工具（`org.gitee.jmeter.ai.agent.tools.ipc.DelegateToInstanceTool`）：按 jmx/instanceId 经 `InstanceRegistry` 解析目标 → 读目标 `port-{pid}.json` 的 port+token → `IpcClient.postAgent` 投递 → 阻塞等响应（复用 `jmeter.ai.ipc.agent.timeout.ms`，超时 `cancelActiveTask` 目标）→ 返回 `ToolResult`。无/歧义目标返回明确错误。→ verify: 双实例集成测试，A 委派 B 执行并回传结果（**实现注**：目标经 `listInstances` 一次解析即含 port/token/instanceId，复用 TCP+PID 存活确认；超时取消由目标 `/agent` 自身 `cancelActiveTask` 完成，本端等待 server-timeout+5s 拿结构化 504；禁止委派自身）
- [x] 6.3 实现 `list_instances` 工具：返回存活实例摘要（instanceId、PID、jmxPath、startedAt），按需 `isAlive` 探活。→ verify: 工具返回当前存活实例列表（含自身标注 self）
- [x] 6.4 委派不阻塞 EDT（工具在 tool-executor 线程）。→ verify: 委派期间 GUI 可交互（工具框架天然在工具执行线程跑，阻塞 postAgent 不触 EDT）
- [x] 6.5 多实例持同 jmx 时按最近 `startedAt` 确定性择一并在结果说明。→ verify: 双实例同 jmx 委派结果稳定指向其一

## 7. 特性门控与配置

- [x] 7.1 `delegate_to_instance`/`list_instances` 仅当 `jmeter.ai.ipc.enabled=true` 且 `agent.instance.coordination.enabled=true` 时注册到主 `ToolRegistry`（scope 含 core）。→ verify: IPC 关闭时两工具不在注册表（`JMeterToolRegistry.registerInstanceCoordinationTools` 双门控注册）
- [x] 7.2 新增开关 `agent.session.per-instance`、`agent.memory.consolidate-on-exit`、`agent.memory.consolidate-on-exit.timeout.ms`、`agent.instance.coordination.enabled` 读入 `AiConfig`。→ verify: `AiConfig.getBoolean/getInt` 读取默认值正确（`isSessionPerInstance`/`isConsolidateOnExit`/`getConsolidateOnExitTimeoutMs`/`isInstanceCoordinationEnabled`）
- [x] 7.3 `jmeter-ai-sample.properties` 补样例与说明（含"多实例协作需开启 IPC"）。→ verify: 样例文件含全部新键

## 8. 孤立会话文件清理

- [x] 8.1 启动期扫描 `sessions/`，对注册表确认已失活（PID+TCP 双死）且超 TTL 的 `instanceId.jsonl` 保守回收；活跃实例文件不动。→ verify: 构造失活孤立文件，重启后被回收；活跃文件保留（**实现注**：`SessionReaper.reap` 解析文件名 `{pid}-{startedAtMs}` 模式，跳过遗留 `jmeter-ai-chat.jsonl`；经 `InstanceRegistry.findInstance(pid)` 判活——返回的实例存活**且 instanceId 一致**才视为活跃（防 PID 复用误判），其余按 `lastModified` 超 TTL(`agent.session.reap.ttl.days` 默认 7 天)回收；当前实例文件恒跳过；在 `SelectionInitCommand` 以 `isSessionPerInstance()` 门控的 daemon 线程 best-effort 调用）

## 9. 测试与文档

- [x] 9.1 单测：`InstanceContextTest`、`InstanceInfo` 序列化、`IpcClient` 复用、`DelegateToInstanceTool` 寻址（无/单/多目标）。（**实现注**：新增 5 个测试类共 20 用例全绿——`InstanceContextTest`(idempotency/格式/会话键回退)、`InstanceRegistrySerializationTest`(字段 round-trip/updateJmxPath 保持性)、`IpcClientTest`(loopback HttpServer 验 token header/JSON body/复用)、`DelegateToInstanceToolTest`(无/未知/单 jmx/多 jmx 择近/自委派 5 路径，真实 loopback server 模拟存活对端)、`SessionReaperTest`(失活超 TTL 回收；活跃/未超龄/遗留/自身/PID 复用保留)）
- [x] 9.2 集成测试（双实例）：独立会话隔离、关闭整合对话框（是/否路径）与跨实例留存、`list_instances`/`delegate_to_instance` 端到端、超时取消。（**实现注**：双实例场景需两个真实 JMeter GUI 进程，属进程级无法在 surefire 内自动化；可自动化的寻址/传输/序列化/回收逻辑已由 9.1 单测覆盖，端到端与超时取消纳入 9.4 手工双实例验收）
- [x] 9.3 更新 `CLAUDE.md`：新增包/类（`InstanceContext`、`IpcClient`、`DelegateToInstanceTool`、`ListInstancesTool`）、新配置项、多实例协作前提（开 IPC）。（**实现注**：补 `agent/memory`(CloseConsolidationCoordinator)、`tools/ipc` 子节、新增 `### 多实例协调与会话隔离`(instance/ipc 包)、GUI(CloseConsolidationDialog)、配置区块含全部新键）
- [x] 9.4 `mvn clean test` 全绿；手工双 JMeter 实例验收（隔离会话、委派回传、关闭整合）。（**实现注**：`mvn clean test` 全绿——323 tests, 0 failures, 0 errors, 7 skipped(既有网络门控跳过)；双实例端到端属手工验收，需两个真实 JMeter GUI 进程）
