## Context

当前插件在会话、记忆、IPC 三层的现状（经代码勘查确认）：

- **会话**：单一硬编码会话键 `"jmeter-ai-chat"`，两处独立定义——`AiChatPanel.java:52`（`CHAT_SESSION_KEY`）与 `IpcServer.java:63`（`AGENT_SESSION_KEY`）。`SessionManager.getSessionFile`（`SessionManager.java:209`）据此产出 `{workspace}/sessions/jmeter-ai-chat.jsonl`。同机多实例全部读写同一文件 → 上下文相互污染。`/agent` 端点默认也用该键，故 CLI 投递与 GUI 聊天共享同一会话。
- **记忆**：`MemoryStore`（`{workspace}/memory/` 下 `MEMORY.md` 长期事实 + `HISTORY.md` 追加日志）跨实例共享；每轮经 `ContextBuilder.buildSystemPrompt()` 读入系统提示。`MemoryConsolidator` 的触发点仅：`AgentRunner` 运行前后 `maybeConsolidate`（`AgentRunner.java:109,135`）与 `/new`/新会话时 `archiveMessagesAsync`。**无任何关闭期触发**——全仓唯一 JVM shutdown hook 在 `IpcServer.java:188`，只删端口文件。`AgentLoop.shutdown()`/`SessionManager.shutdown()` 存在却从未挂到 JMeter 退出路径。
- **IPC（已成熟）**：`IpcServer`（`com.sun.net.httpserver.HttpServer`，回环强制，token 鉴权）三端点 `POST /tool`、`POST /agent`、`GET /health`；`InstanceRegistry` 按 PID 共存（`{jmeterHome}/bin/jmeter-agent/ipc/port-{pid}.json`，`InstanceInfo={pid,port,token,startedAt,bind}`，原子写 + TCP/PID 双确认清理）；`JmeterCli` 薄客户端经 `--pid`/`--jmeter-home` 发现实例并驱动。IPC 默认关闭（`jmeter.ai.ipc.enabled=false`）。`SelectionInitCommand`（ServiceLoader `Command`，处理 `ADD_ALL`，一次性 CAS 守卫，EDT）是启动 bootstrap，已在此启动 IPC、装 `SelectionTracker`、注册 run-capture 监听。
- **JMeter 生命周期**：核心**无干净退出回调**——`ExitCommand.doActionAfterCheck` 直接 `System.exit(0)`（3 处：72/76/80 行），无前置钩子。可靠关闭点排序：① `Runtime.addShutdownHook`（最可靠，`System.exit` 必跑钩子，JMeter 自身 `ResultCollector`/`Restart` 如此）；② `ActionRouter.addPreActionListener(ExitCommand.class,…)`（EDT 上 `doAction` 前同步，`CheckDirty` 先例，但用户点确认 YES/NO 时 `doAction` 不返回、post 监听不触发）；③ `MainFrame` 窗口监听（严格劣于①）。启动序列中 `ADD_ALL` 派发时 `GuiPackage` 与 `MainFrame` 已就绪，是挂载关闭钩子与生成实例标识的正确时机。

约束：插件以 JMeter 5.6.3 为依赖；HTTP 相关类反射访问；所有 AI 调用须在 SwingWorker/工具线程，不得阻塞 EDT；JMeter 可能不经确认直接退出。

## Goals / Non-Goals

**Goals:**
- 每实例独立会话文件（按启动生成 `instanceId`），消除多实例上下文污染；GUI 与 IPC 会话键统一。
- JMeter 关闭时把本实例未整合内容沉淀进共享 `MemoryStore`（至少 `HISTORY.md`），实现"隔离会话、共享记忆"。
- 复用既有 IPC 基础设施，让实例彼此可见（注册表 + 当前 jmx）并能跨实例委派任务、回传结果。

**Non-Goals:**
- 不做跨机器/远程分布式协调（仅本机回环多实例）。
- 不重写 IPC 传输（复用既有 `IpcServer`/`InstanceRegistry`/`JmeterCli` 协议）。
- 不改动既有 `async-subagent`（进程内子代理）需求；跨实例委派是与之正交的跨进程传输层。
- 不强制单实例（允许多实例共存，不做独占锁）。
- 不在本期实现委派任务在接收侧 UI 的专门可视化标注（留作可选增强）。

## Decisions

### D1. `instanceId`：每次启动生成、进程级唯一，作为统一会话键

- **选择**：在 `SelectionInitCommand` 的 `ADD_ALL` 初始化期生成 `instanceId`，格式 `{pid}-{startedAtMs}`（人类可读 + 跨启动唯一），存入一个进程单例 holder（如 `InstanceContext`，或挂在 `AgentLoopFactory`），供 `AiChatPanel`、`IpcServer./agent`、`InstanceRegistry` 三处引用。
- **替代**：① 裸 PID——拒绝，PID 退出后可被 OS 复用，会继承上次会话文件，违背"每次启动独立"；② 纯 UUID——可行但 `sessions/` 目录不可读、与注册表 PID 锚点脱节。`{pid}-{startedAtMs}` 兼顾可读与唯一。
- **会话键统一**：删除 `CHAT_SESSION_KEY`/`AGENT_SESSION_KEY` 两处字面量，二者改为读 holder 的 `instanceId`；会话文件变为 `sessions/{instanceId}.jsonl`。

### D2. 关闭整合：交互式 `ExitCommand` 前置监听 + `SwingWorker` + shutdown hook 兜底

