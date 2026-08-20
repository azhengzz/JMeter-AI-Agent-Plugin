# 实施任务

> 删除顺序：先整类 → 再级联单方法 → 再 getter 批次 → 最后追加扫描项。每步删除后 `mvn clean test-compile` 验证，最终 `mvn clean test` 兜底。

## 1. 整类删除（3 个，均已零实例化验证）

- [x] 1.1 删除 `agent/memory/SaveMemoryTool.java`（已带 `@deprecated`；其 `normalizeToString` 与 `MemoryConsolidator.normalizeToString` 重复）
- [x] 1.2 删除 `gui/TreeNavigationButtons.java`
- [x] 1.3 删除 `gui/ElementSuggestionManager.java`
- [x] 1.4 删除 `KeyPropertyExtractor.java`（`selection/`，追加扫描发现：无任何引用，javadoc 声称供 `SelectionContextBar` 用但后者未 import 它）
- [x] 1.5 grep 全库确认无 `SaveMemoryTool` / `TreeNavigationButtons` / `ElementSuggestionManager` / `KeyPropertyExtractor` 残留引用与 import

## 2. 单方法删除（13 个，均零调用方验证）

- [x] 2.1 `Session.addMessages(List)`（复数重载，保留单数 `addMessage`）
- [x] 2.2 `SessionManager.getHistory(String, int)`
- [x] 2.3 `SessionManager.clearSession(String)`（保留 `invalidate()`）
- [x] 2.4 `MemoryStore.getMemoryFile()`
- [x] 2.5 `MemoryStore.getHistoryFile()`
- [x] 2.6 `MemoryStore()` 无参构造（仅保留 `new MemoryStore(path)` 路径）
- [x] 2.7 `IpcServer.isStarted()`
- [x] 2.8 `AgentRunSpec.getOptions()` + `Builder.option(String, Object)`（连带删除 `options` 字段）
- [x] 2.9 `SubagentManager.getRunningCount()`
- [x] 2.10 `SubagentManager.pruneStatuses()`（连带删除 `statusRetentionMs` / `maxCompletedStatuses` 字段及构造器赋值）
- [x] 2.11 `CloseConsolidationCoordinator.resetForTest()`
- [x] 2.12 `TracedAiService.wrap(AiService, LangSmithClient)` 2 参重载（保留 1 参 `wrap`）

## 3. AgentConfig 死 getter 删除（按 logConfiguration 字段读取对账）

- [x] 3.1 getter + 字段一并删（5 个，未被 logConfiguration 记录）：`getMemoryConsolidationThreshold` / `getSessionTimeout` / `getMaxSessions` / `isFilesystemToolsEnabled` / `isWebsearchToolsEnabled`（后两者字段的开关实际由 `JMeterToolRegistry`/`AbstractFsTool`/`AbstractWebTool` 直接经 `AiConfig.getProperty` 读取）
- [x] 3.2 仅删 getter、保留字段（4 个，字段仍被 logConfiguration 记录）：`getMaxIterations` / `getContextWindowTokens` / `isMemoryEnabled` / `getToolTimeoutMs`
- [x] 3.3 仅删 getter（保留字段），⚠️ 避同名活 getter：`isFailOnToolError()`（活版在 `AgentRunSpec.isFailOnToolError()`，被 AgentRunner:434 引用）
- [x] 3.4 确认 `isConcurrentToolsEnabled()` 已不存在（审计清单漂移项），无需处理

## 4. 追加扫描项：tracing 包（5 个）

- [x] 4.1 `LangSmithClient.getSampleRate()`
- [x] 4.2 `LangSmithClient.formatConversation(List<String>)`
- [x] 4.3 `LangSmithClient.LLMRun.getRunId()`
- [x] 4.4 `LangSmithClient.LLMRun.getName()`
- [x] 4.5 `LangSmithClient.LLMRun.isActive()`（保留 `active` 字段，其被 `complete()`/`error()` 读取）

## 5. 追加扫描项：usage / selection / subagent / utils / model / tools

- [x] 5.1 `AnthropicUsage.getLastRecordedUsage()` + `OpenAiUsage.getLastRecordedUsage()`
- [x] 5.2 `AnthropicUsage.setClient(AnthropicClient)`（`OpenAiUsage.setClient` 被 OpenAiService:99 调用，保留）
- [x] 5.3 `SelectionTracker.getListenerCount()` / `SelectionTracker.isInstalled()`
- [x] 5.4 `SubagentStatus.getTaskDescription()`
- [x] 5.5 `JMeterElementManager.getElementDescription(String)`
- [x] 5.6 `SystemPrompt.get(String)` / `getDefault()` / `isUnifiedConfigured()`（保留 `SystemPrompt.get()` 与 `getDefaultWithWorkspace(...)`）
- [x] 5.7 `JMeterTreeUtils.convertHashTreeToTreeNodes(HashTree)`
- [x] 5.8 `JMeterTreeUtils.findNodeByName` 2 参 + `findNodeByName` 3 参（外部零调用；保留 4 参私有递归 helper 供内部）
- [x] 5.9 `JMeterTreeUtils.findNodesByType(JMeterTreeNode, String)`（保留 `findNodesByTypeAndGui`）
- [x] 5.10 `MessageOptimizer.createOptimized(Message)`（保留 `optimizeContent` / `shouldSkip`）
- [x] 5.11 `JMeterToolRegistry.getToolDescriptions(ToolRegistry)`（删除）
- [x] 5.12 `JMeterToolRegistry.registerFilesystemTools` / `registerWebTools` / `registerExecTools`（改为 `private` —— 仅 `registerDefaultTools` 内部调用，非死代码）

## 6. 文档同步（CLAUDE.md / AGENTS.md / README*）

- [x] 6.1 移除 CLAUDE.md 中被删类（`SaveMemoryTool` / `TreeNavigationButtons` / `ElementSuggestionManager`）的描述段落
- [x] 6.2 修正 CLAUDE.md memory 一节中对 `SaveMemoryTool` 锁语义的描述（已删类，勿再挂）
- [x] 6.3 AGENTS.md / README.md / README_en.md 同步移除被删类引用（若存在）
- [x] 6.4 grep 文档确认无指向已删符号的链接/引用残留

## 7. 验证

- [x] 7.1 全库 grep 兜底：每个被删符号名在 `src/main` + `src/test` 仅剩注释或零命中
- [x] 7.2 `mvn clean test` 全绿（编译是主门禁：任何遗漏引用/悬空符号会编译失败）
- [x] 7.3 确认同名活符号未被误删（`AgentConfig.isFailOnToolError` 删了但 `AgentRunSpec.isFailOnToolError` 仍在；`SystemPrompt.get()` no-arg 仍在被 3 个 service 调用）

## Non-Goals（不删，本次明确排除）

- TEST_ONLY 成员：`Session.getAgeMinutes()`、`SessionManager.getActiveSessionCount()`、`InstanceContext` 实例方法、`IpcClient.postAgent` 3 参、`SubagentManager` 4 参构造
- 遗留 dev harness：`JMeterElementManager.main()`、`JMeterElementManagerTest.main()`
- `AiMenuCreator`（JMeter `MenuCreator` SPI 实现，dormant 但保留）
- `LangSmithClient.createRun(runId, ...)` 内部委托链重载、`getSupportedElementTypes()`（仅 `main()` 诊断用）
- `usage` 包 `client` 字段 / `initializeClient()` 的 write-only/死构造（Medium 置信度，本次不删，避免改变 key 告警行为）