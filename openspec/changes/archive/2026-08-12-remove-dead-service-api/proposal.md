## Why

`mandatory-toolcalling` 已在契约层面禁用纯文本生成路径：Agent Runner **只**经 `generateResponseWithTools` 调用 LLM（AgentRunner 的 `callLLM`），报告 `supportsToolCalling() == false` 的服务会在首次 LLM 调用前中止运行，而非退化到纯文本。一次死代码追踪（5 个并行 tracer、72 次工具调用、交叉验证 + 在 AgentRunner.java:266/551 直接复核）确认纯文本 API 面**零生产调用方**：

- 全部 6 处单参 `generateResponse(List<String>)` 调用点与全部 3 处双参 `generateResponse(List<String>, String)` 调用点都不可达——它们构成 `1-arg ↔ 2-arg` 内部互相委托的闭环，外加已死的流式默认方法与孤儿 `sendMessage(String)`。
- `sendMessage(String)` 在 `src/main` 内零 `.sendMessage(` 调用点；`AiChatPanel.sendMessage()` 是无关的无参 GUI 方法。
- `generateResponseStreaming` / `generateResponseStreamingWithTools` / `supportsStreaming` 只有定义、零调用方。

维持这套 API 存活的唯一原因是 `AiService` 接口把两个 `generateResponse` 重载声明为 abstract。此外 `minimax-provider` spec 仍把纯文本路径当作活的（"被 AgentRunner 与 CodeRefactorer 调用"）——这已失效：`CodeRefactorer` 在 `d65b5e2` 中已移除，AgentRunner 也不再调用它。本次变更通过删除死的纯文本 API、修正失效 spec，完成 `mandatory-toolcalling` 架构的收尾。

## What Changes

- **BREAKING** —— 从 `AiService` 接口删除两个纯文本 abstract 方法：`generateResponse(List<String>)` 与 `generateResponse(List<String>, String)`。（仅对仓库外的实现者构成破坏；仓库内所有实现与测试 mock 都在本变更中一并更新，且无任何生产调用方。）
- 删除全部 4 个实现中的两个 `generateResponse` override：`OpenAICompatibleProvider`、`ClaudeService`、`OpenAiService`、`TracedAiService`。（各 override 因删除而变成孤儿的私有 helper——例如 `OpenAICompatibleProvider.makeSdkRequest` / `buildChatParams` / `generatePlainTextViaToolPath` 与 `useRawHttpClientOnly` 字段——在确认无其他引用后一并删除；详见 design。）
- 从 `AiService` 删除死的流式面：`generateResponseStreaming(List<String>, Consumer<String>)`、`generateResponseStreamingWithTools(...)`、`supportsStreaming()`；以及 `ClaudeService` 中对应的 override。
- 删除 `ClaudeService` 与 `OpenAiService` 中的孤儿 `sendMessage(String)`。
- 去掉 ~8 个 `AiService` 测试 mock 中不再有意义的 `@Override generateResponse(List<String>)`（以及出现处的 `generateResponse(List<String>, String)`）声明——仅为满足接口合规性的移除，不改动任何测试逻辑。
- 更新 `minimax-provider` spec：删除已失效的 "思考控制在所有请求路径上行为一致" 需求（及其两个 scenario），并从 Purpose 中去掉 "两条请求路径" 表述，因为纯文本路径已不存在。

## Capabilities

### New Capabilities

（无）

### Modified Capabilities

- `minimax-provider`："MiniMax 思考控制在所有请求路径上行为一致" 需求（及其两个 scenario）约束的是即将被删除的纯文本 `generateResponse(List<String>)` 路径。删除该需求；剩余工具路径的思考注入行为已由思考开关与 `reasoning_split` 相关需求覆盖。Purpose 中去掉 "两条请求路径行为一致性" 的表述。

## Impact

- **接口 / 公共 API**：`AiService` 减少两个 abstract 方法 + 三个流式 `default` 方法。任何外部 `AiService` 实现者须删除对应方法。仓库内无生产调用方受影响（已验证）。
- **代码**：`AiService.java`；实现 `OpenAICompatibleProvider.java`、`ClaudeService.java`、`OpenAiService.java`、`TracedAiService.java`；`src/test/java/org/gitee/jmeter/ai/agent/{command,subagent}/` 下 ~8 个测试 mock。删除后变成孤儿的私有 helper/字段一并清理。
- **Specs**：`minimax-provider`（delta —— 删除失效的双路径需求）。
- **运行时行为**：在任何活的路径上都不变（被删的面本就不可达）。`mandatory-toolcalling` spec 是本次删除的架构依据，其本身不变——它的 "无纯文本回退" 需求是被强化而非削弱。
- **验证**：`mvn clean test` 须全绿；编译是主门禁（接口收窄）。