- **选择**：关闭整合分两层。
  - **始终静默归档（共享桥）**：把当前会话未整合消息同步归档进共享 `HISTORY.md`（复用 `MemoryConsolidator` 的同步归档入口，**同步**执行，不提交异步池——池可能在关闭中拒绝任务）。两层退出路径都做：① 用户主动关闭——经 `ActionRouter.addPreActionListener(ExitCommand.class, listener)`（EDT，`CheckDirty` 先例）；② 非用户退出——经 `Runtime.addShutdownHook` 兜底。两路径共享同一归档例程 + 幂等守卫（`AtomicBoolean` + 基于 `lastConsolidatedIndex` 的去重）防重复写入。归档**无论用户是否选择深度提炼都执行**（跨实例共享桥不丢失）。
  - **深度提炼由对话框 gating**：前置监听在完成静默归档后，若未整合消息数 N>0，于 EDT 弹出模态对话框告知 N 并询问是否深度提炼（写 `MEMORY.md`）。选"否"→ 跳过 LLM（仅保留已完成的 `HISTORY.md` 归档）；选"是"→ 对话框展示进度，深度提炼在 `SwingWorker`（非 EDT）执行、`publish/process` 回传进度，完成后告知用户，再继续退出。
- **为何不能只用 shutdown hook**：shutdown hook 运行时 JVM 已在关闭、EDT 可能被拆除，**无法弹 Swing 对话框并等待用户输入**——用户要的交互式对话框必须在 `System.exit` 之前的 EDT 上做，故必须用 `ExitCommand` 前置监听作主路径。shutdown hook 降级为非交互兜底（仅静默归档，无对话框、无 LLM）。
- **为何前置监听而非 post/窗口监听**：post 监听失效——`ExitCommand.doAction` 内 `System.exit` 致 `doAction` 不返回，post 不触发；前置监听在 `doAction` 前 EDT 同步执行（`CheckDirty` 同款）。窗口监听（`windowClosing`）早于 dirty 检查、无法得知退出是否真会发生，且监听器注册顺序不确定；前置监听更靠近真实退出点。
- **顺序注意**：本对话框早于 JMeter 自身"未保存改动"确认框（`doAction` 在前置监听之后才 `CHECK_DIRTY`）；两个对话框先后出现可接受。
- **守卫**：测试运行中（`AbstractActionWithNoRunningTest` 会拒绝退出）前置监听跳过对话框；N=0 跳过对话框直接退出（归档为 no-op）。
- **线程注意**：前置监听在 EDT，深度提炼必须转交 `SwingWorker` 以免冻结 UI；shutdown hook 在 JVM 关闭线程，**禁止**读 `GuiPackage`，`instanceId`/session 句柄取自 holder 普通字段。深度提炼有界超时 `agent.memory.consolidate-on-exit.timeout.ms`。

#### D2 实现期校正（两处偏离原 spec，已更安全落地）

实现时发现原 D2"两路径都做静默归档"会造成**退出取消裁剪活会话**的正确性缺陷，且经代码勘查确认 `ContextBuilder.getMemoryContext()` 只注入 `MEMORY.md`、`HISTORY.md` 永不入提示。据此作两处校正：

1. **归档推进索引只发生在 shutdown hook（真实 `System.exit`），前置监听不做归档。**
   - 原 spec：前置监听先静默归档（推进 `lastConsolidatedIndex`）再弹框。问题：`lastConsolidatedIndex` 一旦推进，`Session.getHistory()` 即截断未整合消息；若随后用户在 JMeter 自身"未保存改动"框选**取消**、退出中止，活会话上下文已被裁剪（abort-damage）。
   - 校正后：前置监听（EDT）**仅**负责捕获未整合快照 + 弹框 + 选"是"时深度提炼；深度提炼只写 `MEMORY.md`/`HISTORY.md`，**不推进** `lastConsolidatedIndex`，故退出取消无副作用。真正推进索引的 `archiveSync`（原样追加 `HISTORY.md` + `setLastConsolidatedIndex(末尾)`）只在 shutdown hook 执行——只有真实 `System.exit` 才发生。
   - 幂等守卫 `AtomicBoolean ARCHIVED` 仍守 archive 例程，与原 spec 一致；只是守卫的例程现在只挂在 shutdown hook，前置监听路径不触发它。

2. **`HISTORY.md` 是可搜索日志，不注入提示——跨实例留存仅在"是"路径达成。**
   - 勘查结论：`ContextBuilder.buildSystemPrompt()` 只把 `MEMORY.md` 读入系统提示；`HISTORY.md` 永不被注入。故原 spec 场景"实例 A 关闭（选'否'）后 B 系统提示含 A 的 `HISTORY.md` 归档"在结构上不成立。
   - 校正后（用户确认 Option 2）：`HISTORY.md` 定位为**跨实例可搜索日志**（人工/工具 grep），不自动入提示。跨实例系统提示留存**仅**在 A 关闭时选"是"经深度提炼写 `MEMORY.md` 才达成（B 启动后其系统提示读 `MEMORY.md` 即见 A 沉淀）；A 选"否"仅留 `HISTORY.md` 日志记录，不自动桥入 B 提示。这是"隔离会话、共享记忆"语义的精确边界：共享的是 `MEMORY.md`，不是实时会话或 `HISTORY.md`。

#### D2 后续校正：提炼成功后清空会话（反转校正点 1 的"不清空"权衡）

D2 校正点 1 故意让深度提炼**不推进** `lastConsolidatedIndex`、不清空会话，以求"退出取消时不破坏活会话上下文"。但这恰是 Bug 根源：深度提炼成功后若退出被取消（用户在 JMeter "未保存改动"框选取消），会话消息原封不动，再次点关闭时 `unconsolidatedSnapshot()` 仍返回同一批 N 条 → **二次触发提炼同一批消息**（`MEMORY.md` 被重复整合）。

