# Design: unify-turn-event-display

## Context

三案独立设计（最小演进 TurnEventStream / 同步事件总线 TurnEventBus / Turn 自发射）经评委打分（26/18/22）与三镜头对抗校验（并发顺序 EDT / 双重显示契约回归 / 测试面衔接），**胜案 = TurnEventStream（设计 A）+ 6 项嫁接（G1-G7）+ 校验修复 18 条**。本设计即最终合成果，所有校验修复已并入对应决策。

现状事实（路径相对 `src/main/java/org/gitee/jmeter/ai/`）：

- 显示三通道：本地回合 `AgentSwingWorker`（publish/process → `AiChatPanel.handleProgress/handleAgentResponse`）；IPC 回合 `TurnPresenter` 6 方法（`AgentLoop.notify*` :144-192，**生命周期通知由 IpcServer 观察 future 驱动**，`IpcServer.java:299-345`）；孤儿回合 `republishListener`（`AgentLoop.java:591-601` + `AiChatPanel:627-665`）。
- 8 类补丁：`fromIpc` 门控+面板自渲染（`AgentLoop.java:349-353` + `AiChatPanel.java:1106-1109`）；`ipcTurnGeneration` 呈现窗口+本地回合硬关窗（`AiChatPanel.java:96-111/672-680/694/801/1001-1004`）；`"Message injected"` string-sniff（:1106）；四份"You+loading+Stop"武装（:1006-1042/:696-707/:640-646/:803-812）；Stop 双来源双行（:1469-1476 + :730-732）；SwingWorker 取消静默三联（`AgentSwingWorker.java:124/140-143` + `AiChatPanel.java:652-657`）；`injectMessage` check-then-act 竞态分支（:1089-1142）；模型切换重注册义务（:894-903，漏注册 = 孤儿回复静默丢失）。
- `AgentLoop.startTurn`（:382-530）是全部回合来源的唯一 choke point；`doProcessMessage` 三相路由（:296-361）；`signalCancel` 四步取消（:749-795）；`resetConversation` 在 `resetFenceLock` 内调 `signalCancel`（:978-981）；`republishLeftovers` 在回合 lambda 的 **finally** 内（:494→:549-615），先于 `future.complete`（:497-501）。
- 12 项 IPC 回合显示决策已由 `ipc-turn-gui-display` spec + 测试锁定（见项目记忆 ipc-agent-turn-gui-display-decisions）。

## Goals / Non-Goals

**Goals:** 单一渲染路径（三通道换单入口 switch）；可插拔（第三订阅者 = 1 方法 + 工厂 1 行）；turnId/CancelCause 升格为类型；净代码量下降 150~250 行；`ipc-turn-gui-display` 7 条 Requirements 全保持；每阶段独立 `mvn clean test` 全绿。

**Non-Goals:** 不动 future 通道语义（`processMessage` 返回值、IPC 阻塞 get、wire 信封、DelegationGuard ack 窗口、InjectionManager 时序）；不动 `sessionEpochs`/`resetFenceLock`/子代理取消第 0 步；不合批冲刷（G6 显式延后，先对齐 IPC 路径既有的逐事件 invokeLater）；不统一双代数体系（`conversationGeneration` + `sessionEpochs` 并存，刻意的范围裁剪）；不改 CLI 终端双输出面（拍板决策保留）。

## Decisions

### D1: 事件模型（5 个新类型，包 `agent.presenter`，TurnPresenter 同包终删）

