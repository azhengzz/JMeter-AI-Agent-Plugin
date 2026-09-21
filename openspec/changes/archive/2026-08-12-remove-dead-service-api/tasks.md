# 实施任务

## 1. AiService 接口：删除纯文本与流式 API

- [x] 1.1 删除 `AiService.generateResponse(List<String>)` 与 `generateResponse(List<String>, String)` 两个 abstract 声明（AiService.java:17-18）
- [x] 1.2 删除流式三件套 default 方法：`supportsStreaming()`（~105-107）、`generateResponseStreaming(...)`（~116-120）、`generateResponseStreamingWithTools(...)`（~130-136）
- [x] 1.3 删除因上述删除而孤立的 import（如 `java.util.function.Consumer`）—— 编译前 grep 确认无其他引用
- [x] 1.4 `mvn clean compile` 确认接口层无残留引用（此时实现类会报错，属预期，进入第 2-5 组）

## 2. OpenAICompatibleProvider：删除 override + 已确认的孤立级联

- [x] 2.1 删除 `generateResponse(List<String>)`（130-132）与 `generateResponse(List<String>, String)`（134-153）
- [x] 2.2 删除因此孤立的私有方法：`generatePlainTextViaToolPath`（155-175）、`makeSdkRequest`（177-227）、`buildChatParams`（~723）、`extractErrorMessage`（~756）
- [x] 2.3 删除字段 `useRawHttpClientOnly`（86）+ 构造器赋值（99）+ 日志参数（110）
- [x] 2.4 **保留**（工具路径 `doGenerateWithTools` 仍用）：`stripProviderPrefix`、`toReasoningEffort`、`summarizeParams` —— 删除前逐个 grep 确认仍有调用方
- [x] 2.5 grep 确认 `spec.isRawHttpClientOnly()` 在本类已无消费者（预期）；**不**改 `ProviderSpec`（见 Non-Goal，留作后续）

## 3. ClaudeService：删除纯文本 + 流式 override + sendMessage

- [x] 3.1 删除 `generateResponse(List<String>)`（~126）与 `generateResponse(List<String>, String)`（~286-298）
- [x] 3.2 删除 `supportsStreaming()` override（318-320）与 `generateResponseStreaming(...)` override（322-344）
- [x] 3.3 删除孤儿 `sendMessage(String)`（121-124）
- [x] 3.4 grep `ClaudeService` 私有方法，删除仅服务于上述被删方法的孤立 helper（Java 不会报未使用私有方法，须手工确认）

## 4. OpenAiService：删除纯文本 + sendMessage

- [x] 4.1 删除 `generateResponse(List<String>)`（241）与 `generateResponse(List<String>, String)`（418-435）
- [x] 4.2 删除孤儿 `sendMessage(String)`（236-239）
- [x] 4.3 grep `OpenAiService` 私有方法，删除因此孤立的 helper（手工确认）

## 5. TracedAiService：删除纯文本 override

- [x] 5.1 删除 `generateResponse(List<String>)`（47-49）与 `generateResponse(List<String>, String)`（52-…）两个 override（仅去掉声明，`generateResponseWithTools`/`generateResponseWithForcedTool` 保留）

## 6. 测试 mock：去掉 @Override 声明（不改逻辑）

- [x] 6.1 `NewCommandCancelTest`（2 个内部类：QuietAiService、BlockingAiService）
- [x] 6.2 `AgentRunnerToolCallingRequirementTest`
- [x] 6.3 `SubagentAnnounceTest`
- [x] 6.4 `SubagentCancellationTest`
- [x] 6.5 `SubagentExecutorDeadlockTest`
- [x] 6.6 `SubagentSessionPollutionTest`
- [x] 6.7 `SubagentTurnConfluenceIT`（2 个内部类）
- [x] 6.8 `SubagentTurnScopeTest`
- [x] 6.9 每个文件仅删除 `@Override ... generateResponse(List<String> ...)` 声明行（含单参与双参）；不动任何断言/桩返回值；grep 确认无测试**调用**这些方法（预期仅实现）

## 7. Spec：minimax-provider

- [x] 7.1 delta 已写于 `specs/minimax-provider/spec.md`（REMOVED "MiniMax 思考控制在所有请求路径上行为一致"）—— 归档时由 delta 同步到主 spec
- [x] 7.2 直接编辑主 spec `openspec/specs/minimax-provider/spec.md` Purpose（第 5 行）：删去 "两条请求路径（工具路径与纯文本路径）行为一致性" 表述（delta 格式不覆盖 Purpose 散文，须手工同步）

## 8. 验证

- [x] 8.1 全库 grep 兜底：`generateResponse(List` / `generateResponseStreaming` / `supportsStreaming` / `.sendMessage(` 在 `src/main` 应仅剩注释或零命中
- [x] 8.2 孤立私有方法 sweep：对 4 个实现类各跑一次未使用私有方法检查（IDE inspection 或 grep），确认第 2-5 组未漏删
- [x] 8.3 `mvn clean test` 全绿（编译是主门禁：接口收窄后任何漏改的实现/mock 会编译失败）
- [x] 8.4 确认 `generateResponseWithTools` / `generateResponseWithForcedTool` / `supportsToolCalling` 未被误删（工具路径完好）
