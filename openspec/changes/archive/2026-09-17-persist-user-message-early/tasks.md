## 1. 前置修复与共享变换

- [x] 1.1 修复 `MessageOptimizer.optimizeContent`（design D13）：`role==ASSISTANT && hasToolCalls && content==null` 时返回 `""` 而非 null（与 `shouldSkip` 现状对齐），并补 `MessageOptimizerTest` 用例（null-content 工具调用 assistant 不再被持久化变换丢弃）。验证：`mvn clean test -Dtest=MessageOptimizerTest` 全绿
- [x] 1.2 从 `AgentRunner.saveMessagesToSession` 循环体抽取 `toPersistableMessage(Message)` 私有方法（`shouldSkip` → null；`optimizeContent` 为 null → null；USER 消息挂 `runtimeContextMarker`；timestamp/reasoningContent/metadata 重建），`saveMessagesToSession` 改为调用它。验证：`mvn clean test` 全绿（行为不变的纯重构）

## 2. 重置代数（epoch）守卫接线

- [x] 2.1 `AgentRunSpec` 增 `Supplier<Long>` epoch 活引用字段（同 `injectionCallback` 活闭包模式；null 容忍），`AgentLoop.startTurn` 以 `() -> currentEpoch(sessionKey)` 闭包接线；子代理路径（SubagentManager）不传。`AgentRunner.run()` 入口捕获 `long epochAtStart`（supplier 为 null 时不做中止落盘）。**不引入 CancelCause 打通**（审查 P0 修订：epoch 完全覆盖其判别面，signalCancel 零改动）。验证：`mvn clean test -Dtest=SubagentIsolationTest` 全绿（子代理 spec 无 epoch 不受影响）

## 3. AgentRunner 早落盘与中止落盘

- [x] 3.1 重排 `run()` 消息构建段（design D2/D8/D16）：`maybeConsolidate` 后先做**懒收尾**（会话末条为 USER ⇒ 追加合成 assistant 收尾 + save，收尾纳入本回合 history；写前查 `abortSignal`、提交前复查 epoch，重置进行中则放弃——D16 守卫），再取 `List<Message> history = session.getHistory(AiConfig.getMaxHistorySize())` 局部快照，再 `buildMessages(history, ...)`；仅 `spec.isPersistSession()` 且走 buildMessages 分支时，对末位触发消息调用新增 `persistUserMessageEarly(session, trigger, abortSignal)`——写前单次 abort 检查（置位返回 false）→ `toPersistableMessage`（null 返回 false）→ `addMessage` + `saveSession` → 返回 true
- [x] 3.2 终局去重（design D3/D6）：`run()` 记 `boolean inputPersistedEarly`，取消守卫重排为三分支——`!isAborted && result.isSuccess()` 走既有 `saveMessagesToSession`（skipCount = `messages.size() - (inputPersistedEarly ? 0 : 1)`）+ 后置整合；`(isAborted || !result.isSuccess()) && inputPersistedEarly && epoch 未翻转` 走中止落盘；其余不写。`saveMessagesToSession` 双检与后置整合位置零改动
- [x] 3.3 实现 `materializeInterruptedTurn`（design D10/D11/D17，compose-then-commit + 异常隔离）：**局部构造完整列表**——partial = `result.getCurrentMessages()[skipCount..]` 过 `toPersistableMessage`；对最后一条带 tool_calls 的 assistant 中每个无后续结果的 call_id 合成 tool 消息（`Message.builder()` + 新建 LinkedHashMap 挂 `toolName` + `_recovery_interrupted`）；无 assistant 中止落盘或尾为 USER 时合成 assistant 收尾（metadata 挂 `_recovery_interrupted`）；**入口检查与提交前复查 epoch**（`supplier.get() != epochAtStart` 则整体放弃，尚未 addMessage 无残留）；否则一次性 `addMessages` + `saveSession`；方法整体 catch Exception（中止落盘失败仅 log，不影响回合结果与 GUI）。验证：`mvn clean test -Dtest=AgentRunnerEarlyPersistTest` 覆盖 3.1-3.3 场景（见 5.1）
- [x] 3.4 `runAgentLoop` 异常安全（design D15）：体内包一层 catch（LoopState 创建之后的全部本体），异常时构建同形结果但携带 `state.currentMessages`、`success=false`、`stopReason="exception"`、**`errorMessage = e.getMessage()` 与 usage metadata**（与 run() 既有 catch 结果同形，保证 `toAgentResponse` 终态文本与现状一致）——**不调任何 hook 回调**；`run()` 自身 catch（runAgentLoop 之外的异常）保持不做中止落盘（防终局保存成功后二次中止落盘）。验证：`AgentRunnerEarlyPersistTest` 场景⑬