```java
enum TurnOrigin { LOCAL_PANEL, IPC_CLI, IPC_DELEGATED, REPUBLISH }  // REPUBLISH 的 echoText=null（You 回显已由 INJECTED 给过）
enum CancelCause { USER_STOP, TIMEOUT, RESET, SILENT }
// wire 映射收口一处：USER_STOP→IpcResponse.CANCEL_REASON_USER_STOP、TIMEOUT→CANCEL_REASON_TIMEOUT（单一事实源不弱化）

final class TurnHandle {
    static final AtomicLong SEQ;              // 进程级唯一，跨 switchAiService 重建不回绕 → 陈旧 loop 迟到事件不可能撞新回合 id
    final long id; final String sessionKey; final TurnOrigin origin;
    final String echoText;                    // 含来源前缀的消息文本（REPUBLISH 为 null）
    final boolean commandTurn;                // isPriority(raw)||isDispatchable(raw)（IpcServer.java:379-382 同判据内建于 loop）
    final AtomicBoolean terminalEmitted;      // 双发射点去重
    // 取消原因不经句柄转存：TurnEvent.cancelled 直接携带 cause（事件载荷即单一事实源）
    boolean visibleToPanel() { return !commandTurn || origin == LOCAL_PANEL; }
    boolean tryClaimTerminal();
}

final class TurnEvent {  // 不可变值对象；Kind = TURN_STARTED/PROGRESS/TURN_COMPLETED/TURN_CANCELLED/INJECTED/REJECTED_BUSY/COMMAND_RESULT
    // turn：INJECTED/REJECTED_BUSY/COMMAND_RESULT 均为 null（后两者是会话级事实，无回合身份）
    // origin：独立字段仅 INJECTED（注入方）/COMMAND_RESULT（命令方）非 null（REJECTED_BUSY 恒 null）——
    //   "发起方界面"显示域规则依赖它；回合系事件字段为 null，origin() 访问器统一解析自 turn.origin()
    // progress 仅 PROGRESS；response 仅 TURN_COMPLETED/COMMAND_RESULT；message 仅 INJECTED/COMMAND_RESULT；cause 仅 TURN_CANCELLED
}

interface TurnSubscriber {
    /** 回调线程不保证（EDT/ipc-worker/agent-loop/池线程）；订阅者自行编组（Swing 实现恰一次 invokeLater）+ 会话代数过滤。
     * 不得阻塞、O(微秒)返回：部分发射点在 AgentLoop 内部锁（resetFenceLock）持有期内执行——不得获取该锁。
     * 抛异常被 AgentLoop 吞掉记日志，不影响回合与其他订阅者。
     * 允许实现"已在目标线程则直接投递"的零跳分支（G1）——此时投递路径必须可重入安全。 */
    void onTurnEvent(TurnEvent event);
}
```

**为什么现有 6 方法不够**：缺回合身份（F3/F4 只能靠 `ipcTurnGeneration` 关窗补丁）、缺 origin（无法区分孤儿武装与命令结果归属）、缺取消原因全集（RESET/SILENT 无法表达）、缺 Phase1/2 同步结果通道（COMMAND_RESULT 无处安放，今天靠 string-sniff）、单槽注册不可插拔、6 入口各自 invokeLater 无单一顺序点。

### D2: 发射点与顺序论证（全在稳定边界，同步内联无队列）

| # | Kind | 位置 | 线程 | 条件 |
|---|---|---|---|---|
| 1 | TURN_STARTED | startTurn 内、槽注册（:388-393）后、`executorService.execute`（:401）前 | 调用方（本地=EDT、IPC=ipc-worker） | `visibleToPanel()`（IPC 命令回合不发；Phase1/2 同步路径不进 startTurn 天然无 STARTED） |
| 2 | PROGRESS | startTurn 内 per-turn callback 包装点（:444 附近）：先递发起方回调再 dispatch | hook 载体 | `visibleToPanel()` |
| 3 | TURN_COMPLETED | 回合 lambda **try 尾**（含 Phase3 命令回合 cmdResult :429-430）与 `catch(Throwable)`（error 语义，等价 IpcServer.java:317-324）；REE catch（:503-515）发 error("shutting down") | agent-loop | 各先 `tryClaimTerminal()`，且在 `future.complete` **之前** |
| 4 | TURN_CANCELLED | `signalCancel` 第 3 步 `future.cancel(true)` 返回 true 时（:777-783），回填 cause、tryClaim、摘槽（:792）后发 | 取消发起者 | cancel 返回 false 不发（终态已由 #3 发出）；回合体 catch-CE 兜底也 tryClaim——原子位保证恰好一次 |
| 5 | INJECTED | Phase2 offer 成功（:347），**无条件**（fromIpc 门控删） | 调用方 | — |
| 6 | REJECTED_BUSY | Phase2 delegated 分支（:330） | 调用方 | 不变 |
| 7 | COMMAND_RESULT | Phase1 priority 命中（:306-312）与 Phase2 dispatchable 命中（:337-343），completedFuture 返回前 | 调用方（本地=EDT） | — |

