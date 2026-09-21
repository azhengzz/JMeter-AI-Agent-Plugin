## Context

插件已有成熟的多供应商接入模式：`ProviderRegistry` 是供应商元数据的唯一真相源，已注册 deepseek / zhipu / moonshot / minimax 四个中国 OpenAI 兼容供应商（`backend("openai_compat")`，默认值），外加 openai / anthropic / ollama。`AiServiceFactory.createServiceForSpec` 按 `spec.getBackend()` 分发：`openai_compat` 走 `OpenAICompatibleProvider`（openai-java SDK，可配 `langcat.api.base.url` / `langcat.api.key`），`anthropic` 走 `ClaudeService`，`ollama` 走 `OllamaAiService`。`OpenAICompatibleProvider` 内建 `THINKING_STYLE_MAP`（deepseek/zhipu 使用的 `thinking_type` → `{"thinking":{"type":"enabled"|"disabled"}}` 注入 extra_body）与 `reasoning_content` 响应提取（L593-603）。

LangCat（longcat.chat）提供 OpenAI 兼容 Chat Completions API：`POST https://api.longcat.chat/openai/v1/chat/completions`，`Authorization: Bearer <key>` 鉴权，当前模型 `LongCat-2.0`（128K 上下文），请求支持 `thinking: {"type":"enabled"|"disabled"}` 开关，响应在 `message.reasoning_content` 返回思考内容——与 `thinking_type` 样式和现有 `reasoning_content` 提取完全吻合。

## Goals / Non-Goals

**Goals:**
- 以最小改动把 LangCat 注册为可选的第五个中国 OpenAI 兼容供应商。
- 支持思考模式（`thinking_type`）与 `reasoning_content` 展示，沿用现有管线。
- GUI 模型选择器可选中 `langcat:LongCat-2.0`。
- 配置项 `langcat.api.key` / `langcat.api.base.url` 可覆盖默认端点。

**Non-Goals:**
- 不接入 LangCat 的 Anthropic 消息端点（`/anthropic/v1/messages`）。
- 不为 LangCat 增加实时模型列表拉取（`Models.java` / `ChatUIManager` 下拉仍只列默认模型，与现有中国供应商一致）。
- 不重构 `AiChatPanel` 的开关式模型路由为统一的 `AiServiceFactory` 路由（超出本变更范围）。
- 不改 `OpenAiService` 本身（仅向其常量数组追加一个条目）。

## Decisions

**决策 1：通过 `ProviderSpec` 复用 `OpenAICompatibleProvider`，不新增服务类。**
`ProviderRegistry` 静态块新增一条 `langcat` 注册即可，`backend` 用默认 `openai_compat`，`AiServiceFactory` 的 openai_compat/default 分支自动接管，无需改 `AiServiceFactory`。
- 备选：新建 `LangCatService`。否决——LangCat 走标准 OpenAI 兼容协议，新建服务类是对既有 4 个中国供应商模式的重复。

**决策 2：`thinkingStyle("thinking_type")`。**
LangCat 的 `thinking: {"type":"enabled"|"disabled"}` 与 `THINKING_STYLE_MAP` 中 deepseek/zhipu 同款样式逐字节一致，直接复用，无需新增样式。

**决策 3：不配置 `thinkingModels`。**
`ProviderSpec.supportsThinking(model)` 在 `thinkingModels` 为空时对所有模型返回 true（`ProviderSpec.java:83-86`），与 deepseek 一致。思考在 `reasoning_effort` 非 none 时经 extra_body 注入（`OpenAICompatibleProvider.java:394-409`）。

**决策 4：`defaultApiBase = "https://api.longcat.chat/openai/v1"`。**
`OpenAICompatibleProvider` 构造器从 `langcat.api.base.url` 读取（缺省用 spec 默认值），请求路径追加 `/chat/completions`，得到官方端点。用户可通过属性覆盖。

**决策 5：`AiChatPanel` 三个路由开关各加 `"langcat"`。**
`AiChatPanel.java` 的 L155-169 / L610-620 / L639-652 三个 switch 目前把 `langcat:` 前缀落到 `default`（ClaudeService）分支——路由错误。把 `"langcat"` 并入 `case "openai","deepseek","zhipu","moonshot","minimax"`，使模型加载走 `openAiService.setModel(modelId)`。实际生成路径已由 `getAiServiceForCurrentModel` 经 `AiServiceFactory.createService` 正确处理（`AiChatPanel.java:581-596`）。
- 备选：重构为统一 `AiServiceFactory` 路由。否决——`getAiServiceForCurrentModel` 已走 factory，改三个 switch 是外科手术式最小改动。

**决策 6：`OpenAiService.OPENAI_COMPATIBLE_PROVIDERS` 追加 `"langcat"`。**
`OpenAiService.java:44-46` 的硬编码数组是模型加载路径识别 provider 前缀的依据；追加后 `extractProvider` 正确识别 `langcat:`，避免遗留路径把 LangCat 当默认 openai 处理。

**决策 7：零新依赖。**
LangCat 走 openai-java SDK（pom 已有 4.43.0），无新外部依赖。

## Risks / Trade-offs

- **思考 extra_body 仅注入工具路径**（`generateResponseWithTools`），`makeSdkRequest` 纯文本路径不注入 → 与 deepseek/zhipu 现状一致；Agent 主循环走工具路径，思考可用。纯文本直聊路径维持现状，不作为本变更修复项。
- **`OpenAiService` 若被某处用于生成且未识别 `langcat` 前缀 → 误路由** → 决策 6 追加常量数组以覆盖遗留路径。
- **LangCat 服务端字段/端点变动** → 端点可经 `langcat.api.base.url` 覆盖；错误经现有 `"Error: ..."` 字符串约定上浮，不静默吞掉。
- **`reasoning_effort` 取值 LangCat 若不接受（如 minimal/medium）** → 现有行为是透传、由服务端报错显式暴露配置问题（`OpenAICompatibleProvider.java:373-380` 注释），非静默 clamp。

## Migration Plan

- 部署：新增 `ProviderRegistry` 注册 + `AiChatPanel`/`OpenAiService` 路由 + `jmeter-ai-sample.properties` 配置样例，重启 GUI 生效。无数据迁移。
- 回滚：删除 `ProviderRegistry` 中的 langcat 注册（及属性样例），重启即回退；不影响其它供应商。
- 兼容性：`langcat` 为纯新增值，未改动任何既有 switch 的现有 case，对 openai/deepseek/zhipu/moonshot/minimax/anthropic/ollama 无行为影响。

## Open Questions

- 是否需要在 `Models.java` 增加 LangCat 模型列表拉取？——本变更明确 Non-Goal（下拉仍只列默认模型，与现有中国供应商一致），如用户需要可按后续变更处理。
- 默认 `jmeter.ai.reasoning.effort`（`GenerationSettings.fromConfig`）决定思考默认开关——属既有全局配置，不因 LangCat 引入而改变。
