# Proposal

## Why

当前 AiService 链路完全非流式：Claude 与 OpenAI 兼容路径都等待完整 `LLMResponse` 才返回，用户在长回复/思考模型（DeepSeek、Kimi 等）的首个可见字符前只能盯着 loading 指示，首字延迟等于全响应延迟；reasoning 也没有流式通道。Nanobot 已验证该体验（正文增量回调 + 完整响应聚合），本变更把同等能力移植进插件，并修复 Nanobot 未解决的"已显示后重试导致正文重复"边界。

## What Changes

- **Provider 原生流式**：`ClaudeService` 与 `OpenAICompatibleProvider`（含 Ollama 兼容路径）接入 SDK 流式 API；provider 在 `generateResponseWithTools` 返回前收完整流并聚合出完整 `LLMResponse`（Agent Loop/持久化语义不变）；工具参数 JSON 分片仅在 provider 内拼合，收齐解析后才展示与执行；流读取响应中断/abort——chunk 间轮询 abort 谓词，取消路径主动关闭底层流句柄（服务端停滞也可及时退出）；流提前结束（缺终止标记）判为可重试失败而非成功。
- **流式事件契约**：每次 LLM 调用一个流段 `STREAM_STARTED → CONTENT_DELTA/REASONING_DELTA（可多次）→ STREAM_ENDED`，作为 `ProgressUpdate` 新类型挂现有 `TurnEvent` PROGRESS Kind（不新增 Kind）；`streamId` 稳定于一次逻辑调用，重试携带 `attemptSeq`；发射端约 100ms 节流且载荷带累计文本；`STREAM_ENDED` 携带终态状态（成功/失败/中止）与该段最新累计文本（全败/中止时 GUI 据此收敛显示）。
- **think 增量分段**：发射端累计缓冲 + 整串分段 + 差分 + **尾部 holdback**（扣留可能是半标签的后缀，如 `<thi`），双通道路由（Nanobot `_LoopHook` 骨架）；标签碎片不泄漏进正文。
- **失败重试与整段替换**：重试环在 `AgentRunner.callLLM`，`LLMResponse` 增结构化错误分类；已显示后失败允许重试（同一 streamId），成功以最新完整响应**整段替换**该流段（含思考），全败保留最新非空部分；去重事实源为粘滞标记"该逻辑调用是否曾发射过增量"（跨尝试/回退保留），非流式回退只允许发生在未发射任何增量的尝试上。
- **去重渲染**：抑制发生在**面板渲染端**——已流式呈现的回合不再把 THINKING 全文回放与 INTERMEDIATE_RESPONSE 全文渲染为重复内容；等价全文载荷照常向发起方程序化回调交付（**IPC partialContent 口径不变**）；`TURN_COMPLETED` 带 `streamedFinal` 标记，有标记则按最终流段身份原位重渲（查无卡片回退既有新增卡路径），不重复追加。
- **GUI 流式渲染**：当前卡累计原始 Markdown + EDT 合并重渲（50–100ms 量级、每面板一个合并器、流段终态使 pending 刷新失效防竞态）；流式期间已闭合的代码围栏/成形的表格**实时渲染为内嵌组件**（复制按钮/网格随流出现，未闭合尾部围栏按等宽纯文本直至闭合，流段结束无升级跳变——2026-11-08 修订，取代初版"流式纯文本降级"）；`ThinkingCard` 支持原地替换；`/new` 清屏取消 pending 刷新并弃缓冲。
- **范围边界**：仅 GUI 呈现流式；IPC/CLI 对外 HTTP 响应保持阻塞终态；子代理与 MemoryConsolidator 永不流式；不设 UI 配置项，但提供内部逃生开关（`jmeter.ai.streaming.enabled`，默认开启，网关兼容问题时现场禁用整体回退非流式）；不支持流式的服务静默回退（不伪装成流式）。

## Capabilities

### New Capabilities

- `llm-streaming`: LLM 流式输出端到端契约——provider 流式聚合（含 abort 关闭与提前 EOF 判失败）、流段事件协议（身份、节流载荷、终态状态与失败/中止序列）、think 增量分段（含尾部 holdback）、失败重试与整段替换（含粘滞抑制标记）、去重渲染与终答标记、GUI 合并渲染与卡片生命周期、流式 usage 语义、范围/回退与内部逃生开关。

### Modified Capabilities

- `agent-turn-events`: 「事件顺序保证」扩展——PROGRESS 内流段事件子序列（`STREAM_ENDED` 严格先于该调用的工具进度与回合终态），含失败/中止流段的收尾顺序。
- `chat-transcript-render`: 「Markdown 渲染进样式化文档」修改——流式进行中的渲染与终态一致：已闭合围栏代码块/成形表格即时构建内嵌组件，未闭合尾部围栏按等宽纯文本直至闭合；「思考内容折叠卡」修改——增原地替换/清空能力与"自动折叠 vs 用户手动折叠"两态。

## Impact

- **改动面**：`LlmCallOptions`（delta 消费者 + abort 谓词/流关闭句柄挂点，唯一 seam）、`ClaudeService`/`OpenAICompatibleProvider`（流式实现、错误分类、终止标记校验、流式调用 `maxRetries(0)`）、`LLMResponse`（`streamed`/retryable/状态码分类字段）、`AgentRunner.callLLM`（重试环）、`AgentHook`/`ProgressCallbackHookAdapter`（onStream 三件套改造；全文回放照常发射）、`ProgressUpdate`（STREAM_* 载荷类型）、`AgentLoop`（事件发射、取消路径关闭流句柄）、`AiChatPanel`/`TranscriptView`/`MessageCard`/`ThinkingCard`/`MarkdownRenderer`（渲染端抑制、合并器与竞态失效、per-turn 卡片映射、流式降级渲染）。`TracedAiService` **零改动**（已透传 options，流式天然穿透、每调用一条 run 不变）。
- **不动面**：会话持久化三阶段落盘与 RESET 恢复（完整消息是唯一持久化单元，增量仅显示层）、IPC HTTP 信封与 `TurnContentAccumulator` partialContent 语义、`processMessage` future 语义、子代理与 `MemoryConsolidator` 调用路径、`context-usage-indicator` 行为（USAGE 流末发一次、缺失不动环形指示器）。
- **测试面**：`GatedScriptAiService` 扩展 delta 脚本化能力（含迟到 delta、流中 Stop、停滞流）；`AgentRunnerLoopSequencingTest`、`EventParityTest`、`AiChatPanelIpcTurnPresenterTest`、`TranscriptViewTest`、`MarkdownRendererTest`、`ChatScrollerTest` 为主要回归锚。
- **文档面**：`AGENTS.md` 依赖表 anthropic-java 版本与 pom 对齐（2.53.0）；`TurnContentAccumulator` 类注释随流式落地同步更新。
- **参考实现**：Nanobot 源码 `D:\WorkHome\git\github\nanobot`（`agent/loop.py` `_LoopHook` 差分分段、`providers/base.py` `chat_stream`、`agent/runner.py` 重试与流分段）。
