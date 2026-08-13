## 1. AgentRunner：工具调用成为硬性前提

- [x] 1.1 在 `AgentRunner.run()` 取得 `aiService` 后、进入循环前加 fail-fast 守卫：`!aiService.supportsToolCalling()` 时记日志并以「该模型/供应商不支持工具调用」作为终结 `content` 直接返回，不进入循环（design D1）。沿用现有「终结错误 = `success(true)` + `content`」模式，不引入 `success(false)`。
- [x] 1.2 简化 `callLLM`：删除 `if (aiService.supportsToolCalling()) ... else ...` 整段，仅保留工具调用分支主体（取 tool definitions、调 `generateResponseWithTools(messages, tools, options)`）。移除用户所指的纯文本 `else` 分支（design D2）。
- [x] 1.3 确认 `callLLM` 删改后无孤儿 import/变量（如仅纯文本分支用到的 `ArrayList` 等），编译干净。

## 2. OllamaAiService：补齐工具调用

- [x] 2.1 覆写 `supportsToolCalling()` 返回 `true`（design D3）。
- [x] 2.2 重构请求构建：使 SYSTEM 消息只来自 `messages`，避免 `buildOllamaChatRequest` 现有逻辑再次注入 `systemPrompt` 造成双份系统提示（design D4 风险项）。
- [x] 2.3 实现 `mapTools(List<ToolDefinition>) → List<Tools.Tool>`：`ToolSpec(name, description, Parameters)` + `type("function")`；`parameters` 按 D5 best-effort 映射（顶层 `properties` → `Tools.Property(type, description, enumValues, required)`，`Tools.Parameters.of(map).setRequired(list)`）；嵌套结构降级为 `type:"object"` 并 warn。不设 `toolFunction`。
- [x] 2.4 实现 `Message → OllamaChatMessage` 映射：SYSTEM/USER 二参；ASSISTANT 带 toolCalls 走三参 `withMessage(ASSISTANT, content, mapToolCalls(...))`，无工具调用走二参；TOOL → `withMessage(TOOL, content)`（design D4）。
- [x] 2.5 实现 `generateResponseWithTools(messages, tools, options)`：构建带 `.withTools(mapTools(tools)).withUseTools(true)` 的请求 → `ollamaClient.chat(request, null)` → 解析 `getResponseModel().getMessage()`：有 `getToolCalls()` 映射为 `ToolCall(id, name, arguments)` 构建 `LLMResponse(finishReason="tool_calls")`，否则 `LLMResponse.text(getResponse())`。`options.getModel() != null` 时 try/finally 临时换模型（design D4）。
- [x] 2.6 不注册 `ToolFunction` 并按需 `ollamaClient.setMaxChatToolCallRetries(0)`，确保工具调用返回给 AgentLoop 执行而非 Ollama 内部自执行（design D6）。

## 3. 测试

- [x] 3.1 新增 `AgentRunner` 测试：`supportsToolCalling()==false` 时 run 不进入循环、不调用 LLM，结果 `content` 包含「不支持工具调用」语义（对应 spec 场景 1.1）。
- [x] 3.2 新增 `mapTools` 单元测试：`ToolDefinition`（含 properties/required/enum）正确映射到 `Tools.Tool`（D5）。
- [x] 3.3 新增消息映射单元测试：覆盖 SYSTEM、USER、ASSISTANT(带 toolCalls)、TOOL 四种角色（D4）。
- [x] 3.4 新增 `generateResponseWithTools` 单元测试：工具调用响应 → `LLMResponse(tool_calls)`；纯文本响应 → `LLMResponse.text`（对应 spec 场景 2.2/2.3）。Ollama 依赖用可注入的客户端抽象或 mock 隔离真实服务。
- [x] 3.5 确认现有 9 个 `AiService` 测试 mock（subagent/command）仍编译通过——它们已返回 `supportsToolCalling()==true`，理论上不受影响。

## 4. 构建与验证

- [x] 4.1 `mvn clean test-compile`：必须用 `clean` 防 stale class 掩盖编译错误（记忆：mvn-stale-classes）。
- [x] 4.2 `mvn test`：除既有常驻失败的 `CodeRefactorerTest`（记忆：coderefactorer-test-preexisting-fail）外无新增失败。
- [x] 4.3 （本地若有 Ollama 实例）冒烟测试一轮真实工具调用往返：建一个会触发工具的请求，确认 Ollama 返回工具调用、AgentLoop 执行并回传结果、多轮正常收敛。
