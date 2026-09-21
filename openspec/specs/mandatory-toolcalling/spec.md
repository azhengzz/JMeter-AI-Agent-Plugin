# mandatory-toolcalling

## Purpose

工具调用是 Agent 执行的前提：Agent Runner 只通过工具调用接口驱动 LLM，不允许退回纯文本生成；所有被支持的提供者（含 Ollama）必须报告并实现工具调用能力，工具定义翻译、结果回放均交由 Agent loop 处理。

## Requirements

### Requirement: Tool calling is mandatory for agent execution

The Agent Runner SHALL invoke the LLM exclusively through the tool-calling interface. When the configured `AiService` reports `supportsToolCalling() == false`, the Agent Runner SHALL terminate the run before the first LLM call with a user-visible error stating the model/provider does not support tool calling, and SHALL NOT fall back to text-only generation.

#### Scenario: Unsupported service aborts before the first LLM call

- **WHEN** the Agent Runner is started with an `AiService` whose `supportsToolCalling()` returns `false`
- **THEN** the run terminates without entering the agent loop and without calling the LLM
- **AND** the run result carries a user-visible error message naming the provider/model as not supporting tool calling

#### Scenario: No text-only fallback in the agent loop

- **WHEN** the Agent Runner invokes the LLM
- **THEN** it SHALL call only `generateResponseWithTools`

#### Scenario: Supported service proceeds normally

- **WHEN** the Agent Runner is started with an `AiService` whose `supportsToolCalling()` returns `true`
- **THEN** the run executes through the normal tool-calling loop unchanged

### Requirement: Ollama provider supports tool calling

The Ollama `AiService` SHALL report `supportsToolCalling() == true` and SHALL implement `generateResponseWithTools(messages, tools, options)` so the model can emit tool calls that the Agent loop executes, including multi-turn round-trips where prior assistant tool calls and tool results are replayed.

#### Scenario: Ollama reports tool-calling support

- **WHEN** `OllamaAiService.supportsToolCalling()` is queried
- **THEN** it SHALL return `true`

#### Scenario: Ollama response containing tool calls is mapped to a tool-call response

- **WHEN** the Ollama chat response message carries one or more tool calls
- **THEN** `generateResponseWithTools` SHALL return an `LLMResponse` whose `toolCalls` are populated from the provider tool calls and whose `finishReason` is `tool_calls`

#### Scenario: Ollama response without tool calls is mapped to a text response

- **WHEN** the Ollama chat response message carries no tool calls
- **THEN** `generateResponseWithTools` SHALL return an `LLMResponse` with the message text as `content` and `finishReason` `stop`

#### Scenario: Tool definitions are translated to the provider tool format

- **WHEN** `generateResponseWithTools` is called with a list of `ToolDefinition`
- **THEN** each tool SHALL be translated into the provider's tool specification format and registered with the request, and the provider SHALL NOT auto-execute tools (execution stays with the Agent loop)
