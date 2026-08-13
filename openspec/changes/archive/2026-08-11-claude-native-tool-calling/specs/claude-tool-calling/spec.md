## ADDED Requirements

### Requirement: Claude performs real tool calling

ClaudeService SHALL implement tool calling through the Anthropic SDK: it SHALL send tool definitions in the request and parse `tool_use` content blocks from the response into `ToolCall`s. It SHALL NOT silently fall back to text generation that ignores the supplied tools.

#### Scenario: Response containing tool_use is mapped to a tool-call response

- **WHEN** the Anthropic response message contains one or more `tool_use` content blocks
- **THEN** `generateResponseWithTools` SHALL return an `LLMResponse` whose `toolCalls` are populated from those blocks (id, name, input) and whose `finishReason` is `tool_calls`

#### Scenario: Response without tool_use is mapped to a text response

- **WHEN** the Anthropic response contains only text (no `tool_use`) content blocks
- **THEN** `generateResponseWithTools` SHALL return an `LLMResponse` with the concatenated text as `content` and `finishReason` `stop`

#### Scenario: Tool definitions are sent to the API and not ignored

- **WHEN** `generateResponseWithTools` is called with a non-empty list of `ToolDefinition`
- **THEN** each tool SHALL be translated into the SDK `Tool` format (name, description, input schema) and attached to the request, so the model can actually choose to call them

### Requirement: Multi-turn tool-use round-trips correctly

ClaudeService SHALL replay prior assistant tool calls and tool results across turns with correct Anthropic role mapping and `tool_use_id` linkage, so multi-turn tool-calling conversations complete.

#### Scenario: Tool results are sent as USER-role tool_result blocks linked by tool_use_id

- **WHEN** the message history contains a TOOL-role message (a tool result)
- **THEN** it SHALL be mapped to a USER-role message containing a `tool_result` block whose `tool_use_id` references the originating assistant `tool_use` id (the message's `toolCallId`)

#### Scenario: Prior assistant tool calls are replayed as tool_use blocks

- **WHEN** the message history contains an ASSISTANT message with tool calls
- **THEN** it SHALL be mapped to an ASSISTANT message whose content blocks include a `tool_use` block per prior call (id, name, reconstructed input)

#### Scenario: Consecutive tool results coalesce into one USER message

- **WHEN** the history contains multiple consecutive TOOL-role messages (multiple tools called in one turn)
- **THEN** they SHALL be coalesced into a single USER-role message carrying multiple `tool_result` blocks, preserving strict user/assistant alternation

### Requirement: System prompt is passed top-level on every turn

ClaudeService SHALL source the system prompt from the SYSTEM-role messages in the list and send it via the Anthropic top-level `system` parameter (never as a message), on every call — it SHALL NOT be dropped after the first call.

#### Scenario: System prompt sent as top-level system, not as a message

- **WHEN** the message list contains a SYSTEM-role message
- **THEN** its content SHALL be sent via the request's top-level `system` parameter and SHALL NOT appear as a `MessageParam` in the messages array

#### Scenario: System prompt retained on mid-conversation tool turns

- **WHEN** `generateResponseWithTools` is called on a later (non-first) turn that includes a SYSTEM message
- **THEN** the system prompt SHALL still be sent (not omitted), unlike the legacy first-call-only text-path behavior