## 4. jsonl 标记 round-trip

- [x] 4.1 `ContextBuilder` 增 `RECOVERY_INTERRUPTED_META_KEY = "_recovery_interrupted"`；`SessionManager.messageToJson` 增写分支（`node.put(key, true)`）；`jsonToMessage` 增读分支（`isBoolean()` 守卫）并**把 metadata 读取改为合并语义**（toolName / `_runtime_context` / `_recovery_interrupted` 三源合并进同一 LinkedHashMap，替换现状的整替换——design D14）。验证：`mvn clean test -Dtest=SessionManagerRecoveryMarkerTest`（新增，参照 `SessionManagerRuntimeContextMarkerTest` 模板：round-trip + 多 key 共存合并 + 旧行无标记加载不变）

## 5. 契约测试与回归

- [x] 5.1 新建 `AgentRunnerEarlyPersistTest`（复用 `testsupport/GatedScriptAiService` 挂起钉子 + 临时 workspace 真实 `ContextBuilder` + 临时目录 `SessionManager`，参照 `AgentRunnerLoopSequencingTest`/`SessionTimestampFidelityTest` 脚手架），覆盖 spec 场景：① 回合完成 → 触发消息恰好一条带 `_runtime_context` 标记、无重复，**且捕获的 LLM 请求中触发文本恰好出现一次**（D2 核心回归钉）；② LLM 挂起期置 abort 且无任何产出 → jsonl = user + 合成 assistant 收尾（带 `_recovery_interrupted`）；③ abort 落在工具结果 append 前（before/after-tools 窗口）→ 真实 assistant(tool_calls) + 每悬空 call 合成 tool 消息；④ 终答后 abort（窗口 H）→ 全真实无合成；⑤ 注入消费后 abort → 注入 user 保留 + USER 尾收尾；⑥ 重置取消（epoch 翻转）→ 不做中止落盘；⑥′ **Stop 后紧接重置**（先置 abort 再翻 epoch，P0 回归钉）→ 不做中止落盘、新会话文件无被取消回合内容；⑦ run 前预置 abort → 不落盘不做中止落盘；⑧ `persistSession(false)` → 无任何写；⑨ 中止落盘后 session `getHistory` 含中止落盘消息且无孤儿 tool（`findLegalStart` 不截肢）；⑩ null-content 工具响应中止落盘 → assistant 以空串落盘；⑪ **懒收尾**：早落盘后模拟崩溃（弃用内存 session、重载 SessionManager 模拟重启）→ 新回合开始 → 悬空 user 后补合成收尾、LLM 上下文无连续 user；⑫ **LLM 错误回合 USER 尾**（错误响应无 assistant 产出、回合「正常完成」落盘 trigger）→ 下回合补收尾（既有砖化源修复钉）；⑫′ **drain6 USER 尾** → 下回合补收尾（同上）；⑬ **异常中止落盘**：hook 抛 RuntimeException 中止回合（abort flag 未置）→ partial + 合成收尾落盘、hook 无新增发射、终态文本与现状一致（RecordingHook + AgentResponse 双断言）；⑭ 懒收尾重置竞态：收尾检查通过后置 abort/翻 epoch → 放弃写入。验证：全绿
- [x] 5.2 存量契约测试重新谈判（design D12 契约修订）：核查并更新 `AgentLoopTurnEventTest` 的取消持久化断言（:947/:1054 /new 后 jsonl 仅 metadata 行——应仍成立；:975-980/:1076-1080 `T1-` 不得落盘——按所用取消路径分别更新为「user 行 + 合成标记在场、assistant 半截内容仅在非重置取消时中止落盘」）；`NewCommandCancelTest` 同步核查。验证：断言与新契约一致且全绿
- [x] 5.3 全量回归：`mvn clean test`（含 `AgentLoopCancelRaceInvariantTest` 压测——观测中止落盘新增写的耗时影响；`EventParityTest`；用 clean 防 stale class）。验证：全绿且压测耗时可接受

