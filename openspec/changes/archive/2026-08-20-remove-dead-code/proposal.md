## Why

`TODO/dead-code-audit-2026-08-18.md` 提供了一批死代码清单，但审计基于旧分支 `v0.3.2-…-glm`，后续又合入了 `concurrency-safe-tool-batching` 等改动，清单存在漂移（例如审计列出的 `AgentConfig.isConcurrentToolsEnabled()` 已不存在）。死代码长期滞留会误导后续开发者（同名活/死 getter 陷阱、重复实现分叉），也放大重构时的搜索噪音。本变更通过 4 个并行 tracer 逐一 grep 求证 + 追加扫描，把清单收敛为「已验证可删」的确定性集合，并顺带发现审计之外的死代码（主要聚集在 `tracing` 包）。所有删除项均经 `src/main` + `src/test` 双向验证零引用（含反射/字符串查找），删除不改变任何运行时行为。

## What Changes

纯死代码删除，无功能/行为变更，无新增能力。

**1. 整类删除（4 个，均已 `.java` 全库零实例化确认）**
- `SaveMemoryTool`（`agent/memory`）—— 已带 `@deprecated` javadoc；其 `normalizeToString` 与活路径 `MemoryConsolidator.normalizeToString` 重复实现（差异仅 `.trim()`）。
- `TreeNavigationButtons`（`gui`）—— 无实例化。
- `ElementSuggestionManager`（`gui`）—— 无实例化；其 `formatElementType` 已被权威路径 `JMeterElementManager.getDefaultNameForElement` / `ElementRegistry.resolveDefaultName` 遮蔽，`mapToNormalizedElementType` 独有但不可达。
- `KeyPropertyExtractor`（`selection`）—— 追加扫描发现，全库零引用。

**2. 单方法删除（13 个，均已确认零调用方）**
`Session.addMessages(List)`、`SessionManager.getHistory(String,int)`、`SessionManager.clearSession(String)`、`MemoryStore.getMemoryFile()`、`MemoryStore.getHistoryFile()`、`MemoryStore()` 无参构造、`IpcServer.isStarted()`、`AgentRunSpec.getOptions()`、`AgentRunSpec.Builder.option(String,Object)`（连带 `options` 字段）、`SubagentManager.getRunningCount()`、`SubagentManager.pruneStatuses()`（连带 `statusRetentionMs`/`maxCompletedStatuses` 字段）、`CloseConsolidationCoordinator.resetForTest()`、`TracedAiService.wrap(AiService,LangSmithClient)` 2 参重载。

**3. AgentConfig 死 getter 删除（经 logConfiguration 字段读取逐一对账）**
- 删除 getter + 字段（5 个，均未被 logConfiguration 记录且无调用方）：`getMemoryConsolidationThreshold`/`getSessionTimeout`/`getMaxSessions`/`isFilesystemToolsEnabled`/`isWebsearchToolsEnabled`。
- 仅删除 getter、保留字段（4 个，字段仍被 logConfiguration 记录）：`getMaxIterations`/`getContextWindowTokens`/`isMemoryEnabled`/`getToolTimeoutMs`，以及 `isFailOnToolError`（⚠️ 同名活 getter 在 `AgentRunSpec`）。
- 修正审计：`isConcurrentToolsEnabled()` 已不存在，无需处理。

**4. 追加发现（审计之外，经追加扫描 + 二次 spot-check 确认）**
- 整类 1 个：`KeyPropertyExtractor`（`selection/`，javadoc 声称供 `SelectionContextBar` 用但后者未 import 它）。
- `tracing` 5 个：`LangSmithClient.getSampleRate()`、`LangSmithClient.formatConversation(List)`、`LLMRun.getRunId()/getName()/isActive()`。
- `usage` 3 个：`AnthropicUsage.getLastRecordedUsage()`、`OpenAiUsage.getLastRecordedUsage()`、`AnthropicUsage.setClient(AnthropicClient)`。
- `selection` 2 个：`SelectionTracker.getListenerCount()`、`SelectionTracker.isInstalled()`。
- `subagent` 1 个：`SubagentStatus.getTaskDescription()`。
- `utils` 若干：`JMeterElementManager.getElementDescription(String)`、`SystemPrompt.get(String)/getDefault()/isUnifiedConfigured()`、`JMeterTreeUtils.convertHashTreeToTreeNodes`、`JMeterTreeUtils.findNodeByName` 2/3 参、`JMeterTreeUtils.findNodesByType`。
- `model` 1 个：`MessageOptimizer.createOptimized(Message)`。
- `tools` 2 个：`JMeterToolRegistry.getToolDescriptions()`（删）；`registerFilesystemTools/registerWebTools/registerExecTools`（改为 `private`，仅内部调用非死代码）。

**5. 文档同步**
CLAUDE.md / AGENTS.md / README* 中列出的被删类（`SaveMemoryTool`、`TreeNavigationButtons`、`ElementSuggestionManager`）需同步移除误挂引用；`ai` `SaveMemoryTool` 的 CLAUDE.md 描述段落一并修正。

**不删（已确认非死代码）**：`AiMenuCreator`（实现 JMeter `MenuCreator` SPI，虽当前 dormant 亦保留）；所有 TEST_ONLY 成员、两个 `JMeterElementManager.main()`/`JMeterElementManagerTest.main()` 遗留 dev harness 本次不动（低收益，单列后续）。

## Capabilities

### New Capabilities

- `code-hygiene`: 死代码清理范围的确定性 —— 被删符号须经零引用/零实例化/零反射验证，并以 `mvn clean test` 全绿为编译门禁；同名活符号不得误删。

### Modified Capabilities

（无 —— 不改变任何既有 spec 的需求）

## Impact

- **代码**：删除 4 个类、13 个审计单方法、9 个 AgentConfig 死 getter（其中 5 个连字段删除）、约 20 个追加扫描发现的死成员，另将 3 个 `JMeterToolRegistry` 方法改为 private。集中在 `agent/memory`、`agent/session`、`agent/run`、`agent/subagent`、`agent/ipc`、`agent/tools`、`agent/model`、`gui`、`selection`、`usage`、`tracing`、`utils` 包。
- **运行时行为**：不变 —— 所有被删成员原本不可达。
- **文档**：CLAUDE.md、AGENTS.md、README（中英）中对被删类的描述需同步。
- **验证**：`mvn clean test` 全绿；编译是主门禁（删除后任何遗漏引用会编译失败）。