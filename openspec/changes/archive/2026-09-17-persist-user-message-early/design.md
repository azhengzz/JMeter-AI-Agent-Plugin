## Context

现状持久化链路（[AgentRunner.java:97-213](../../../src/main/java/org/gitee/jmeter/ai/agent/run/AgentRunner.java#L97-L213)）：`run()` 内 `maybeConsolidate`（前置整合）→ `buildMessages(session.getHistory(...), userMessage, ...)`（历史 + 触发消息含 Runtime Context 块）→ `runAgentLoop` → `spec.isPersistSession() && !isAborted(spec)` 守卫下 `saveMessagesToSession(skipCount = messages.size()-1)` 统一落盘。取消时整段跳过——今天被取消的回合在 jsonl 里**零痕迹**（`session.addMessage` 唯一调用点在跳过的块内）。

Nanobot 参照两项机制：
- `_persist_user_message_early`（loop.py:677-718，`_build_turn` 内回合执行前）+ `_save_skip_for_turn`（turn_continuation.py:184-197，skip 前移去重）；
- 中止落盘（session/recovery.py）：`restore_runtime_checkpoint` 保留 checkpoint 的 assistant/tool 行并为悬空 `pending_tool_calls` 合成 `"Error: Task interrupted before this tool finished."`（:325-334）；`restore_pending_interruption` 为 user 结尾的转录合成 `"Error: Task interrupted before a response was generated."`（:384-405）；合成行带 `_recovery_interrupted: True`；get_history 白名单剥离标记、内容原样进 LLM 上下文（manager.py:438-441）。

关键差异（我们更简单也更强）：Nanobot 是异步 runner，中断时无法接触 runner 转录，须靠逐迭代 checkpoint 边车（只存**当前迭代**——多迭代被停回合的更早迭代在 Nanobot 里也丢失）+ 懒墓碑（`pending_user_turn` 下回合补写空回合收尾）；我们的 runner 同步跑在 run 执行线程上，abort 时 `result.getCurrentMessages()` 手握**全量**回合消息——即时中止落盘全部已完成迭代，无需边车、无需懒补写。

取消因果现状：`signalCancel(sessionKey, cause)` 的 cause 只到达 `TurnEvent.cancelled`（且仅 `future.cancel` 成功时）；runner 只见裸 `AtomicBoolean` abort flag，对 USER_STOP/TIMEOUT/RESET/SILENT 完全盲（研究确认无任何既有机制把 cause 或 reset 事实送达 runner）。

abort 时 `currentMessages` 的可达尾部形状（全枚举，AgentRunner 行号）：

| 窗口 | 位置 | 尾部形状 | 悬空 |
|---|---|---|---|
| A/B 迭代顶/LLM 前检查 | :376/:389 | `USER(trigger 或 injected)` | 无 |
| C LLM 后检查 | :401 | 同上（响应被丢弃，未 add） | 无 |
| D LLM 内中断（OpenAI 路径 `"Interrupted"`） | :417 | 同上 | 无 |
| E 工具执行前检查 | :511 | `ASSISTANT(tool_calls, content 可为 null)` | **有** |
| F 工具执行抛异常 | :517 | 同 E，异常经 D15 异常安全结果携带 `currentMessages` | **有** |
| G 工具执行后检查 | :554 | 同 E（结果已算出但未 append，只存于 toolEvents） | **有** |
| H 终答分支（无 abort 检查） | :598-631 | `ASSISTANT(终答)`，回合"正常完成"但守卫拦截 | 无 |
| I abort 伪装 maxIterations | :447-463 | drain6 追加 `USER(injected)` | 无 |

其余关键事实：并行工具批的 `CompletableFuture.join()` 不可中断（JDK 17 实证）——中断必然收敛到 G 窗口干净 break；`MessageOptimizer.optimizeContent` 对 content==null 无条件返回 null（OpenAI 工具响应 content 恒 null）→ 悬空 assistant 会被持久化变换丢弃；`jsonToMessage` 的 metadata 读取是替换非合并；GUI 从不读 jsonl（中止落盘对 GUI 不可见）；`SessionManager.shutdown()` 用内存态整体重写 jsonl（中止落盘必须走内存 Session，不能直接 append 文件）。

**连续同角色消息 = Anthropic API 400**（当前 API 参考：`Consecutive same-role messages → 400, fix: alternate`）：本项目 `ClaudeService.addMessages` 逐条透传不合并（ClaudeService.java:328-331），代码库内 `appendInjectedMessages` 专为保序 role alternation 而存在即为佐证。后果链：悬空 user 尾（crash/异常路径早落盘了 user 却无收尾）→ 下回合历史 [.., user, user(新)] → Claude 400 → 错误回合本身不追加 assistant、终局又落盘新 user → 连续 user 自我加深、**会话对 Claude 砖化**直至 /new。OpenAI 系 provider 不受影响。该危害由 early-persist 引入（现状这些路径零落盘、无悬空尾），是 D16 的直接动因。

## Goals / Non-Goals

**Goals:**
- 触发 user 消息在首次 LLM 调用前落盘；取消/崩溃后幸存，后续回合 LLM 可见
- 非 RESET 取消时中止落盘部分上下文 + 合成中断标记（形状对齐 Nanobot），RESET 取消不做中止落盘
- 中止落盘结果 provider 合法：无悬空 tool_calls、无孤儿 tool 结果
- 终局保存与提前落盘不产生重复；持久化形状（Runtime Context 块 + 标记 + 截断/跳过规则）全路径一致
- 保持既有重置竞态纪律（abort 检查、原子写、双检收窄）

**Non-Goals:**
- 不做 GUI 重启后聊天历史回显（面板现状只显欢迎语）
- 不做命令消息持久化（命令在 Phase 1/2 拦截；Nanobot `_command=True` 路径不移植）
- 不移植 Nanobot runtime checkpoint 边车（`.checkpoint.json`）与 RecoveryCoordinator「Continue」——同步 runner 即时中止落盘覆盖其诉求且更完整；崩溃（kill -9）时已完成迭代仍丢（user 消息幸存，悬空尾由 D16 懒收尾闭合）
- **悬空 user 尾须闭合**（D16 纯尾检查启发式）：即时中止落盘覆盖不了崩溃与 `Error` 逃逸路径（run 未跑完；catch(Exception) 异常路径已由 D15 覆盖）；**不移植墓碑**（审查证实其「正常完成即清除」区分在本项目留洞）
- 不改 busy 注入队列残留的作废语义（`discardCancelledLeftovers` 不动）

## Decisions

### D1: 提前落盘放在 `AgentRunner.run` 内、`buildMessages` 之后、`runAgentLoop` 之前

持久化职责已在 AgentRunner（终局保存同处），AgentLoop 层不碰 Session 写路径。`buildMessages` 产出的末位消息（用户正文 + Runtime Context 块）就是要落盘的形状——一次构建、LLM 上下文与 jsonl 同源，不二次渲染 Runtime Context（避免时间戳/选区快照双读漂移）。备选「`spec.getUserMessage()` 裸文本先落盘、块后补」会造成早落盘版本与终局版本形状分叉，否。

### D2: 历史快照先于提前落盘读取

现调用 `contextBuilder.buildMessages(session.getHistory(...), ...)` 把 history 读取内联在参数里。重构为：先取 `List<Message> history = session.getHistory(...)` 局部快照 → `buildMessages(history, ...)` → 早落盘 → `runAgentLoop`。否则刚 `addMessage` 进会话的触发消息会经 `getHistory` 再入 history，LLM 上下文中出现两次。对齐 Nanobot：`ctx.history = session.get_history()`（loop.py:1887）先于 `_persist_user_message_early`（1960）。

### D3: 终局去重用 skipCount 前移，`inputPersistedEarly` 局部布尔串联

```java
int skipCount = Math.max(0, messages.size() - (inputPersistedEarly ? 0 : 1));
```

对齐 Nanobot `_save_skip_for_turn` 的 `input_persisted_early` 分支。备选「终局按消息 identity/内容匹配再过滤」：identity 在注入 merge 用 `set()` 替换 Message 对象后失效，内容匹配引入模糊性，否。`inputPersistedEarly` 仅 `run()` 局部变量，无需进 AgentRunSpec/Turn。**注入 merge 进触发消息的已知边界保留**（D7）。

### D4: 早落盘守卫——入口 abort 检查 + 提交前 epoch 复查（对抗测试轮修订）

`persistUserMessageEarly` 入口查一次 `abortSignal`（重置经 signalCancel 先置 flag 再 clear，先到则不写），**addMessage 后、saveSession 前复查重置代数**——与 `closeDanglingUserTail`/`materializeInterruptedTurn` 的提交前复查同纪律。原稿「单检即可、addMessage 与 saveSession 间无耗时窗口」的论证是错的：既有双检纪律防的是 [入口检查 → 写文件] 整个跨度（含消息变换与全文件写、载体线程被调度出去的任意停顿），不是 addMessage 循环的内部窗口——对抗测试以确定性 PoC 实证了该缺口（重置在跨度内完整落地 → 触发消息复活进刚清空的新会话文件，`AgentRunnerEarlyPersistResetRaceTest` 钉定）。复查失败时跳过文件写（内存残留随重置 invalidate 失效，不再被 flush）；入口 abort 检查保留（Stop 在 [pickup → 早落盘] 微秒窗口先到时不写，与「回合从未开跑」语义一致）。

### D5: 抽取共享的单消息持久化变换

从 `saveMessagesToSession` 循环体抽 `Message toPersistableMessage(Message msg)`（`shouldSkip` → null；`optimizeContent`；USER 消息挂 `runtimeContextMarker`；字段重建）。早落盘、终局循环、中止落盘三处共用，保证 jsonl 形状同源。

### D6: 取消守卫的结构性重排

```java
if (spec.isPersistSession()) {
    boolean turnFailed = !result.isSuccess();          // D15：runAgentLoop 异常安全结果
    if (!isAborted(spec) && !turnFailed) {
        saveMessagesToSession(...);                    // 既有终局路径不动
        memoryConsolidator.maybeConsolidate(...);      // 中止落盘路径不整合（取消/异常回合无整合，语义保持）
    } else if (inputPersistedEarly && resetEpochUnchanged(spec)) {   // epoch 守卫见 D10
        materializeInterruptedTurn(session, result.getCurrentMessages(), skipCount, abortSignal, spec);
    }
}
```

`saveMessagesToSession` 双检、`!isAborted` 语义、后置整合位置全部原样。中止落盘条件 = **取消或异常中止**（D15）且早落盘已发生——早落盘被跳过意味着回合在起点前就被取消（未跑），不留任何痕迹是正确语义。

### D7: 已知边界——注入合并进触发消息的内容不落盘（接受）

`appendInjectedMessages` 在末位是 user 消息时合并（`set()` 替换对象）。inj4/inj5 首迭代路径会把注入文本合并进**触发消息**；终局/中止落盘按索引 skip 后，这部分注入内容不进 jsonl。触发条件极窄（首迭代即错/空 且 恰有注入到达），注入内容仍送达当轮 LLM 且 GUI 已 ack。对比方案「identity 检测被替换则补写合并版」会让触发正文双写进 jsonl，污染下一轮 LLM 上下文，更差。接受并记录。

### D8: 顺序——前置整合 → 懒收尾（D16）→ 历史快照 → buildMessages → 早落盘 → 循环

`maybeConsolidate`（前置）保持在早落盘**之前**（对齐 Nanobot `prepare_session` 先于 early persist）：整合以未整合消息为输入，先落盘会让触发消息参与本轮整合边界计算，语义漂移。懒收尾在历史快照**之前**（收尾须纳入本回合 LLM 上下文）。后置 `maybeConsolidate` 位置不动（终局保存之后）。

### D9: 中止落盘时机 = run 收尾即时（eager），中止落盘范围 = 全量已完成迭代；不移植 checkpoint 边车与懒墓碑

Nanobot 因异步架构只能靠逐迭代 checkpoint 边车（且只保末迭代）+ 懒墓碑下回合补写；我们同步 runner 在 abort 后手握 `result.getCurrentMessages()` 全量——在 run() 的取消分支内**即时**中止落盘：真实保留窗口 A-I 的全部已完成消息（比 Nanobot 多保留更早迭代），合成消息即时落盘（无需 `pending_user_turn` 墓碑与下回合补写）。异常路径（窗口 F）例外见 D15。**中止落盘必经内存 Session（addMessage + saveSession）**，不可直接 append 文件——`SessionManager.shutdown()` 会以内存态整体重写 jsonl 冲掉旁路写入。

### D10: 重置代数守卫（epoch）——取代取消因果打通（对抗审查 P0 修订）

中止落盘/懒收尾的「RESET 不写」判别**不用 CancelCause**。原设计把 cause 打通到 runner，对抗审查确认 P0：cause 写入若锚在 `signalCancel` 的 `abortVisible` 守卫内（AgentLoop.java:845-850），Stop-后-//new 序列下先前的 USER_STOP 消费守卫（future.cancel → isDone + terminalEmitted），后续 RESET 永远写不进 cause——中止落盘把被取消回合写进刚清空的新会话（复活）。改用既有 session epoch 机制：`markConversationReset`（唯一 RESET 源）在 resetFenceLock 下先翻代数再清空——**epoch 翻转 ⟺ 会话被重置**，完整覆盖 cause 的判别面且不可被取消时序欺骗（USER_STOP/TIMEOUT/SILENT/异常均不翻代数）。

- `AgentRunSpec` 增 `Supplier<Long>` epoch 活引用（startTurn 以 `() -> currentEpoch(sessionKey)` 闭包接线，同 `injectionCallback` 活闭包模式；子代理不传，null 容忍）。
- `run()` 入口捕获 `long epochAtStart`；中止落盘入口检查与一次性提交前复查均为 `supplier.get() == epochAtStart`，不同则放弃（对齐 `republishLeftovers` 的 turnEpoch vs currentEpoch 纪律，AgentLoop.java:621-635）。
- 残余窗口（复查与写之间的指令级）与既有持久化双检同级别，接受；TOCTOU 全闭需 reset 链路重构（reset 的 clear+save 本就在栅栏锁外），不在本变更范围。
- Turn.cancelCause 字段、signalCancel 的 cause 写入、spec cause 供应商**全部取消**——机件更少，P0 从根上消失。SILENT/TIMEOUT/异常均不翻代数 ⇒ 中止落盘；关闭整合快照在取消等待（latch）之后才取，中止落盘在 latch 前完成，时序天然安全。
- **换血路由腿（实施期发现）**：epoch 闭包捕获<b>发起 loop</b> 的代数表——`resetConversationAny` 的 RESET 路由腿必须在取消之外对被触达的每个 loop 调 `markConversationReset`（新增 `signalCancelAndMarkResetRouted`），否则退役 loop 上垂死回合的中止落盘守卫恒判未重置、把旧会话内容写回 self 腿刚截断的 jsonl（既有不变式「退役回合靠 abort 复查放弃写盘」对中止落盘路径失效——中止落盘本就要在 abort 时写；`AgentLoopTurnEventTest` 的模型切换场景钉定此修复）。

### D11: 中止落盘算法（对齐 Nanobot recovery.py 的消息形状）

```
materializeInterruptedTurn(session, currentMessages, skipCount):
  入口检查: resetEpoch != epochAtStart → return（D10）
  局部构造完整中止落盘列表（compose-then-commit，D17——不边构造边 addMessage）:
    partial = currentMessages[skipCount..] 逐条过 toPersistableMessage（触发消息已被 skipCount 排除）
    对最后一条带 tool_calls 的 assistant 中每个无后续 tool 结果的 call_id：
        合成 Message.tool(callId, name, "Error: Task interrupted before this tool finished.")
    若本回合无任何 assistant 消息中止落盘、或中止落盘尾为 USER 角色：
        合成 Message.assistant("Error: Task interrupted before a response was generated.")
        （对齐 restore_pending_interruption 的 user 结尾收尾；消除下回合连续 user 的 provider 风险）
    合成消息 metadata 挂 RECOVERY_INTERRUPTED_META_KEY（"_recovery_interrupted"→true，经
        SessionManager 写为 jsonl 顶层布尔，round-trip 读回；真实消息不带）
  入口检查与提交前复查: resetEpoch != epochAtStart → 放弃（D10；此刻尚未 addMessage，无内存残留）
  session.addMessages(完整列表) + saveSession
```

中止落盘自身**异常隔离**（审查修订）：`materializeInterruptedTurn` 与懒收尾整体 catch Exception——中止落盘是尽力而为的持久化增强，自身的 IO/构造异常只 log，绝不影响回合结果与 GUI 终态。合成消息一律经 `Message.builder()` + **新建 LinkedHashMap** 构造 metadata（`Message` 的 metadata 不可变 Map 禁止原地 put；TOOL 行合并 `toolName` + `_recovery_interrupted` 两键，assistant 收尾仅 `_recovery_interrupted`）。

窗口映射：A/B/C/D → partial 空 → 仅合成 assistant 收尾（= Nanobot `1+1` 案例）；E/G → 真实 assistant(tool_calls) + 合成 tool 结果；G 的已算出未 append 真实结果不回用（`AgentRunResult` 无对应字段，合成更简单且 Nanobot 同形；真实结果已经 toolEvents 送达 GUI）；H → 全真实无合成；I → drain6 追加的注入 user 随 partial 保留 + USER 尾收尾。

### D12: 契约修订——已消费进上下文的注入消息随中止落盘保留

2026-08-23 契约「取消回合消费进上下文的消息随回合作废」（`discardCancelledLeftovers` 注释）针对的是**队列残留**；本变更新增：已消费进回合上下文的注入 user 消息在非 RESET 取消时**随中止落盘保留**。理由：它们是用户真实输入、LLM 真实看过的消息——本变更的核心诉求（「刚发的消息不丢」）对注入消息同样成立；队列残留的作废语义不变（点得快/点得慢的对称性论证对队列仍然有效，对已消费消息不再适用——它们已进入对话事实）。`republishLeftovers`/`discardCancelledLeftovers` 代码零改动。

### D13: 前置必修——`MessageOptimizer` 对 null-content assistant-with-tool_calls 的丢弃

`optimizeContent` 对 `content == null` 无条件返回 null（MessageOptimizer.java:23-25），而 OpenAI 路径工具调用响应 content 恒 null（OpenAiService.java:473）——恰是中止落盘必须保留的悬空 assistant。不修则合成 tool 结果成孤儿、`findLegalStart` 下次加载截肢整段回合尾（连带正常终局路径同款隐患）。修复：`optimizeContent` 对 `role==ASSISTANT && hasToolCalls && content==null` 返回 `""`（`shouldSkip` 本就不跳过这类消息，两函数现状不一致，修复即对齐）。

### D14: jsonl 标记 round-trip + `jsonToMessage` 替换→合并

- 常量 `RECOVERY_INTERRUPTED_META_KEY = "_recovery_interrupted"` 落 `ContextBuilder`（与 `RUNTIME_CONTEXT_META_KEY` 同址）。
- 写：`messageToJson` 增分支 `node.put(key, true)`；读：`jsonToMessage` 增 `isBoolean()` 守卫分支。
- **必须同时把读取侧改为合并语义**：现有 `_runtime_context` 分支 `builder.metadata(new LinkedHashMap<>(singletonMap(...)))` 是整替换（"与 toolName 互斥"只对现有两 key 成立）；第三 key 加入后 toolName（TOOL 角色）/`_runtime_context`（USER 角色）/`_recovery_interrupted`（任意角色）须合并进同一 LinkedHashMap，否则按键序静默互相覆盖。
- LLM 不可见性免费获得：`Session.getHistory` Step-5 本就只保留 toolName、丢弃其余 metadata——与 Nanobot get_history 白名单同构，标记只活在 jsonl/归档/审计层。

### D15: 异常路径中止落盘——runAgentLoop 异常安全结果

run() 的 catch 块构建的 `AgentRunResult` 不带 `currentMessages`（窗口 F 的部分列表是 `runAgentLoop` 局部量）。解法不把 LoopState 提升到 run()（会动 turn-centric 重构刚定型的分解面），而是在 `runAgentLoop` 体内包一层 catch：异常时构建同形结果但携带 `state.currentMessages`、`success=false`、`stopReason="exception"`。要点：

- **hook 零新增发射**：内层 catch 不调 `hook.onError` 等任何回调——外部行为（GUI 终态、事件流）与现状完全一致，仅持久化增强。
- **结果同形**（审查修订）：内层 catch 结果须带 `errorMessage = e.getMessage()` 与 usage metadata（`metadata.put("usage", context.getUsage())`）——与 run() 既有 catch 结果同形，`toAgentResponse` 的终态文本与现状一致；新增字段仅 `currentMessages` 与 `stopReason="exception"`。
- **run() 的 catch 保持现状不做中止落盘**：它覆盖的是 `runAgentLoop` 之外的异常（前置整合/buildMessages/早落盘、终局保存与后置整合链）——终局保存成功后 `maybeConsolidate` 抛异常若走中止落盘会二次落盘重复内容，保持不做中止落盘天然防重。
- run() 中止落盘条件扩展为 `(isAborted(spec) || !result.isSuccess()) && inputPersistedEarly && resetEpochUnchanged(spec)`（epoch 守卫见 D10——异常不翻代数，天然中止落盘）；合成消息复用 D11 同款文本与标记（"interrupted" 涵盖「未完成」，不为异常单列文案，从简）。
- `Error`（如工具 jar 错位 NoClassDefFoundError）仍逃逸 `catch (Exception)`，与 kill -9 同形 → D16 懒收尾兜底。

### D16: 悬空 user 尾的懒收尾——纯尾检查启发式（对抗审查修订，取代墓碑移植）

对齐 Nanobot `restore_pending_interruption` 的**效果**（USER 尾转录以合成 assistant 收尾闭合），但**不移植墓碑**：对抗审查证实「合法 USER 尾」在本项目不存在——LLM 错误回合（错误响应不产生 assistant 行，终局保存只落盘 trigger）与 maxIterations+drain6 完成回合都会留下 USER 尾，两者与崩溃尾同样触发 Anthropic 连续同角色 400 砖化（均为**既有**砖化源，本变更加以修复）；Nanobot 墓碑「正常完成即清除」的区分恰好把这些尾排除在收尾外、留洞。纯「尾为 USER」检查覆盖全部源，且免去 Session 字段、jsonl 元数据格式变更、三处置位/清除接线。

- **条件**：回合开始序列中（`maybeConsolidate` 后、历史快照前），会话末条消息为 USER 角色 ⇒ 追加合成 assistant 收尾（`"Error: Task interrupted before a response was generated."` + `_recovery_interrupted` 标记）+ save，收尾纳入本回合 history（LLM 本回合即见闭合转录）。
- **守卫**（审查修订：原稿无守卫，reset 竞态下会把合成行写进刚清空的会话）：追加前查 `abortSignal`（reset 的 signalCancel 先置 flag 再 clear）+ 提交前复查 epoch（D10 同款）；放弃则跳过——reset 清空后尾非 USER，天然无需收尾。
- **幂等**：收尾后尾为 assistant，重复触发不可能；连续崩溃产生 `[user, closer, user, closer…]` 交错序列，始终合法。
- kill -9/`Error` 逃逸遗留的悬空尾由此闭合（唯一中止落盘覆盖不了的路径）；LLM 错误回合与 drain6 的既有砖化源顺带修复。

### D17: 中止落盘 compose-then-commit（瞬时悬空态不可见）

中止落盘不得边构造边 `addMessage`：真实消息已入内存会话、合成收尾未入的**瞬时悬空 tool_calls 态**对并发 flush 可见——shutdown hook 的 `SessionManager.shutdown()` 若恰在此窗口 flush 且最后落盘，悬空 tool_calls 固化进文件，下次加载 `findLegalStart` 不裁尾、provider 400。中止落盘先在局部构造完整列表（真实 partial + 全部合成消息），再一次性 `addMessages` + 墓碑清除 + `saveSession`。任一并发 flush 看到的只会是中止落盘前或中止落盘后的完整状态。

## Risks / Trade-offs

- [saveSession 吞 IOException（既有）] → 早落盘/中止落盘返回成功但文件未写成，终局 skip 后消息丢失。缓解：与终局保存失败同类静默风险，不为此改 saveSession 签名。
- [每取消回合多两次全文件原子写（早落盘 + 中止落盘）] → jsonl 为 KB 级小文件；`AgentLoopCancelRaceInvariantTest` 压测每轮多一次写，需观测是否拖慢（该测试已按轮清会话防 O(N²)）。
- [CLAUDE.md 关闭整合 N 口径陈旧] → 实际按全量消息计数（含 tool），CLAUDE.md 写「仅 user/assistant」。顺带在文档任务中更正。
- [read_instance_session 对端可见合成消息] → role user/assistant、非空 content 的合成行会被对端计入/检索。对齐 Nanobot WebUI 可见性（无过滤），接受；TOOL 角色合成行天然被其 role 过滤跳过。
- [关闭整合快照包含中止落盘行] → 取消等待（≤5s/latch）覆盖中止落盘耗时（单次文件写），超窗则中止落盘行落进下次快照——无害。
- [Stop 期间 GUI 双轨] → 面板「Stopped.」行/结构化回执照旧，jsonl 中止落盘对 GUI 不可见（面板从不读会话文件），无双渲染。
- [maxIterations+drain6 与 LLM 错误回合的 USER 尾（既有砖化源）] → **已由 D16 启发式修复**（纯尾检查不区分来源，一律收尾）——原墓碑方案会留洞，审查修订后覆盖。
- [epoch 复查与写的指令级残余窗口] → 与既有持久化双检同级别，接受；TOCTOU 全闭需 reset 链路重构（clear+save 本就在栅栏锁外），另立变更。
- [模型切换双写者（latent）] → 审查确认模型切换当前不可达（与 2026-09-09 审计一致），设计依赖「单活 loop 写同一 jsonl」假设；退役 loop 上的中止落盘写是该 latent 竞态的新增面，本变更不扩大处理，记录在案。
- [关闭对话框 N=0 门翻转] → 回合运行中关闭时，早落盘的 user 消息使 N≥1，原「N=0 不弹」的门在跑动中关闭时不再触发——记录为接受的行为变化（关闭整合更完整）。

## Migration Plan

纯增量变更，无数据迁移：旧 jsonl 读取端无形状变化（新字段 `_recovery_interrupted` 被所有既有消费者按未知字段忽略——Jackson/逐行解析天然容忍）。回滚 = revert 单一提交，无状态残留（下次回合起回到终局统一落盘行为）。
