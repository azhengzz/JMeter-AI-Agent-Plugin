## Context

`ClaudeService.generateResponseWithTools` 当前是纯文本降级：忽略 `tools`、剥离 SYSTEM/TOOL 消息、调 `generateResponse(text)`（[ClaudeService.java:329-348](src/main/java/org/gitee/jmeter/ai/service/ClaudeService.java)），3 参版本（AgentLoop 入口）又无条件委托给它。`supportsToolCalling()` 对 `claude-*` 返回 `true`，所以选 Claude 时 agent 静默退化为无工具问答。

anthropic-java 原生支持 tool use。**依赖已从 2.18.0 升至 2.53.0**（2026-08-07 最新；2.18→2.53 落后 35 个 minor 版本，CHANGELOG 无核心 messages/tool-use 的 BREAKING 变更，现存 ClaudeService 对 2.53.0 编译通过）。本设计用 SDK 真工具调用替换该降级。所有 SDK 类/方法签名已用 `javap -cp` 对 **2.53.0** 核心 jar 逐个核实，并经对抗验证（见下「验证结论」）。

现有文本路径用法（已确认）：`client.messages().create(MessageCreateParams)`；`.system(String)`（顶层，非消息）；`.addUserMessage(String)`/`.addAssistantMessage(String)`；响应 `message.content().get(0).text().get().text()`；usage `message.usage().inputTokens()/outputTokens()`（primitive long）。

## Goals / Non-Goals

**Goals:**

- 用 SDK 原生 tool use 重写 `ClaudeService.generateResponseWithTools(2 参)`：请求携带 tool 定义，响应 `tool_use` 块解析为 `ToolCall`，`tool_result` 回传支持多轮往返。
- 顶层 `system` 正确取自 messages，避免双重/丢失（并顺手修掉文本路径「首轮后才丢 system」的隐患）。
- 保持 3 参 override（options 的 try/finally 状态切换）与 `supportsToolCalling()` 不变。

**Non-Goals:**

- 不做纯文本面（`generateResponse` 1/2 参、`sendMessage`、`generateResponseStreaming`）的 sweep 删除——那是 Claude 真用工具之后的独立清理变更。
- 不引入流式工具调用、MCP 工具。
- ~~不改 `MemoryConsolidator` 的 forced-tool 路径~~（**范围调整，见 D7**）：实现阶段核查发现原"回退到 2 参方法"的假设对 Claude 不成立，经用户确认改为让 ClaudeService 真正实现 forced-tool。

**已纳入范围（D7）：** Anthropic `tool_choice` 强制工具调用（仅 `generateResponseWithForcedTool`，供 `MemoryConsolidator` 的 `save_memory` 用）。

## Decisions

### D1：只换 2 参方法体；3 参 override 与 `supportsToolCalling` 不动

3 参 override（[351-369](src/main/java/org/gitee/jmeter/ai/service/ClaudeService.java)）已用 try/finally 把 `generationSettings`/`currentModelId` 按 `LlmCallOptions` 临时切换后委托给 2 参方法，对 2 参透明。重写 2 参体即可，3 参无需改。删除仅被旧降级调用的 `convertToStringList`（[371-377](src/main/java/org/gitee/jmeter/ai/service/ClaudeService.java)，唯一调用方即 :331）。

### D2：ToolDefinition → SDK Tool（复用现成 JSON-schema）

`ToolDefinition.parameters` 本就是完整 input_schema（`ToolDefinition.toAnthropicTool()` 已原样用作 `input_schema`）。分解到类型化 setter：
- `Tool.builder().name().description().inputSchema(buildInputSchema(parameters)).build()`
- `buildInputSchema`：`type`→`JsonValue.from(type)`；`properties` 每个 sub-schema 作为 `JsonValue` 塞进 `Tool.InputSchema.Properties.putAdditionalProperty`；`required`→`List<String>`；其余键走 `putAdditionalProperty`。
- 注册用 `paramsBuilder.addTool(tool)`（内部自动 `ToolUnion.ofTool` 包装）。**无顶层 `ToolInputSchema` 类——是嵌套 `Tool.InputSchema`。**

### D3：Message → MessageParam 映射（关键：Anthropic 只有 USER/ASSISTANT 角色）

