## Purpose

触发 Agent 回合的用户消息在回合开始期（首次 LLM 调用之前）即持久化进会话 jsonl；非重置类取消（Stop/超时/关闭整合）时回合以中止落盘的部分上下文 + 合成中断标记消息收尾，重置类取消不做中止落盘。对齐 Nanobot `_persist_user_message_early` 与 `session/recovery.py` 的持久化时机与中止落盘契约。

## ADDED Requirements

### Requirement: 回合开始期持久化触发用户消息

持久化会话的 Agent 回合 SHALL 在首次 LLM 调用之前，把触发该回合的 user 消息写入会话文件并完成落盘。提前写入的消息 SHALL 与回合终局持久化采用同一形状：正文后随当回合的 Runtime Context 块，并携带 `_runtime_context` 精确剥离标记；公共视图按标记剥离后 SHALL 剩余用户正文。回合的 LLM 上下文中该触发消息 SHALL 恰好出现一次（历史快照先于提前落盘读取，刚写入的消息不得经历史路径再次进入上下文）。

#### Scenario: 首次 LLM 调用前消息已在会话文件中
- **WHEN** 一个持久化会话的回合启动，且在 LLM 循环进行中（已发起至少一次 LLM 调用）读取会话 jsonl
- **THEN** 触发该回合的 user 消息已存在于 jsonl 中，其行携带 `_runtime_context` 标记

#### Scenario: 消息携带 Runtime Context 块与剥离标记
- **WHEN** 提前落盘的 user 消息带 @ 实例引用等当回合 Runtime Context
- **THEN** jsonl 中该消息内容含完整 Runtime Context 块；按 `_runtime_context.suffix` 精确剥离后剩余用户正文

#### Scenario: LLM 上下文中触发消息不重复
- **WHEN** 回合完成首轮 LLM 调用
- **THEN** 发送给 LLM 的消息列表中，触发消息恰好出现一次（提前落盘不产生历史副本）

### Requirement: 非重置取消或内部异常中止时中止落盘部分上下文与中断标记消息

回合因取消（Stop、对端等待超时、关闭整合静默取消）或内部异常（进度回调、上下文治理等非 LLM 调用自身的 RuntimeException）中止时，若触发消息已提前落盘，系统 SHALL 把回合内已产生的真实消息中止落盘进会话：已完成的 assistant 消息（含带 tool_calls 的）、已追加的 tool 结果、已消费进回合上下文的注入 user 消息（注入文本合并进触发消息本身的内容除外——索引去重将其排除，见设计 D7 已接受边界）。中止落盘 SHALL 补齐两类合成消息：为每个没有对应 tool 结果的悬空 tool_call 合成一条 tool 消息（内容 `"Error: Task interrupted before this tool finished."`，携带原 tool_call_id 与工具名）；当中止落盘后本回合无任何 assistant 消息、或最后一条中止落盘消息是 user 角色时，合成一条 assistant 收尾消息（内容 `"Error: Task interrupted before a response was generated."`）。合成消息 SHALL 在 jsonl 中携带顶层 `_recovery_interrupted: true` 标记并完整 round-trip；真实消息不带该标记。标记 SHALL NOT 进入 LLM 上下文（历史清洗剥离 metadata，中断语义由消息内容本身传达）。重置类取消（/new、「+」、关闭整合后清空——以会话重置代数翻转为判据）SHALL NOT 中止落盘任何消息，且该判别 SHALL NOT 可被先前的取消操作欺骗。异常中止的中止落盘 SHALL NOT 改变既有外部行为（GUI 终态与事件流与现状一致）。

#### Scenario: LLM 首调中被停止的回合以合成收尾
- **WHEN** 用户发送消息，首次 LLM 调用尚未返回时点击 Stop
- **THEN** jsonl 中触发 user 消息之后紧跟一条内容为 `"Error: Task interrupted before a response was generated."` 的 assistant 消息，携带 `_recovery_interrupted: true`，且无其他回合消息

#### Scenario: 工具执行中被停止的回合保留真实 assistant 并补合成 tool 结果
- **WHEN** 回合已产出带 tool_calls 的 assistant 消息、工具结果尚未追加时被 Stop 取消
- **THEN** jsonl 保留该真实 assistant 消息（不带标记），其后为每个悬空 tool_call 一条合成 tool 消息（原 tool_call_id、内容 `"Error: Task interrupted before this tool finished."`、带标记）

