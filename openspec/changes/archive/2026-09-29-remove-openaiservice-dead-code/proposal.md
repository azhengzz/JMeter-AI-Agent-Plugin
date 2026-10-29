# Proposal

## Why

`OpenAiService`（557 行、完整 openai-java SDK 实现）已被 `ProviderRegistry` + `OpenAICompatibleProvider` 架构整体取代：经 7-agent 双对抗验证器确认，它运行时是死代码——全库唯一构造点在 `AiChatPanel.java:146`（字段注释 "Keep for model loading" 已过时），整个生命周期只被 `setModel()` 簿记调用两次，HTTP 调用路径（`OpenAiService.java:420`）不可达。它与 `OpenAICompatibleProvider` 零代码共享，却名字像正主，读代码的人（含 AI 助手）查/改 OpenAI 请求逻辑极易改错文件。仓库已有同型先例：`OllamaAiService` 已随 `2026-08-12-cleanup-ollama-service` 以相同文件形态清除。

## What Changes

- **删除** `src/main/java/org/gitee/jmeter/ai/service/OpenAiService.java`（整文件，含私有静态 `OPENAI_COMPATIBLE_PROVIDERS` 与 `toReasoningEffort`）
- **摘除** `AiChatPanel` 中的 openAiService 接线：import（:48）、字段（:90）、构造（:146）、两处 `setModel` 调用体（:243、:1019），及两处过时注释改写（:240、:1006）。**保留** openai 族 case 标签（变空体/仅注释），防止 openai 兼容前缀落入 default 分支误触 `claudeService.setModel`；`updateRawServiceForModel` 方法本身与 `claudeService` 字段不动（后者是工厂失败回退的活 LLM 路径）
- **级联删除** `src/main/java/org/gitee/jmeter/ai/usage/OpenAiUsage.java`（整文件）：唯一生产调用方即 OpenAiService（:99 setClient、:425 recordUsage）；活路径的用量统计由 `OpenAICompatibleProvider` 内联提取进 `LLMResponse` usage map，从不经过它
- **级联删除** `AiConfig.getOpenAiApiKey()`：二级孤儿（唯一调用方 OpenAiUsage.java:38）；活路径的 `openai.api.key` 读取在 `OpenAICompatibleProvider.java:97` 经 `spec.getEnvKey()`
- **删除** `src/test/java/org/gitee/jmeter/ai/service/OpenAiServiceTest.java`（整文件：构造 + 反射专测被删类，覆盖类别在 `OpenAICompatibleProviderTest` 均有活路径镜像）
- **注释/文档清理**：`ClaudeService.java:179`（去掉 OpenAiService 提及）、`OpenAICompatibleProviderTest.java:32`（过时 `{@code OpenAiServiceTest}` 引用）、`AGENTS.md:230`（服务层条目）与 `AGENTS.md:278`（使用统计条目）
- 行为零变化：纯死代码移除，遵循 `code-hygiene` spec 的既有门禁（逐一求证 + `mvn clean test` 全绿 + 残留引用清零）

## Capabilities

纯死代码移除，无 spec 级行为变化，已在 `.openspec.yaml` 声明 `skip_specs: true`。`code-hygiene` spec 的既有需求约束本次实施但不被修改。

### New Capabilities

（无）

### Modified Capabilities

（无）

## Impact

- **代码**：`OpenAiService.java`（-557 行）、`OpenAiUsage.java`（-162 行）、`OpenAiServiceTest.java`（-214 行）、`AiChatPanel.java`（7 处编辑）、`AiConfig.java`（删 1 方法）、`ClaudeService.java`（注释）、`OpenAICompatibleProviderTest.java`（注释）
- **文档**：`AGENTS.md` 两行（CLAUDE.md 为 @AGENTS.md 指针，无需单独改）
- **不动**：`pom.xml`（openai-java SDK 仍被 `OpenAICompatibleProvider` 及其测试使用）、`openspec/changes/archive/**`（不可变历史记录）、`THINKING_STYLE_MAP` 及两个反射测试（属 `OpenAICompatibleProvider`，与被删类无关）、`AnthropicUsage`（`ClaudeService:443` 活路径）、`jmeter-ai-sample.properties`/README（零引用）
- 无配置迁移、无数据迁移、用户无感知；回滚 = git revert 单提交