校正（用户确认）：深度提炼**成功后立即清空当前会话**——

- **数据层** `CloseConsolidationCoordinator.clearCurrentSession()`（EDT）：`session.clear()` + `saveSession` + `invalidate`，对齐 `BuiltinCommands.cmdNew`（但不再二次归档，因刚提炼过）。清空后 `unconsolidatedSnapshot()` 返回空，杜绝二次触发；shutdown hook 的 `archiveSilently` 随后亦为 no-op。
- **GUI 层** `AiChatPanel.resetAfterConsolidation()`（EDT，经静态 `INSTANCE` 触达单实例面板）：`chatArea.setText("")` + 显示欢迎信息（复用 `displayWelcomeMessage`），视觉效果与「开启新会话」按钮一致。

权衡反转的合理性：提炼结果已落入 `MEMORY.md`（经系统提示持续生效），原会话消息不再有价值；保留它们反而有害（重复提炼）。接线点为 `CloseConsolidationDialog` 的 `SwingWorker.done()`（EDT）在 `result==true` 时先后调用二者；两者均在 EDT，避免与 `Session` 非线程安全的消息集合并发。

### D3. jmxPath 维护：`ActionRouter` 动作监听 + 原子写回 `port-{pid}.json`

- **选择**：在 `SelectionInitCommand` 注册针对 JMeter `Load`/`Open`、`Save`/`Save As`、`Close`、`New` 类 `Command` 的 `ActionRouter` 监听（post-action），读取当前计划文件路径（`GuiPackage` 当前计划文件），原子写回本实例 `port-{pid}.json` 的 `jmxPath`。无计划时写空。
- **替代**：新增 `/info` 端点按需读 `GuiPackage`——可行但 `list_instances` 需 N 次探测、延迟高、且 `GuiPackage` 须在 EDT 读。注册表内嵌 `jmxPath` 一次写、多次读更省。
- **为何不用 `TestPlanListener`**：其回调语义（`testPlanLoaded`/`beforeTestPlanCleared`）覆盖面窄于显式动作监听，且与既有 run-capture 的 `ActionRouter` 监听模式一致。

### D4. 跨实例委派：复用 `/agent` + `port-{pid}.json` token，阻塞式工具

- **选择**：`delegate_to_instance` 工具在工具执行线程上：① 经 `InstanceRegistry` 按jmx/instanceId 解析目标；② 读目标 `port-{pid}.json` 取 `port`+`token`；③ 复用 `JmeterCli.postIpc` 同款逻辑（抽成进程内可复用客户端）`POST /agent` 投递任务；④ 阻塞等响应（复用既有 `CompletableFuture`+`jmeter.ai.ipc.agent.timeout.ms`，超时 `cancelActiveTask` 目标），把响应内容作为 `ToolResult` 返回主代理。
- **为何阻塞式而非 spawn 式非阻塞**：用户语义是"委派并反馈结果"——主代理本轮即需结果；阻塞工具在工具线程上等待符合正常工具调用语义，不阻塞 EDT。`async-subagent` 的非阻塞+回合内注入模型是进程内子代理的产物，跨进程 RPC 用同步 RPC + 工具结果更简单（YAGNI）。非阻塞 spawn 式留作未来增强。
- **接收侧**：委派请求经既有 `/agent` 进入目标 `AgentLoop`（其 `instanceId` 会话、自身工具集），不引入第二套执行引擎。

#### D4 后续校正：深度 1 硬阻断 + 来源前缀 + 端口文件前向兼容

对比同期两份外部实现方案（DeepSeek/方案2、GLM/方案3）后吸收三项增强（2026-08）：

1. **委派深度硬阻断（取自方案2，ThreadLocal 方案）**：`IpcRequest` 新增 `delegated` 布尔；`IpcServer.handleAgent` 透传给 `AgentLoop.processMessage(msg, session, delegated)` → `AgentRunSpec.delegated`，`AgentRunner` 在 run 任务<b>内部</b>（执行该任务的池化载体线程）置 `DelegationGuard`（ThreadLocal），`delegate_to_instance` 在寻址前检查——被委派回合内再委派直接报错。否则 A 委派 B、B 再委派 A 会形成 ping-pong，两侧互卡满超时。深度上限 1 足够（任务是自包含文本，不缺上下文）。**线程校正（对抗性审查发现）**：初版把 guard 置在 agent-loop 单线程执行器上，但 `AgentRunner.run` 用无执行器的 `supplyAsync` 提交、整回合（含串行工具内联执行）跑在 ForkJoinPool 载体线程，loop 线程只 park 在 `join()`——守卫在生产中永不生效。修正为在 run 任务内与 `AgentRunContext` 同位置置/清（"carrier threads are pooled" 同款推理）。ThreadLocal 仅在默认串行工具执行（工具与回合同载体线程内联）下可见；`agent.tools.concurrent.enabled=true` 时退化为"由超时兜底"——单线程循环下至多一个委派阻塞其余回合，无死锁可能，可接受。**忙碌会话校正（同轮审查发现）**：委派请求命中目标正在跑回合的会话时必须立即失败（"session busy, retry later"），不得并入注入队列——队列只存 String 会丢失 `delegated` 标记，且"已注入"回执会被委派方误当任务结果。
2. **来源前缀（取自方案3）**：委派载荷在客户端拼 `[delegated-from instanceId=<self> pid=<pid> script=<self-jmx>] <task>` 前缀，对端会话/GUI 可审计这轮是委派任务及其出处（脚本路径取自本实例端口文件的 `jmxPath`，未开脚本则省略）。
3. **端口文件/请求信封前向兼容（取自方案3）**：`InstanceInfo` 与 `IpcRequest` 加 `@JsonIgnoreProperties(ignoreUnknown=true)`——未来版本新增字段时，旧读者不会因 Jackson 默认 `FAIL_ON_UNKNOWN_PROPERTIES` 把整条实例当损坏文件跳过。

