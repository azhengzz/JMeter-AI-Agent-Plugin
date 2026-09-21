# cleanup-ollama-service Design

## Context

Ollama 的实际 LLM 调用早已走 OpenAI 兼容路径：`ProviderRegistry` 中 ollama 注册为 `backend("openai_compat")`（`defaultApiBase=http://localhost:11434/v1`、`envKey=ollama.api.key`），`AiServiceFactory.createService` 据此创建 `OpenAICompatibleProvider`，用 openai-java SDK 打到 Ollama 的 `/v1` OpenAI 兼容端点，模型 id（如 `ollama:qwen3.5`）经 `stripProviderPrefix` 剥为裸名。

`OllamaAiService`（ollama4j 原生实现）当前只被死代码引用：

- `AiServiceFactory.createServiceForSpec` 的 `case "ollama" -> new OllamaAiService()` 分支——注册表 backend 为 `openai_compat`，该分支永不命中。
- `ChatUIManager`——全仓库零引用的遗留孤儿 GUI 类，用 `ollamaService.listModels()` 枚举模型。
- `Models.getModelIds` / `getOllamaModelIds`——零调用方。

相关配置里 `ollama.thinking.mode` / `ollama.thinking.level` / `ollama.request.timeout.seconds` 仅被 `OllamaAiService` 读取；`ollama.host` / `ollama.port` / `ollama.enabled` 无任何读取方（README 已把 host/port 标为废弃）。`ollama.api.base.url` 是 openai_compat 路径实际使用的端点配置，必须保留。

## Goals / Non-Goals

**Goals:**
- 删除 `OllamaAiService.java`、`OllamaToolCallingTest.java`、`ChatUIManager.java`、`Models.java` 四个源文件。
- 清理 `AiServiceFactory` 永不命中的 `case "ollama"` 分支与 import；清理 `AiChatPanel` 的 `ollamaService` 字段、实例化与 3 处 `case "ollama"` switch，使代码无死代码、无编译残留。
- 移除 `pom.xml` 的 ollama4j 依赖与相关配置项（thinking/timeout/host/port/enabled）。
- 同步更新 `AGENTS.md` / `CLAUDE.md` / `README.md` / `README_en.md` 架构与配置文档。
- `mvn clean package` 编译通过，`mvn test` 全绿，grep 确认无残留引用。

**Non-Goals:**
- 不改动 Ollama 的实际请求路径：`ProviderRegistry` ollama spec、`OpenAICompatibleProvider`、`AiServiceFactory` 的 openai_compat 路由全部保持原样。
- 不改 `AiService` 接口、其他 AI provider、Agent 工具层。
- 不重构 `AiChatPanel` 中与 ollama 无关的既有逻辑。
- 不删除 README 中"Ollama 是受支持提供者"的一般性描述（Ollama 仍是 8 个提供者之一）。
- 不实现 Ollama 模型枚举的替代方案（走 `/v1/models`）——当前无此能力，属未来增强。

## Decisions

### D1. Ollama 继续走 openai_compat，不引入 ollama4j 替代
**决策**：保留 `ProviderRegistry` ollama spec 的 `backend("openai_compat")`、`envKey("ollama.api.key")`、`defaultApiBase("http://localhost:11434/v1")`；不改 `OpenAICompatibleProvider`；`AiServiceFactory` 仅删除永不命中的 `case "ollama"` 分支与对应 import。
**理由**：这是生产实际路径，用户明确要求"继续按当前 openai_compat 执行"；改动它引入回归风险，且与"清理冗余"的变更意图相悖。
**备选**：把 ollama 改为 `backend("ollama")` 让 `OllamaAiService` 真正生效 → 需消化 ollama4j 路径的已知问题（thinking 映射、工具 schema 扁平化、`num_ctx` 截断丢工具等），与本变更意图相反，弃。

