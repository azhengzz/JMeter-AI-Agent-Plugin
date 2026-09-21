## Context

`AiService` 是与供应商无关的 LLM 契约。它暴露三类生成方法：纯文本（`generateResponse(List)` / `generateResponse(List, String)`）、流式（`generateResponseStreaming`、`generateResponseStreamingWithTools`、`supportsStreaming`）与工具调用（`generateResponseWithTools`、`generateResponseWithForcedTool`）。

`mandatory-toolcalling` spec 让工具调用成为**唯一**的 agent 路径：`AgentRunner.callLLM`（AgentRunner.java:551）只调用 `generateResponseWithTools`；AgentRunner.java:266 的守卫在服务报告 `supportsToolCalling() == false` 时中止运行，而非退化到文本。死代码追踪确认纯文本与流式面**零生产调用方**：

- 6 处单参 + 3 处双参 `generateResponse` 调用点全部不可达（`1-arg ↔ 2-arg` 内部委托闭环 + 死的流式默认方法 + 孤儿 `sendMessage`）。
- `sendMessage(String)` 在 `src/main` 内无 `.sendMessage(` 调用方。
- 流式三件套只有定义、零调用方。

它们存活仅因接口把两个 `generateResponse` 重载声明为 abstract。`minimax-provider` spec 还把纯文本路径当作活的（引用了 `d65b5e2` 中已移除的 `CodeRefactorer`）。

相关方：任何仓库外实现 `AiService` 的代码（仓库内无）。仓库内实现者：`OpenAICompatibleProvider`（在用）、`ClaudeService` / `OpenAiService`（legacy，仍为模型加载与默认 loop 服务被实例化）、`TracedAiService`（追踪包装层）。

## Goals / Non-Goals

**Goals:**
- 从接口与所有实现中删除死的纯文本 `generateResponse` API。
- 从接口与唯一 override 它的实现（`ClaudeService`）中删除死的流式三件套。
- 删除孤儿 `sendMessage(String)`。
- 删除**仅因上述删除**而失去引用的私有 helper/字段（清理自己制造的孤儿，CLAUDE.md §3）。
- 修正 `minimax-provider` spec，使其不再把已删的纯文本路径当作活的。
- 任何活的路径运行时行为零变化；`mvn clean test` 保持全绿。

**Non-Goals:**
- **不**删 `generateResponseWithTools` / `generateResponseWithForcedTool` / `supportsToolCalling` —— 它们是活路径。
- **不**删 `ClaudeService` / `OpenAiService` 本身 —— 它们在生产中仍被实例化（模型加载；`ClaudeService` 作默认 loop 服务）。只删它们的死方法。
- **不**删 `ProviderSpec.isRawHttpClientOnly()` 访问器及其配置管线 —— 那是更深的 spec-schema 清理，上一轮审计明确留作后续；本变更只删 `OpenAICompatibleProvider.useRawHttpClientOnly` 字段（其唯一消费者）。`ProviderSpec.isRawHttpClientOnly()` 变成无消费者，作为后续项标注。
- **不**重构工具路径或其余活方法。

## Decisions

### Decision 1: 从接口删除，而非仅删实现
本变更的核心就是：不应强迫实现者背负死的 override。保留 abstract 方法会让每个实现者（及每个测试 mock）都必须提供死方法体。故两个 `generateResponse` 重载从 `AiService` 删除；流式三件套也从 `AiService` 删除（它们是 `default` 方法——删除不会破坏不 override 它们的实现；`ClaudeService` override 了它们，本变更一并更新）。

**备选方案**：保留接口、只删实现方法体 → 否决：留下一个零活调用方的 abstract 契约，且强迫到处写死 override。

### Decision 2: 接口收窄标记为 BREAKING（影响范围可控）
删除公共接口方法对仓库外 `AiService` 实现者构成破坏。仓库内无此类（所有实现 + mock 都在本变更中更新）。proposal 中标记为 BREAKING；不设废弃期，因为被删面在设计上本就运行时已死。