#### D4 二次校正：关闭前取消 + 对抗性验证补强

继续对同一份外部方案做对抗性验证后的补吸与修正（2026-08）：

1. **关闭前取消活动回合（取自方案3）**：`CloseConsolidationDialog` 在深度提炼前先 `AgentLoop.cancelActiveTask(currentSessionKey())`，避免关闭期还有一个在跑的 Agent 回合与提炼+清会话并发写记忆/会话文件。
2. **信封 `delegated` 仅委派时上线（对抗性验证补强）**：`IpcRequest.delegated` 若对每个请求都序列化（`false` 也上线），新→旧版本混布（lib/ext 旧 jar 残留）时，旧端 strict Jackson（`FAIL_ON_UNKNOWN_PROPERTIES=true`、不认此字段）会把每个普通请求 400。改为 `@JsonInclude(NON_DEFAULT)`：`false` 不上线，旧读者不受影响；委派请求 `true` 照常上线——旧对端本就不支持深度 1 守卫，显式失败是正确行为。
3. **僵尸回合竞态（对抗性验证补强）**：`cancelActiveTask` 的 interrupt 与 `future.cancel(true)` 都够不到 pre-loop 整合回合——整合 round loop 跑在无执行器 `supplyAsync` 的 ForkJoinPool 载体线程上；`runningThread` 原先只在 `runAgentLoop` 内赋值（pre-loop 阶段为 null）；`future.cancel(true)` 只标记 CF 取消、不打断执行线程，反而立即 `countDown` latch 让 `cancelActiveTask` 快速"成功"返回。修复：abort 谓词（spec 与 `abortFlags` map 持有的是**同一** AtomicBoolean）穿进 `maybeConsolidate` round loop + `consolidateWithAi`/`extractAndSaveToolCallResult` 写盘前检查——被取消的僵尸回合在 LLM 返回后、写 HISTORY/MEMORY/session 前放弃落盘；`runningThread` 提前到 run 任务开头，覆盖 pre-loop 窗口，Stop 的 interrupt 也能落地。

#### D4 三次校正：并发工具下守卫可见性 + 环 breaking 论述修正（nanobot 对照，2026-08）

对"DelegationGuard 在工具并发执行时失效"的专项分析（对照 nanobot 参考实现）：

- **现状定性**：并发路径当前不可达——`AgentLoop` 构建 spec 硬编码 `concurrentTools(false)`，`AgentConfig.isConcurrentToolsEnabled()`（`agent.tools.concurrent.enabled`）读而未接线（零调用方，死配置：将来要么接线要么删）。
- **原论述修正**：此前 javadoc 记载"并发失效时退回超时兜底（环上等待方必然超时）"不准确。真正的环 breaking 是**接收侧 delegated-busy 快速失败**（`AgentLoop.processMessage`：`delegated=true` 命中忙碌会话立即报错、绝不入队）——任何委派环中每个成员回合都在飞（`activeTasks` 有键），闭合跳毫秒级失败，与执行模式无关。守卫的独立价值是**封跨空闲第三实例的无界委派链**（A→B→C→D…每跳合法、每跳阻塞满 `jmeter.ai.ipc.agent.timeout.ms`、深度无界）。
- **nanobot 对照**（agent/runner.py、agent/tools/base.py、agent/tools/context.py）：执行原语 `asyncio.gather` 为同事件循环线程协程、无线程跳变——Java 阻塞工具世界不可移植；`contextvars.ContextVar`（RequestContext/file_states/workspace_scope，token 式 bind/reset）靠 asyncio.Task 创建时自动拷贝 context 进入并发工具——Java 17 无等价物（ScopedValue 需 JDK 21+）；`concurrency_safe` 分批纪律（默认 `read_only && !exclusive`，不安全工具单例批串行）**完全可移植**，且 nanobot loop 默认 `concurrent_tools=True` 正因有此门槛。
- **将来接线 concurrentTools 时的移植计划（两件套）**：① `Tool` 层加 `concurrency_safe` 分类（默认 false），`executeAsyncWithEvents` 改"安全并行批 + 不安全单例批"——`DelegateToInstanceTool`（阻塞/有副作用）永不上并发批、永远内联 run 载体线程，守卫天然可见（主修复）；② 守卫随 `AgentRunContext` 在 `ToolRegistry.executeAsyncWithEvent` 异步派发处 capture/set/clear 搬运（既有 AgentRunContext 搬运模式的同构扩展，contextvars 的 Java 直译，防御纵深）。
- **当下落地**：仅修正 DelegationGuard/DelegateToInstanceTool javadoc 论述，零机制代码改动。
- **后续（已落地）**：两件套已由 `concurrency-safe-tool-batching` 变更实施（含开关/死配置移除，并发常开 + 分批纪律）；DelegationGuard javadoc 已更新为双通道保证描述。

### D5. 特性门控：协作依赖 IPC 开启

- **选择**：`delegate_to_instance`/`list_instances` 仅当 `jmeter.ai.ipc.enabled=true` 且 `agent.instance.coordination.enabled=true`（默认 true）时注册到主 `ToolRegistry`。IPC 关闭则两工具不注册。`agent.session.per-instance`（默认 true）门控每实例会话；`agent.memory.consolidate-on-exit`（默认 true）门控关闭整合。
- **为何协作依赖 IPC**：委派传输即 IPC 通道，IPC 关闭则无传输、注册表不写端口文件，协作无从谈起；显式门控避免主代理看到工具却调用即失败。

