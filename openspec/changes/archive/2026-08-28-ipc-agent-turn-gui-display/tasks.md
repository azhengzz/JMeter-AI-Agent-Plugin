## 1. 协议与响应契约

- [x] 1.1 `IpcResponse` 新增可选字段 `cancelled`/`cancelReason`/`partialContent`，类与既有字段加 `@JsonIgnoreProperties(ignoreUnknown=true)`；单测覆盖：新字段序列化形状、反序列化忽略未知字段（模拟旧版本读到新响应体）
- [x] 1.2 `JmeterCli.printResp` 识别 `cancelled`/`cancelReason`/`partialContent`：终止原因 + 部分内容（截断标记）打到输出，`--json` 路径自然携带；单测覆盖三分支（正常/被目标用户终止/超时）
- [x] 1.3 `DelegateToInstanceTool` 结果文案区分「被目标实例用户终止（附部分内容）」与「对端超时」「执行失败」；在 `DelegateToInstanceToolTest` 追加对应用例

## 2. per-turn 回调架构（AgentLoop）

- [x] 2.1 `AgentLoop.startTurn` 的 hook 回调改为按回合解析（本地回合=发起方回调，语义不变；废弃 loop 级 `progressCallback` 残留依赖，`setProgressCallback` 降级为兼容垫片或移除并同步调用方——同步范围显式含 `AgentSwingWorker` 调用点与 `AgentLoop` 内 loop 字段读取点，grep 兜底确认无其他生产调用方）；`mvn clean test` 全绿 + 新单测：两个回合的回调互不串扰
- [x] 2.2 新增 `TurnPresenter` 接口（onTurnStarted/onProgress/onTurnCompleted/onTurnCancelled/onTurnRejectedBusy/onInjected）与 `AgentLoop` 注册/注销点；单测：注册者收到回调、null 注册者 no-op、非当前会话键不派发

## 3. IpcServer：前缀、累积器与结构化取消

- [x] 3.1 `handleAgent` 对 `delegated=false` 的**非命令**消息加 `[from cli] ` 前缀（进消息文本与 jsonl）；命令豁免：先经 `AgentLoop.getCommandRouter()` 判 `isPriority||isDispatchable`，命中则原样投递不加前缀；单测断言：普通消息文本与落盘内容带前缀、CLI 发 `/status` 不带前缀且仍走命令分发路径
- [x] 3.2 为每个 `/agent` 回合挂线程安全的 assistant 文本累积回调（GUI 无关、独立于 presenter）；单测：并发 append 与取消时读取
- [x] 3.3 取消分支升级：`CancellationException` → 409 + `cancelled=true, cancelReason="cancelled_by_target_user", partialContent`（8000 字符截断+省略标记）；超时 504 分支带 `cancelled=true, cancelReason="timeout"`；单测以 mock future 分别抛 Cancellation/Timeout 验证响应体
- [x] 3.4 presenter 接线：回合开始/完成/超时取消在 IpcServer 侧通知；busy 快拒与 CLI 注入 ack 的通知落点在 `AgentLoop.processMessage` Phase 2 分支；单测以 fake presenter 断言各通知与顺序

## 4. 面板领养（AiChatPanel）

- [x] 4.1 AiChatPanel 实现 `TurnPresenter` 并注册（构造时 + `switchAiService` 重建 loop 后与 `republishListener` 同点位重注册）；所有回调 `invokeLater` 到 EDT 并过 `conversationGeneration` 代数过滤；单测覆盖投递与代数丢弃路径
- [x] 4.2 `onTurnStarted`：append 带前缀的来源消息 + `setButtonToStopMode()`；回合终结后按 `hasActiveRun(currentSessionKey)` 复位发送模式（与 `handleAgentResponse` 判据统一）；手动验证：委派回合到达后按钮切换、结束后复位
- [x] 4.3 `onProgress` 接入既有 `handleProgress` 渲染链（思考/工具事件/进度/中间回复），`onTurnCompleted` 走 `appendBotResponse` 同路径；手动验证：委派回合全流与本地回合视觉一致
- [x] 4.4 系统提示行：`onTurnCancelled`（超时/人工终止两种文案，人工终止含"部分结果已回传"回执）与 `onTurnRejectedBusy` 各渲染一行系统样式文本（不进会话存储）；手动验证三场景文案
- [x] 4.5 核验本地注入 IPC 回合的现状 UI 反馈（`sendMessage` → `injectMessage` 路径），缺失则补齐注入消息的面板显示；手动验证：委派回合运行中本地输入显示并注入

## 5. STOP 的 EDT 阻塞修复

- [x] 5.1 `stopActiveTask` 按 `AgentLoop.java:605-606` TODO 原始切分修复 EDT 阻塞：`signalCancel`（非阻塞，含摘注入路由槽）保留 EDT 同步执行——维持"UI 复位时路由槽必已摘除"不变式，防 STOP 后输入被误注入垂死回合遭静默作废；仅 ≤5s latch await 挪后台线程；EDT 立即复位按钮 + append 提示；保留 `activeWorker.cancel(true)`；回归既有 `/stop` 相关测试 + 手动验证长回合点 STOP 无 UI 冻结、STOP 后立即输入走正常发送而非注入

