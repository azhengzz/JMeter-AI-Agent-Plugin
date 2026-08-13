## Context

`AgentRunner.callLLM` 当前有两条分支：`supportsToolCalling() == true` 走 `generateResponseWithTools`；为 `false` 时走纯文本 `generateResponse(List<String>)` 降级路径。该降级路径现实中只服务 Ollama（`OllamaAiService` 未覆写 `supportsToolCalling()`，默认 `false`，且未实现 `generateResponseWithTools`）。

ollama4j 1.1.6 已自带完整 chat 工具调用 API（`OllamaChatRequest.withTools(List<Tools.Tool>)` / `withUseTools(true)`；响应经 `getResponseModel().getMessage().getToolCalls()` 取回 `OllamaChatToolCalls → OllamaToolCallsFunction{name, arguments}`；`OllamaChatMessageRole.TOOL` 用于回传工具结果）。因此 Ollama 完全有能力走工具路径，纯文本降级已成多余维护负担。

本设计覆盖两个互相支撑的改动：让工具调用成为 Agent 硬性前提（不支持即清晰报错），并给 Ollama 补齐真正的工具调用实现，使其继续可用。

## Goals / Non-Goals

**Goals:**

- Agent 执行只在「服务支持工具调用」的前提下进行；不支持时立即、清晰地向用户报错，不再静默降级为无工具文本循环。
- 移除 `AgentRunner.callLLM` 中的纯文本 `else` 分支（即用户所指的那段），让工具调用成为唯一路径。
- `OllamaAiService` 真正实现工具调用：覆写 `supportsToolCalling()` 为 `true`，实现 `generateResponseWithTools(messages, tools, options)`，含 `ToolDefinition → Tools.Tool` 与 `Message → OllamaChatMessage` 的双向映射，支持多轮工具调用往返。

**Non-Goals:**

- 不移除 `AiService.generateResponse(List<String>)` 接口方法及其在各 Service 的实现。它仍被 `ClaudeService` 内部 fallback（工具/流式）、`OpenAiService.sendMessage`、接口默认流式等方法调用，且被 9 个测试 mock 实现；全量移除是独立的大爆炸半径重构。
- 不改 `ClaudeService.supportsToolCalling()` 的模型 ID 前缀判定逻辑（对真实 Claude 模型仍返回 `true`；非 claude 前缀的误配现由新的 Agent 报错兜底）。**【后续变更】** 该前缀判定后被独立变更移除：`supportsToolCalling()` 现无条件返回 `true`，故本守卫对 Claude 路径恒不触发，非 claude 前缀的误配改由 Anthropic API 拒绝（与其他三家 provider 行为一致）。
- 不动 `OpenAiService` / `OpenAICompatibleProvider`（已恒 `true`）。
- 不引入 Ollama 的流式工具调用或 MCP 工具（`Tools.Tool.isMCPTool`）。

## Decisions

### D1：在 `AgentRunner.run()` 起始处 fail-fast 守卫，而非依赖 callLLM 返回 error

在 `run()` 取得 `aiService` 之后、进入循环之前，加一处守卫：`if (!aiService.supportsToolCalling())` → 记日志并以一条清晰的错误信息作为本次运行的终结结果返回（不进入循环）。

**为何不**直接把 `callLLM` 的 `else` 改成 `return LLMResponse.error(...)` 复用循环内 `response.isError()` 通路（305–323 行）？那是更小的 diff，但语义偏弱：错误会经过 injection-check-4，理论上若有 pending 注入会 `continue`、再次进入 `callLLM` 又拿到同样错误……虽然最终会因注入耗尽而 break，但对「配置不支持」这类硬错误，第一轮就应干净终止。fail-fast 守卫语义最清晰、无副作用。

**备选**：在 `callLLM` 内改 `else` 为 `LLMResponse.error(...)`（最小 diff）。已否决，理由如上；但若希望最小化改动面，此备选仍可接受。

终结结果的 `success` 标志：现有终结错误（max-iterations、LLM error）均设置 `content` 并以 `success(true)` 返回。本守卫沿用同一模式——错误信息放进 `content`，不引入新的 `success(false)` 分支，避免改动结果消费方。

### D2：简化 `callLLM` 为工具调用专用

D1 守卫保证进入循环时服务必支持工具调用，因此 `callLLM` 内 `if (aiService.supportsToolCalling())` 恒真。删除整个 `if/else`，仅保留工具调用分支主体（取 tool definitions、调 `generateResponseWithTools(messages, tools, options)`）。这正好移除用户所指的 `else` 纯文本分支。

### D3：`OllamaAiService.supportsToolCalling()` 覆写为 `true`

Ollama 经 D4 实现工具调用后，对所有支持 tools 的模型可用。对个别不支持 tools 的本地模型，Ollama 服务端会返回错误，经既有异常通路正常上浮——不再需要客户端层面的能力开关。

### D4：`OllamaAiService.generateResponseWithTools(messages, tools, options)` 实现