#### D5 后续校正：移除冗余开关 + 放宽提炼超时

实现后复盘，两处门控开关冗余、徒增认知负担，予以移除（行为固定为原默认）：

1. **移除 `agent.instance.coordination.enabled`**：协作工具现仅受 `jmeter.ai.ipc.enabled` 单门控注册。原二级开关默认 true 且无独立配置意义（IPC 开即应提供协作），移除后 `JMeterToolRegistry.registerInstanceCoordinationTools` 退化为单门。安全门 `jmeter.ai.ipc.enabled`（默认 true、仅 loopback、token 鉴权）保留不变。
2. **移除 `agent.memory.consolidate-on-exit`**：关闭整合始终开启。原开关默认 true，语义即"始终静默归档 HISTORY.md"，实际无人会关；`CloseConsolidationCoordinator.archiveSilently` 退化为幂等守卫 + agent 检查，`CloseConsolidationDialog.handleExit` 仅保留 `agent.memory.enabled` / N=0 / 测试运行中守卫。
3. **`agent.memory.consolidate-on-exit.timeout.ms` 默认 60s→120s**：深度提炼含一次 LLM 调用，60s 常触发误超时；放宽至 120s（与 `/agent` 路由超时一致），仍保留"超时保留已整合部分并继续退出"的 best-effort 语义。
4. **`jmeter.ai.ipc.enabled` 默认 false→true**：多实例协作开箱即用（设计期 Risks 原按默认关闭评估，见下）。`AiConfig.isIpcEnabled` 代码默认、CLAUDE.md、README（中/英）、`jmeter-ai-sample.properties`（注释与发行值）统一为默认开启；安全边界不变（仅 loopback、token 鉴权、`bind` 拒绝通配地址，威胁模型本机同用户进程），需关闭显式设 `false`。

#### D5 二次校正：跨进程写锁 + 已知限制记录

对抗性复核（fix-adversarial 审计）确认两条 minor 后的处置（2026-08）：

1. **MEMORY.md 双实例并发 lost-update 加固（fix-adversarial#2）**：审计确认共享默认 workspace（`{jmeter.home}/bin/jmeter-agent`）的双实例同时关闭深度提炼时，`MemoryConsolidator` 的 read-modify-write（read → LLM → write）会让后写者基于陈旧读覆盖前者、整份提炼静默丢失。原设计接受 last-writer-wins；复核确认后决定加固：`MemoryStore` 新增 `lockLongTermMemory()`（`memory.lock` 文件 + OS 级 `FileLock`，跨进程互斥、阻塞可中断），`consolidateWithAi` 与 `save_memory` 工具（`SaveMemoryTool.persistMemoryUpdate`）的整个读改写全程持锁——后写者重读到前者结果再提炼，不再覆盖。（注：`SaveMemoryTool` 实为死代码、从未接线，其锁语义还与活路径分叉，见 D5 四次校正第 5 条。）同一 JVM 内先经静态互斥串行（`FileLock` 是 JVM 级持有，防 `OverlappingFileLockException`）；等锁失败按 best-effort 降级为无锁执行，阻塞中被中断视为未执行、不落盘。
2. **委派 busy 检查残余 TOCTOU（fix-adversarial#3，已接受不修）**：`AgentLoop` Phase 2 的 busy 检查与 `activeTasks.put` 之间无原子性，两次同会话并发提交（peer 委派 + 本机用户消息）都过检查时，先跑完回合的 `whenComplete` 按 key 删除取消映射——后提交（委派）回合的 abortFlag/latch/future 被删，`cancelActiveTask` 对该回合静默失效（对端已 504 但委派回合继续跑）。窗口毫秒级、需会话超 token 预算（pre-loop 整合期）且同会话双提交，对抗复核后判定可接受、不修。

#### D5 三次校正：写锁等锁可中止化（对抗复核 3 major → 修复）

针对写锁加固本身的对抗性复核（writelock-adversarial-round，5 镜头 + 双席位反驳，2026-08）确认 **3 个 major + 1 个 minor**，全部源于同一根因：**阻塞式 `FileChannel.lock()` 等锁不可中止**——`cancelActiveTask` 的 `interrupt()` 只打到 `join()` 中的 run 载体、`CompletableFuture.cancel(true)` 不打断运行线程、原生文件锁等待对中断无响应（Windows 实测），abort 谓词仅在获锁后才轮询。后果：

1. **`distillSync` 超时预算计入等锁（major）**：并发关闭时后到实例的提炼被等锁挤占预算，`f.cancel` 后等锁/跑 LLM 的 daemon 载体随 JVM 退出被杀——fix-adversarial#2 本要防住的场景从"丢一份"退化为"可两份全丢"（静默）。
2. **pre-loop 整合 `join()` 等锁不可中止（major）**：对端持锁（其 LLM 无超时、可挂起数分钟）时，本机整个 agent 循环停摆，Stop/cancel 全失效，并泄漏 commonPool 载体。
3. **`channel.lock()` 阻塞等待不可中断（major）**：两个逃生口（distill 的 `f.cancel`、GUI 的 `agentRunner.interrupt`）都够不到等锁线程；`commonPool` 载体被外部 I/O 永久占用（未声明 `managedBlock`，不补位）。
4. **测试盲区（minor）**：`MemoryStoreWriteLockTest` 两线程同 JVM，静态 `INTRA_JVM_LOCK` 单独即可串行化，OS 级跨进程互斥从未被测到。