**顺序保证**：(a) 回合内 STARTED 先于 execute（happens-before）⇒ 严格先于一切 PROGRESS；(b) 跨回合：**自然完成路径**终态发于 try/catch 内、先于 `future.complete` 与 whenComplete 摘槽（:519-527），下一回合 startTurn 只会在槽空后到达 ⇒ 终态(N) HB STARTED(N+1)。**取消路径例外（契约修订）**：`signalCancel` 的取消终态在 `future.cancel(true)` 触发摘槽**之后**才发射（发射须以 cancel 真实成功 + tryClaimTerminal 认领为前提，不能提前），槽已空窗口内新回合即可开跑 ⇒ CANCELLED(N) 可能晚于 STARTED(N+1) 到达订阅者。订阅者不得以「先见终态后见开始」做状态机假设，须按回合身份（turnId）过滤——面板的活回合集合天然满足；(c) **孤儿交错（关键重排）**：`republishLeftovers` 在 finally（:494）内调 startTurn——终态发射必须留在 try/catch（finally 之前）而非 lambda 尾，否则孤儿 STARTED 会插到本回合 COMPLETED 之前。这是唯一需要动 lambda 结构的点，由 `AgentLoopTurnEventTest` 显式用例钉住（G2）。(d) EDT 编组：订阅侧单入口恰一次 invokeLater + EDT FIFO 单写者 ⇒ 渲染序 = 发射序。已知并保留的无序对（今日同样无序、UI 互不相干行）：会话级 INJECTED/REJECTED_BUSY/COMMAND_RESULT 与在跑回合 PROGRESS 的交错——turnId 过滤保证不破坏武装状态；取消路径的 CANCELLED(N)/STARTED(N+1) 倒序（上述例外）。

**锁上下文（校验修正）**：两处发射点在 `resetFenceLock` 持有期内执行——`resetConversation`（:978-981）内 `signalCancel` 的 TURN_CANCELLED(RESET)（busy 期 /new 在 EDT 内联走 Phase2 时即 EDT 上持锁发射）；`republishLeftovers`（finally 内仍持锁段）→ startTurn 的孤儿 TURN_STARTED。`TurnSubscriber` javadoc 与 spec 已写明"回调可能持 AgentLoop 内部锁执行、不得获取该锁、O(微秒)返回"。零跳分支（G1）叠加时该约束同样生效。

**EDT 编组位置**：编组放订阅侧单入口（① loop 保持零 javax.swing；② 发射线程四种不可枚举封闭；③ 保序靠 EDT FIFO；④ 面板门字段 EDT-confined 只能订阅者比对；⑤ 现 TurnPresenter 契约已要求实现方自行编组——零迁移成本）。收敛双跳：现 `handleProgress` 内层 invokeLater（:1285）删。

### D3: 面板单入口与活回合集合（校验修复 #1 的落点）

```java
@Override public void onTurnEvent(TurnEvent e) {           // 任意线程
    final int gen = conversationGeneration;                // 通知线程快照（F4 语义保留）
    if (SwingUtilities.isEventDispatchThread()) dispatch(e, gen);   // G1 零跳（本地提交路径 = EDT 同步武装，无滞后拍）
    else SwingUtilities.invokeLater(() -> dispatch(e, gen));
}
private void dispatch(TurnEvent e, int gen) {              // EDT；dispatch 可重入安全（G1 要求）
    if (gen != conversationGeneration) return;             // /new 后迟到（递增点 :944/:1159/:1220 语义不变）
    switch (e.kind()) {
        case TURN_STARTED -> { liveTurnIds.add(e.turn().id());
            if (e.turn().origin() != REPUBLISH) appendMessage("\nYou: " + e.turn().echoText());
            armActiveTurn(); }                             // 四份武装的独份（loading+Stop）
        case PROGRESS -> { if (liveTurnIds.contains(e.turn().id())) handleProgressNow(e.progress()); }
        case TURN_COMPLETED -> { if (!liveTurnIds.remove(e.turn().id())) return;
            handleAgentResponse(e.response(), gen); }      // 统一 sink 原样（hasActiveRun 复位判据 :1271-1273 保留）
        case TURN_CANCELLED -> { if (!liveTurnIds.remove(e.turn().id())) return;
            if (e.cause() == RESET || e.cause() == SILENT) return;
            removeLoadingIndicator(); appendCancelLine(e.cause(), e.turn().origin());
            if (loop == null || !loop.hasActiveRun(currentSessionKey())) setButtonToSendMode(); }
        case INJECTED -> { if (/* P1a 过渡 */ !localPathStillWorker || e.origin() != LOCAL_PANEL)
                               appendStyled("[Injected] You: " + e.message(), GREEN, ITALIC); }
        case REJECTED_BUSY -> appendGreyLine("Delegation rejected: …");
        case COMMAND_RESULT -> { if (e.origin() != LOCAL_PANEL) return;   // CLI/委派命令结果走其对端界面（HTTP 信封）——值基显示域规则，非 fromIpc 补丁转世（javadoc 写明区别）
            appendMessage("\nYou: " + e.raw()); handleAgentResponse(e.response(), gen); }
    }
}
```

