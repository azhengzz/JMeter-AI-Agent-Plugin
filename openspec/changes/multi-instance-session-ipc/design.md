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

### D5. 特性门控：协作依赖 IPC 开启

- **选择**：`delegate_to_instance`/`list_instances` 仅当 `jmeter.ai.ipc.enabled=true` 且 `agent.instance.coordination.enabled=true`（默认 true）时注册到主 `ToolRegistry`。IPC 关闭则两工具不注册。`agent.session.per-instance`（默认 true）门控每实例会话；`agent.memory.consolidate-on-exit`（默认 true）门控关闭整合。
- **为何协作依赖 IPC**：委派传输即 IPC 通道，IPC 关闭则无传输、注册表不写端口文件，协作无从谈起；显式门控避免主代理看到工具却调用即失败。

#### D5 后续校正：移除冗余开关 + 放宽提炼超时

实现后复盘，两处门控开关冗余、徒增认知负担，予以移除（行为固定为原默认）：

1. **移除 `agent.instance.coordination.enabled`**：协作工具现仅受 `jmeter.ai.ipc.enabled` 单门控注册。原二级开关默认 true 且无独立配置意义（IPC 开即应提供协作），移除后 `JMeterToolRegistry.registerInstanceCoordinationTools` 退化为单门。安全门 `jmeter.ai.ipc.enabled`（默认 true、仅 loopback、token 鉴权）保留不变。
2. **移除 `agent.memory.consolidate-on-exit`**：关闭整合始终开启。原开关默认 true，语义即"始终静默归档 HISTORY.md"，实际无人会关；`CloseConsolidationCoordinator.archiveSilently` 退化为幂等守卫 + agent 检查，`CloseConsolidationDialog.handleExit` 仅保留 `agent.memory.enabled` / N=0 / 测试运行中守卫。
3. **`agent.memory.consolidate-on-exit.timeout.ms` 默认 60s→120s**：深度提炼含一次 LLM 调用，60s 常触发误超时；放宽至 120s（与 `/agent` 路由超时一致），仍保留"超时保留已整合部分并继续退出"的 best-effort 语义。
4. **`jmeter.ai.ipc.enabled` 默认 false→true**：多实例协作开箱即用（设计期 Risks 原按默认关闭评估，见下）。`AiConfig.isIpcEnabled` 代码默认、CLAUDE.md、README（中/英）、`jmeter-ai-sample.properties`（注释与发行值）统一为默认开启；安全边界不变（仅 loopback、token 鉴权、`bind` 拒绝通配地址，威胁模型本机同用户进程），需关闭显式设 `false`。

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

- 委派任务是否需要在接收侧 UI/session 中打标（如 `[delegated]` 前缀）？→ 本期不做，留可选增强。
- 孤立会话文件清理策略（启动扫描 TTL vs 显式命令）→ 倾向启动扫描 + TTL（如 7 天），具体值待定。
- `list_instances` 是否每次 TCP 探活 → 倾向按需探活（复用 `InstanceRegistry.isAlive`），信任端口文件 + 惰性清理。
- 关闭整合对话框是否提供"记住选择/不再询问"选项 → 本期每次关闭都询问；"记住选择"留作可选增强。