### Decision 3: OpenAICompatibleProvider 的孤立级联
删除 `generateResponse(List, String)`（按 `useRawHttpClientOnly` 分支到 `generatePlainTextViaToolPath` 或 `makeSdkRequest`）会使以下方法/字段成为孤儿，一并删除：

- `generatePlainTextViaToolPath` —— 唯一调用方是被删的分支。
- `makeSdkRequest` —— 唯一调用方是被删的另一分支。
- `buildChatParams` —— 唯一调用方是 `makeSdkRequest`。
- `extractErrorMessage` —— 唯一调用方是被删的 `generateResponse` catch 块。
- 字段 `useRawHttpClientOnly`（+ 构造器赋值 + 日志参数）—— 其唯一作用是路由现已删除的纯文本路径。

**保留**（仍被活路径 `doGenerateWithTools` 使用）：`stripProviderPrefix`、`toReasoningEffort`、`summarizeParams`。`generatePlainTextViaToolPath` 为纯文本提供的 MiniMax 思考注入，在纯文本消失后无关紧要；工具路径本就注入它。

### Decision 4: spec delta 用 REMOVED，而非 MODIFIED
`minimax-provider` 的 "MiniMax 思考控制在所有请求路径上行为一致" 需求存在仅因曾有两条路径。纯文本路径删除后该需求空洞，故 **REMOVED**（而非改写）。剩余的工具路径思考行为已由既有的 "思考开关经 thinking.type 控制" 与 "推理输出经 reasoning_split 路由" 需求覆盖。spec Purpose 叙述也去掉 "两条请求路径" 表述（直接编辑 spec，因为 delta 格式只作用于 Requirements，不覆盖 Purpose 散文）。

### Decision 5: 测试 mock —— 只删声明
8 个测试 mock 实现 `AiService` 纯为满足接口。其 `generateResponse(List<String>)` / `generateResponse(List<String>, String)` 方法体是桩（`return "ok"` 等）。接口方法消失后，`@Override` 声明移除；不改任何测试逻辑或断言。这些测试都不调用被删方法（它们测的是工具路径或 subagent 管线）。

## Risks / Trade-offs

- **[Java 不对未使用私有方法报错]** → 孤立级联（Decision 3）不会被 `javac` 抓到。**缓解**：逐文件 grep 确认每个被删私有 helper 无剩余调用方再删；依赖 `mvn clean compile` + IDE inspection。已列为显式任务步骤。
- **[对仓库外实现者 BREAKING]** → 仓库内无；方法本就运行时已死。**缓解**：标注 BREAKING；对已死面不设废弃期。
- **[丢失 minimax "纯文本路径 reasoning_content 提取" scenario]** → 该 scenario 基于 `CodeRefactorer`（`d65b5e2` 中移除）与纯文本路径。工具路径已提取 `reasoning_content`（由 reasoning_split 需求 + 其 "思考开启时推理走独立字段" scenario 覆盖）。**无行为损失**。
- **[`useRawHttpClientOnly` 字段删除后 `ProviderSpec.isRawHttpClientOnly()` 无消费者]** → 保留 ProviderSpec 访问器是保守选择（数据访问器代价低；删它级联到 spec builder + 样例 properties）。**缓解**：标注为后续项；无行为变化，因为该字段唯一作用是路由现已删除的路径。
- **[编辑顺序导致的瞬时编译破坏]** → 先删接口方法再更新 impl/mock 会瞬时破坏编译。**缓解**：作为单个原子变更；`mvn clean test` 是门禁。

## Migration Plan

单个变更，作为一个单元应用。变更内的编辑顺序无关（原子），但安全序列为：(1) 更新全部 4 个实现 + 8 个 mock 去掉 override，(2) 删接口方法 + 流式 default，(3) 删孤立 helper/字段，(4) 编辑 `minimax-provider` spec，(5) `mvn clean test`。回滚 = 还原本变更。

## Open Questions

- `ProviderSpec.isRawHttpClientOnly()` 及其 `ProviderSpec`/样例 properties 管线是否应在后续单独删除？**默认：是，作为独立变更** —— 为保持本变更的外科手术性、避免 ProviderSpec builder 动荡，这里不做。（继承自上一轮审计的延后项。）