**活回合集合而非单槽（校验攻击 1/2 的修复）**：垂死回合终态与新回合 STARTED 交叠时两者都渲染——今日基线（`AgentSwingWorker.java:122-132`、`AiChatPanel.java:648-663`）两处都渲染，单槽过滤是行为回归，不可声明为"行为变更"。集合同时消化 G1 零跳反序与模型切换旧 loop 迟到终态。字段注释写明不变式：`liveTurnIds` 仅由 TURN_STARTED 分支与 adopt 写入。

**领养（校验修复）**：`adoptRunningIpcTurnIfNeeded` 改 `loop.activeTurn(sessionKey)` 查询 → **`liveTurnIds.add(handle.id())`** + armActiveTurn + 提示行（Q12 不补放保持）。漏写 id 登记 = 被领养回合全部 PROGRESS/终态被弃（`AiChatPanelIpcTurnPresenterTest.java:315-322` 钉死的领养武装语义），重写用例须断言"领养后进度/终态照常渲染"。

**Stop 无可取消对象（校验修复）**：`stopActiveTask` 保留**无条件** `removeLoadingIndicator` + `setButtonToSendMode`（取消文案行可事件化）——回合已终、终态事件在 EDT 队列未出队的毫秒窗口内点击 Stop 不死寂。

**SILENT 显示域（校验修复）**：SILENT 抑制本地侧源（LOCAL_PANEL 与 REPUBLISH 孤儿——后者无对端调用方，回执文案无的放矢，取消由 Stop 路径的 "Stopped." 行交代）的取消渲染；IPC 源回合照旧渲染 USER_STOP 回执行（今日行为，`AiChatPanel.java:730-732`，spec"手动终止回执提示"保持）。关闭整合取消 IPC 回合时目标面板的终止反馈行不消失。上方伪代码 `cause == SILENT return` 系初稿笔误，显示域以此句为准。

**4 处刻意 UX 差异（终版）**：① 竞态注入成回合时补画 You 行（今日该消息从转录消失——改善）；② busy 期本地命令补画 You 行（今日只渲染结果行）；③ 空闲 `/new` 经 Phase3 完整回合短暂武装 loading+Stop 后自复位（今日直接渲染不武装；与 /help 今日武装行为一致化——拍板：武装，不做 commandTurn 特判，代码最简）；④ /new 回执渲染时机从面板同步改为事件驱动（busy 期 COMMAND_RESULT / 空闲期终态）。G1 零跳已消灭原第 5 处（本地武装滞后一拍）。全部记入 CLAUDE.md 与本节，防未来"对齐旧行为"误修。

### D4: 订阅挂接（G3 + 校验修复）

