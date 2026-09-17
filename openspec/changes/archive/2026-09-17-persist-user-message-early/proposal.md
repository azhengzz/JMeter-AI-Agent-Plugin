## Why

用户发出的消息只在回合**成功结束后**才统一落盘（[AgentRunner.java:163](../../../src/main/java/org/gitee/jmeter/ai/agent/run/AgentRunner.java#L163) 的 `!isAborted` 守卫在取消时整段跳过持久化）。一旦用户点 Stop、会话被重置取消、agent 异常或进程崩溃，刚发的消息（含 @ 实例引用、委派任务）就从 jsonl 里消失：GUI 当场可见，但同进程后续回合的 LLM 上下文（`getHistory` 读内存 session，取消回合从未 `addMessage`）与重启后的会话文件都没有它。且被中断的回合在会话转录里不留任何痕迹——悬空的 tool_call、戛然而止的对话对后续上下文都是黑洞。

对齐 Nanobot 两项机制（[loop.py:677-718](../../../../../nanobot-zheng/nanobot/agent/loop.py) + `session/recovery.py`）：

1. **persist-early**：回合开始即把触发 user 消息落盘；
2. **interrupt 中止落盘**：被停止的回合以合成中断标记消息收尾（`"Error: Task interrupted before a response was generated."` / `"Error: Task interrupted before this tool finished."` + `_recovery_interrupted` 标记），真实完成的部分上下文（assistant 消息、tool 结果）一并保留。

## What Changes

- **回合开始即持久化触发 user 消息**：`AgentRunner.run` 在 `buildMessages` 之后、LLM 循环之前，把触发 user 消息（含 Runtime Context 块 + `_runtime_context` 标记，复用终局持久化的同一变换）`addMessage` + `saveSession` 立即落盘。
- **终局保存去重**：已提前落盘的回合，终局 `saveMessagesToSession` 的 skipCount 前移一位，避免 user 消息双重持久化（对齐 Nanobot `_save_skip_for_turn`）。
- **非 RESET 取消或内部异常中止时中止落盘部分上下文 + 中断标记**（新增，对齐 Nanobot `restore_runtime_checkpoint`/`restore_pending_interruption`）：Stop/超时/关闭整合取消、或内部异常（非 LLM 调用自身的 RuntimeException）中止的回合，把本回合已完成的真实消息（assistant、tool 结果、已消费进上下文的注入消息）中止落盘；为每个悬空 tool_call 合成 tool 结果、为无产出或 USER 结尾的回合合成 assistant 收尾消息；合成消息携带 jsonl 顶层 `_recovery_interrupted: true` 标记（round-trip）；异常中止的中止落盘不改变既有 GUI 终态与事件流。
- **重置代数（epoch）守卫接线**（前置依赖，对抗审查修订）：RESET 取消不做中止落盘的判别用既有 session epoch（`markConversationReset` 栅栏锁下先翻代数再清空——epoch 翻转 ⟺ 会话被重置），经 `AgentRunSpec` 以活引用 Supplier 供 runner 在中止落盘入口与提交前复查。取代原 CancelCause 打通方案（审查 P0 证实 cause 写入锚在 signalCancel 守卫内、Stop-后-//new 序列下 RESET 永远写不进；epoch 完全覆盖其判别面且不可被取消时序欺骗，signalCancel 零改动）。
- **悬空 user 尾懒收尾**（对齐 Nanobot `restore_pending_interruption` 的效果，纯尾检查启发式）：回合开始时会话末条为 USER ⇒ 补合成 assistant 收尾（带 `_recovery_interrupted` 标记）。兜底即时中止落盘覆盖不了的路径（进程强杀、`Error` 逃逸），并**顺带修复两个既有砖化源**——LLM 错误回合与迭代上限收尾抽干同样留下 USER 尾，下回合连续两条 user 消息使 Anthropic API 返回 400（本项目 ClaudeService 不合并）、会话连续失败直至 /new。不移植 Nanobot 墓碑（审查证实其「正常完成即清除」的区分恰好把这些尾排除在收尾外、留洞）。
- **契约修订（2026-08-23 取消作废契约的收窄）**：**已消费进上下文**的注入消息随中止落盘保留（用户真实输入过、LLM 真实看过）；**队列残留**仍作废不变（`discardCancelledLeftovers` 不动）。
- **前置必修 bug**：`MessageOptimizer.optimizeContent` 对 `content == null` 的 assistant-with-tool_calls 返回 null 导致其被丢弃——OpenAI 路径工具调用响应的 content 恒为 null，悬空 assistant 恰是中止落盘必须保留的消息，丢弃会让合成 tool 结果变孤儿、`findLegalStart` 在下次加载时截肢整段回合尾部。中止落盘路径顺带修复正常路径的同款隐患。
- **jsonl 标记读写**：`SessionManager.jsonToMessage` 现为**替换**语义（"与 toolName 互斥"只对现有两 key 成立）——加第三 key 必须改为合并语义，否则静默摧毁兄弟 key。

不在范围内：GUI 重启后聊天历史回显（面板不读 jsonl）；命令消息持久化；Nanobot 的 runtime checkpoint 边车（`.checkpoint.json` 逐迭代快照）、墓碑机制与 RecoveryCoordinator「Continue」——同步 runner 在 abort 时手握全量 `currentMessages`，可**即时中止落盘全部已完成迭代**（优于 Nanobot 只保留末迭代），无需边车；悬空尾以纯 USER 尾检查闭合（见上，墓碑的区分对本项目有害无益）。

## Capabilities

### New Capabilities

- `early-user-message-persist`: 触发 user 消息的回合开始期持久化 + 非 RESET 取消时的部分上下文中止落盘与中断标记契约——何时写、写什么形状（Runtime Context 块、`_runtime_context`、`_recovery_interrupted` 标记、合成消息内容）、终局如何去重、各取消原因下的幸存/中止落盘/不复活语义、中止落盘结果的 provider 合法性。

### Modified Capabilities

（无——`per-instance-session` 管会话身份与归档，本变更不动其需求。）

## Impact

- **代码**：`AgentRunner.java`（run 重排 + 懒收尾 + `persistUserMessageEarly` + `materializeInterruptedTurn` + 共享变换抽取 + `runAgentLoop` 异常安全）、`AgentRunSpec`（epoch 活引用 Supplier）、`SessionManager`（`_recovery_interrupted` round-trip + metadata 合并语义）、`MessageOptimizer`（null-content 修复）；`ContextBuilder`（新常量）；`Turn`/`signalCancel`/`Session` 零改动。
- **行为涟漪**：关闭整合的未整合计数与归档输入会包含被取消回合的 user 消息与合成标记消息（更完整的历史记录）；`read_instance_session` 对端可见合成消息（user/assistant 角色、非空 content，对齐 Nanobot WebUI 可见性）；两者无需改动。
- **测试契约重新谈判**：`AgentLoopTurnEventTest` 的「取消回合 Q&A 不得落盘（T1-）」断言需按新契约更新（user 行在场 + 合成标记在场、assistant 半截内容仅在非 RESET 取消时中止落盘）；`AgentLoopCancelRaceInvariantTest` 压测每取消回合多一次全文件原子写。
- **委派回合**：委派任务文本同样提前落盘、被取消时中止落盘；子代理（`persistSession=false`）全程静默不变。
