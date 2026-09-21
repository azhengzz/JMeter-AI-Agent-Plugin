## Why

MiniMax 的"思考开关"被错误地接到 `reasoning_split` 上，而 `reasoning_split` 只是**输出格式开关**，不控制思考开闭。当用户设 `reasoning_effort=none` 想关闭思考时，代码发送的是 `{"reasoning_split": false}`——这从未真正关掉思考，只是把推理内容从独立字段换成内联 `<think>` 标签塞回正文。真正的思考开关是 `thinking.type`（M3: `adaptive`/`disabled`，传 `enabled` 直接 HTTP 400；M2.x: `enabled`/`disabled`，但 `disabled` 被服务端忽略、思考关不掉）。

雪上加霜的是，MiniMax 被标记为 `rawHttpClientOnly(true)`，导致**纯文本聊天路径**（`makeRawHttpRequest`）完全不发送任何思考参数，与**工具路径**（SDK，`doGenerateWithTools`）行为不一致；而该纯文本路径被 `AgentRunner` 与 `CodeRefactorer` 实际调用，并非死代码。该 workaround 是否仍需要需一并确认。

## What Changes

- 用正确的 `thinking.type` 控制替换语义错位的 `reasoning_split` 思考样式。新增 MiniMax 专用思考样式（`minimax_thinking`），**按模型族**选择"开"值：M3 系列 → `adaptive`，M2.x 系列 → `enabled`；"关"值统一为 `disabled`。
- 思考开启时一并发送 `reasoning_split: true`（作为**输出格式开关**），使推理内容落到 `reasoning_content` 字段、被现有 reasoning 展示管线消费，而不是以 `<think>` 标签污染正文。
- 调查并消除 `rawHttpClientOnly(true)` 带来的纯文本/工具路径不一致：工具路径已证明 openai-java SDK 能正确容忍 MiniMax 的额外响应字段（经 `_additionalProperties` 捕获 `reasoning_content`）。据此评估是否移除该 workaround，让纯文本也走 SDK 以获得一致的思考控制；若经验证仍有不兼容字段，则把思考控制注入扩展到纯文本路径。
- 移除 `THINKING_STYLE_MAP` 中语义错误的 `"reasoning_split"` 条目，更新 `ProviderSpec` / `Builder` 中对思考样式的文档注释。
- 补充针对 MiniMax 思考 `extra_body` 产出的单元测试（当前 `OpenAICompatibleProviderTest` 未覆盖 `THINKING_STYLE_MAP` 的实际产出）。
- 酌情更新 `README.md` / `README_en.md` / `jmeter-ai-sample.properties` 中与 MiniMax 思考行为相关的说明。

## Capabilities

### New Capabilities
- `minimax-provider`: MiniMax 供应商接入——注册与配置、OpenAI 兼容请求构建（含**正确的 `thinking.type` 思考开关与 `reasoning_split` 输出格式开关**）、模型族感知（M3 vs M2.x 的"开"值差异）、统一的请求路径（消除 raw-HTTP 与 SDK 之间的思考参数不一致）、响应解析与 reasoning 展示接入。（MiniMax 此前无 spec，本次随修复一并建立。）

### Modified Capabilities
<!-- 无：openspec/specs/ 下原本不存在 minimax 相关 spec -->

## Impact

- **代码**：`ProviderRegistry.java`（MiniMax spec 的 `thinkingStyle`、`rawHttpClientOnly` 取舍）、`ProviderSpec.java`（思考样式文档与 `Builder.thinkingStyle` 入参说明）、`OpenAICompatibleProvider.java`（`THINKING_STYLE_MAP` 重构、思考注入逻辑、`makeRawHttpRequest` / `makeSdkRequest` 的思考参数一致性）。
- **测试**：`OpenAICompatibleProviderTest.java`（新增覆盖 MiniMax 思考 `extra_body` 的用例：M3 开=`adaptive`、M3 关=`disabled`、M2.x 开=`enabled`）。
- **文档**：README / README_en / jmeter-ai-sample.properties 中 MiniMax 思考相关说明（如必要）。
- **外部依赖**：无新增；行为 correctness 依赖 MiniMax `text-openai-api` 的 `thinking` 对象语义。
- **用户可见影响**：设 `reasoning_effort=none` 时，M3 系列**真正关闭**思考（正文不再混入 `<think>`）；M2.x 因 API 限制仍无法关闭思考，将作为已知限制记录在案。