- `AgentLoopFactory` 持 `static final CopyOnWriteArrayList<TurnSubscriber> GLOBAL_SUBSCRIBERS`；`addTurnSubscriber(s)` = 记入全局表 **并挂接当前存活单例**（若已创建）；`getAgentLoop`/`createAgentLoop` 创建实例时把全局表逐个挂到新实例。**不在 AgentLoop 构造器末尾挂**（`AgentLoopPresenterTest.java:55`、`AgentLoopRepublishTest.java:93` 直接 `new AgentLoop(...)`，构造器挂工厂级订阅会造成测试跨用例污染）。
- 面板构造器调一次 `AgentLoopFactory.addTurnSubscriber(this)`（早于首个 `getAgentLoop`——`initializeAgentLoop` 在构造器 :128 附近很早）；`initializeAgentLoop`/`switchAiService` 对绑定实例的挂接保留为兜底（实例级、与创建先后无关）。
- `AgentLoopFactory.reset()` **不清空** GLOBAL_SUBSCRIBERS（生产 `switchAiService:894` reset 后重建依赖订阅继承，清空 = 显示静默死亡）；另提供 `clearTurnSubscribersForTest()` 供测试 tearDown。
- "先有 loop 后有面板"链路：面板注册时存活单例已存在 → addTurnSubscriber 挂接单例分支生效；`AgentLoopTurnEventTest` 增该顺序用例（adopt→PROGRESS→终态全达面板）。

### D5: 外围改动

- **AgentLoop**（净约 +70）：`turnSubscribers`（CopyOnWriteArrayList）+ `activeTurnHandles`（CHM，whenComplete 按值条件删除，同 activeTasks 款）；`dispatchTurnEvent` 保留三守卫（空订阅 no-op / sessionKey≠`InstanceContext.currentSessionKey()` 不派发 :185 / 逐订阅者 try-catch 吞异常）；`notify*` 六方法删；republishListener 字段与 setter 删（无监听器 WARN 分支删——无订阅者 = no-op 派发，回合照跑落盘，nullListener 韧性语义等价）；`doProcessMessage(message, sessionKey, callback, TurnOrigin)`；对外重载 **兼容 shim 全保留**：`processMessage(msg,key)` / `(msg,key,callback)`（默认 LOCAL_PANEL）/ `(msg,key,boolean)` / `(msg,key,callback,boolean)`（delegated=true→IPC_DELEGATED）/ `(msg,key,callback,origin)`；`signalCancel(String)`（默认 USER_STOP）/ `signalCancel(key,cause)`；`cancelActiveTask(String)` / `(String,CancelCause)`。新 API：`activeTurn(sessionKey)`、`addTurnSubscriber/removeTurnSubscriber`。
- **AiChatPanel**（净约 -230，1697→约 1470）：删 `registerRepublishListener` 整方法 + 两处调用（:606/:902）、`runInIpcTurn`+`ipcTurnGeneration` 全部读写、`startNormalSend` 武装段与 worker 构造（→`submitToLoop`）、`injectMessage` 同步回执+嗅探+竞态分支（→`submitToLoop`）、`handleNewCommand` 手绘 You 行与 future.handle（→gen++/清屏/`submitToLoop("/new")`）、六个 TurnPresenter 实现、`activeWorker` 字段与 AgentSwingWorker 引用；`sendMessage` 收缩为空串守卫→/new 拦截→`submitToLoop`（hasActiveRun 预路由删——loop 槽路由是唯一仲裁者）；`handleProgressNow` = 现 `handleProgress`（:1284-1312）去外层 invokeLater 与 gen 参数（门已上移）。
- **IpcServer**（净约 -30）：`handleAgent` 删 turnPresented 判据（:297-302）、6 处 notifyTurn*（:301/:311/:321/:329/:338/:344）与 notifyProgress 转发腿（:290-293 回调瘦身为只喂 accumulator）；`processMessageFromIpc` → `processMessage(message, session, u->accumulator.onProgress(u), req.isDelegated()?IPC_DELEGATED:IPC_CLI)`；超时分支 `cancelActiveTask(session, TIMEOUT)`；ExecutionException 分支不再通知（loop 已发 TURN_COMPLETED(error)，wire 500 照旧）；CE 分支不再通知（signalCancel 已发 USER_STOP，wire 409 照旧）；**InterruptedException 分支不再通知假终态**（回合本体未死，真终态稍后自然到达——比今日更准确）；`isCommandMessage`/`applyCliProvenance`/`cancelledResponse` 不动。
- **CloseConsolidationDialog**（:110，1 行）：`cancelActiveTask(session)` → `(session, SILENT)`。
- **删除文件**：`TurnPresenter.java`、`AgentSwingWorker.java`（P1b）。CLI/SubagentManager/CloseConsolidationCoordinator 零改动。

