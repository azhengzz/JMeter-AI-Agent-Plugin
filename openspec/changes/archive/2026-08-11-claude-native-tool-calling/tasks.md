## 0. 依赖升级（已完成）

- [x] 0.1 `pom.xml`：anthropic-java `2.18.0` → `2.53.0`（最新，2026-08-07）。`mvn clean compile` 通过（现存 ClaudeService 向后兼容）。
- [x] 0.2 对 2.53.0 core jar `javap` 复核工具调用 API：所有承重签名（`ToolUseBlock._input()`、`StopReason.known()`、`Tool.InputSchema` 嵌套、`addTool(Tool)`、各 `*BlockParam` builder、`isToolUse/asToolUse`、`Usage.inputTokens()/outputTokens()`）与 2.18.0 一致。差异仅：`MessageParam.Role` 新增 `SYSTEM`（不影响——仍用顶层 `system()`、TOOL 角色仍不存在）；`Tool.Builder` 新增可选字段（不设）。

## 1. ClaudeService 真工具调用实现

- [x] 1.1 新增 imports：`com.anthropic.core.JsonValue`、`com.fasterxml.jackson.core.type.TypeReference`、`com.anthropic.models.messages.{ContentBlock,ContentBlockParam,StopReason,TextBlock,TextBlockParam,ThinkingBlock,Tool,ToolUseBlock,ToolUseBlockParam,ToolResultBlockParam}`。**P1 修复**：保留 `import com.anthropic.models.messages.Message;`，所有 helper 签名里的我方消息类全限定为 `org.gitee.jmeter.ai.agent.model.Message`（与现有 329 行一致，Java 无 `import as`）。
- [x] 1.2 实现 `buildTool(ToolDefinition)` + `buildInputSchema(Map)`：`Tool.builder().name().description().inputSchema(...)`；input schema 用嵌套 `Tool.InputSchema`（type→JsonValue、properties→`Tool.InputSchema.Properties.putAdditionalProperty`、required→List）（D2）。无顶层 `ToolInputSchema`。
- [x] 1.3 实现消息映射（D3）：`addMessages(builder, messages)` 按 SYSTEM(skip)/USER/ASSISTANT(toolCalls→块)/TOOL 处理；`flushToolResults` 合并连续 TOOL 为单条 USER 消息；`buildAssistantBlocks`（可选 TextBlockParam + 每 ToolCall 一个 `ToolUseBlockParam{id,name,input}`）；`buildToolResultBlock`（`ToolResultBlockParam.toolUseId(getToolCallId()).content`）。
- [x] 1.4 实现 `extractSystem(messages)`：拼接 SYSTEM 内容，供顶层 `.system(...)`（D4）。
- [x] 1.5 实现 `toLLMResponse(SDK Message, modelId)` + `mapStopReason(StopReason)`：用 `isToolUse/isText/isThinking + asXxx`（非 instanceof）；`ToolUseBlock._input().convert(TypeReference<Map<String,Object>>{})` 取参；有 tool_use→finishReason `tool_calls`，否则 `mapStopReason`（`StopReason.known()` switch：END_TURN/STOP_SEQUENCE→stop、MAX_TOKENS→length）；usage 用 `inputTokens()/outputTokens()`（long→int）+ `AnthropicUsage.recordUsage`（D5）。
- [x] 1.6 重写 `generateResponseWithTools(2 参)` 方法体（替换 [329-348](src/main/java/org/gitee/jmeter/ai/service/ClaudeService.java) 的文本降级）：组装 `MessageCreateParams`——`maxTokens/model`、thinking/temperature 互斥分支（复用 `mapReasoningEffortToBudget`，照搬 [144-152](src/main/java/org/gitee/jmeter/ai/service/ClaudeService.java)）、`extractSystem`→`.system`、`addMessages`、逐 `addTool(buildTool(td))`；`client.messages().create(params)`；`toLLMResponse`；catch→`LLMResponse.error(extractUserFriendlyErrorMessage(e))`（D6）。
- [x] 1.7 删除 `convertToStringList`（[371-377](src/main/java/org/gitee/jmeter/ai/service/ClaudeService.java)）——唯一调用方即旧降级 :331，重写后无引用。

## 2. 测试

- [x] 2.1 `buildInputSchema` 单测：`ToolDefinition.parameters`（含 type/properties/required/enum）→ `Tool.InputSchema`，断言 type、各 property 的 sub-schema、required 列表。
- [x] 2.2 消息映射单测：SYSTEM（不进 messages、由 extractSystem 出）、USER、ASSISTANT(带 toolCalls→tool_use 块)、TOOL（USER + tool_result + toolUseId=getToolCallId）、连续两条 TOOL 合并为单条 USER。
- [x] 2.3 `toLLMResponse` 单测：构造 SDK `Message`（若可直接 builder 构造则直接，否则用 mock）——tool_use 响应→`LLMResponse(tool_calls)`；纯文本→`text`；含 thinking→reasoningContent；usage 提取正确。
- [x] 2.4 `mapStopReason` 单测：`StopReason.Known.{END_TURN,MAX_TOKENS,STOP_SEQUENCE,PAUSE_TURN,REFUSAL}` 分别映射到 stop/length/stop/stop。
- [x] 2.5 回归：确认 `MemoryConsolidator` forced-tool 路径（ClaudeService 不覆写 forced-tool → 抛 UnsupportedOperationException → 回退到 2 参工具方法）现在走真工具调用，行为合理。**核查→修复**：原核查发现 ClaudeService 默认异常消息不匹配 `isToolChoiceUnsupported`、回退永不触发（无回归但设计假设落空）。经用户确认直接修复：ClaudeService 现覆写 `generateResponseWithForcedTool`，用 Anthropic `tool_choice=ToolChoice.ofTool(name=forcedToolName)` 强制工具，MemoryConsolidator Step 1 直接成功、无需回退；并覆写 `supportsForcedToolChoice()` 返回 `supportsToolCalling()`。共享参数构建抽成 `buildToolCallParams(..,forcedToolName)`（auto/forced 复用），单测覆盖 forced 才置 tool_choice 且 name 正确。

## 3. 构建与验证

- [x] 3.1 `mvn clean test-compile`：`clean` 防 stale class 掩盖编译错误（记忆：mvn-stale-classes）。
- [x] 3.2 `mvn test`：除既有跳过外无新增失败。
- [ ] 3.3 （有 Anthropic API key 时）真实冒烟：选 Claude 模型，发起一个会触发工具的请求，确认 Claude 返回工具调用、AgentLoop 执行并回传、多轮收敛。**状态：暂缓**——本机未配置真实 `anthropic.api.key`（jmeter.properties 为占位、user.properties 无、无环境变量），且真实调用消耗 token 属对外动作，需用户有 key 时手动在 GUI 冒烟。单元层已用 mock 覆盖 tool_use↔ToolCall 往返（2.3）。
