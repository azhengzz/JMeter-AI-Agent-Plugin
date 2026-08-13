## Context

MiniMax 经 `OpenAICompatibleProvider`（OpenAI 兼容路径）接入。思考控制由两部分协作实现：

- `ProviderRegistry` 为每个供应商设置一个 `thinkingStyle` 字符串（MiniMax 当前为 `"reasoning_split"`）。
- `OpenAICompatibleProvider.THINKING_STYLE_MAP` 把该字符串映射为 `Function<Boolean, Map<String,Object>>`——输入"思考是否开启"布尔，输出注入 `extra_body` 的字典。

MiniMax 当前的映射是 `on -> {"reasoning_split": on}`。但依据官方 `text-openai-api` 文档（已在提案阶段抓取确认）：

- `reasoning_split` 是**输出格式开关**（`true` → 推理落到 `reasoning_content`/`reasoning_details`；`false`/省略 → 推理以内联 `<think>` 标签留在 `content`），**不控制思考开闭**。
- 思考开闭由 `thinking.type` 控制：**M3 系列**仅接受 `adaptive`（开）/ `disabled`（关），传 `enabled` 直接 HTTP 400；**M2.x 系列**仅接受 `enabled`/`disabled`，且 `disabled` 被服务端忽略（思考关不掉）。

因此当前实现语义错位：`reasoning_effort=none` 时发送 `{"reasoning_split": false}`，从未真正关掉思考，只是把推理换成内联标签污染正文。