### D6: 迁移分段（G4 拆分 + 影子等价 G5）

**P0（加法，约半天）**：5 新类型 + AgentLoop 订阅表/dispatchTurnEvent/activeTurnHandles/新 API + 工厂注册表（含挂接语义）+ startTurn 建 handle + 全部 7 发射点 + `CancelCause` 与 `signalCancel(key,cause)` 重载（单参保留 shim，**P0 即引入 cause**——消解"P0 全发射点 vs cause 缺位"矛盾）。旧 notify* 原样并存，无 GUI 订阅者 → 零行为变化。新增 `AgentLoopTurnEventTest`（回合内严格序 / 跨回合终态先于下回合 STARTED / 孤儿 STARTED 晚于垂死终态（G2，含 reset 路径用例）/ tryClaimTerminal 双发射点去重 / 三守卫 / commandTurn+IPC 不可见且终态可无起点 / COMMAND_RESULT 两发射点 / 先有 loop 后有面板的挂接）+ **`EventParityTest`**（双通道影子等价：旧 notify* 与事件流并行期双订阅断言等价）。**等价关系定义（校验修复）**：Kind 序列等价 + 载荷映射表（含 `"agent failed: "` 前缀归一）+ 显式排除清单（InterruptedException 分支旧通知 / INJECTED 的 fromIpc 门控差异新通道无条件）；超时路径按事件序列断言而非墙钟（今日 notifyTurnCancelled 在 cancelActiveTask ≤5s 等待后、事件在 signal 瞬间，双通道墙钟必然错位）。

**P1a（换轨一，IPC/孤儿/命令事件源）**：面板 `implements TurnSubscriber` + 工厂注册；IPC/孤儿/COMMAND_RESULT/INJECTED(IPC 源)/REJECTED_BUSY 走事件渲染；**LOCAL 源回合事件被面板丢弃**（本地路径暂留 worker 与自渲染，INJECTED 的 LOCAL 源仍走面板自渲染防双显）；IpcServer 瘦身 + republishListener 通道删除。测试：`AgentLoopPresenterTest` 接口形态迁移（除 localInject 断言）、`AgentLoopRepublishTest` 15/15 用例订阅化（`SESSION_KEY` 改 `InstanceContext.currentSessionKey()`——守卫 :185 会滤掉固定键，机械前置变更）、`AgentLoopAdversarialTest` 孤儿系与 nullListener/queueFull 用例、`AiChatPanelIpcTurnPresenterTest` setUp 换 `loop.addTurnSubscriber(panel)`（反射 invoke 行删；:81-83 竞态等待保留）、**`AiChatPanelNewConversationTest.panel()` 反射 invoke(p,"registerRepublishListener")（:532）改订阅接线**（测试直建 loop 须显式 addTurnSubscriber——工厂挂接对直建不生效）。

**P1b（换轨二，本地路径）**：`submitToLoop` 替换 startNormalSend/injectMessage/handleNewCommand 的提交段；fromIpc 门控删 + INJECTED 无条件 + 面板自渲染删；`stopActiveTask` 收缩（保留无条件复位）；`AgentSwingWorker.java` 删除；`localInjectWhileBusyDoesNotNotifyPresenter` 断言显式反转为"本地注入必通知恰一次"；F3 用例（`lateIpcTerminalAfterLocalTurnStartIsDropped`）改 turnId 驱动语义；活回合集合过滤落地。

**P2（清扫，约半天）**：删旧 `notify*`/`setTurnPresenter`/`setRepublishListener`/`processMessageFromIpc` 与冗余重载（**保留 D5 列出的兼容 shim**）、`TurnPresenter.java`、面板残留字段（ipcTurnGeneration/activeWorker/toolCallsDisplayedProgressively 保留）；CLAUDE.md 架构段更新（含 4 处 UX 差异与 SwingWorker 删除决策）；`MessageProcessor` 入口加 `EventQueue.isDispatchThread()` 断言作迁移期护栏；**测试卫生**：所有构造过 AiChatPanel 的测试 tearDown 调 `AgentLoopFactory.removeTurnSubscriber(panel)`（静态表跨 reset 存活是 D4 设计前提，不能寄望 reset 清理）；新增"模型切换后面板订阅仍存活"用例（对齐 `switchAiService:894` 路径）。

