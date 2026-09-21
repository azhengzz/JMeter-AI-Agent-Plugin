## ADDED Requirements

### Requirement: MiniMax 供应商注册与配置

系统 SHALL 将 MiniMax 注册为可选的 AI 服务供应商（`minimax`），经 OpenAI 兼容 Chat Completions 协议访问 `POST {minimax.api.base.url}/chat/completions`，默认 base URL 为 `https://api.minimaxi.com/v1`。MiniMax 服务的配置 MUST 从 JMeter 属性读取：`minimax.api.key`（API 密钥）、`minimax.api.base.url`（端点前缀）。所有请求 MUST 以 `Authorization: Bearer <minimax.api.key>` 请求头鉴权。

#### Scenario: 配置齐全时创建 MiniMax 服务
- **WHEN** 用户选择 `minimax` 供应商且已配置 `minimax.api.key`
- **THEN** 系统创建可用的 MiniMax AiService 实例
- **AND** 该实例使用 `minimax.api.base.url` 作为端点前缀（未配置时使用默认 `https://api.minimaxi.com/v1`）

#### Scenario: 缺少 API 密钥时给出清晰错误
- **WHEN** 用户选择 `minimax` 供应商但 `minimax.api.key` 未配置
- **THEN** 系统不发起网络请求，返回可读的错误信息提示配置密钥
- **AND** 不静默回退到其它供应商

### Requirement: MiniMax 思考开关经 thinking.type 控制

系统 SHALL 通过 `extra_body` 中的 `thinking` 对象（`thinking.type`）控制 MiniMax 的思考开闭，而 MUST NOT 使用 `reasoning_split` 作为思考开闭开关。`thinking.type` 的取值 MUST 按模型族区分：

- **M3 系列**（剥离供应商前缀、小写后模型名以 `minimax-m3` 开头）：开启 → `adaptive`，关闭 → `disabled`。M3 MUST NOT 发送 `enabled`（会被服务端以 HTTP 400 拒绝）。
- **M2.x 系列**（其余 MiniMax 思考模型）：开启 → `enabled`，关闭 → `disabled`。

思考参数仅在配置了 `reasoning_effort`（且模型支持思考）时注入；未配置时 MUST 保留 MiniMax 服务端默认（不发送思考 `extra_body`）。

#### Scenario: M3 开启思考
- **WHEN** 选中 M3 系列模型且 `reasoning_effort` 配置为非 `none`
- **THEN** 请求 `extra_body` 包含 `thinking: {"type":"adaptive"}`
- **AND** 不包含 `thinking.type` 为 `enabled` 的取值

#### Scenario: M3 关闭思考真正生效
- **WHEN** 选中 M3 系列模型且 `reasoning_effort` 为 `none`
- **THEN** 请求 `extra_body` 包含 `thinking: {"type":"disabled"}`
- **AND** 响应正文不再混入 `<think>` 标签（思考被真正关闭，而非仅切换输出格式）

#### Scenario: M2.x 开启思考使用 enabled
- **WHEN** 选中 M2.x 系列模型且 `reasoning_effort` 配置为非 `none`
- **THEN** 请求 `extra_body` 包含 `thinking: {"type":"enabled"}`

#### Scenario: 未配置 reasoning_effort 时不发送思考参数
- **WHEN** 选中任意 MiniMax 思考模型但未配置 `reasoning_effort`
- **THEN** 请求 `extra_body` 不包含 `thinking` 对象
- **AND** MiniMax 服务端默认行为被保留

#### Scenario: M2.x 关闭思考为已知限制
- **WHEN** 选中 M2.x 系列模型且 `reasoning_effort` 为 `none`
- **THEN** 系统仍发送 `thinking: {"type":"disabled"}`
- **AND** 该限制（服务端忽略 disabled、思考无法关闭）作为已知 API 限制记录在用户文档，不视作本端缺陷

### Requirement: MiniMax 推理输出经 reasoning_split 路由到 reasoning_content

当 MiniMax 思考开启时，系统 SHALL 一并发送 `reasoning_split: true`（输出格式开关），使推理内容落到 `choices[0].message.reasoning_content` 字段，并被既有 reasoningContent 结构化展示管线（MessageProcessor）消费，而非以内联 `<think>` 标签污染正文 `content`。当思考关闭时系统 MUST NOT 发送 `reasoning_split`（无推理内容可路由）。`reasoning_split` MUST 仅作输出格式开关使用，不得用于控制思考开闭。

#### Scenario: 思考开启时推理走独立字段
- **WHEN** MiniMax 思考开启且响应含推理内容
- **THEN** 请求 `extra_body` 同时包含 `thinking.type`（开）与 `reasoning_split: true`
- **AND** 推理内容从 `choices[0].message.reasoning_content` 提取并接入展示管线
- **AND** 助手正文仅为 `content`，不含 `<think>` 标签

#### Scenario: 思考关闭时不发送 reasoning_split
- **WHEN** MiniMax 思考关闭
- **THEN** 请求 `extra_body` 仅含 `thinking: {"type":"disabled"}`，不含 `reasoning_split`

### Requirement: MiniMax 思考控制在所有请求路径上行为一致

无论请求经工具路径（`generateResponseWithTools`，SDK）还是纯文本路径（`generateResponse(List<String>)`，被 AgentRunner 与 CodeRefactorer 调用）发出，MiniMax 的思考开关与 `reasoning_split` 输出格式注入 SHALL 行为一致。两条路径 MUST NOT 在是否发送思考参数、发送何种 `thinking.type` 取值上产生分歧。

#### Scenario: 纯文本路径与工具路径注入一致
- **WHEN** 同一模型与同一 `reasoning_effort` 配置下分别经纯文本路径与工具路径请求 MiniMax
- **THEN** 两条路径产生的请求 `extra_body` 在思考参数（`thinking.type`、`reasoning_split`）上完全一致

#### Scenario: 纯文本路径也提取推理内容
- **WHEN** MiniMax 思考开启且经纯文本路径（如 CodeRefactorer）请求
- **THEN** 响应中的 `reasoning_content` 被正确识别，正文 `content` 不被 `<think>` 标签污染

### Requirement: MiniMax 响应解析与用量统计

MiniMax 服务 SHALL 解析 OpenAI chat.completion 响应：读取 `choices[0].message.content` 作为助手正文、`choices[0].message.reasoning_content` 作为思考内容、`usage.prompt_tokens` / `usage.completion_tokens` 作为用量统计。对 MiniMax 返回的、OpenAI schema 之外的额外字段，系统 MUST 容忍而不报错（经 SDK 的 additional properties 机制或等价方式忽略）。

#### Scenario: 解析标准响应
- **WHEN** MiniMax 返回标准 chat.completion 响应（含额外未知字段）
- **THEN** 助手正文取自 `choices[0].message.content`
- **AND** 额外未知字段被忽略，不引发反序列化错误
- **AND** 用量统计被记录

#### Scenario: 提取 reasoning_content
- **WHEN** 响应中 `choices[0].message.reasoning_content` 非空
- **THEN** 该字段被提取为思考内容并接入展示管线，与正文分离展示