#### Scenario: 终答已产出但保存前被取消的回合只中止落盘真实消息
- **WHEN** 回合已产出最终 assistant 回复，回合收尾保存执行前取消信号到达
- **THEN** jsonl 中止落盘该回合的全部真实消息（含最终回复），不合成任何标记消息

#### Scenario: 已消费的注入消息随中止落盘保留
- **WHEN** 回合运行中用户追发消息、经注入检查点消费进上下文，随后回合被 Stop 取消
- **THEN** 该注入 user 消息随中止落盘，且其后有合成 assistant 收尾消息（中止落盘尾为 user 角色时必收尾）

#### Scenario: 重置取消不做中止落盘
- **WHEN** 回合运行中用户执行 /new 重置会话
- **THEN** 会话文件被清空，不做中止落盘任何回合消息（触发消息随重置快照归档）；注入队列残留照旧作废

#### Scenario: 内部异常中止的回合同样中止落盘
- **WHEN** 回合因内部错误（如进度回调抛出 RuntimeException）中止，触发消息已提前落盘
- **THEN** 部分上下文与合成收尾按中止落盘同款规则落盘（带 `_recovery_interrupted` 标记）；GUI 终态与事件流与现状一致（不因中止落盘新增错误事件）

#### Scenario: 后续回合上下文可见中止落盘结果
- **WHEN** 一个回合被 Stop 取消（已中止落盘）后，用户在同一会话发送新消息开启下一回合
- **THEN** 下一回合的 LLM 上下文包含被取消回合的触发消息与中止落盘消息（合成标记已剥离、内容在场）

#### Scenario: Stop 后紧接重置不做中止落盘（守卫不可被先前取消欺骗）
- **WHEN** 回合被 Stop 取消（终态已被认领）后、runner 尚在收尾时用户立即执行 /new 重置会话
- **THEN** 重置代数已翻转，中止落盘（含其提交前复查）放弃写入；被取消回合的内容不出现在刚清空的新会话文件中

### Requirement: 中止落盘结果的 provider 合法性

中止落盘后的会话转录 SHALL 满足下一回合 LLM 调用的 provider 约束：每个带 tool_calls 的 assistant 消息 SHALL 有对应的 tool 结果消息（真实或合成）；assistant 消息 content 为 null 时 SHALL 以空串落盘（不得因 content 为 null 被持久化变换丢弃，否则其后 tool 结果成为孤儿并在下次加载时被历史裁剪截肢）。

#### Scenario: 悬空 tool_call 全部补齐合成结果
- **WHEN** 中止落盘一段含带 tool_calls 但无结果的 assistant 消息的部分上下文
- **THEN** 每个悬空 tool_call_id 都有对应合成 tool 消息，下次 `getHistory` 构建的 LLM 上下文无孤儿 tool 消息、无悬空 tool_calls

#### Scenario: null content 的 assistant 消息不被持久化变换丢弃
- **WHEN** OpenAI 路径的工具调用响应（content 为 null、带 tool_calls）被中止落盘
- **THEN** 该 assistant 消息以空串 content 落盘，其 tool 结果不成为孤儿

### Requirement: 悬空 user 尾的懒收尾

回合开始构建历史前，若会话末条消息为 user 角色（无论成因：进程强杀、Error 逃逸、LLM 错误回合无 assistant 产出、迭代上限耗尽后的收尾抽干），系统 SHALL 追加一条合成 assistant 收尾消息（内容 `"Error: Task interrupted before a response was generated."`、携带 `_recovery_interrupted` 标记）并落盘，且该收尾 SHALL 纳入本回合 LLM 上下文——保证发送给 LLM 的消息列表中不存在连续两条 user 消息。收尾写入 SHALL 遵守重置守卫（中止信号与代数检查，重置进行中则放弃写入）。会话为空或末条非 user 角色 SHALL NOT 触发收尾。

