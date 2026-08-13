## ADDED Requirements

### Requirement: langcat 供应商注册与配置

系统 SHALL 将 LangCat 注册为可选的 AI 服务供应商（服务类型 `langcat`），与现有 Claude / OpenAI / Ollama 并列。`langcat` 服务的配置 MUST 从 JMeter 属性读取：`langcat.api.key`（API 密钥）、`langcat.base.url`（默认 `https://api.longcat.chat/openai/v1`）、`langcat.default.model`（默认 `LongCat-2.0`）、`langcat.temperature`、`langcat.max.history.size`。所有对 LangCat 的请求 MUST 以 `Authorization: Bearer <api_key>` 请求头鉴权。

#### Scenario: 配置齐全时创建 langcat 服务
- **WHEN** 用户选择 `langcat` 服务类型且已配置 `langcat.api.key`
- **THEN** 系统创建可用的 LangCat AiService 实例
- **AND** 该实例使用 `langcat.base.url` 作为端点前缀（未配置时使用默认值 `https://api.longcat.chat/openai/v1`）

#### Scenario: 缺少 API 密钥时给出清晰错误
- **WHEN** 用户选择 `langcat` 服务类型但 `langcat.api.key` 未配置
- **THEN** 系统不发起网络请求，返回可读的错误信息提示配置密钥
- **AND** 不静默回退到其它供应商

### Requirement: OpenAI 兼容 Chat Completions 请求构建

LangCat 服务 SHALL 以 OpenAI chat.completions 格式向 `POST {langcat.base.url}/chat/completions` 发送请求。请求体 MUST 包含：`model`（默认 `LongCat-2.0`）、`messages`（元素为 `{role: system|user|assistant, content: string}`，仅文本内容）、`max_tokens`（上限 `131072`）、`temperature`（范围 `0~1`）、`stream`（默认 `false`）。当思考模式开启时 MUST 附带 `thinking: {"type":"enabled"}`，关闭时 MUST 附带 `thinking: {"type":"disabled"}`。

#### Scenario: 基础非流式调用
- **WHEN** Agent 发送一次对话请求且未开启思考模式
- **THEN** 请求体包含 `model`、`messages`、`max_tokens`、`temperature`、`stream:false`
- **AND** 请求体包含 `thinking: {"type":"disabled"}`
- **AND** 请求以 `Authorization: Bearer <key>` 头发出

#### Scenario: 开启思考模式
- **WHEN** 用户/配置开启思考模式（`reasoning_effort` 非 none）
- **THEN** 请求体包含 `thinking: {"type":"enabled"}`
- **AND** 该请求经 `thinking_type` 样式注入 `extra_body`（复用现有 DeepSeek / Zhipu 路径）

### Requirement: 流式与非流式调用

LangCat 服务 SHALL 同时支持 `stream:true`（SSE）与默认非流式两种调用模式，行为与现有 `openai_compat` 供应商一致（经 openai-java SDK 的 streaming 路径与普通 create 路径）。流式模式 MUST 逐步下发增量内容；非流式 MUST 一次返回完整响应。

#### Scenario: 非流式调用
- **WHEN** Agent 以默认方式（`stream` 未开启）调用 LangCat
- **THEN** 服务通过 SDK 的 `chat().completions().create()` 一次返回完整响应

#### Scenario: 流式调用
- **WHEN** 调用方开启流式（`stream:true`）
- **THEN** 响应以 SSE 增量形式被消费，最终结果与全部增量拼接一致

### Requirement: 响应解析与 reasoning 展示

LangCat 服务 SHALL 解析 OpenAI chat.completion 响应：读取 `choices[0].message.content` 作为助手回复、`choices[0].message.reasoning_content` 作为思考内容、`usage.prompt_tokens` / `usage.completion_tokens` / `usage.total_tokens` 以及 `usage.completion_tokens_details.reasoning_tokens` 作为用量统计。当响应含 `reasoning_content` 时 MUST 将其接入现有 reasoningContent 结构化展示管线（MessageProcessor），使终端用户看到独立于正文的思考区块，而 LLM 消费的正文仅为 `content`。

#### Scenario: 非流式响应解析
- **WHEN** LangCat 返回标准 chat.completion 响应
- **THEN** 助手正文取自 `choices[0].message.content`
- **AND** 用量统计（含 `reasoning_tokens`）被记录到现有 usage 统计

#### Scenario: reasoning_content 走展示管线
- **WHEN** 响应中 `choices[0].message.reasoning_content` 非空
- **THEN** 该内容经现有 reasoningContent 展示管线渲染为独立思考区块
- **AND** 不影响作为 LLM 回注消息的正文内容

### Requirement: 模型选择器与默认模型集成

LangCat 模型 MUST 出现在 GUI 模型选择器（`AiChatPanel`）的下拉列表中，条目以 `langcat:` 前缀标识（如 `langcat:LongCat-2.0`），与现有 `openai:` / `ollama:` 前缀一致。默认模型解析 MUST 读取 `langcat.default.model`。

#### Scenario: 模型下拉出现 langcat 条目
- **WHEN** 用户打开 GUI 模型选择器
- **THEN** 下拉列表包含 `langcat:LongCat-2.0` 条目
- **AND** 选择该条目后 Agent 会话使用 `langcat` 服务与 `LongCat-2.0` 模型

#### Scenario: 供应商枚举完整覆盖
- **WHEN** 服务工厂 / 选择器按服务类型分支（switch/if）
- **THEN** `langcat` 在所有此类分支中被显式处理，不落入未知供应商的静默回退
