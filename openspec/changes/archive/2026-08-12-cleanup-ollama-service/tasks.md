# cleanup-ollama-service Tasks

## 1. 删除源码与测试文件

- [x] 1.1 删除 `src/main/java/org/gitee/jmeter/ai/service/OllamaAiService.java`
- [x] 1.2 删除 `src/main/java/org/gitee/jmeter/ai/gui/ChatUIManager.java`（全仓库零引用的遗留死类）
- [x] 1.3 删除 `src/main/java/org/gitee/jmeter/ai/utils/Models.java`（随 ChatUIManager 删除后 100% 死代码）
- [x] 1.4 删除 `src/test/java/org/gitee/jmeter/ai/service/OllamaToolCallingTest.java`（针对被删类的单测）

## 2. 清理 AiServiceFactory 死分支

- [x] 2.1 删除 `src/main/java/org/gitee/jmeter/ai/service/provider/AiServiceFactory.java` 第 6 行 `import org.gitee.jmeter.ai.service.OllamaAiService;`
- [x] 2.2 删除 `createServiceForSpec` 中永不命中的 `case "ollama" -> { ... }` 分支（第 158-165 行，含 `new OllamaAiService()` 与 `setModel` 调用）；backend 为 `openai_compat` 的 ollama 分支路由保持不变

## 3. 清理 AiChatPanel 接线

- [x] 3.1 删除 `src/main/java/org/gitee/jmeter/ai/gui/AiChatPanel.java` 第 39 行 `import org.gitee.jmeter.ai.service.OllamaAiService;`
- [x] 3.2 删除第 68 行字段 `private OllamaAiService ollamaService; // Keep for model loading`
- [x] 3.3 删除第 100 行 `ollamaService = new OllamaAiService();` 实例化
- [x] 3.4 模型选择监听器 switch（第 150-164 行）：`case "ollama"` 并入 openai_compat 分组 `case "openai", "deepseek", "zhipu", "moonshot", "minimax", "langcat", "ollama"`（传完整前缀 id），删除原 ollama 专用 case
- [x] 3.5 `updateRawServiceForModel` switch（第 606-616 行）：同上并入 openai_compat 分组
- [x] 3.6 `setModelForProvider` switch（第 635-648 行）：同上并入 openai_compat 分组

## 4. 移除 pom.xml 依赖

- [x] 4.1 删除 `pom.xml` 第 65-69 行 `io.github.ollama4j:ollama4j:1.1.6` 依赖块（含 `<dependency>` 开始标签与 `</dependency>` 结束标签）

## 5. 清理样例配置

- [x] 5.1 `jmeter-ai-sample.properties`：Ollama 配置段（第 71-86 行）删除 `ollama.enabled`、`ollama.thinking.mode`、`ollama.thinking.level`、`ollama.request.timeout.seconds` 及对应注释，保留 `ollama.api.base.url` 与其注释
- [x] 5.2 更新第 36 行 reasoning_effort 映射注释，去掉 "Ollama ThinkMode"（ollama4j 概念已移除；Ollama 经 openai_compat 与其他兼容提供者一致收 reasoning_effort）

## 6. 同步文档

- [x] 6.1 `AGENTS.md`：删除服务层 `OllamaAiService` 条目（第 206 行）、GUI 层 `ChatUIManager` 条目（第 225 行）、工具类 `Models` 条目（第 242 行）、依赖表 ollama4j 行（第 346 行）
- [x] 6.2 `CLAUDE.md`：同上 4 处（第 206 / 225 / 242 / 346 行）
- [x] 6.3 `README.md`：Ollama 配置表删除 `ollama.enabled`（第 274 行）、`ollama.host`（第 276 行）、`ollama.port`（第 277 行）、`ollama.thinking.mode`（第 278 行）、`ollama.thinking.level`（第 279 行）、`ollama.request.timeout.seconds`（第 280 行），保留 `ollama.api.base.url`（第 275 行）
- [x] 6.4 `README_en.md`：同上（第 277 / 279 / 280 / 281 / 282 / 283 行删除，保留第 278 行 `ollama.api.base.url`）

## 7. 验证

- [x] 7.1 `mvn clean package` 编译通过
- [x] 7.2 `mvn test` 全绿（不再有 `OllamaToolCallingTest`）
- [x] 7.3 grep 确认 `src/` 下无 `OllamaAiService` / `ChatUIManager` / `io.github.ollama4j` 残留引用
- [x] 7.4 确认 `openspec validate cleanup-ollama-service` 通过
