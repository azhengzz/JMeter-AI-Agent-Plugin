## Why

Agent 目前仅支持 Claude (Anthropic)、OpenAI 与 Ollama 三种模型供应商，用户无法使用 LangCat（longcat.chat）的模型。LangCat 提供 OpenAI 兼容的 Chat Completions API（`POST https://api.longcat.chat/openai/v1/chat/completions`，`Authorization: Bearer <key>` 鉴权），当前模型 `LongCat-2.0`（128K 上下文、支持思考模式与 SSE 流式）。因其协议与现有 OpenAI 兼容管道一致，接入成本低、收益明确：用户可自由在 GUI 中选择 LangCat 模型驱动 Agent。

## What Changes

- **新增 `langcat` 供应商**：注册 LangCat 为新的 AI 服务供应商（服务类型 `langcat`），复用现有 OpenAI 兼容的请求构造 / 响应解析管道。
- **认证**：通过 `Authorization: Bearer <API_KEY>` 请求头发送，密钥由配置读取（对齐 `openai.api.key` 命名）。
- **配置项**：新增 `langcat.api.key`、`langcat.base.url`（默认 `https://api.longcat.chat/openai/v1`）、`langcat.default.model`（默认 `LongCat-2.0`）、`langcat.temperature`、`langcat.max.history.size` 等 JMeter 属性。
- **模型选择集成**：GUI 模型下拉框与默认模型解析支持 `langcat:LongCat-2.0` 前缀条目，与现有 `openai:` / `ollama:` 前缀一致。
- **思考模式**：支持请求体 `thinking: {"type":"enabled"|"disabled"}` 开关，并解析响应中的 `message.reasoning_content` 与 `usage.completion_tokens_details.reasoning_tokens`，沿用现有 reasoningContent 展示管线。
- **流式与非流式**：支持 `stream:true`（SSE）与默认非流式两种模式，行为与现有 OpenAI 供应商对齐。
- **明确排除（Non-Goal）**：不实现 LangCat 的 Anthropic 消息端点（`/anthropic/v1/messages`）；本轮仅接入 OpenAI 兼容路径，Anthropic 兼容端点留待后续。

## Capabilities

### New Capabilities

- `langcat-provider`: LangCat 模型供应商的接入契约——OpenAI 兼容 chat completions 端点的认证与 Base URL、请求构建（model / messages / max_tokens / temperature / thinking）、响应解析（含 `reasoning_content` 与 `usage.reasoning_tokens`）、流式与非流式模式、`langcat.*` 配置键、以及服务注册与 GUI 模型选择器集成。

### Modified Capabilities

<!-- openspec/specs/ 当前仅有 async-subagent，与本变更无需求交集。本变更为首次引入模型供应商相关 spec。 -->

## Impact

- **服务层**：`AiServiceFactory` 的供应商 switch 增加 `langcat` 分支；`ProviderRegistry` / `ProviderSpec` 注册 langcat 供应商（复用 OpenAI 兼容 provider 逻辑或新增轻量服务类）。
- **配置**：`AiConfig` 新增 `langcat.*` 属性读取；`jmeter-ai-sample.properties` 增加示例配置。
- **模型常量**：`Models` 增加 `LongCat-2.0` 常量。
- **GUI**：`AiChatPanel` 模型选择器（及任何枚举供应商的下拉列表）加入 `langcat:LongCat-2.0` 条目。
- **依赖**：无新外部依赖——LangCat 走 OpenAI 兼容协议，复用现有 HTTP/SDK 管道。
- **潜在破坏点**：供应商枚举的 switch/if 语句需全部覆盖 `langcat`，避免"未知供应商"静默回退。