## 6. 集成验证

- [x] 6.1 双实例联调：实例 A `delegate_to_instance` 委派实例 B —— B 面板全流显示（消息/思考/工具/最终回复）、按钮状态正确、运行中注入一条本地消息、STOP 终止后 A 收到含部分内容的「被目标用户终止」结果（用户 2026-08-28 实测通过）
- [x] 6.2 CLI 联调：`jmeter-cli agent` 消息在面板显示且带 `[from cli]` 前缀；目标忙时 CLI 注入消息同样显示；CLI 发起的回合被 STOP 后输出终止原因与部分内容（用户 2026-08-28 实测通过）
- [x] 6.3 边界验证：`--session foo` 回合 headless 不进面板；JMeter 启动后面板未创建时委派 headless 且不弹 UI；`/new` 后迟到回调被代数过滤丢弃（用户 2026-08-28 实测通过；超时路径经 `jmeter.ai.ipc.agent.timeout.ms=15000` 收到 504+cancelled 结构化回执，15s 内无中间文本故无部分内容段属预期——部分内容打印路径已在 6.2 STOP 场景验证）
- [x] 6.4 回归：`mvn clean test` 全绿（clean 防 stale class）；GUI 验证前确认 `lib/ext` 只留最新 jar、且从 `bin/` 启动 JMeter

## 7. 对抗性审查修复（8 agent 审查 + 逐条对抗复核，4 确认 0 推翻）

- [x] 7.1 F1 signalCancel 连坐：取消排队中的会话回合时无差别 `agentRunner.interrupt()` 会打断单线程 executor 上另一会话正在跑的 LLM 调用（生产路径：CLI `--session` 键超时自取消连坐本地回合）。修复：`runningTurnSession`（volatile，pickup 时置/收尾时清）限定 interrupt 只命中目标会话自己的运行回合；排队回合仍由 `future.cancel(true)` + 任务头取消预检作废。回归：`AgentLoopSignalCancelScopingTest`（排队会话取消不连坐 + 运行中会话取消仍 interrupt 的反向兜底）
- [x] 7.2 F2 面板懒创建换掉工厂单例 loop（孤儿化在跑 IPC 回合：通知丢失 + STOP 失效）。（a）构造期 `TracedAiService.wrap(new ClaudeService())` 鲜实例 ≠ IPC 预热实例 → 改走 `AiServiceFactory.createService(AiConfig.getDefaultModel())` 与 IpcServer 预热同缓存引用；选择器条目带 `provider:` 前缀进工厂会分裂 cache key → `getAiServiceForCurrentModel` 剥前缀对齐（出向 API 请求不受影响，provider 内部本就再剥一次）；（b）构造完成时 `adoptRunningIpcTurnIfNeeded` 领养在跑回合：提示行 + loading + Stop 模式 + 武装呈现窗口，错过的不补放（Q12）。回归：`panelConstructionSharesFactoryCachedLoopWithIpcWarmup` + `adoptsRunningIpcTurnWhenPanelJoinsMidTurn`
- [x] 7.3 F3 垂死 IPC 回合迟到终结渗入本地新回合：`startNormalSend` 顶部 `ipcTurnGeneration = -1` 关闭呈现窗口（本地回合一开即硬边界，对齐 /new）；STOP 自身的取消回执在渲染后到达不受影响。回归：`lateIpcTerminalAfterLocalTurnStartIsDropped`
- [x] 7.4 F4 `onTurnStarted` 代数在 EDT 执行时才读：/new 先落地时按新代数武装窗口，幽灵行与取消回执渗入新会话。修复：通知时快照（对齐 onTurnRejectedBusy/onInjected 既有模式），EDT 执行时比对不符即弃。回归：`turnStartQueuedBehindSlashNewDoesNotRearmWindow`
- [x] 7.5 回归：`mvn clean test` 全绿（440 tests，0 failures；含 7.1-7.4 新增 6 个回归用例）
- [x] 7.6 手动联调中发现的既有 CLI 文案缺陷：`--timeout`（客户端整体 HTTP 超时，默认 130s）到点时 `friendly()` 把读超时也报成 "cannot reach JMeter IPC server"——loopback 上服务器可达、回合仍在跑，误导排障方向。修复：读超时分支单拆并回显**实际生效值**（显式传过 `--timeout` 的用户看到自己的数；提示回合可能在服务器侧仍在跑、断开不中止、建议调大 `--timeout` 或调小 `jmeter.ai.ipc.agent.timeout.ms`）；连接拒绝分支保留 stale port file 诊断。缺省值散在 5 处（timeoutOf/报错文案/agent·run·全局帮助）统一收敛为 `DEFAULT_REQUEST_TIMEOUT_MS` 常量（注释说明其须长于服务器 120s 的缘由），markdown 技能文档一处字面量保留。回归：`JmeterCliTest.friendlyDistinguishesReadTimeoutFromUnreachableServer` / `friendlyKeepsUnreachableHintForConnectRefused`