`MessageParam.Role` 在 2.53.0 有 `USER`/`ASSISTANT`/`SYSTEM`（2.35.0 起新增 SYSTEM 角色），**仍无 TOOL**。映射：
- `SYSTEM` → 不发消息，由 D4 的顶层 `system` 消费。
- `USER` → `addUserMessage(text)`。
- `ASSISTANT` 有 toolCalls → `addAssistantMessageOfBlockParams(...)`：可选前导 `TextBlockParam` + 每个 `ToolCall` 一个 `ToolUseBlockParam{id,name,input}`（input 由 `arguments` map 经 `ToolUseBlockParam.Input.putAdditionalProperty` 重建）；无 toolCalls → `addAssistantMessage(text)`。
- `TOOL` → **USER 角色消息**内含 `ToolResultBlockParam{toolUseId=getToolCallId(), content}`。
- **合并连续 TOOL 消息为单条 USER 消息**（多个 tool_result 块），保证 user/assistant 严格交替（助手一轮多工具调用场景必需）。

`tool_use_id` 链接：`ToolResultBlockParam.toolUseId` ↔ 助手 `tool_use.id`。AgentRunner 已把响应里的 `tu.id()` 存为 TOOL 消息的 `toolCallId`（[AgentRunner.java:418-428](src/main/java/org/gitee/jmeter/ai/agent/run/AgentRunner.java)），直接对接。

### D4：System prompt 顶层化，且每次调用都重新取（修复隐患）

工具路径从 messages 抽取 SYSTEM 内容 → `paramsBuilder.system(systemText)`，`addMessages` 对 SYSTEM `continue`。**不**沿用文本路径的 `systemPromptInitialized` 标志（那个首轮后丢 system 是 bug）。ContextBuilder 每次都在消息列表 index 0 注入 SYSTEM（[ContextBuilder.java:166](src/main/java/org/gitee/jmeter/ai/agent/context/ContextBuilder.java)），所以每轮工具调用都正确携带 system——比文本路径更正确。无 SYSTEM 则省略。

### D5：响应 ContentBlock → ToolCall / LLMResponse

遍历 `message.content()`（`List<ContentBlock>`，非 Optional）。**用 `isXxx()/asXxx()`，不用 `instanceof`**：
- `isToolUse()` → `ToolUseBlock`：`tu.id()`、`tu.name()`、`tu._input()`（**注意是 `_input()` 不是 `input()`**，返回 `JsonValue`）→ `.convert(new TypeReference<Map<String,Object>>(){})` 得参数 map，构造 `ToolCall(id,name,args)`。
- `isText()` → 累加 `asText().text()` 为 content。
- `isThinking()` → 累加为 reasoningContent（可选）。

finishReason：有任意 `ToolUseBlock` → `"tool_calls"`（AgentLoop 按 `hasToolCalls()` 判定，不看字符串）；否则 `mapStopReason(stopReason)`。**`StopReason` 是类不是 enum**：用 `sr.known()` 取真 Java enum `StopReason.Known{END_TURN,MAX_TOKENS,STOP_SEQUENCE,TOOL_USE,PAUSE_TURN,REFUSAL}` 再 switch：`END_TURN/STOP_SEQUENCE→"stop"`、`MAX_TOKENS→"length"`、其余→`"stop"`（`TOOL_USE` 被前面的 `!toolCalls.isEmpty()` 分支短路，不会到这里）。

usage：`Usage.inputTokens()/outputTokens()`（primitive long）→ cast int 塞 `Map<String,Integer>{prompt_tokens,completion_tokens}`；`AnthropicUsage.recordUsage(message, modelId, in, out)` 独立 try/catch。

### D6：复用现有支路，保持行为一致

- `mapReasoningEffortToBudget`（[214-224](src/main/java/org/gitee/jmeter/ai/service/ClaudeService.java)）：reasoning effort → thinking budget。
- thinking/temperature **互斥**：`enabledThinking(budget)` 时**绝不**调 `.temperature(...)`（API 拒绝），`maxTokens<budget+1` 时上调到 `budget+1000`。照搬 [144-152](src/main/java/org/gitee/jmeter/ai/service/ClaudeService.java) 的分支。
- `extractUserFriendlyErrorMessage`（[233-269](src/main/java/org/gitee/jmeter/ai/service/ClaudeService.java)）：credit/限流/key 错误友好串。catch 块 `return LLMResponse.error(...)`。

### D7：实现 forced-tool（tool_choice），修 MemoryConsolidator 的 Claude 路径

**实现阶段发现**：原 Non-Goal 假设"ClaudeService 不覆写 forced-tool → 抛 `UnsupportedOperationException` → MemoryConsolidator 回退到 2 参工具方法"对 Claude **不成立**——默认异常消息 "Forced tool calling not supported by..." 不匹配 `isToolChoiceUnsupported`（只认 `tool_choice`/`does not support`/`should be ["none","auto"]`），catch 分支不触发回退，consolidation 直接失败到 raw-archive。改动前后行为一致（无回归），但契约落空。