**修复（等锁改 abort 感知轮询）**：`MemoryStore.lockLongTermMemory(BooleanSupplier aborted)` 不再调用阻塞式 `lock()`，改 `tryLock()` 轮询（同 JVM 锁与 OS 锁皆然，间隔 50ms），每轮先查 `aborted`、可被中断——Stop / 提炼超时置位后 ≤50ms 放弃，返回 `null` = 未执行，不占载体、不泄漏。`distillSync` 超时先置共享 `timedOut` flag 再 `cancel`（flag 才是真正的中止信号），等锁轮询与写盘前检查立即放弃。锁放弃不降级写盘（降级会重新打开 lost-update 敞口），仅真实 IO 故障（盘满/权限）best-effort 降级。`MemoryWriteLock.close()` 幂等（`AtomicBoolean` 守卫 + `RuntimeException` 兜底），防静态锁永久泄漏。新增跨进程 fork-JVM 测试验证 OS 锁互斥 + abort 放弃。

**修复后残余行为（已接受）**：并发关闭且两者合计超 120s 预算时，后到者提炼**可见跳过**（对话框报 incomplete、会话保留、HISTORY.md 由关闭归档兜底）——从"静默丢两份"回到"至多可见丢一份且原始数据不丢"，且单实例超 120s 属锁前既有行为。单实例/串行关闭、或双实例合计在预算内（常见 LLM 时长）时仍完整串行化、双提炼共存。

#### D5 四次校正：锁泄漏 + 后置整合重复 + 写失败吞错（对抗复核 4 → 修复）

对三次校正修复 diff 的对抗性再验证（5 镜头 + 双席位反驳，2026-08）确认 **2 major + 1 minor + 2 nit**，全部修复：

1. **`FileChannel.open` 在 try 外（F1, major, 2/2 CONFIRMED）**：`lockLongTermMemory` 的锁文件通道在进入 try/finally 之前打开，`open` 抛 IOException（只读目录/锁文件不可创建）时 finally 不执行——静态 `INTRA_JVM_LOCK` 永久泄漏，后续所有 MEMORY.md 写永久轮询 tryLock 至 abort/死锁。修复：`open` 移入 try 内，`channel` 置 null、finally 以 null 守卫关闭通道再释放 JVM 锁。
2. **post-run 整合关闭期不可中止（F4, major, 2/2 CONFIRMED）**：AgentRunner 回合后的 `maybeConsolidate` 为 fire-and-forget，run future 完成时 AgentLoop `whenComplete` 立即把 abort flag 从 `abortFlags` map 移除——关闭期 `signalCancel` 找不到 flag，僵尸整合回合写盘前 abort 检查恒过，与关闭对话框对同一批消息重复整合（HISTORY.md 重复条目、MEMORY.md 被后写者覆盖）。修复：后置整合 `join()` 同步化（与 pre-loop 一致），整合在 run future 完成前落盘，关闭窗口内无僵尸回合。
3. **写失败吞错（F10, minor, 2/2 CONFIRMED）**：`writeLongTermMemory` 吞 IOException、`extractAndSaveToolCallResult` 无条件返回 true——MEMORY.md 只读时对话框报"整合完成"并清会话，内容实际未落盘。修复：写方法返回 boolean，写失败时整合返回 false（对话框报 incomplete、会话保留，HISTORY.md 仍由关闭归档兜底；失败内容本就未落盘，无数据丢失）。
4. **测试盲等握手（F3/F5, nit）**：跨进程测试 300ms 盲等会在 waiter 慢启动时空真通过——改为谓词握手（waiter 真正进入等锁轮询后置位）+ 存活/结果断言；`CHILD_READY` 单次 `readLine` 在出现前导日志行时误判——改循环读到标记。
5. **`SaveMemoryTool` 确认为死代码（F2/F6/F8, minor）**：全仓库无 `new SaveMemoryTool`，从未接线进任何 `ToolRegistry`；其锁语义（仅 read-compare-write）与活路径 `MemoryConsolidator` 的 read→LLM→write 全程持锁分叉，留着是维护陷阱（误接线会以陈旧 `memoryUpdate` 覆盖并发提炼结果）。处置：保留 + 类 javadoc 标注 `@deprecated 死代码`，design.md 不再将其描述为活路径参与者。

#### D5 五次校正：再验证 3 推翻 + F10 契约补全 + 快照竞态（对抗复核 5 → 修复/接受）

对四次校正修复 diff 的对抗性再验证（5 镜头 + 双席位反驳，2026-08）：13 发现去重后 10 条，**3 条被 2/2 反驳推翻、7 条确认存活**。

**推翻（不修）**：
1. **post-run join 自死锁（AgentRunner:160, REFUTED）**：`CompletableFuture.join()` 走 `ForkJoinPool.managedBlock → compensate()`，commonPool 为停车的 run 载体起补偿线程，排队提炼能拿到 worker，不构成死锁。唯一会挂的 parallelism=1 场景是前置 pre-loop join（:128）早已存在，非 F4 引入——**F4 的 join 保留**。
2. **`consecutiveFailures` 非 volatile（MemoryConsolidator:414, REFUTED）**：需同 session 级并发失败才丢计数，未证实可达。
3. **`SaveMemoryTool` 假成功（:86, REFUTED）**：死代码不可达。

