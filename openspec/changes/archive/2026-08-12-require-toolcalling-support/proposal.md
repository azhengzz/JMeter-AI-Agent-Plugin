## Why

Agent 主循环 `AgentRunner.callLLM` 当前维护着两条并行的 LLM 调用路径：工具调用路径，以及当 `aiService.supportsToolCalling() == false` 时走的纯文本降级路径（`else` 分支）。如今主流模型（Claude、OpenAI 兼容系列、新版 Ollama）都已原生支持 function/tool calling，纯文本降级既增加维护负担，又不再服务真实场景——把一个不支持工具的模型悄悄降级成「无工具文本问答」，会让 Agent 丧失行动力，且行为极具误导性。应当让工具调用成为硬性前提：不支持就清晰报错，同时给 Ollama 补齐真正的工具调用实现，使其继续作为受支持供应商。

## What Changes

- **BREAKING**：`AgentRunner` 不再对不支持工具调用的模型静默降级为纯文本。当配置的 `AiService` 报告 `supportsToolCalling() == false` 时，本次运行以一条清晰的用户可见错误终止，而非进入无工具的文本循环。
- 移除 `AgentRunner.callLLM` 中的纯文本 `else` 分支（即调用 `generateResponse(List<String>)` 的那条路径），替换为错误响应，复用既有 `response.isError()` 报错通路。
- **Ollama 补齐工具调用**：`OllamaAiService` 基于 ollama4j 1.1.6 的 chat 工具 API（`withTools` / `withUseTools`，解析响应中的 `OllamaChatToolCalls`）实现 `generateResponseWithTools(messages, tools, options)`，并覆写 `supportsToolCalling()` 返回 `true`。Ollama 不再是纯文本供应商。
- 建立映射：我们的 `ToolDefinition` → ollama4j `Tools.Tool`；`Message` 历史（含 ASSISTANT 携带的工具调用、TOOL 角色的工具结果）→ `OllamaChatMessage`，以支持多轮工具调用的完整往返。

## Capabilities

### New Capabilities

- `mandatory-toolcalling`：工具调用是 Agent 执行的硬性前提——不支持的供应商/模型须清晰报错，所有受支持供应商（含 Ollama）须实现工具调用接口。

### Modified Capabilities

（无）现有 provider 类 spec（langcat-provider / minimax-provider）经 `OpenAICompatibleProvider` 已返回 `supportsToolCalling() == true`，本变更下天然合规；`async-subagent` 复用同一 `AgentRunner`，自动继承新行为，不涉及需求层改动。

## Impact

- **代码**：
  - `agent/run/AgentRunner.java`：`callLLM` 的 `else` 分支改为返回错误响应，工具调用路径成为唯一路径。
  - `service/OllamaAiService.java`：新增 `generateResponseWithTools` 实现、覆写 `supportsToolCalling()`、新增 tool/message 映射辅助方法。
  - `service/AiService.java`：接口不变。纯文本 `generateResponse(List<String>)` 方法**保留**——它仍被多处内部调用（`ClaudeService` 工具/流式 fallback、`OpenAiService.sendMessage`、接口默认流式）以及 9 个测试 mock 实现；全量移除属独立重构，爆炸半径大，不在本次范围内。
- **依赖**：无新增。ollama4j 1.1.6 已在类路径上且自带完整工具 API。
- **测试**：现有 9 个 AiService 测试 mock 均返回 `supportsToolCalling() == true`，不受 AgentRunner 改动影响。新增：Ollama tool/message 映射的单元测试；AgentRunner「不支持工具调用即报错」路径的测试。
- **配置/UX**：用户选中其供应商不支持工具调用的模型时，会在运行开始处收到清晰错误；Ollama 用户将获得完整的工具调用 Agent 行为，而非降级的文本循环。
