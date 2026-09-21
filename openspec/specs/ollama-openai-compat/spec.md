# ollama-openai-compat Specification

## Purpose

Ollama 通过统一 OpenAI 兼容提供者（`OpenAICompatibleProvider`，backend `openai_compat`）接入的路径——与 Claude / OpenAI / LangCat 等供应商并列。覆盖端点与鉴权配置（`ollama.api.base.url` 缺省 `http://localhost:11434/v1`）、模型 id 前缀剥除（`ollama:` → 裸模型名，保留 tag 自身冒号）、无 API 密钥时的占位 key，以及工具调用经 `OpenAICompatibleProvider.doGenerateWithTools` 的统一路径。

## Requirements

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