#### Scenario: 崩溃后的悬空 user 尾在该会话再次加载时被闭合
- **WHEN** 回合提前落盘后进程被强杀（无中止落盘），该会话文件再次被加载并开启新回合（同进程路径对崩溃不适用——同会话的下一回合；跨进程需会话键可复达的部署，如 `agent.session.per-instance=false`；默认每实例键下强杀实例的文件不再被本进程链加载，悬空尾作为耐久记录留存）
- **THEN** 新回合开始时在悬空 user 消息后追加合成 assistant 收尾消息（带 `_recovery_interrupted` 标记）并落盘；本回合 LLM 上下文以该收尾闭合转录，不存在连续两条 user 消息

#### Scenario: LLM 错误回合的 user 尾同样被闭合
- **WHEN** 一个回合因 LLM 错误中止（错误响应不产生 assistant 消息，终局保存只落盘触发 user 消息），下一回合开始
- **THEN** 该 user 尾被合成收尾闭合（既有连续 user 砖化源随本变更修复）

#### Scenario: 迭代上限收尾抽干后的 user 尾同样被闭合
- **WHEN** 一个回合正常完成但其转录以收尾抽干的注入 user 消息结尾，下一回合开始
- **THEN** 该 user 尾被合成收尾闭合（同上，既有砖化源修复）

#### Scenario: 收尾幂等且空会话不触发
- **WHEN** 会话末条为 assistant/tool 消息，或会话为空（如刚被重置）
- **THEN** 不追加收尾；连续两次崩溃产生 user/收尾交错序列，始终合法

#### Scenario: 收尾写入遵守重置守卫
- **WHEN** 回合开始时的懒收尾检查通过后、写入完成前重置信号到达（中止信号置位或代数翻转）
- **THEN** 收尾放弃写入；重置清空后末条非 user，无需收尾

### Requirement: 终局保存去重

回合自然完成时的终局持久化 SHALL 跳过已提前落盘的触发 user 消息，只追加回合内新增消息；提前落盘未发生（如写前已检测到中止信号）时维持既有行为（终局一并保存触发消息）。一个成功回合完成后，触发 user 消息在会话文件中 SHALL 恰好一条。

#### Scenario: 正常完成的回合不产生重复 user 消息
- **WHEN** 一个回合正常完成（终局保存执行）
- **THEN** 会话文件中该回合的触发 user 消息恰好一条，回合内新增的 assistant/tool 消息按既有规则追加

#### Scenario: 提前落盘被跳过时终局兜底
- **WHEN** 提前落盘因写前中止检查未执行，且回合随后自然完成（未被取消）
- **THEN** 终局保存仍写入触发 user 消息（既有 skipCount 行为兜底，消息不丢）

### Requirement: 重置竞态下不复活旧会话内容

提前落盘与中止落盘 SHALL 在写入前检查取消状态：会话重置先于提前落盘发生时 MUST NOT 把旧会话的触发消息写入刚清空的新会话文件；重置在中止落盘进行中到达时，中止落盘 SHALL 在落盘前复查并放弃文件写入（对齐终局持久化的 abort 双检纪律）。提前落盘先于重置完成时，重置的清空语义 SHALL 照常生效（消息随旧会话归档清空）。

#### Scenario: 重置先于提前落盘则不写
- **WHEN** 回合启动的同时会话被重置，中止信号在提前落盘写前检查时已置位
- **THEN** 提前落盘跳过，新会话文件不含旧回合的触发消息

#### Scenario: 中止落盘提交前重置到达则整体放弃
- **WHEN** 中止落盘在局部列表构造完成、一次性提交（内存追加+落盘）之前检测到重置代数翻转
- **THEN** 中止落盘整体放弃——不追加内存、不写文件（compose-then-commit，无任何残留）

#### Scenario: 提前落盘先于重置则随重置清空
- **WHEN** 触发 user 消息已提前落盘，随后用户执行 /new 重置会话
- **THEN** 会话文件被清空，该消息不再存在于当前会话（随重置快照归档）

### Requirement: 非持久化回合不提前落盘不做中止落盘

非持久化回合（子代理 `persistSession=false`、临时会话）SHALL NOT 执行提前落盘与中止落盘，也不产生任何会话文件写入。

#### Scenario: 子代理回合适度静默
- **WHEN** 主回合经 spawn 启动子代理分析任务，子代理被取消
- **THEN** 子代理回合的触发消息与中止落盘消息均不出现在主会话 jsonl 或任何会话文件中