此外 MiniMax 是 `rawHttpClientOnly(true)`：纯文本聊天（`generateResponse(List<String>)`）走 `makeRawHttpRequest`，**不发任何思考参数**、**只取 `content`**（不取 `reasoning_content`）；而工具路径（`doGenerateWithTools`）走 SDK，发 `reasoning_split` 并提取 `reasoning_content`。两条路径行为不一致。**注**：该纯文本路径的运行时入口经实证（见 D3）仅 `CodeRefactorer`（[CodeRefactorer.java:96](../../../../src/main/java/org/gitee/jmeter/ai/service/CodeRefactorer.java#L96)，JSR223 右键「AI 重构」）；`AgentRunner`（[AgentRunner.java:542](../../../../src/main/java/org/gitee/jmeter/ai/agent/run/AgentRunner.java#L542)）的纯文本分支因 `supportsToolCalling()` 恒真是死代码，主聊天链路始终走 `doGenerateWithTools`。

约束：
- 现有 4 个供应商共用 `THINKING_STYLE_MAP`（DeepSeek/Zhipu/Moonshot/LangCat 用 `thinking_type`、DashScope 系用 `enable_thinking`、MiniMax 用 `reasoning_split`），重构须不破坏其它供应商。
- `thinkingActive` / `reasoning_content` 回填等既有逻辑（`doGenerateWithTools` 第 382-409、444-458 行）须保持对 MiniMax 正确。

## Goals / Non-Goals

**Goals:**
- 让 MiniMax 的思考**真正可开关**：`reasoning_effort=none` → M3 真正关闭思考（无 `<think>` 污染）；思考开启时推理内容经 `reasoning_content` 进入既有展示管线。
- 消除 MiniMax 纯文本路径与工具路径在思考参数上的不一致。
- 保持 `THINKING_STYLE_MAP` 作为"供应商思考样式"的单点抽象，不退化成散落的 if/else。

**Non-Goals:**
- 不重构 MiniMax 之外的任何供应商思考逻辑。
- 不改变 `reasoning_effort`（`ReasoningEffort` 枚举）的取值集合与映射。
- 不处理不支持思考的老型号 MiniMax 模型（如 `abab*`/`MiniMax-Text-01`）——当前默认型号为 M2.7/M3，均支持思考；见 Open Questions。
- 不动 MiniMax base URL / api key 配置（与思考控制无关，预存的不一致不在本次范围）。

## Decisions

### D1：把 `THINKING_STYLE_MAP` 的值类型从 `Function<Boolean, Map>` 升级为 `BiFunction<String, Boolean, Map>`

MiniMax 的"开"值依赖**模型族**（M3=`adaptive`、M2.x=`enabled`），而现有签名只接受布尔、不知道模型名。

- 选型：把 map 值改为 `BiFunction<String modelName, Boolean on, Map<String,Object>>`。调用点（`doGenerateWithTools` 第 398-401 行）已有 `modelName` 在作用域内，改为 `styleBuilder.apply(modelName, thinkingEnabled)`。现有 3 个样式（`thinking_type`/`enable_thinking`/新增 `minimax_thinking`）的第一个参数直接忽略即可。
- 备选（否决）：在调用点对 MiniMax 写特殊分支——破坏 `THINKING_STYLE_MAP` 的单点抽象、与"mirrors Nanobot's `_THINKING_STYLE_MAP`"的注释相悖。
- 备选（否决）：给 MiniMax 硬编码 `adaptive` 当"开"——会让 M2.x 直接 HTTP 400。

### D2：新增 `minimax_thinking` 样式，删除语义错误的 `reasoning_split` 样式

`minimax_thinking` 的产出（伪码）：

```
(model, on) ->
  if on:
    onType = isM3Family(model) ? "adaptive" : "enabled"
    return { "thinking": {"type": onType}, "reasoning_split": true }
  else:
    return { "thinking": {"type": "disabled"} }
```

要点：
- **开**时同时发 `thinking.type`（真正的开关）与 `reasoning_split: true`（输出格式开关，把推理导向 `reasoning_content`，被 `doGenerateWithTools` 第 596 行提取、经 MessageProcessor 展示）。一并发送 `reasoning_split: true` 是**显式确定**的，不依赖"省略时默认值"（文档该处抓取被截断，留作假设——见 Risks）。
- **关**时只发 `thinking.type=disabled`；不发 `reasoning_split`（无推理可路由）。
- M3 vs M2.x 判定：剥离 provider 前缀、小写后的模型名**包含** `minimax-m3` → M3 系列（`adaptive`），否则按 M2.x 系列（`enabled`）。用子串而非前缀匹配，兼容第三方聚合供应商对 M3 的重命名（如 `acme-minimax-m3-pro`）。`minimax-m3` 子串足够特异，不会误判 M2.x/abab 系列。MiniMax 若发 M4 等需扩展。

`ProviderRegistry` 的 MiniMax spec 由 `.thinkingStyle("reasoning_split")` 改为 `.thinkingStyle("minimax_thinking")`。同步删除 `THINKING_STYLE_MAP` 中的 `"reasoning_split"` 条目，更新 `ProviderSpec` 字段注释与 `Builder.thinkingStyle` 入参文档。

### D3：`rawHttpClientOnly(true)` —— 已决策 = 结论 A（MiniMax 作用域统一）

证据：工具路径（`doGenerateWithTools`）**已**对 MiniMax 走 SDK，并经 `_additionalProperties` 成功容忍 MiniMax 的额外响应字段、提取 `reasoning_content`。实现期（2026-08-07）用 raw HTTP 直探 MiniMax API 进一步实证：响应为标准 OpenAI chat.completion JSON，仅多出 `reasoning_content` 字段（已被工具路径消费），无 SDK 不兼容字段 → **结论 A**。

**实际实现（MiniMax 作用域，最小爆炸半径）：**
- 保留 `.rawHttpClientOnly(true)` 作为**路由标记**（语义改为"纯文本走统一工具路径"，`ProviderSpec` 字段/javadoc 与 `OpenAICompatibleProvider` 字段注释已同步），不改动其它供应商的纯文本路径（仍走 `makeSdkRequest`）。
- 新增私有方法 `generatePlainTextViaToolPath(conversation, model)`：把 `List<String>`（user/assistant 交替）转为 `List<Message>`，以 `LlmCallOptions.builder().model(model).build()` 委托 `doGenerateWithTools(messages, null, null, opts)`，返回 `LLMResponse.content()`（错误则返回错误串）。MiniMax 纯文本由此复用既有思考注入与 `reasoning_content` 提取。
- 删除已孤儿的 `makeRawHttpRequest` + `parseResponseIgnoringUnknownFields` 及其 5 个反射测试，清理 6 个随之孤儿的 import（`JsonNode`、`IOException`、`URI`、`java.net.http.*`）。

**未采用的全量方案**：把"所有供应商"纯文本都改走 `doGenerateWithTools` 可彻底消除 `makeSdkRequest` 的思考注入缺失（DeepSeek/Zhipu 等纯文本目前也不发思考参数）。但**实现期调用链实证（2026-08-07）缩小了该 gap 的实际影响面**：`makeSdkRequest` 的唯一运行时入口是 JSR223 右键「AI 重构」（`CodeRefactorer:96`，且仅当默认供应商为 deepseek/zhipu/moonshot/langcat 等非 MiniMax 国产供应商时——openai/anthropic 默认拿到的是 `OpenAiService`/`ClaudeService`，不经 `OpenAICompatibleProvider`）；主 Agent 聊天链路（`AgentRunner.callLLM`）因 `OpenAICompatibleProvider.supportsToolCalling()` 硬编码 `true`，恒走 `doGenerateWithTools`，`AgentRunner:542` 的纯文本分支对它是**死代码**。故"思考注入缺失"只波及 JSR223 重构场景、不影响各供应商正常聊天——全量统一作为后续改动的优先级因此较低。回归面也更大（会改变这些供应商的纯文本行为），留作独立后续改动。本次仅满足 spec 的 MiniMax 路径一致性要求。

### D4：测试覆盖思考样式的实际产出

当前 `OpenAICompatibleProviderTest` 只覆盖纯逻辑方法（`stripProviderPrefix`、`parseResponseIgnoringUnknownFields` 等），未断言 `THINKING_STYLE_MAP` 的产出。新增用例（经反射调用样式 builder，或抽取一个包级静态方法后直测）：
- M3 + 开 → `{"thinking":{"type":"adaptive"},"reasoning_split":true}`
- M3 + 关 → `{"thinking":{"type":"disabled"}}`
- M2.x + 开 → `{"thinking":{"type":"enabled"},"reasoning_split":true}`
- M2.x + 关 → `{"thinking":{"type":"disabled"}}`

## Risks / Trade-offs

- **[风险] `reasoning_split` 省略时默认值未知**（文档抓取被截断）→ 缓解：思考开启时**始终显式发送 `reasoning_split: true`**，不依赖默认。**已实测确认（2026-08-07 raw HTTP 探测）**：M3 省略 `reasoning_split` 时，推理以内联 `<think>...</think>` 留在 `content`（如 `<think>The user is asking a simple math question.</think>\n\n2+2 = 4.`），`reasoning_content` 字段不存在 → 印证"思考开时显式发 `reasoning_split:true`"是必要的。
- **[风险] M3/M2.x 判定基于模型名子串启发式**，MiniMax 未来新型号可能不符 → 缓解：把判定收敛到单一 `isM3Family(model)` 私有方法，便于扩展；在 ProviderRegistry 注释里写明规则与依据文档。
- **[风险] M2.x 的 `disabled` 被服务端忽略**，用户设 `reasoning_effort=none` 时 M2.x 仍会思考 → 已知 API 限制，无法在本端修复；不把 M2.x 加入 `thinkingAlwaysOnModels`（那是为"传 disabled 会被 API 报错"的模型设计的，M2.x 是"接受但忽略"，语义不同）。在 README/spec 注明为已知限制。
- **[风险] MiniMax 纯文本改走工具路径后回退** → 缓解：raw HTTP 探测已实证 SDK 兼容（结论 A）；改动集中在 `generateResponse` 一个分支 + 新增 `generatePlainTextViaToolPath`，回滚经 git 即可（`makeRawHttpRequest` 已删，历史可查）。
- **[权衡] 升级 map 签名为 `BiFunction`** 触及所有样式条目（3 处）→ 改动小且机械，可读性反而更清晰（明确"样式可依赖模型名"）。

## Migration Plan

无数据/存储迁移。发布前手工验证（见 tasks，1/3 已由 raw HTTP 探测实证）：
1. M3：`reasoning_effort=medium` → 响应有独立 reasoning；`reasoning_effort=none` → 正文无 `<think>`、思考关闭。**（已实证：adaptive+reasoning_split:true → reasoning_content 出现、正文干净；disabled → 无推理）**
2. M2.x：`reasoning_effort=none` → 请求发出 `thinking.type=disabled`（服务端忽略属预期），正文行为与官方一致。
3. 纯文本路径（AgentRunner/CodeRefactorer 触发）经 `generatePlainTextViaToolPath` 与工具路径思考行为一致。

回滚：`git revert` 即可（`makeRawHttpRequest`/`parseResponseIgnoringUnknownFields` 已删，但其历史与原 `reasoning_split` 样式均可在 git 中找回）。

## Open Questions

- MiniMax 不支持思考的老型号（`abab*`/`MiniMax-Text-01`）若被用户选中且设了 `reasoning_effort`，发送 `thinking.type` 是否会被报错？是否需要给 MiniMax spec 加 `thinkingModels("minimax-m2","minimax-m3",...)` 白名单来 gate 思考注入（对齐 Moonshot 做法）？倾向：本次不加（默认型号 M2.7/M3 都支持），留作后续硬化。
- ~~D3 冒烟测试结论（移除 vs 保留 raw HTTP）~~ **已确认 = 结论 A（移除 + 统一）**。2026-08-07 经 raw HTTP 直探 MiniMax API（绕开 SDK/Jackson）：①M3 `thinking.type=adaptive`+`reasoning_split:true` → 200，`reasoning_content` 出现、正文无 `<think>`；②M3 无思考参数 → 推理内联 `<think>`；③M3 `thinking.type=enabled` → **HTTP 400**（`allowed: adaptive, disabled`，印证 M3/M2.x 分支必需）；④M3 `thinking.type=disabled` → 200，无推理、正文干净。响应为标准 OpenAI chat.completion JSON（仅多出 `reasoning_content` 字段，已被工具路径经 `_additionalProperties` 消费），无 SDK 不兼容字段 → `rawHttpClientOnly` 可移除。
- **已知项目缺陷（本次不修）**：`jackson-core` 2.16.1 与 `jackson-databind` 2.20.1 版本错配，导致 openai-java SDK 在反序列化时抛 `NoSuchMethodError: ParserMinimalBase.<init>(StreamReadConstraints)`——任何走 SDK 的**实时**调用都会触发（既有的 mock 测试不触发，故一直潜伏）。它阻断了基于 SDK 的冒烟测试（`MinimaxRawHttpSmokeTest`），故 D3 结论改由 raw HTTP 探测 + 生产工具路径佐证得出。修复需统一 Jackson 版本（如强制 `jackson-core`/`databind`/`annotations` 同版本），属独立变更。
