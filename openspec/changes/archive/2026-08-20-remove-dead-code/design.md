## Context

`TODO/dead-code-audit-2026-08-18.md` 在旧分支 `v0.3.2-multi-instance-session-ipc-glm` 上产出一份死代码清单，但后续分支演进（`concurrency-safe-tool-batching` 等）已使清单漂移。本次通过 4 个并行 tracer（3 类 + 单方法 + AgentConfig getter + 追加扫描）逐一 grep 求证，把清单收敛为「已验证零引用」的确定性集合，并发现审计之外的 `tracing` 包死成员。

关键求证结论（写进 design 以固定决策依据）：
- **整类 3 个**均零 `new`、零 import、零反射/字符串查找、零注册。
- **13 个单方法**全部 CONFIRMED_DEAD；仅 `AgentRunSpec.getOptions()`（L62）与 `Builder.option()`（L130-136）行号较审计漂移。
- **AgentConfig**：审计的 `isConcurrentToolsEnabled()` 已不存在（并发改由 `Tool.isConcurrencySafe()` 驱动），无需处理；5 个 getter 的字段未被 `logConfiguration` 记录 → 字段可随 getter 删；4 个 getter 字段仍被 log → 仅删 getter 保字段。
- **`AiMenuCreator`** 实现 JMeter `MenuCreator` SPI，当前 dormant（无 `META-INF/services` 文件且 `**/META-INF/**` 被 gitignore），**不删**。
- **追加**（追加扫描 + 二次 spot-check）：整类 `KeyPropertyExtractor`；`tracing` 5 个（`getSampleRate`/`formatConversation`/`LLMRun` 三 getter）；`usage` 3 个（两个 `getLastRecordedUsage` + `AnthropicUsage.setClient`）；`selection` 2 个（`getListenerCount`/`isInstalled`）；`subagent` 1 个（`SubagentStatus.getTaskDescription`）；`utils` 若干（`JMeterElementManager.getElementDescription`、`SystemPrompt` 三个重载、`JMeterTreeUtils.convertHashTreeToTreeNodes`/`findNodeByName`/`findNodesByType`）；`model` 1 个（`MessageOptimizer.createOptimized`）；`tools` 2 类（`getToolDescriptions` 删、三个 `registerXxxTools` 改 private）。

## Goals / Non-Goals

**Goals:**
- 删除所有「经零引用验证」的死代码，不改变任何运行时行为。
- 以 `mvn clean test` 全绿为唯一正确性门禁。
- 修正文档（CLAUDE.md / AGENTS.md / README*）中对被删类的误挂描述。

**Non-Goals:**
- 不删 TEST_ONLY 成员（`Session.getAgeMinutes()`、`SessionManager.getActiveSessionCount()`、`InstanceContext` 实例方法、`IpcClient.postAgent` 3 参、`SubagentManager` 4 参构造）—— 收益低、连带改测试。
- 不删两个遗留 dev harness（`JMeterElementManager.main`、`JMeterElementManagerTest.main`）—— 单列后续。
- 不删 `AiMenuCreator`（SPI 实现）。
- 不动 `LangSmithClient.createRun(runId, ...)` 内部委托链重载 —— 属 coherent API 族，非明显孤儿。
- 不改 `ProviderSpec` / `useRawHttpClientOnly` 等已单列事项。

## Decisions

1. **「getter 死但字段仍被 logConfiguration 读取」→ 只删 getter、保留字段。**
   理由：`logConfiguration()`（AgentConfig L86-98）读取 10 个字段并打印，是启动期可观测性。删字段会连带改日志行为，超出死代码清理范围。被 log 的字段保留，仅移除无调用方的 getter。
   - 备选：连字段删并同步 logConfiguration —— 拒绝，改变可观测行为。

2. **「getter 死且字段未被 log」→ getter + 字段一并删。**
   `memoryConsolidationThreshold` / `sessionTimeout` / `maxSessions` / `filesystemToolsEnabled` / `websearchToolsEnabled` 既不 log 也无调用方（filesystem/websearch 开关实际由 `JMeterToolRegistry`/`AbstractFsTool`/`AbstractWebTool` 直接经 `AiConfig.getProperty` 读取，绕过 AgentConfig getter）。字段删除无任何副作用。

3. **删除顺序按「先整类、后级联、再 getter 批次」。**
   先删 3 个整类（消除 `normalizeToString`/元素类型映射重复），再删单方法（其中 `SubagentManager.pruneStatuses()` 连带删 `statusRetentionMs`/`maxCompletedStatuses` 字段及构造器赋值），最后 AgentConfig getter 批次与 tracing 包成员。每步删除后 `mvn clean test-compile` 验证，避免级联遗漏。

4. **同名 getter 陷阱用「引用计数 + 调用方签名」双确认。**
   `isFailOnToolError`/`getMaxIterations` 在 AgentConfig 与 AgentRunSpec 各有一份。删除前 grep 每个调用方，确认引用的是 `spec.…`/`AgentRunSpec` 版本而非 `config.…`/`AgentConfig` 版本（已核实 AgentRunner:299/434 均指向 AgentRunSpec）。

5. **tracing / usage / utils 追加项遵循同一判据：零调用方才删，被内部/活方法引用的字段保留。**
   `LLMRun` 三个 getter 零调用方即删，但 `active` 字段被 `complete()/error()` 读取须保留；`AnthropicUsage.setClient` 删而 `OpenAiUsage.setClient` 保留（后者被 `OpenAiService:99` 调用）；`SystemPrompt.get(String)/getDefault()/isUnifiedConfigured()` 删而 `SystemPrompt.get()` no-arg（3 个 service 调用）与 `getDefaultWithWorkspace`（ContextBuilder:382）保留；`JMeterToolRegistry` 三个 `registerXxxTools` 是「仅内部调用」，改 `private` 而非删除。

6. **Medium 置信度项（write-only 字段 / 仅 main 用）本次不删，留 Non-Goal。**
   `usage` 包 `client` 字段与 `initializeClient()` 的死构造（删除会改变 API-key 告警行为）、`getSupportedElementTypes()`（仅 `main()` 诊断用）、`Session.getAgeMinutes()`（仅自身 `toString()` 用）均不删，避免扩大行为变更面。

## Risks / Trade-offs

- [删错活符号（同名 getter / 反射装配）] → 每项删除前 grep 全部 `src/main`+`src/test` 调用方 + `Class.forName`/字符串查找；`AiMenuCreator` 等 SPI 类明确豁免；`mvn clean test` 兜底（接口收窄/引用悬空会编译失败）。
- [字段删除改变 `logConfiguration` 输出] → 仅删「未被 log 且无调用方」的字段；被 log 字段一律保留，getter 删除不影响日志（日志直读字段）。
- [`SubagentManager` 两字段删除影响状态清理语义] → `statusRetentionMs`/`maxCompletedStatuses` 的唯一读者是死方法 `pruneStatuses()`，删之无行为变化；保留构造器其余参数。
- [文档误挂引用造成 README 失真] → 同步 CLAUDE.md/AGENTS.md/README* 中被删类相关段落，避免文档指向不存在类。

## Migration Plan

无需数据迁移。纯源码删除 + 文档同步。回滚 = `git revert`（变更独立成 commit，不夹杂其他改动）。
