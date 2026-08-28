# Design: IPC 回合在目标实例 GUI 的显示与控制

## Context

现状事实（经代码探查确认）：

- IPC 回合经共享 `AgentLoop` 单例执行（`IpcServer.handleAgent` → `AgentLoop.processMessage(msg, sessionKey, delegated)`，IpcServer.java:283），不创建 `AgentSwingWorker`，不触碰面板。
- 面板渲染链只服务本地回合：`startNormalSend` append "You: …" → `AgentSwingWorker`（`setProgressCallback(update -> publish(update))`）→ EDT `handleProgress` / `handleAgentResponse`。
- **残留回调缺陷**：`progressCallback` 是 `AgentLoop` 级字段、由 GUI 最后一次设置且从不清除——GUI 发过消息后，IPC 回合的工具事件经旧 worker 的 publish 漏进面板（半状态根源）。
- 按钮状态由 `activeWorker` 字段驱动（只反映本地回合）；`cancelActiveTask(sessionKey)` 本身不分发起方，STOP 已能取消 IPC 回合（取消后 `/agent` 回 409），只是按钮没露出。
- `stopActiveTask` 在 EDT 上同步等取消收尾 latch ≤5s（AgentLoop.java:605 既有 TODO）。
- CLI 直连（`delegated=false`）busy 时消息进注入队列并回 ack "Message injected"；委派（`delegated=true`）busy 时 Phase 2 快速失败 "session busy"（HTTP 200 + `success=false`）。
- 委派载荷前缀 `[delegated-from …]` 由发起侧 `withProvenance` 添加；CLI 直连无前缀。
- 面板纯内存 append，不从 jsonl 重建；正常完成的 IPC 回合写 jsonl（中止跳过）。
- `IpcServer` 与 GUI 无引用关系；`AiChatPanel.INSTANCE` 为 private，唯一静态入口是 `resetAfterConsolidation()`。`republishListener`（`AgentLoop` → 面板）证明"面板领养非本地回合"的监听模式已有先例。

## Goals / Non-Goals

**Goals:**

- IPC 回合在面板的完整对等显示与控制（specs 各 Requirement）。
- **消灭 loop 级 `progressCallback` 残留依赖**：回调改为 per-turn 绑定，从机制上移除半状态，而非在其上打补丁。
- IPC 层保持 GUI-free：`IpcServer` 不获得面板引用（与 `InstanceRegistry` 零 JMeter 依赖的既有方向一致）。
- 协议演进向后兼容：新旧版本插件互委派不因新字段崩溃。

**Non-Goals:**

- 提案已列非目标（回放、委派方取消、落盘变更、气泡样式、自动开面板、非默认会话显示）不再重复。
- 不改 `/agent` 的执行模型（单线程 agent-loop 串行、busy 语义、超时机制均维持）。
- 不为 CLI 增加 caller 标识字段（见 D4 的取舍）。

## Decisions

### D1: 面板领养经 `AgentLoop` 上的 presenter 接口，而非 IpcServer 拿面板引用

新增回合呈现接口（暂名 `TurnPresenter`），由 `AiChatPanel` 构造时（及 `switchAiService` 重建 loop 后，与 `republishListener` 重注册同点位）注册到 `AgentLoop`。接口按 sessionKey 过滤：只领养 `InstanceContext.currentSessionKey()` 的回合（满足"仅当前会话显示"），面板未创建时无注册者 = headless（Q12 边界自然成立）。

```java
interface TurnPresenter {
    void onTurnStarted(String sessionKey, String message);   // append 来源消息 + 进 Stop 模式
    void onProgress(String sessionKey, ProgressUpdate u);    // 复用 handleProgress 渲染链
    void onTurnCompleted(String sessionKey, String finalText);
    void onTurnCancelled(String sessionKey, String reason);  // 超时/人工终止，一行系统提示
    void onTurnRejectedBusy(String sessionKey);              // busy 快拒，一行系统提示
    void onInjected(String sessionKey, String message);      // CLI 注入消息显示
}
```

- 备选 1：公开 `AiChatPanel.getInstance()` 供 IpcServer 直调——被否：IPC 层引入 GUI 依赖，且 IpcServer 在无 GUI 语境（CLI 复用传输）下不可测。
- 备选 2：继续用 loop 级 `progressCallback` 字段、由面板在领养时重设——被否：换一个残留者而已，本地/IPC 回合并发时仍互相踩。
- 所有回调内部 `invokeLater` 到 EDT，复用 `conversationGeneration` 代数过滤（`/new` 后迟到渲染丢弃，与本地回合同规则）。