**确认存活并修复（C1 F10 契约补全）**：
1. **`writeLongTermMemory` finally 清理翻转布尔（MemoryStore:105, nit）**：`Files.deleteIfExists(tmp)` 在 move 成功后抛 IOException（如 Windows AV 暂时占用临时文件）会落入外层 catch 返回 false——内容已落盘却报"未整合"，重提炼出重复 HISTORY 条目。修复：清理独立 try/catch，仅记录、不翻返回值。
2. **`appendHistory` 吞 IOException（MemoryStore:232 + MemoryConsolidator:384, minor）**：F10 只门控了 MEMORY.md，历史侧 append 失败（HISTORY.md 只读/目录占位）仍报成功 → 关闭路径清会话而 HISTORY.md 无记录，唯一可检索日志静默丢失。修复：`appendHistory` 返回 boolean，`extractAndSaveToolCallResult` 双门控。
3. **append 先于 MEMORY 写检查（MemoryConsolidator:384, minor）**：MEMORY 写失败时 history 条目已提交、索引未推进，同一批消息每次重试/关闭被再次追加，重复条目无限累积。修复：**先写 MEMORY 后 append**——重试时 `memoryUpdate==currentMemory` 跳过 MEMORY 写、仅补 history，幂等。

**确认存活并修复（C2/C3）**：
4. **残留中断泄漏到池化载体（AgentRunner:190, minor）**：`interrupt()` 读→log→interrupt 的 TOCTOU 窗口内 run 的 finally 已清中断，晚到中断落在复用载体上，下一轮在迭代 1 因 `isInterrupted()` 空回复。修复：run 任务入口清一次遗留中断（`signalCancel` 先置 abort flag，flag 才是取消唯一事实来源，清中断不影响取消语义）。
5. **关闭对话框快照竞态（CloseConsolidationDialog:48, minor）**：EDT 快照先于 `cancelActiveTask`，模态等待期间后置整合完成（写完 HISTORY/MEMORY、推进索引、run future 完成后 flag 被移除）→ cancel 空转 → 对同一批消息二次提炼。修复：worker 内 cancel 后重读未整合快照，已空则视为完成、否则只提炼仍未整合部分。

**接受不修（C4）**：**`saveSession` 吞 IOException（SessionManager:127, minor）**——关闭路径索引推进/清会话不落盘，重启后消息以旧索引复活、下次关闭重复归档；或"已清空"会话从磁盘复活。接受理由：根因是 HISTORY.md 与 session 索引的**两文件非原子提交**，即便 `saveSession` 返回 boolean 也只能让关闭路径打"响亮失败"日志，重启后的重复归档仍挡不住（诊断价值 > 防错价值）；且它是热路径（每次持久 run + 每轮整合都调），改签名波及多处忽略返回值的调用点。数据本身不丢（消息仍在 jsonl，仅重复归档/会话复活），与 fix-adversarial#3 同档接受。

#### D5 六次校正：`maybeConsolidate` 内联化（简化复核 4 候选 → 2 落地，2026-08）

对 abort/`BooleanSupplier` 传播机制的简化专项复核（3 读者测绘 → 提案 → 双席位对抗验证，4 候选全 SAFE）推翻了一条"砍不动"的初判：`maybeConsolidate` 内部的 `supplyAsync + join` 异步包装可整体拆除。

- **前提事实**：循环体所有 break 路径后恒 `return true`，返回值信息量为零；全仓库仅 AgentRunner 前置/后置两处调用，均立即 `.join()` 且丢弃返回值——异步性从未被消费。现状是"两条 commonPool 载体挂同一时长"（run 载体停车 join、整合独占另一载体），内联收敛为一。
- **安全性论据（2/2 SAFE）**：`signalCancel` 先置 abort flag 再 interrupt（AgentLoop:421-428），flag 是取消唯一事实来源；interrupt 落在内联整合的任何阶段（等锁 sleep 抛 `InterruptedException` → 锁返回 `null`"未执行"；LLM 调用异常 → catch-all → 失败处理按"取消非失败"）都收敛到与 flag 相同的"不落盘"结局。`saveSession` 本就同线程暴露于 interrupt，非新暴露面。
- **收益**：F4 防僵尸从"靠 join 兜住"变为结构性保证（内联代码不可能活过 run future，`whenComplete` 移除 flag 必然后于整合完成）；commonPool 载体占用减半；顺带删除零调用方的 `maybeConsolidate(Session)` 单参重载。
- **签名**：`void maybeConsolidate(Session, BooleanSupplier)`——旧包装恒返回 true 且无人消费，保留 boolean 是死信息。
- **保持不动**：supplier 逐层传参（flag 是 per-run，consolidator 共享实例，字段化错误）；5 个 aborted 检查点（各守独立窗口）；`distillSync` 的 `supplyAsync + 有界 get + timedOut`（唯一真跨线程超时观测路径，`orTimeout` 不能停运行中任务）；`consolidateWithAi(List)` 单参重载（`/new` 后台归档 `archiveMessagesAsync` 唯一调用方，"无取消语义"是故意——快照取自 signalCancel 之前，仍应归档）；`SaveMemoryTool`（依 D5 五次校正处置保留 + `@deprecated`，复核确认可删但遵循既有决定）。

#### D5 七次校正：`concurrency-safe-tool-batching` 对抗复核（2026-08）

对工具并发分批变更（`concurrency-safe-tool-batching`）的对抗性复核。多 agent 工作流因限流（429→501）三轮未能完整跑通，最终由逐行读码判定；关键竞态定性经用户纠正后收敛。

