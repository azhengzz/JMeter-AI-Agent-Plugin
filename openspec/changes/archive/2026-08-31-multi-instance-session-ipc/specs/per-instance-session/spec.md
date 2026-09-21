## ADDED Requirements

### Requirement: 每实例独立会话标识

每个 JMeter GUI 实例 SHALL 在启动期（`SelectionInitCommand` 处理 `ADD_ALL` 时）生成一个进程级唯一、每次启动新建的 `instanceId`，并以此为会话键。会话文件 SHALL 落在 `{workspace}/sessions/{instanceId}.jsonl`，取代当前全局硬编码的 `jmeter-ai-chat.jsonl`。`instanceId` SHALL 在一次启动的全生命周期内保持不变，且每次新启动生成新的 `instanceId`（即使操作系统复用了同一 PID，也 MUST NOT 继承上一次启动遗留的会话文件）。`instanceId` 的生成格式 SHALL 至少包含启动时间戳或等价随机量以保证跨启动唯一。

#### Scenario: 两个并发实例拥有各自独立的会话文件
- **WHEN** 同一台机器上同时启动两个 JMeter GUI 实例（共享同一 `{workspace}`）
- **THEN** 两个实例各自生成不同的 `instanceId`，分别读写 `sessions/{instanceId-A}.jsonl` 与 `sessions/{instanceId-B}.jsonl`
- **AND** 一个实例的聊天上下文不出现在另一个实例的会话文件中

#### Scenario: GUI 与 IPC 在同一实例内共享同一会话键
- **WHEN** 一个实例的 GUI 面板（`AiChatPanel`）发起对话，且该实例的 `IpcServer` `/agent` 端点收到一条投递消息
- **THEN** 两者使用相同的 `instanceId` 作为会话键，写入同一个 `{instanceId}.jsonl`
- **AND** 不再出现 GUI 与 IPC 各自指向不同会话键的漂移

#### Scenario: 每次启动即新建独立会话，不继承遗留
- **WHEN** 一个 JMeter 实例关闭后，同一 PID 被操作系统复用于启动一个新的 JMeter 实例
- **THEN** 新实例生成新的 `instanceId`，使用全新的 `sessions/{newInstance}.jsonl`
- **AND** 不读取、不追加前一次启动遗留的会话文件内容

### Requirement: 会话键单一来源（消除双常量漂移）

代码中当前两处独立定义的会话键常量——`AiChatPanel.CHAT_SESSION_KEY`（`AiChatPanel.java:52`）与 `IpcServer.AGENT_SESSION_KEY`（`IpcServer.java:63`）——SHALL 被移除或统一为对同一进程级 `instanceId` 来源的引用。GUI 会话键与 `/agent` 端点默认会话键 MUST 派生自同一来源，MUST NOT 各自维护独立字面量。

#### Scenario: 两处常量不再各自硬编码
- **WHEN** 审查 `AiChatPanel` 与 `IpcServer` 的会话键来源
- **THEN** 二者均读取同一进程级 `instanceId` 来源，仓库中不再存在两处独立的 `"jmeter-ai-chat"` 字面量会话键定义

#### Scenario: 修改实例标识来源时 GUI 与 IPC 同步生效
- **WHEN** 进程级 `instanceId` 来源在同一启动内被确定
- **THEN** GUI 会话与 `/agent` 投递目标会话一致，无漂移窗口

### Requirement: 关闭期记忆归档（始终沉淀进共享记忆）

JMeter 实例关闭时，插件 SHALL 始终（静默、best-effort）把当前实例会话中未整合的消息归档进共享 `HISTORY.md`（本地文件写入，不涉及 LLM，快、可靠），使任一实例关闭后其会话内容对其他实例可见——该归档 SHALL 无论用户是否选择深度提炼都执行。归档 SHALL 在两条退出路径上都触发：① 用户主动关闭（窗口关闭按钮 / File→Exit，经 `ActionRouter` 对 `ExitCommand` 的前置动作监听 `addPreActionListener`，EDT）；② 非用户退出路径（崩溃 / `Restart` / 致命 `System.exit`，经 JVM `Runtime.addShutdownHook` 兜底）。两条路径 SHALL 共享同一归档例程并以幂等守卫防止重复写入。归档异常（IO 错误等）MUST 被捕获并记录日志，MUST NOT 阻断 JVM 退出。归档 SHALL 受 `agent.memory.consolidate-on-exit`（默认 `true`）门控；`agent.memory.enabled=false` 时不触发。整合目标 `MemoryStore` 是跨实例共享的（`{workspace}/memory/`）。注：因 JMeter 核心无干净退出回调（`ExitCommand` 直接 `System.exit(0)`），shutdown hook 仅作非交互兜底；交互式深度提炼对话框经 `ExitCommand` 前置监听实现（见下一需求）。

#### Scenario: 用户主动关闭时未整合消息归档进共享记忆
- **WHEN** 用户在对话进行中关闭 JMeter（无论随后在对话框选"是"或"否"）
- **THEN** `ExitCommand` 前置监听把当前会话未整合消息归档进共享 `HISTORY.md`
- **AND** 随后退出流程继续

#### Scenario: 非用户退出时 shutdown hook 兜底归档
- **WHEN** JMeter 经非用户路径退出（如崩溃、`Restart`、致命错误 `System.exit`）
- **THEN** JVM shutdown hook 兜底把未整合消息归档进共享 `HISTORY.md`
- **AND** 不弹出对话框（关闭期无法交互）