### D2: per-turn 回调绑定，替换 loop 级字段

`startTurn` 组装 `AgentRunSpec` 时 hook 的 callback 改为按回合解析：本地回合 = 发起 worker 的 publish（现状语义不变）；IPC 回合 = presenter（存在则包装为回调，null 则无回调）。`setProgressCallback` 的公开语义废弃或降级为兼容垫片。这同时是"无残留依赖"需求（spec）的机制保证。

### D3: 按钮状态由"当前会话有活跃回合"驱动

- 领养事件 `onTurnStarted` → EDT `setButtonToStopMode()`（复用现状：STOP 可见 + 发送按钮重挂 inject，注入语义对本地/IPC 回合一视同仁——Q4 决策）。
- 回合结束（completed/cancelled/rejected 均算终结）→ 若 `agentLoop.hasActiveRun(currentSessionKey)` 为假则 `setButtonToSendMode()`；与 `handleAgentResponse` 既有判据（AiChatPanel.java:1068-1070）统一。
- 本地回合的 `activeWorker` 路径照旧，两路在"hasActiveRun"这一判据上收敛，不引入第二套状态机。

### D4: `[from cli]` 前缀在 `/agent` 处理器入口注入

`IpcServer.handleAgent` 对 `delegated=false` 的**非命令**消息统一加 `[from cli] ` 前缀（进消息文本 → 回合上下文 → jsonl 单一事实源）。不加 caller 标识字段的取舍：现状 `delegated=false` 的唯一调用方就是 CLI，字段是投机性设计（CLAUDE.md 反对）。委派消息的 `[delegated-from …]` 由发起侧添加，维持不动。

**命令豁免（对抗校验修正）**：斜杠命令不加前缀——命令分发是 trim 后的精确匹配（`CommandRouter.isPriority` / `isDispatchable`），加前缀会把 `jmeter-cli agent "/status"` 这类命令挡成普通消息发给 LLM。`handleAgent` 先经 `AgentLoop.getCommandRouter()`（public，纯路由逻辑，不破 IpcServer 的 GUI-free 约束）判断 `isPriority(message) || isDispatchable(message)`，命中则原样投递不加前缀、走既有命令路径。

### D5: 手动终止的结构化反馈与部分内容采集

- **采集**：`handleAgent` 为每个回合包装一个线程安全的累积回调，独立于 presenter——服务端响应不依赖 GUI 是否存在。累积来源为各迭代完成的内容（`onIntermediateResponse` 携带的中间回复 + 最终迭代内容）；代码无流式 API（`AgentHook.onStream` 无调用方，AiService 非流式），THINKING 事件受思考展示开关影响，均不作为累积来源。
- **响应**：`CancellationException` 分支（GUI STOP 竞争到取消）从裸 409 升级为：HTTP 409 + `IpcResponse` 新增可选字段 `cancelled=true`、`cancelReason="cancelled_by_target_user"`、`partialContent=<累积文本>`（截断上限 8000 字符，超出尾部省略标记，防响应膨胀）。超时 504 分支同样带 `cancelled=true`、`cancelReason="timeout"`（可与 409 程序化区分，CLI 友好）。成功/其他错误响应不变。
- **互操作**：`IpcResponse` 加 `@JsonIgnoreProperties(ignoreUnknown=true)`——旧版本对端（互委派场景）收到新字段时 Jackson 默认 `FAIL_ON_UNKNOWN_PROPERTIES=true` 会反序列化失败，必须双向防御。
- **调用方**：`JmeterCli.printResp` 识别 `cancelled/cancelReason/partialContent` 打印"被目标用户终止 + 部分内容"；`DelegateToInstanceTool` 结果文案区分"被目标实例用户终止（附部分内容）"与"对端超时/执行失败"。
- **EDT 修复（按 `AgentLoop.java:605-606` TODO 原始切分，不整体搬移）**：`cancelActiveTask` 内的 `signalCancel`（非阻塞：abort flag、interrupt、`future.cancel`、摘注入路由槽）保留在 EDT 同步执行，仅把 ≤5s 的 `completionLatches` await 挪到后台线程；await 结束无需回 EDT 收尾（UI 已提前复位按钮 + append 提示）；本地 `activeWorker.cancel(true)` 保留在 EDT（interrupt SwingWorker 本身不阻塞）。**不可整体搬移**的原因（对抗校验修正）：现状不变式是"UI 复位时注入路由槽必已摘除"——若 `signalCancel` 也进后台，窗口内 EDT 已复 Send 模式、用户输入被 `hasActiveRun`（槽未摘）误判为注入并渲染注入回执，而垂死回合的取消契约会把该消息静默作废（仅记日志），用户看到回执却永无回复。`signalCancel` 留在 EDT 保证该窗口为零。
- **竞态**：用户 STOP 与对端超时同时到达时，`future` 的取消二者幂等，响应分支以 `handleAgent` 内先触发的异常为准（409/504 可能互换，窗口毫秒级，可接受；`cancelReason` 以本端分支自述为准）。