每段独立 `mvn clean test` 全绿（clean 防 stale class；`THINKING_STYLE_MAP` 反射类测试用 clean 兜底；AiChatPanel 测试注入 loop 前仍须等 `loadModelsInBackground.done()` 落地）。

### D7: 与 refactor-agent-loop-turn-centric 的顺序契约

本变更先行落地，登记为 turn-centric 的 **Phase 0 前置**（修订其 proposal/tasks 门禁基线为 P2 后测试形态——两项文档修订列为本变更 P2 交付物）。向 turn-centric 声明的稳定 API：`TurnEvent`/`TurnHandle`/`TurnSubscriber` 与发射周界；Turn 聚合 MUST NOT 绕过 `dispatchTurnEvent` 私发事件、MUST NOT 移除 `tryClaimTerminal` 去重与"终态先于 complete/摘槽、在 finally-republish 之前"序约束；`TurnHandle` 是未来 Turn 聚合的身份字段子集（turn-centric 落地时 handle 升格为 Turn 或 Turn 携带 handle，订阅者零感知）；其 design D1 字段表须补 `TurnHandle`/`activeTurnHandles`/`terminalEmitted`；AgentRunner 同步化不改变"发射线程多样"结论（本设计不假设发射线程封闭）。反向论点（先 turn-centric 再事件化省一遍测试重写）不成立：RepublishTest 监听器断言与 PresenterTest 接口形态两序都要重写，而先事件化立刻消灭 8 类补丁与活跃 bug 面（F3 关窗、重注册孤儿化），收益即时；A 与 C（Turn 自发射）实为同一路线两站，turn-centric 落地后可把发射方法内聚进 Turn 而订阅者零感知。

## Risks / Trade-offs

- [P1 换轨原子性] → G4 拆 P1a/P1b 各独立全绿；P0 影子等价测试让换轨期任何顺序/载荷偏差当场暴露。
- [发射点锁上下文被忽视] → spec + javadoc + TurnEventTest reset 路径用例三重钉住"回调可能持 resetFenceLock、O(微秒)返回"；第三订阅者（审计/指标类）在此路径做 IO 即拖累 EDT 上的 /new——契约必须先于生态写死。
- [单槽过滤回归] → 活回合集合 + EventParityTest 两条竞态钉子（Enter 在途横跨自然完成 / cleanup→孤儿 register 间隙——两场景断言"都渲染"= 今日基线）。
- [静态订阅表测试污染] → reset 不清空（生产依赖）+ clearTurnSubscribersForTest + tearDown 义务写入任务清单；IpcTurnPresenterTest 每方法一个 panel 的用例最易踩。
- [EDT 洪泛] → G6 合批冲刷显式延后：先对齐 IPC 路径既有的逐事件 invokeLater 行为，慢机工具密集回合实测出问题再引入（约 85 行独立优化）。
- [兼容 shim 漂移] → D5 列出全部保留重载与调用点清单（DelegationGuardTest 4 参 :112/:130/:134/:143、PerTurnCallbackTest 3 参 :118、SignalCancelScopingTest/RepublishTest/AdversarialTest/NewConversationTest 的 signalCancel(String) ≈15 处、cancelActiveTask(String) 3 处）——shim 是委托不是 fork。
- [对齐旧行为的误修] → 4 处 UX 差异 + SILENT 显示域 + InterruptedException 不再通知假终态（比今日更准确）逐条记录于 D3/D5 与 CLAUDE.md，防止未来"修 bug"改回。

## Open Questions

- `TurnHandle.commandTurn` 的判据内建于 loop 后，IpcServer 的 `isCommandMessage` 是否随之退役为 handle 查询——P1a 实施时看是否只剩一处消费。
- 合批冲刷（G6）触发阈值——延后到实测 EDT 拥堵证据出现再定，本变更不预留钩子。