#### Scenario: 两条路径不重复写入
- **WHEN** 用户主动关闭（前置监听已归档）后 `System.exit` 触发 shutdown hook
- **THEN** 幂等守卫使 shutdown hook 跳过已完成的归档
- **AND** `HISTORY.md` 不出现重复条目

#### Scenario: 跨实例记忆留存
- **WHEN** 实例 A 关闭（其会话已归档进共享 `HISTORY.md`），随后实例 B 启动并发起对话
- **THEN** 实例 B 的系统提示词（经 `ContextBuilder.buildSystemPrompt()` 读取 `memoryStore.getMemoryContext()`）可包含实例 A 沉淀的内容
- **AND** 实例 B 的独立会话文件与实例 A 的互不干扰

#### Scenario: 归档异常不阻断退出
- **WHEN** 归档过程抛出异常（如 IO 错误）
- **THEN** 异常被捕获并记录为日志
- **AND** JMeter 照常退出，已完成的归档保留

#### Scenario: 开关关闭时不归档
- **WHEN** `agent.memory.consolidate-on-exit=false`（或 `agent.memory.enabled=false`）且用户关闭 JMeter
- **THEN** 不触发关闭期的 `HISTORY.md` 归档
- **AND** 未整合消息仍以原始形式保留在 `{instanceId}.jsonl` 中（不丢失）

### Requirement: 关闭整合交互对话框（深度提炼由用户 gating）

当用户主动关闭 JMeter 且当前会话存在未整合消息（N>0，N 经 `Session` 未整合消息计数得出）时，插件 SHALL 在退出前于 EDT 弹出模态对话框，告知用户待整合消息数 N，并询问是否进行 LLM 深度提炼（把事实写入 `MEMORY.md`）。对话框 SHALL 提供"是/否"选择：

- 选"否"：跳过深度提炼（不调用 LLM、不写 `MEMORY.md`）；共享 `HISTORY.md` 归档仍照常完成（见上一需求）。
- 选"是"：对话框 SHALL 展示整合状态/进度（深度提炼在 EDT 之外执行，进度回传 EDT 实时更新），整合完毕后告知用户，随后才继续退出流程。

N=0 时 SHALL NOT 弹出对话框，直接退出。测试运行中（`ExitCommand`/`AbstractActionWithNoRunningTest` 会拒绝退出）时 SHALL NOT 弹出对话框。深度提炼 SHALL 受有界超时（`agent.memory.consolidate-on-exit.timeout.ms`）约束；超时/异常按 best-effort 处理，已整合部分保留，MUST NOT 阻断退出。对话框出现时机早于 JMeter 自身的"未保存改动"确认对话框（`ExitCommand.doAction` 在前置监听之后才做 `CHECK_DIRTY`）。

#### Scenario: 存在未整合消息时弹出对话框
- **WHEN** 用户关闭 JMeter，当前会话有 N>0 条未整合消息
- **THEN** EDT 弹出模态对话框，显示待整合消息数 N 并询问是否深度提炼
- **AND** 对话框提供"是/否"

#### Scenario: 选"否"跳过深度提炼但保留归档
- **WHEN** 用户在对话框选"否"
- **THEN** 不调用 LLM、不写 `MEMORY.md`
- **AND** 共享 `HISTORY.md` 归档仍完成
- **AND** 退出流程继续

#### Scenario: 选"是"展示进度并在完成后告知
- **WHEN** 用户在对话框选"是"
- **THEN** 对话框展示整合状态/进度，深度提炼在非 EDT 线程执行
- **AND** 整合完毕后对话框告知用户，随后退出流程继续

#### Scenario: 无未整合消息时不弹对话框
- **WHEN** 用户关闭 JMeter，当前会话未整合消息数 N=0
- **THEN** 不弹出对话框，直接退出（归档为 no-op）

#### Scenario: 测试运行中不弹对话框
- **WHEN** 测试正在运行时用户触发关闭
- **THEN** 不弹出整合对话框（`ExitCommand` 自身守卫会拒绝退出）

#### Scenario: 深度提炼不冻结 UI
- **WHEN** 用户选"是"后深度提炼进行中
- **THEN** 整合在 EDT 之外执行，对话框进度持续更新，UI 不冻结

#### Scenario: 深度提炼超时/异常不阻断退出
- **WHEN** 深度提炼超过 `agent.memory.consolidate-on-exit.timeout.ms` 或抛异常
- **THEN** 已整合部分保留，对话框告知未完成，退出流程继续

### Requirement: 孤立会话文件的清理

由于每次启动新建独立会话文件，`{workspace}/sessions/` 下 SHALL 提供对孤立会话文件（来自崩溃或已退出实例、且无活跃实例引用）的清理机制。清理 SHALL 是保守的：MUST NOT 删除任何仍可能被活跃实例使用的会话文件，且 SHOULD 仅在确认对应实例已退出（经实例注册表 PID/TCP 双确认）后回收。清理可经启动期扫描或显式命令触发。

#### Scenario: 崩溃实例遗留的会话文件最终被回收
- **WHEN** 一个实例异常退出（未执行正常关闭回调），遗留 `{instanceId}.jsonl`，且注册表确认其 PID 与 TCP 均已失活
- **THEN** 后续某次启动期扫描或显式清理回收该孤立会话文件
- **AND** 活跃实例正在使用的会话文件不被回收

#### Scenario: 活跃实例的会话文件不被误删
- **WHEN** 清理机制扫描 `sessions/` 目录
- **THEN** 任何仍被活跃实例（注册表中 PID 存活）使用的会话文件 MUST NOT 被删除