### D6: 生命周期系统提示

presenter 各终结回调映射一行系统提示（呈现为系统样式文本，不进会话存储）：`onTurnCancelled` → 终止原因（"对端等待超时，回合已终止" / "已终止，部分结果已回传给发起方"）；`onTurnRejectedBusy` → "有委派请求因会话忙被拒收"。超时路径：`handleAgent` 超时分支在 `cancelActiveTask` 后经 presenter 通知（`reason="timeout"`）。

### D7: CLI busy 注入消息的显示

`delegated=false` 且会话忙时，既有行为在 `AgentLoop.processMessage` Phase 2（AgentLoop.java:238-263）：可分发命令直接分发返回快照；其余消息进注入队列并回 ack "Message injected into current conversation."。**通知落点在该 Phase 2 分支（非 IpcServer）**——经 presenter `onInjected` 显示该消息（带 `[from cli]` 前缀，普通用户消息样式）。本地用户的注入反馈维持现状 UI 行为（如缺则随实现补齐，tasks 中含验证项）。

### D8: 面板接入点

`AiChatPanel` 增加包内可见/受控的注册方法（构造器向当前 `AgentLoop` 注册 presenter；`switchAiService` 重建 loop 时随 `republishListener` 同点位重注册；`resetAfterConsolidation` 不变）。不公开全局单例 getter，避免 IpcServer 侧绕过 D1。

## Risks / Trade-offs

- **[EDT 违例]** presenter 回调来自 agent-loop / ipc-worker / ForkJoinPool 载体线程，任何直接触碰 Swing 的路径都会违规（memory: EDT 线程模型）→ 所有回调强制 `invokeLater`；代码评审与测试覆盖。
- **[新旧版本互操作]** 旧版对端反序列化新版本发出的 409/504（含新字段）会失败 → `@JsonIgnoreProperties(ignoreUnknown=true)` 只能保护"新版本读含未知字段的响应"与未来字段演进，救不了旧版对端；残余失败面收敛为"混布 + 委派被取消/超时"的交集，表现为该次委派工具报传输错误（进程不崩溃），成功响应无新字段不受影响——接受此残余风险（见 Migration）。
- **[STOP/超时竞态]** 409/504 可能互换 → 窗口毫秒级、语义均有 `cancelReason` 兜底，接受。
- **[partialContent 一致性]** 累积器与最终渲染可能有小段差异（流式 vs finalize）→ 部分内容定位为"尽力而为"，CLI/委派方文案注明"部分"。
- **[面板创建时机]** 用户在 IPC 回合运行中途才首次打开面板 → 该回合中途的进度无法补放（无缓冲，Q12 决策）；提示行与后续事件照常。接受此边界。
- **[注入语义扩散]** 本地注入会改变委派方收到的回复内容（Q4 有意决策）→ 委派方文案含 [delegated-from] 前缀，主代理可感知；不额外设防。

## Migration Plan

纯插件内变更，无存储/配置迁移。构建部署新 jar 即生效；回滚 = 换回旧 jar（新字段只由新代码在取消/超时响应中发出，回滚后不再出现）。**混布残余风险如实说明**（对抗校验修正）：`@JsonIgnoreProperties` 只覆盖"新版本读含未知字段响应"的方向；**旧版本对端**解析新版本的 409/504（含新字段）仍会反序列化失败，表现为该次委派工具报错而非进程崩溃，且仅命中"混布 + 委派被取消/超时"交集窗口。

## Open Questions

无（grilling 两轮 12 项决策 + 6 项实现假设已全部 settle；`partialContent` 截断上限 8000 字符为本设计直接裁定，可随实现微调）。