**决断（用户确认直接修复）**：让 ClaudeService 真正实现 forced-tool，而非依赖回退：
- 覆写 `generateResponseWithForcedTool(messages, tools, forcedToolName)` → 复用 auto 路径，额外设 `paramsBuilder.toolChoice(ToolChoice.ofTool(ToolChoiceTool.builder().name(forcedToolName).build()))`。
- 抽 `buildToolCallParams(messages, tools, forcedToolName)`（auto/forced 共用，返回 `MessageCreateParams`，便于单测）+ 私有 `callWithTools(...)`（build → create → toLLMResponse）。
- 覆写 `supportsForcedToolChoice()` 返回 `supportsToolCalling()`（契约自洽；目前仅 `TracedAiService` 委托读取，无门控）。
- 效果：MemoryConsolidator Step 1 直接得到强制 `save_memory` 调用，不再走回退。`isToolChoiceUnsupported` 不动（其它 OpenAI-compat provider 仍用它）。
- 单测 `buildToolCallParamsForcesSpecificToolOnlyWhenRequested`：auto 不置 tool_choice、forced 置且 `asTool().name()` 正确，两种都附 tools。

### D3 勘误：ContentBlockParam 是 Kotlin sealed class，Java 端须 ofXxx() 包装

实现首编译即报 `TextBlockParam/ToolUseBlockParam/ToolResultBlockParam` 不可转为 `ContentBlockParam`——它们在 Java 里**不是**其子类（Kotlin sealed class 互操作）。须用静态工厂包装：`ContentBlockParam.ofText/ofToolUse/ofToolResult(...)`（请求侧）；响应侧仍用 `ContentBlock.isToolUse()/asToolUse()` 等。原 D3 漏记此点。

## Risks / Trade-offs

- **[P1 机械修复] Java 无 `import as`**：helper 签名里的 `Message` 在 SDK 类与我们的 model 类间歧义。**决断**：保留 `import com.anthropic.models.messages.Message;`，helper 中我们的消息类全限定为 `org.gitee.jmeter.ai.agent.model.Message`（与现有 329 行签名一致）。
- **[未来 StopReason] 新增未知 stop reason** → `known()` 可能抛/_UNKNOWN；switch 有 default→"stop"，外层 try/catch 兜底。
- **[`_input().convert` 失败]** 工具入参非 object 或异常 → warn + 用空 map，不整轮失败。
- **[消息交替] Anthropic 拒绝两条连续 assistant** → D3 合并连续 TOOL 为单条 USER；ContextBuilder 现不产生连续 ASSISTANT，无需额外合并。
- **[BREAKING：行为改变] 选 Claude 时从「无工具文本」变为「真工具调用」** → 这是预期修复。真实 Claude API 调用会消耗 token 并真正发起工具调用。
- **[请求/响应块类型不对称] 请求侧 `*BlockParam`、响应侧 `*Block`** 是不同类，不可混用（已在 D2/D5 区分）。

## 验证结论（对抗验证）

verify agent 用 `javap -cp` 对 jar 逐符号核对（先 2.18.0，pom 升级后对 **2.53.0** 复核）：D1-D6 用到的所有 SDK 类/方法/构造**全部存在且签名一致**，含三个承重陷阱（`ToolUseBlock._input()` 非 `input()`、`StopReason.known()`、`MessageParam.Role` 无 TOOL 角色）。`tool_use_id↔tool_result` 链接、`hasToolCalls()` 契约、system 处理（无双重/无丢失/不泄漏文本路径标志）均经源码核对正确。**唯一问题即 P1（记法），修复后可编译。**

**2.53.0 相对 2.18.0 的差异（均不影响本设计）**：`MessageParam.Role` 新增 `SYSTEM`（我们仍用顶层 `system()`，TOOL 角色仍不存在）；`Tool.Builder` 新增 `strict`/`allowedCallers` 等可选字段（不设）；新增若干内置工具的 `addTool` 重载（我们用 `addTool(Tool)`）。2.20.0 的「structured stop_details」未改变 `stopReason()`/`known()` 路径。

## Migration Plan

- 纯代码改动，无数据/配置迁移。
- 升级后选 Claude：从「静默无工具文本」变为「真工具调用」。
- 回滚：还原 `ClaudeService.generateResponseWithTools(2 参)` 方法体 + 恢复 `convertToStringList`。

## Open Questions

- Claude 模型若未开 API 权限/无 quota：真实调用会失败 → 经 `extractUserFriendlyErrorMessage` 友好提示，与文本路径一致。
- 是否需要在 `mapStopReason` 显式处理 `REFUSAL`/`PAUSE_TURN`：暂归 `"stop"`，后续若需区分再加。
