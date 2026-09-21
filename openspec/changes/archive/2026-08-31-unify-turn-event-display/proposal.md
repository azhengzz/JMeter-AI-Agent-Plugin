# Proposal: unify-turn-event-display

## Why

面板显示今天是**三套并行通道 + 两处 future 消费分支**：本地回合走 `AgentSwingWorker` 回调链（publish/process → handleProgress/handleAgentResponse），IPC 委派/CLI 回合走 `TurnPresenter` 6 方法接口（IpcServer 观察 future 驱动通知），孤儿 re-publish 回合走独立的 `republishListener` 通道。三条通道最终收敛于同一渲染链，但入口分裂迫使系统长出 **8 类防双重显示/防渗入补丁**：`fromIpc` 门控+面板自渲染对（同一注入反馈双路径）、`ipcTurnGeneration` 呈现窗口+本地回合一开即硬关窗（F3）、`"Message injected"` 前缀 string-sniff、四份手写"You+loading+Stop"武装、Stop 取消双来源双行、SwingWorker 取消静默三联守卫、`injectMessage` check-then-act 竞态分支、模型切换后手工重注册义务（漏注册 = 孤儿最终回复静默丢失）。每次新增回合来源（IPC、子代理、CLI）都要再造一套通知路径并重新推理一遍防双显——这正是用户要求的"统一通过 AgentLoop 的事件更新面板"要消灭的结构性成本。

## What Changes

- **TurnPresenter 重塑为多订阅者回合事件流**：单槽 6 方法接口 → `TurnSubscriber.onTurnEvent(TurnEvent)` 单方法多订阅者（`CopyOnWriteArrayList`）。新增 5 个类型（`TurnOrigin`：LOCAL_PANEL/IPC_CLI/IPC_DELEGATED/REPUBLISH；`CancelCause`：USER_STOP/TIMEOUT/RESET/SILENT；`TurnHandle`：进程唯一 turnId+origin+commandTurn+终态原子去重位；`TurnEvent`：Kind 值对象，7 种 Kind 含旧模型缺失的 `COMMAND_RESULT` 通道；`TurnSubscriber`）。**BREAKING**（内部 API）：`TurnPresenter`/`setTurnPresenter`/`AgentSwingWorker`/`setRepublishListener`/`processMessageFromIpc` 删除；公共 `processMessage`/`signalCancel`/`cancelActiveTask` 兼容重载保留为委托 shim。
- **事件发射权收回 AgentLoop**：7 个发射点全在稳定边界（`doProcessMessage` 三相、`startTurn` 入口/出口、`signalCancel`、Phase1/2 内联点），同步内联派发无中间队列；IpcServer 只留 wire 职责（accumulator/超时/HTTP 信封）。`startTurn` 本就是全部回合来源的唯一 choke point，发射点内建于它即天然覆盖本地/IPC/CLI/孤儿四类来源。
- **面板成为唯一 GUI 订阅者**：删 `AgentSwingWorker`（渲染事件化后 future 消费是死代码）、`republishListener` 通道、`ipcTurnGeneration` 窗口、`fromIpc` 门控、string-sniff、四份手写武装收敛为 `TURN_STARTED` 分支一份；过滤改为**活回合集合**（STARTED/领养加入、任一终态按 id 移除、终态对集合内任意 id 渲染——垂死回合迟到终态照常渲染，对齐今日双渲染基线）。
- **订阅挂工厂级锚**：`AgentLoopFactory` 静态订阅表 + 创建时挂接 + 注册时挂接当前存活单例；跨 `switchAiService` 重建自动继承，根治"漏注册孤儿化"bug 类。
- **future 通道语义零变化**：`processMessage` 返回值、IPC 阻塞 `get`、wire 格式（200/409/504+cancelReason）、DelegationGuard ack 窗口、InjectionManager 时序、`sessionEpochs`+`resetFenceLock` 全部不动——事件流严格限于显示/观察通道。
- **4 处刻意 UX 差异**（均本地回合域、毫秒级、逐条记录于 design）：竞态注入成回合时补画 You 行（今日该消息从转录消失——改善）；busy 期本地命令补画 You 行（今日只渲染结果行）；空闲 `/new` 经完整回合短暂武装 loading+Stop 后自复位（今日直接渲染不武装）；`/new` 回执渲染时机从面板同步改为事件驱动。G1 零跳分支（已在 EDT 则直接投递）使本地武装保持同步节奏，无感知差异。
- **登记为 `refactor-agent-loop-turn-centric` 的 Phase 0 前置**：向其声明 `TurnEvent`/`TurnHandle`/`TurnSubscriber` 与发射周界为稳定 API；其 design D1 字段表须补 `TurnHandle`/`activeTurnHandles`/`terminalEmitted` 与"终态先于 finally-republish"序约束；其契约测试门禁基线改写为本变更落地后的测试形态。

## Capabilities

### New Capabilities

- `agent-turn-events`: AgentLoop 回合事件流契约——全部回合来源（本地面板/IPC 委派/CLI 直连/孤儿 re-publish/运行中注入/忙拒绝/命令结果）的显示更新统一经单方法多订阅者事件流交付；事件种类与载荷、回合内与跨回合顺序保证、终态恰好一次、订阅者线程/异常/锁上下文契约、可插拔性（第三订阅者零改动接入）、headless 边界（无订阅者回合照常执行落盘）。

### Modified Capabilities

（无）——`ipc-turn-gui-display` 现有 7 条 Requirements 经三镜头对抗校验逐条对照全部保持（完整对等显示、按钮状态、注入共享回复、结构化终止反馈、生命周期提示、headless 边界）；4 处 UX 差异全部位于本地回合显示域，不在该 spec 范围内；关闭整合取消对 IPC 源回合仍渲染 USER_STOP 回执行（今日行为）。

## Impact

- **代码**：`AgentLoop`（净约 +70：订阅表/dispatchTurnEvent/activeTurnHandles/7 发射点/重载 origin 化）、`AiChatPanel`（净约 -230：1697→约 1470 行，删三通道换单入口 switch）、`IpcServer`（净约 -30：删 6 处 notify* 转发腿）、`AgentLoopFactory`（+15：静态订阅表与挂接）、`CloseConsolidationDialog`（1 行：cancelActiveTask 传 SILENT）；删除文件 `TurnPresenter.java`、`AgentSwingWorker.java`；净代码量下降约 150~250 行，删除的恰是历史上补丁最密的三段。
- **测试**：P0 新增 `AgentLoopTurnEventTest`（顺序/去重/守卫/不可见回合）+ `EventParityTest`（双通道影子等价，Kind 序等价+载荷映射+显式排除清单）；P1a/P1b 迁移 8 个测试类（PresenterTest 接口形态+1 断言反转、RepublishTest 15 用例订阅化+SESSION_KEY 改实例键、AdversarialTest 孤儿系用例、IpcTurnPresenterTest setUp 换订阅、NewConversationTest 反射 invoke 改接线、PerTurnCallbackTest/DelegationGuardTest/SignalCancelScopingTest 零改动——兼容 shim 保障）。
- **调用方**：CLI（`JmeterCli`）零改动（wire 不变）；`SubagentManager` 零改动（只经 signalCancel 消费，不订阅）。
- **顺序与风险**：作为 turn-centric 的 Phase 0 先行落地（发射周界在其重排的稳定边界上，TurnHandle 前向兼容 Turn 聚合）；最大风险 = P1 换轨原子性，已按 G4 拆 P1a（IPC/孤儿/命令换轨、本地暂留 worker）/P1b（本地换轨+fromIpc 删除+worker 删除+断言反转），各独立 `mvn clean test` 全绿。