### D2. 删除范围：OllamaAiService + 依赖它的死类
**决策**：删除 `OllamaAiService.java`、`OllamaToolCallingTest.java`、`ChatUIManager.java`（全仓库零引用）、`Models.java`（随 ChatUIManager 删除后 100% 死代码）。用户已确认该范围。
**理由**：`ChatUIManager` 是遗留孤儿 GUI 类，唯一引用来自自身；`Models` 的全部公开方法只被 ChatUIManager 调用（`getModelIds` / `getOllamaModelIds` 本身零调用）。保留它们需剥离 ollama 引用以维持编译，等于维护已知死代码。
**备选**：仅剥离 ollama 引用保留死类 → 保留两个无调用方的类，与"清理冗余"诉求相悖，弃。

### D3. AiChatPanel 清理方式
**决策**：
- 删除字段 `private OllamaAiService ollamaService;` 及构造函数中的 `ollamaService = new OllamaAiService();`。
- 3 处模型选择 switch（模型选择监听器、`updateRawServiceForModel`、`setModelForProvider`）中把 `case "ollama"` 并入 openai_compat 分组：`case "openai", "deepseek", "zhipu", "moonshot", "minimax", "langcat", "ollama"`，传完整前缀 id（`selectedModel` / `modelId`）。
**理由**：实际聊天路径由 `AiServiceFactory.createService` 生成 `OpenAICompatibleProvider` 并 `setModel`，AiChatPanel 里的 raw service 仅"为模型加载"；openai-java 的 `stripProviderPrefix` 会剥掉 `ollama:` 前缀，并入 openai_compat 分组语义一致，不再需要独立的 ollama raw 服务。
**备选**：仅删 ollamaService 相关行、让 ollama 落到 `default`(claude) 分支 → 会把模型名误设到 `claudeService`，语义错误，弃。

### D4. 配置清理范围
**决策**：删除 `ollama.thinking.mode`、`ollama.thinking.level`、`ollama.request.timeout.seconds`（仅被 `OllamaAiService` 读取）、`ollama.host` / `ollama.port` / `ollama.enabled`（无任何读取方）。保留 `ollama.api.base.url`（openai_compat 路径实际使用）与 `ollama.api.key`（ProviderSpec envKey）。
**理由**：无读取方的配置是纯噪音；README 已把 host/port 标为废弃，本变更将其真正移除，避免误导。
**备选**：保留 thinking/timeout 以备 ollama4j 路径将来复活 → 无计划、违反 YAGNI，弃。

### D5. 文档同步
**决策**：`AGENTS.md` / `CLAUDE.md` 删除服务层 `OllamaAiService` 条目、GUI 层 `ChatUIManager` 条目、工具类 `Models` 条目及依赖表 ollama4j 行；`README.md` / `README_en.md` 更新 Ollama 配置表（删 host/port/thinking/timeout/enabled，保留 api.base.url）。
**理由**：文档须与代码一致，否则误导后续维护者。

## Risks / Trade-offs

- **[Ollama 模型下拉枚举能力消失]** → 当前 `AiChatPanel.loadModelsInBackground` 只返回全局默认模型，从未实际枚举 Ollama 模型（枚举仅存在于死代码中）；删除后无功能损失。未来如需枚举，可基于 openai_compat 端点 `/v1/models` 实现，不在本变更范围。
- **[误删仍在使用的 import 导致编译失败]** → 逐一 grep 各文件内 import 的实际引用后再删；以 `mvn clean package` 兜底验证。
- **[README 已标废弃的 ollama.host/port 被删除]** → 属预期清理；仍按旧配置引用者读到默认值，无异常。
- **[删除 ChatUIManager 波及 Models 方法]** → 已确认 `Models` 全部公开方法无有效调用方，删除安全；`getAnthropicModels` / `getOpenAiModels` 也仅被 ChatUIManager 调用。

## Migration Plan

- 直接删除文件 + 清理接线，属向后不兼容的主动移除（**BREAKING**），无灰度需求。
- 回滚：`git revert` 即可恢复全部文件与配置；无状态持久化、无数据迁移。
- 配置迁移：原 `ollama.host` / `ollama.port` 用户本就应使用 `ollama.api.base.url`（README 已废弃旧键），无实际迁移负担。

## Open Questions

无。
