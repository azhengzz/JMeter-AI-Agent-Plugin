## REMOVED Requirements

### Requirement: MiniMax 思考控制在所有请求路径上行为一致

无论请求经工具路径（`generateResponseWithTools`，SDK）还是纯文本路径（`generateResponse(List<String>)`，被 AgentRunner 与 CodeRefactorer 调用）发出，MiniMax 的思考开关与 `reasoning_split` 输出格式注入 SHALL 行为一致。两条路径 MUST NOT 在是否发送思考参数、发送何种 `thinking.type` 取值上产生分歧。

#### Scenario: 纯文本路径与工具路径注入一致
- **WHEN** 同一模型与同一 `reasoning_effort` 配置下分别经纯文本路径与工具路径请求 MiniMax
- **THEN** 两条路径产生的请求 `extra_body` 在思考参数（`thinking.type`、`reasoning_split`）上完全一致

#### Scenario: 纯文本路径也提取推理内容
- **WHEN** MiniMax 思考开启且经纯文本路径（如 CodeRefactorer）请求
- **THEN** 响应中的 `reasoning_content` 被正确识别，正文 `content` 不被 `<think>` 标签污染

**Reason**: The plain-text `generateResponse(List<String>)` path is being removed entirely from the `AiService` contract (see `remove-dead-service-api`). `mandatory-toolcalling` already restricts the agent to the tool-calling path, and this change deletes the now-dead plain-text surface. The two scenarios above constrain a path (`generateResponse(List<String>)`) and a caller (`CodeRefactorer`, removed in `d65b5e2`) that no longer exist, so the "two paths must agree" requirement becomes vacuous.

**Migration**: No action required. With only the tool path (`generateResponseWithTools`) remaining, thinking-switch injection (`thinking.type`) and reasoning-output routing (`reasoning_split`) for MiniMax continue to be governed by the existing "MiniMax 思考开关经 thinking.type 控制" and "MiniMax 推理输出经 reasoning_split 路由到 reasoning_content" requirements, which already specify the surviving behavior including `reasoning_content` extraction on the tool path.
