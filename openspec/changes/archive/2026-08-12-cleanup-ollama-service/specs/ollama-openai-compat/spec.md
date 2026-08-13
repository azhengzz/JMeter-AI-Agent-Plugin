# ollama-openai-compat Specification

## ADDED Requirements

### Requirement: Ollama 通过 OpenAI 兼容路径接入

系统 SHALL 通过统一 OpenAI 兼容提供者（`OpenAICompatibleProvider`，backend `openai_compat`）承载 Ollama 的 LLM 调用。请求 SHALL 使用 openai-java SDK 发送到 OpenAI 兼容端点 `ollama.api.base.url`（缺省 `http://localhost:11434/v1`）。模型 id 形如 `ollama:<model>`，发送前 SHALL 剥除 `ollama:` 前缀得到裸模型名。Ollama 不需要 API 密钥，`ollama.api.key` 为空时以占位 key 请求。

#### Scenario: 通过 openai_compat 调用 Ollama 聊天
- **WHEN** 用户选择模型 `ollama:qwen3.5` 并发送消息
- **THEN** Agent 使用 `OpenAICompatibleProvider` 向 `ollama.api.base.url`（缺省 `http://localhost:11434/v1`）发起 OpenAI 兼容聊天请求，请求体模型参数为裸模型名 `qwen3.5`

#### Scenario: 工具调用经统一路径
- **WHEN** Agent 需要对 Ollama 发起带工具定义的请求
- **THEN** 工具定义经 `OpenAICompatibleProvider.doGenerateWithTools` 转换为 OpenAI function tools 并通过 SDK 发送，工具调用结果在 Agent Loop 内回注

#### Scenario: 提供者前缀剥离保留模型 tag 冒号
- **WHEN** 模型 id 为 `ollama:qwen3.5:2b`（Ollama tag 自身含冒号）
- **THEN** 仅剥除 `ollama:` 前缀，请求模型参数保持 `qwen3.5:2b` 不被截断

## REMOVED Requirements

### Requirement: OllamaAiService（ollama4j 原生集成）

系统 SHALL 不再提供 `OllamaAiService`（基于 ollama4j 的原生 Ollama 集成），其 `generateResponse` / `generateResponseWithTools` / 思考模式（ThinkMode）/ 工具映射（`mapTools` / `mapToolCalls` / `appendMessage` / `buildLLMResponse`）行为一并移除。

**Reason**: Ollama 实际调用早已走 openai_compat 路径；`OllamaAiService` 仅被死代码引用（`AiServiceFactory` 永不命中的 `case "ollama"` 分支、零调用方的 `ChatUIManager` / `Models`），属冗余实现。

**Migration**: Ollama 用户无需改动——聊天与工具调用继续由 `OpenAICompatibleProvider` 承载。原 ollama4j 专属配置（`ollama.thinking.mode` / `ollama.thinking.level` / `ollama.request.timeout.seconds` / `ollama.host` / `ollama.port`）不再生效；端点统一使用 `ollama.api.base.url`。

#### Scenario: 不再引用 OllamaAiService
- **WHEN** 检查代码仓库
- **THEN** `src/` 下不存在对 `OllamaAiService` 的任何引用，且 `AiServiceFactory` 无 `case "ollama"` 分支

### Requirement: ollama4j 依赖与模型枚举

系统 SHALL 不再依赖 `io.github.ollama4j:ollama4j`，且 SHALL 不再通过 ollama4j 枚举本地 Ollama 模型（`OllamaAiService.listModels()` 及 `Models` / `ChatUIManager` 中的枚举逻辑随类删除）。

**Reason**: ollama4j 仅被本次删除的代码使用；模型枚举只存在于零调用方的死类中，移除无功能损失。

**Migration**: 当前聊天面板的模型下拉只展示全局默认模型，本不枚举 Ollama 模型；如需枚举可另行基于 OpenAI 兼容端点 `/v1/models` 实现（不在本变更范围）。

#### Scenario: 构建不再包含 ollama4j
- **WHEN** 检查 `pom.xml` 依赖
- **THEN** 不存在 `io.github.ollama4j:ollama4j` 依赖项

#### Scenario: 无 ollama4j 源码残留
- **WHEN** 检查代码仓库
- **THEN** `src/` 下无任何 `io.github.ollama4j` 导入或引用

### Requirement: ollama 专属配置项

系统 SHALL 不再读取或暴露配置项 `ollama.thinking.mode`、`ollama.thinking.level`、`ollama.request.timeout.seconds`、`ollama.host`、`ollama.port`、`ollama.enabled`，并从样例配置与文档中删除。

**Reason**: 前三个仅被已删除的 `OllamaAiService` 读取；后三个在代码中无任何读取方（`ollama.host` / `ollama.port` 已被 README 标记为废弃）。保留会误导用户以为这些开关仍生效。

**Migration**: 用户无需迁移——这些键本就未作用于 openai_compat 路径；Ollama 端点统一由 `ollama.api.base.url` 配置（缺省 `http://localhost:11434/v1`）。

#### Scenario: 样例配置不再包含 ollama 专属键
- **WHEN** 检查 `jmeter-ai-sample.properties`
- **THEN** 仅保留 `ollama.api.base.url`（及注释），不含 thinking / timeout / host / port / enabled 键
