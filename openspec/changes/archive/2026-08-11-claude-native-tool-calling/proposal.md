## Why

刚完成的纯文本路径审计发现：`ClaudeService.generateResponseWithTools` 是个**纯文本降级**——它忽略 `tools` 参数、剥离 SYSTEM/TOOL 消息，直接调 `generateResponse(text)`（[ClaudeService.java:329-348](src/main/java/org/gitee/jmeter/ai/service/ClaudeService.java)），3 参版本（AgentRunner 实际调用的入口）又无条件委托给它。然而 `ClaudeService.supportsToolCalling()` 对 `claude-*` 模型返回 `true`。

后果：**当用户选中 Claude 模型时，Agent 主循环以为在做工具调用，实则 Claude 被静默降级成无工具的文本问答**——这恰好是 `require-toolcalling-support` 变更要消除的行为，只是默认配置（MiniMax/OpenAI-compat）掩盖了它。Anthropic 官方 SDK（anthropic-java）原生支持 tool use，所以这是可修的。本变更让 Claude 真正用上工具调用，堵上 `mandatory-toolcalling` 契约对 Claude 后端的漏洞，并为后续清理整片纯文本面扫清前提。

## What Changes

- 用 anthropic-java SDK 的原生 tool use 重写 `ClaudeService.generateResponseWithTools`：请求中携带 tool 定义，从响应的 `tool_use` 内容块解析出 `ToolCall`，并把 `tool_result` 消息回传以支持多轮工具调用往返。
- 移除/替换那条文本降级路径（2 参 `generateResponseWithTools` 及委托给它的 3 参版本），改为真正的工具调用实现。
- 建立 `Message`（含 ASSISTANT 的 tool_use、TOOL 角色的 tool_result）与 Anthropic 内容块格式之间的双向映射。
- `ClaudeService.supportsToolCalling()` 对 `claude-*` 模型继续返回 `true`——现在名副其实。

## Capabilities

### New Capabilities

- `claude-tool-calling`：ClaudeService 须通过 Anthropic SDK 执行**真正的**工具调用（不再静默文本降级），从而让 Claude/Anthropic 后端满足 `mandatory-toolcalling` 契约。

### Modified Capabilities

（无）当前无 Claude 相关 main spec；关联的 `mandatory-toolcalling` capability 仍处在一个未归档的变更 delta 中，待 `require-toolcalling-support` 归档后再考虑合并表述。

## Impact

- **代码**：`ClaudeService.java`——用 SDK 原生 tool use 取代文本降级；新增 tool/message 映射。涉及现有消息/请求构建路径（当前 `generateResponse` 走 SDK 文本调用）。
- **依赖**：anthropic-java 已从 2.18.0 升至 **2.53.0**（最新，2026-08-07）；现存代码对该版本编译通过。其 tool use API 将被首次真正使用。
- **测试**：新增 Anthropic tool/message 映射的单元测试；验证 Claude 现能返回 `tool_calls`。现有测试 mock 不受影响（它们不继承 ClaudeService）。
- **后续（Non-Goal）**：一旦 Claude 真正支持工具，纯文本面（`generateResponse` 1 参/2 参、`sendMessage`、`generateResponseStreaming`）对 Claude 也随之死亡，可在**独立变更**中一次性 sweep 删除。本变更只做「让 Claude 真用工具」，不做那场大清理。
