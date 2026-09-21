# cleanup-ollama-service

## Why

Ollama 的实际 LLM 调用早已走 OpenAI 兼容路径：`ProviderRegistry` 中 ollama 注册的是 `backend("openai_compat")`，由 `OpenAICompatibleProvider` 用 openai-java SDK 打到 `http://localhost:11434/v1`。而 `OllamaAiService`（ollama4j 原生实现）只被死代码引用：`AiServiceFactory` 的 `case "ollama"` 分支对当前注册表永不命中；模型枚举只发生在零调用方的 `ChatUIManager` / `Models` 里。整套 ollama4j 实现及其专属配置（thinking / timeout / host / port）均属冗余，README 已把 `ollama.host` 标记为废弃。清理后 Ollama 行为不变，代码与配置大幅精简。

## What Changes

- **BREAKING** 删除 `OllamaAiService` 类（ollama4j 原生集成）。Ollama 继续由 `OpenAICompatibleProvider`（openai_compat backend）承载，路由与请求行为不变。
- **BREAKING** 删除 `ChatUIManager`（全仓库零引用的遗留死类）、`Models`（随 ChatUIManager 删除后 100% 死代码）、`OllamaToolCallingTest`（针对被删类的单测）。
- 删除 `AiServiceFactory.createServiceForSpec` 中永不命中的 `case "ollama"` 分支及对应 import。
- 清理 `AiChatPanel`：删除 `ollamaService` 字段与实例化；3 处模型选择 switch 中把 `ollama` 并入 openai_compat 分组（传完整前缀 id）。
- 移除 `pom.xml` 的 `io.github.ollama4j:ollama4j` 依赖。
- 删除配置项 `ollama.thinking.mode`、`ollama.thinking.level`、`ollama.request.timeout.seconds`、`ollama.host`、`ollama.port`、`ollama.enabled`（均无任何读取方）；保留 `ollama.api.base.url`（openai_compat 路径实际使用）。
- 同步更新 `AGENTS.md` / `CLAUDE.md` / `README.md` / `README_en.md` 架构与配置文档。

## Capabilities

### New Capabilities
- `ollama-openai-compat`: 记录 Ollama 统一由 OpenAI 兼容路径（openai_compat backend → `OpenAICompatibleProvider`）承载这一能力边界；以 **REMOVED Requirements** 形式归档 ollama4j 原生实现（`OllamaAiService`）的移除，附 Reason 与 Migration。

### Modified Capabilities

（无 —— 现有 OpenSpec 能力（async-subagent / claude-tool-calling / langcat-provider / mandatory-toolcalling / minimax-provider / run-result-capture）均不涉及 Ollama 提供者或 ollama4j 集成）

## Impact

- **删除文件**：`src/main/java/org/gitee/jmeter/ai/service/OllamaAiService.java`、`src/main/java/org/gitee/jmeter/ai/gui/ChatUIManager.java`、`src/main/java/org/gitee/jmeter/ai/utils/Models.java`、`src/test/java/org/gitee/jmeter/ai/service/OllamaToolCallingTest.java`
- **修改文件**：`src/main/java/org/gitee/jmeter/ai/service/provider/AiServiceFactory.java`（死分支清理）、`src/main/java/org/gitee/jmeter/ai/gui/AiChatPanel.java`（ollamaService 清理）、`pom.xml`（ollama4j 依赖移除）、`jmeter-ai-sample.properties`（配置移除）、`AGENTS.md`、`CLAUDE.md`、`README.md`、`README_en.md`
- **不涉及**：`AiService` 接口、`OpenAICompatibleProvider`、`ProviderRegistry` / `ProviderSpec`（ollama spec 的 openai_compat 注册保持不变）、其他 AI provider、Agent 工具层
- **验证**：`mvn clean package` 编译通过；`mvn test` 全绿；grep 确认 `src/` 下无 `OllamaAiService` / `ChatUIManager` / `ollama4j` 残留