## 6. 文档与收尾

- [x] 6.1 更新 CLAUDE.md「Agent 运行」节 `AgentRunner` 描述（触发 user 消息回合开始即落盘、非重置取消/异常中止落盘部分上下文与 `_recovery_interrupted` 中断标记、epoch 守卫判重置、悬空 user 尾懒收尾）；顺带更正「关闭期整合对话框告知未整合消息数 N（仅 user/assistant 口径）」为全量消息口径（CloseConsolidationDialog.java:46-49），并记录「回合运行中关闭时 N≥1、N=0 不弹门不再触发」为已知行为变化。验证：文档与实现一致
- [ ] 6.2 手动冒烟（可选，需 `JMETER_HOME`）：`mvn clean install` 装机后启动 GUI（**同实例**验证——每实例会话键下重启即新会话，跨重启记忆走 MEMORY.md 不走会话文件）：① 发消息 → 运行中 Stop → 同实例再发「复述我上一条消息」→ Agent 能答出（取消回合的 user + 中断标记进了 LLM 上下文）；② 发触发工具调用的消息 → 工具执行中 Stop → 查 jsonl 含真实 assistant + 合成 tool 结果（`_recovery_interrupted`）；③ 发消息 → Stop → 立即 /new → 查会话文件只剩 metadata 行（P0 场景手动钉）；④ 强杀进程 → 查旧 jsonl：user 行幸存、无收尾（耐久记录）。验证：jsonl 形状与 Nanobot session 文件一致

## 7. 对抗测试轮（2026-09-17）

- [x] 7.1 三阶段对抗测试（4 攻击视角 → 怀疑者验证 → 可运行 PoC 实证）：16 项发现，3 项 CONFIRMED、2 项 REFUTED、0 项 UNRESOLVED、6 项 P2。**实证缺陷 1 个**：`persistUserMessageEarly` 缺提交前复查（重置在 [入口检查 → 落盘] 跨度内完整落地时触发消息复活进刚清空的新会话）——PoC 跑红坐实
- [x] 7.2 修复实证缺陷：早落盘补 addMessage 后/saveSession 前的 epoch 复查（对齐姊妹路径纪律，design D4 修订），PoC 转绿后收编为 `AgentRunnerEarlyPersistResetRaceTest` 回归钉
- [x] 7.3 收编另两个 PoC 为测试强度钉：`AgentLoopPersistEarlyWiringTest`（经真实 processMessage 接线钉中止落盘/懒收尾——删 startTurn 的 epoch 接线行即红，封堵「特性全灭测试全绿」突变缺口）与 `AgentRunnerMaterializeEpochRecheckTest`（compose-then-commit 提交前复查 + 懒收尾放弃写入）；补「早」字钉（LLM 调用挂起期间触发消息已在盘上，`AgentRunnerEarlyPersistTest.triggerOnDiskWhileLlmCallInFlight`）。验证：`mvn clean test` 全量 649/0/0