复用现有 `buildOllamaChatRequest` 构建骨架（model、来自 `generationSettings` 的 temperature、thinking mode），再 `.withTools(mapTools(tools)).withUseTools(true)`。

**消息映射** `Message → OllamaChatMessage`（按角色）：
- `SYSTEM` → `withMessage(SYSTEM, content)`。注意：`buildOllamaChatRequest` 当前会注入 `systemPrompt`；工具路径下消息已由 `ContextBuilder` 含系统消息，须避免重复注入（见 Risks）。
- `USER` → `withMessage(USER, content)`。
- `ASSISTANT` 且 `hasToolCalls()` → 三参 `withMessage(ASSISTANT, content, mapToolCalls(msg.getToolCalls()))`，回放历史工具调用。
- `ASSISTANT` 无工具调用 → `withMessage(ASSISTANT, content)`。
- `TOOL` → `withMessage(TOOL, content)`，回传工具结果（`msg.getToolName()`/`toolCallId` 不强求映射，Ollama 按角色即可关联）。

**调用**：`ollamaClient.chat(request, null)`（不传 token handler）。

**响应解析** `result.getResponseModel().getMessage()`：
- `getToolCalls()` 非空 → 每个 `OllamaChatToolCalls` 映射为我们的 `ToolCall(id, function.getName(), function.getArguments())`，构建 `LLMResponse`（`finishReason="tool_calls"`，`content` 取 `getResponse()` 可能为空）。
- 否则 → `LLMResponse.text(getResponse())`（`finishReason="stop"`）。

**options 处理**：当 `options.getModel() != null`，本次调用临时切换 `this.model`（仿 `ClaudeService` 362 行做法），try/finally 还原。其余 override（temperature 等）按需从 `options` 应用到 request。

### D5：工具参数映射 `ToolDefinition.parameters → Tools.Parameters`（best-effort）

我们的 `parameters` 是类 JSON-schema 对象（`{type, properties:{name:{type,description,enum}}, required:[...]}`）；ollama4j `Tools.Parameters` 要分解后的 `Map<String, Tools.Property>` + `required` 列表。

映射：从 `parameters.get("properties")` 取每个属性，构造 `Tools.Property(type, description, enumValues, required)`；`Tools.Parameters.of(propertyMap)` 再 `setRequired(requiredList)`。

`Tools.Tool`：`toolSpec(ToolSpec(name, description, parameters))` + `type("function")`，**不设** `toolFunction`、`isMCPTool(false)`。

### D6：不让 ollama4j 自动执行工具

我们 **不**给 `Tools.Tool` 注册 `ToolFunction`，且按需 `ollamaClient.setMaxChatToolCallRetries(0)`，确保 Ollama 把工具调用返回给 Agent、由 AgentLoop 统一执行——与 Claude/OpenAI 路径行为一致。

## Risks / Trade-offs

- **[Ollama 模型不支持 tools]** → 服务端报错经既有异常通路上浮为用户错误。可接受：等价于「该模型不可用于 Agent」。
- **[工具参数 schema 保真度]** ollama4j `Tools.Property` 是扁平模型（type/description/enum/required），无法表达我们的嵌套 `properties`/`itemProperties`。 → best-effort 映射顶层属性；嵌套结构降级为 `type:"object"` 占位。绝大多数 JMeter 工具参数是扁平的，影响有限。实现时对无法识别的结构记 warn 日志而非抛错。
- **[系统消息重复注入]** 工具路径消息已含 SYSTEM，而 `buildOllamaChatRequest` 现有逻辑会再注入 `systemPrompt`。 → D4 实现须重构请求构建，使 SYSTEM 仅来自 messages，避免双份系统提示。
- **[BREAKING：非工具模型现报错]** 原先静默降级为文本的配置，现改为报错。 → 这是预期行为；错误信息须明确指向「该模型/供应商不支持工具调用」。
- **[多轮工具调用时序]** ollama4j 对 `TOOL` 角色消息的关联方式（是否需要 toolCallId）与 OpenAI 不同。 → 实现时验证单轮工具调用往返；若 Ollama 要求字段，再补 `metadata` 透传。

## Migration Plan

- 纯代码改动，无数据/配置迁移。
- 升级后：Claude / OpenAI 兼容用户无感（本就走工具路径）；Ollama 用户从「降级文本」升级为「完整工具调用」；误配的非工具模型从「静默降级」变为「清晰报错」。
- 回滚：还原 `AgentRunner`（守卫 + callLLM 还原 if/else）与 `OllamaAiService`（移除新方法 + `supportsToolCalling` 覆写）两个文件即可。

## Open Questions

- `AgentRunResult` 是否需要区分「配置不支持」的 `success(false)`？倾向沿用现有 `success(true)+content` 模式（见 D1），实现时确认结果消费方无分支依赖。
- Ollama `TOOL` 角色消息是否需要携带 `toolCallId` 才能正确关联多轮（见 Risks）——实现期用真实 Ollama 实例验证。