- **C1 存量 EDT 读竞态（定性修正，记录不修）**：4 个树读工具（`get_test_plan_tree`/`find_element`/`get_selected_element`/`query_element_properties`）在 run 载体线程（非 EDT）同步读 `DefaultTreeModel`（`JMeterTreeModel extends DefaultTreeModel`，无同步），与用户在 EDT 上的树编辑（拖拽/删节点）**从来无互斥**。定性要点：**串行时代同样存在**（`executeWithEvent` 内联在 run 载体线程，一样非 EDT），本变更仅把树读放进并发批使重叠窗口略宽——**存量问题、非新引入**。后果为间歇性 `ConcurrentModificationException`/越界/半吊子快照，被各工具 try/catch 兜成 error 非 kill JVM。
- **C1 处置与竞态对边界**：Agent 写工具（`create`/`move`/`delete`…）均无并发标记 → 单例批内联执行，`partitionByConcurrencySafety` 保证**读批与 Agent 写批严格顺序、不重叠**；唯一重叠方是**用户 EDT 编辑 vs Agent 并发读**。修复（摘除树读出白名单）不成立——单例批仍内联 run 载体线程，一样非 EDT；**根治需另行变更给树读工具加 `invokeAndWait` EDT 封装**（与并发机制正交，唯一正确解）。
- **白名单审计补强**：复核中发现 3 个"读但未准入"工具。定论——`parse_jmx_file`（纯 DOM 文件读）、`get_script_info`（String/static 快照）无共享可变状态，**准入**；`get_log_panel_content`（已 `EdtRunner` EDT 封装读）标了无并发收益且徒增 EDT 竞争，**不标**。白名单 11 → 13。
- **文档发现 2 条**：`proposal.md`「10 个」→ 实为 11→13（已修）；`docs/`+`TODO/`（本次 `.gitignore` 排除、不入库不分发）仍宣扬已删 `concurrentTools` API——判定不修（历史对照文档，编译器纠正，新开发者不可见）。

### D6. 遗留 `jmeter-ai-chat.jsonl` 的迁移

- **选择**：启用每实例会话后，旧的 `jmeter-ai-chat.jsonl` 不再被读写。启动期一次性、best-effort 把其内容归档进共享 `HISTORY.md`（标注来源为 legacy），原文件保留不删。之后各实例各自 `instanceId` 文件。
- **替代**：直接忽略旧文件——拒绝，会丢失既有用户上下文。

## Risks / Trade-offs

- **交互式深度提炼拖延退出** → 对话框 + `SwingWorker`，深度提炼有界超时（`agent.memory.consolidate-on-exit.timeout.ms`）；超时/异常保留已整合部分并继续退出；用户可选"否"直接跳过 LLM。
- **EDT 冻结** → 深度提炼转 `SwingWorker`（非 EDT），进度经 `publish/process` 回传；对话框模态阻塞但不冻结。
- **前置监听 + shutdown hook 重复归档** → 共享归档例程 + 幂等守卫（`AtomicBoolean` + `lastConsolidatedIndex` 去重），`HISTORY.md` 不重复写入。
- **对话框早于 JMeter"未保存改动"确认框** → `doAction` 在前置监听之后才 `CHECK_DIRTY`；两框先后出现可接受。
- **shutdown hook 兜底无 LLM/无对话框** → 兜底仅静默归档 `HISTORY.md`（快、可靠），不做 LLM；非用户退出仍保留共享桥，只是不深度提炼。
- **hook 线程读 `GuiPackage` 致 NPE/死锁** → `instanceId`/session 句柄取自 holder 普通字段；`jmxPath` 在动作监听期（EDT）预先写好，hook 不现读。
- **`port-{pid}.json` 并发读写（jmxPath 更新 vs 他实例读）** → 沿用既有 temp+`ATOMIC_MOVE` 原子写；读者容忍读到上一版本。
- **委派到繁忙实例（用户正在聊天）** → 目标 `AgentLoop` 串行处理，委派可能排队/超时；复用既有超时+`cancelActiveTask`；结果/错误明确回传。
- **孤立会话文件累积** → 启动期扫描 `sessions/`，对注册表确认已失活（PID+TCP 双死）且超过 TTL 的 `instanceId` 文件保守回收；活跃实例文件不动。
- **多实例持有同一 jmx** → 按最近 `startedAt` 确定性择一，结果中说明。
- **token/安全** → 回环 + 端口文件 token，威胁模型为本机同用户进程（与既有 CLI↔GUI 一致）；不扩展到跨机。
- **IPC 默认开启**（设计期原为默认关闭，实现期翻转，见 D5 校正点 4）→ 协作特性开箱即用；`jmeter-ai-sample.properties`/文档明确如何关闭（`jmeter.ai.ipc.enabled=false`）。

## Migration Plan

1. 发布带新开关的版本（`per-instance`/`consolidate-on-exit`/`coordination` 默认 true，但协作受 IPC 开关约束）。
2. 用户首次启动：旧 `jmeter-ai-chat.jsonl` 一次性归档进 `HISTORY.md`（best-effort），随后各实例用 `instanceId` 文件。
3. 回滚：置 `agent.session.per-instance=false` 即恢复全局 `jmeter-ai-chat` 会话键行为（backcompat 分支保留对旧键的读取）。

## Open Questions

- ~~委派任务是否需要在接收侧 UI/session 中打标（如 `[delegated]` 前缀）？~~ → **已解决（2026-08）**：委派载荷带 `[delegated-from instanceId=… pid=… script=…]` 前缀 + 请求信封 `delegated=true`（见 D4 后续校正），对端会话/GUI 可审计来源并据此硬阻断再委派。
- 孤立会话文件清理策略（启动扫描 TTL vs 显式命令）→ 倾向启动扫描 + TTL（如 7 天），具体值待定。
- `list_instances` 是否每次 TCP 探活 → 倾向按需探活（复用 `InstanceRegistry.isAlive`），信任端口文件 + 惰性清理。
- 关闭整合对话框是否提供"记住选择/不再询问"选项 → 本期每次关闭都询问；"记住选择"留作可选增强。
