# Design

## Context

现状链路完全非流式：`AiService` 契约是同步返回完整 `LLMResponse`（`src/main/java/org/gitee/jmeter/ai/service/AiService.java:36-53`），`ClaudeService.callWithTools` 走 `client.messages().create`（`ClaudeService.java:210-274`），`OpenAICompatibleProvider` 走一次性 `chat.completions().create`（`OpenAICompatibleProvider.java:378-493`）。UI 唯一的"渐进"是 thinking 卡逐段追加（`TranscriptView.java:137-181`）；助手正文卡一经创建即不可变（`MessageCard.setMarkdownContent` 是全量重渲入口，`MessageCard.java:155-181`）。`AgentHook` 已预留 `wantsStreaming()/onStream(...)/onStreamEnd(...)` 三件套但零调用方（`AgentHook.java:14-55`，`TurnContentAccumulator.java:6-17` 注释明确记载）。参考实现：Nanobot `agent/loop.py:92-110`（`_LoopHook` 差分分段）、`providers/base.py:491-516`（`chat_stream` 契约、默认回退非流式后单 delta 发射）、`agent/runner.py:281-384`（多轮流分段）。

SDK 能力已核实（评审 javap 确认）：`anthropic-java 2.53.0` 提供 `MessageService.createStreaming` → `StreamResponse<RawMessageStreamEvent>`（非 beta `ThinkingDelta` 存在，AutoCloseable）；`openai-java 4.43.0` 提供 `ChatCompletionService.createStreaming` → `StreamResponse<ChatCompletionChunk>`、`ChatCompletionStreamOptions.includeUsage`、`Delta._additionalProperties()` 可读 `reasoning_content`。探针步骤的重心是**网关行为**（include_usage 兼容、thinking 增量实际到达形态），不是 SDK 是否有 API。注意 `AGENTS.md` 依赖表版本已与 pom 对齐（2.53.0）。

约束（既有契约必须保持）：回合事件 7-Kind 契约与订阅者线程契约（`openspec/specs/agent-turn-events/`）、转录渲染契约（`openspec/specs/chat-transcript-render/`）、持久化三阶段与 RESET 恢复、IPC HTTP 信封与 `TurnContentAccumulator` partialContent 口径、`processMessage` future 语义。

## Goals / Non-Goals

**Goals:**

- 首个正文/思考增量到达即上屏；首字延迟不再等于全响应延迟
- 流式与聚合双轨并存：事件通道走增量，Agent Loop/持久化只消费完整 `LLMResponse`
- 两条 provider 路径统一增量语义；不支持流式的服务静默回退；内部逃生开关可现场禁用
- 修复 Nanobot 未解决的"已显示后重试重复追加"：整段替换语义 + 粘滞抑制标记
- Stop 可及时打断在途/停滞流（含服务端不发 chunk 的场景）

**Non-Goals:**

- 不改 IPC/CLI 对外协议（HTTP 信封保持阻塞终态；不新增流式 wire 格式；partialContent 口径不变）
- 不改会话持久化格式（jsonl 无 delta 记录；reasoning 持久化/回放语义维持现状，含 `Session.getHistory` 剥离 reasoningContent 的既有行为）
- 不做工具参数流式展示、不做按 hash 的内嵌组件缓存、不设 UI 配置项
- 不处理流式中途的模型历史回传变化（增量不进上下文）

## Decisions

### D1 事件载体：ProgressUpdate 新类型挂 PROGRESS

`ProgressUpdate.Type` 新增 `STREAM_STARTED/CONTENT_DELTA/REASONING_DELTA/STREAM_ENDED`，载荷为结构化 StreamDelta（streamId + attemptSeq + 类型 + **累计文本** + STREAM_ENDED 时终态状态 SUCCESS/FAILED/ABORTED），经既有 `TurnEvent.Kind.PROGRESS` 交付。备选"新增 TurnEvent Kind"被否：需要改全部 `TurnSubscriber` 实现与 7-Kind spec 契约，而活回合集合/代数过滤（`AiChatPanel.java:939-1035`）天然复用于 PROGRESS 载荷。顺序由构造保证：provider 在 `generateResponseWithTools` 返回前收完整流（`AgentLoop.buildProgressHook` 的发射点在 runner 线程，`AgentLoop.java:706-727`），`STREAM_ENDED` 必然先于工具进度与终态发射；面板侧仍记录已结束（含 FAILED/ABORTED）streamId 丢弃迟到 delta（防御性）。

### D2 streamId 稳定于逻辑调用，重试带 attemptSeq

streamId 由 `AgentRunner.callLLM` 一次逻辑调用内铸造（与 turnId 同源的单调序即可，无需跨进程唯一）。重试是同一逻辑调用的再次尝试：不换 streamId，`STREAM_STARTED(attemptSeq>1)` 通知面板"该段将整体替换"。备选"每次尝试新 streamId"被否：与"重试成功整段替换同一段"的显示语义冲突，且需要跨段替换指令。GUI 流式状态按 `turnId → {thinkingCard, contentCard, currentStreamId, endedStreamIds, attemptSeq}` 键控；`TranscriptView` 现有单槽 `thinkingCard/activityGroup`（`TranscriptView.java:34-42`）同步改造为按回合映射，`finishReasoningIfRunning` 等收尾操作按回合执行——否则重叠活回合（模型切换退役 loop、加载指示交叠）下一个回合的正文会折叠另一个回合的思考卡。`STREAM_STARTED` 遇新 streamId：先把上一段卡片置终态，再为当前段建立卡片对；同 streamId + attemptSeq>1 复用同一对。

### D3 发射端节流 + 累计文本载荷

adapter 在 LLM 线程按流段节流（约 100ms，常数实现时可调 50–100ms）：每次发射携带该流段该通道的累计文本而非碎片。收益：事件量有界（~10 次/秒，保护同步内联派发路径与订阅者 O(µs) 契约）；渲染幂等（乱序/重复/懒领养均正确）；整段替换天然免费；失败/中止时 `STREAM_ENDED` 的累计载荷就是"最新非空部分"的交付载体。代价：载荷随答案增长，典型答案几 KB、每 100ms 一次，可忽略。

### D4 唯一 seam：LlmCallOptions 增量消费者（无 AiService 重载）

评审指出双 seam（流式重载 + options 消费者）会让包装层只覆写其一就静默失去流式。采纳单一 seam：`LlmCallOptions` 增 delta 消费者 + **abort 谓词（BooleanSupplier）+ 流关闭句柄注册点**（provider 开流时注册 `StreamResponse::close`，取消路径经 Turn/AgentRunSpec 持有的注册表调用）。`TracedAiService` **零改动**——它已在 supplier 中透传 options（`TracedAiService.java:48-64`），流式天然穿透、每调用一条 run 不变。判定链：`AgentRunner.callLLM` 依 `hook.wantsStreaming()`（`AgentHook.java:18-20` 默认 false）与内部逃生开关决定是否在 options 上挂消费者；provider 侧唯一判据 = **消费者是否存在**（存在即流式）。带回合身份的 hook 返回 true，`SubagentHook` 不覆写保持 false，`MemoryConsolidator` 直连不传 options——子代理与整合器天然不流式（原"TurnHandle 判定"表述与代码结构不符，handle 只存在于 `AgentLoop.buildProgressHook` 闭包，`AgentRunner` 取不到）。`GatedScriptAiService` 经同一挂点获得 delta 脚本化能力。

### D5 think 分段：累计缓冲 + 整串分段 + 差分 + 尾部扣留

adapter 维护内容通道累计缓冲；每次发射前对**完整累计串**做整串标签分段，与上次已发射状态差分 → 新增思考片段进 REASONING_DELTA、新增正文进 CONTENT_DELTA（Nanobot `agent/loop.py:92-110` 骨架）。**但不可直接复用 `TextUtils.splitThink`**（评审证实）：`THINK_SPAN` 正则要求完整 `<think` 字面量（`TextUtils.java:15`），累计串尾部的 `"<thi"` 不匹配、会作为正文泄漏；且 `addSegment` 的 strip+丢空段（`TextUtils.java:57-62`）会让前缀差分丢段首缩进。故流式分段器独立实现：① 分段函数**不裁剪**（保留空白，差分安全）；② 发射前对累计串尾部做 **holdback**——若最长后缀是 `<think`/`</think`（含属性形式）的前缀则暂扣，流段结束/中止时冲刷；③ 结构化 `reasoningContent` 增量直入思考通道（不经分段）。`showThinking=false` 在发射端抑制思考增量（分段照常运转），以流段为粒度快照。备选"GUI 累计重拆"被否：两层会对"这是不是思考"产生瞬时分歧。

### D6 重试环、错误分类与流的失败判定

重试环在 `AgentRunner.callLLM`（现状 `callLLM` 把一切异常吞成 `LLMResponse.error`，`AgentRunner.java:849-867`；两 provider 的 catch 也都压平成人读字符串，`OpenAICompatibleProvider.java:497-510`）。`LLMResponse` 增最小分类字段（`retryable` + 状态码/错误类别），由各 provider 在 catch 处从 SDK 异常填充（OpenAI/Anthropic SDK 均有 `statusCode()`；`SocketTimeoutException` 是 `InterruptedIOException` 子类，须**先于** `isCausedByInterrupt` 判别归网络类）。策略：429/5xx/网络/超时/**提前 EOF（流结束但缺终止标记：Anthropic 无 message_stop、OpenAI 无 finish_reason）**可重试；4xx 参数/鉴权、context-length、`"Interrupted"`、已置 abort 不重试；总尝试 3 次、指数退避（1s/2s 量级），退避为 abort/deadline 感知的分片轮询（对齐 `MemoryStore.lockLongTermMemory` 的既有模式）；429 可选读取 `Retry-After`。**SDK 内部重试不可计入外部预算**（外部无法感知）：流式调用对 SDK client 设 `maxRetries(0)`，外层环是唯一重试控制点。

### D7 重试显示：屏外缓冲 + 原地复用组件对 + 折叠两态 + 粘滞标记

重试期间屏幕保留上一次部分内容；重试尝试（attemptSeq>1）的增量进屏外缓冲不实时显示。成功：以完整 `LLMResponse` 整段替换该流段——原地复用同一对组件（`ThinkingCard` 新增 `replaceText/clear`，正文卡走 `setMarkdownContent` 全量重写），卡片位置不变。全部尝试失败：以最后一次 `STREAM_ENDED(FAILED)` 携带的累计载荷收敛屏幕（最新非空部分），走既有错误路径。**抑制/终答的事实源是粘滞标记 `emittedAnyDelta`**（该逻辑调用一旦向消费者发射过任何增量即置位，跨尝试、跨回退保留）——防"已显示后某次尝试走非流式路径成功"造成 `streamed=false` 双渲染；并约束**非流式回退只允许发生在未发射增量的尝试上**（已发射后的失败只能同 streamId 重试或失败收尾）。`ThinkingCard` 折叠态拆两 bit：自动折叠（首条正文触发）与用户手动折叠/展开；整段替换可重新展开自动折叠卡，永不动用户手动选择（`finish()` 现无条件 `setCollapsed(true)`，`ThinkingCard.java:99-119`，须拆分 API）。

### D8 去重在渲染端，终答按流段身份查卡

评审证实发射端抑制与 IPC 冲突：adapter 的单一 callback 同时喂"发起方回调 + TurnEvent 派发"（`AgentLoop.java:715-727`），而 `TurnContentAccumulator` 只消费 INTERMEDIATE_RESPONSE（`TurnContentAccumulator.java:31`，`IpcServer.java:320-328` 挂载）——发射端抑制会让 IPC 取消/超时回执的 partialContent 系统性变空。故**抑制下移到渲染消费者**：adapter 照常发射 THINKING/INTERMEDIATE 全文（每工具轮一次，事件量可忽略）；`AiChatPanel` 按回合流式状态（粘滞标记沿事件链透传）忽略已流式回合的全文回放；未发射增量的回合（回退路径）照常渲染。终态：完成事件带 `streamedFinal`（含最终 streamId）；面板**按 streamId 查卡**——查到则原位以权威 `finalContent` 重渲（始终覆盖，不做文本比对/增量拼接；goal 后缀、stripThink 差异自动吸收）；**查无（终答剥标签后为空、纯思考流正文卡未建、面板晚领养）回退 `processAiResponse` 既有路径**（含空内容占位）；无标记照旧新增卡。

### D9 GUI 合并器、终态竞态与清屏失效

每面板一个合并器（非每流）：per-stream 脏标志 + 单个自重投 EDT flush runnable，一次 EDT 通道重渲全部脏流段；计时器经工厂注入（GoalStrip `StripTimerFactory` 先例，`GoalStrip.java:45-70`），测试可手动步进并断言 fire 后自停。**终态竞态防御**（评审证实）：`STREAM_ENDED` 与 `streamedFinal` 覆盖到达时立即同步冲刷并把该流段标记 finalized；flush runnable 执行前校验流段未 finalized——否则丢弃，防止排队的旧 flush 在权威终答之后用旧累计文本回写。缓冲与 flush 归 `TranscriptView`：`clearTranscript`（`TranscriptView.java:211-228`）在同一 EDT 步骤取消 pending flush 并清空缓冲；`/new` 属清屏（advanceRenderEpoch → clearTranscript），面板代数过滤作双保险。流式正文卡的滚动遵循既有 capture-before-mutation 协议（`ChatScroller`），无需新模式。

### D10 流式渲染：代码块/表格实时渲染（2026-11-08 修订，推翻初版降级）

初版（已废弃）：流式 tick 期间围栏/表格降级纯文本、终态一次性构建内嵌组件，以消除每 tick 重建 JPanel/滚动面板的 EDT 开销。修订（用户实测后拍板）：流式 tick 与终态渲染走**同一路径**——已闭合的围栏代码块与成形的表格（表头+分隔行+已到行）即时渲染为内嵌组件，复制按钮/网格随流出现，流段结束无"升级跳变"；未闭合的尾部围栏仍按纯文本逐行呈现（闭合即升级，`MarkdownRenderer.java:41-42` 的闭包正则本就如此）。理由：终态升级瞬间整卡重排的视觉跳变体验差；参考项目 jmeter-ai（qainsights）的 Swing 转录层其实是更彻底的降级（流式裸文本追加 + 终态一次解析），但其内嵌终端模式（JediTerm 跑 CLI）给用户的观感是全程实时渲染——本修订在既有 Swing 架构内以"逐 tick 全量重渲 + 组件即时构建"逼近该观感。代价：每 tick 重建内嵌组件（典型回复 1–5 个块 × 10fps，JPanel 创建为微秒级，可接受）；`fitEmbeddedScrollers` 改为每 tick 渲染后执行（新嵌入滚动面板即时获得宽度钳制）。若实测长回复下 EDT 压力可感知，逃生口是 D13 总开关（整体回退非流式），不再恢复降级档。

### D11 卡片生命周期时序

加载指示保留至**首条可见增量**（任一通道）——`STREAM_STARTED` 懒发射，不早于首个增量（评审证实 `handleProgressNow` 对任何非 USAGE 事件先移 loading，`AiChatPanel.java:1566-1573`：若流段开始信号在请求时发射而首 token 需 10s，loading 会在整个等待期消失，体验反而劣化）。正文卡懒建于首条**剥标签后非空**的 CONTENT_DELTA（纯思考流无空卡）；同流段首条可见正文触发既有"折叠思考卡 + 收尾活动组"不变式（今日由 `addAssistantMarkdown` 隐式保证，`TranscriptView.java:64-72`；流式卡走 setMarkdownContent 须显式接线），其后新到思考增量开新卡。`STREAM_ENDED` 只做该段终态渲染（最终冲刷；组件已随流构建，无升级步骤）。`handleProgressNow` 的 switch 为 STREAM_* 类型加**显式分支**（`default ->` 会把未知类型渲染成系统行）。

### D12 外围：追踪零改动、usage 细则、测试接缝

追踪：经 D4 的 options 透传自然携带流式，`TracedAiService` 不改（每调用一条 run；无独立流式入口即无"漏覆写"降级面）。usage：流式请求带 `stream_options.include_usage`；**网关因该选项 400 时去掉选项重试（保持流式）**，不使调用失败；**usage-only 尾包（choices 为空且 usage 非空）在聚合循环显式接受**——非流式路径的 `choices.isEmpty() → error` 分支（`OpenAICompatibleProvider.java:435-438`）不可照搬进流式聚合。usage 于流末聚合，`onUsage` 每调用至多一次，缺失时环形指示器保持前值。测试：`GatedScriptAiService` 支持逐调用 delta 脚本（交错 reasoning/content、迟到 delta、流中 Stop、停滞流、attemptSeq 重试序列）经 `LlmCallOptions` 消费者发射、可选发射线程；`RecordingSubscriber` 断言 PROGRESS 载荷序列（含 STREAM_ENDED 状态与顺序）；TranscriptView 暴露 per-turn 卡片访问器供"同一组件实例跨 delta/替换"断言。

### D13 内部逃生开关

`jmeter.ai.streaming.enabled`（JMeter 属性，默认 `true`，非 UI 配置项）：`AgentRunner.callLLM` 组装 options 时与 `wantsStreaming()` 一并判定；置 false 后全部调用走非流式路径（provider 不挂消费者），GUI 行为等价改造前。对齐项目惯例（`jmeter.ai.recorder.enabled`/`agent.subagent.enabled`/`jmeter.ai.ipc.enabled`），作为某网关流式实现有兼容问题时的现场逃生口；默认值变更须同步 `jmeter-ai-sample.properties` 与 AiConfig 默认值清单（6 处同步惯例）。

## Risks / Trade-offs

- [`AgentRunnerLoopSequencingTest` 等特征测试锁定现有 hook 顺序，流式改造必然触碰] → 任务分层（provider 契约 → runner/事件 → 渲染抑制 → GUI），每层先更新对应特征测试再改实现；全量回归按既有惯例单独跑（勿与审查并行）
- [粘滞标记需沿 runner→事件→面板透传] → 标记由 `AgentRunner.callLLM` 域持有并随完成事件/回合流式状态透出，单一事实源，不引入第二份簿记
- [累计文本载荷在超长回复（>100KB）下的载荷放大] → 量级评估：100ms 一次 × 数十 KB 仍在内存拷贝量级以下；若实测有问题，发射端可退化为"新增尾巴 + 流段累计长度"双字段，面板拼接（面板仍持累计缓冲，幂等语义不破）
- [`showThinking` 流段粒度快照与现构造期快照（`ProgressCallbackHookAdapter.java:27`）的口径差] → 每流段读一次配置，回合内口径一致；文档注明开关变更下一流段生效
- [思考卡"自动/手动折叠"两态是对现有单 collapsed 布尔的语义扩展，触碰 `ThinkingCard.finish()` 无条件折叠（`ThinkingCard.java:99-119`）] → 折叠 API 拆分为 `collapseAutomatically()`（可被替换重新展开）与用户手势路径（置手动位，双向保护折叠与展开）；既有测试同步更新
- [Ollama/网关对 `include_usage` 的兼容性未知（网关重写参数有前科）] → 选项失败时去参重试保持流式；不支持时静默无 usage，行为等价现状（用量指示不动），不阻断
- [全量 Markdown 重渲 + 每 tick 内嵌组件重建的 EDT 成本在数十 KB 回复下可能可感知] → D10（2026-11-08 修订）接受该成本换取实时组件观感；实测可感知时经 D13 总开关整体回退非流式，不恢复降级档
- [流关闭句柄的注册/调用时序（provider 开流与 signalCancel 并发）] → 注册表为并发结构（对齐 TurnRegistry 模式）；close 幂等（StreamResponse.close 可重入）；未注册时退化为 chunk 间 abort 轮询（chunks 正常流动时的主路径）

## Migration Plan

分四层按序落地（详见 tasks.md）：① provider 契约与聚合（options 消费者/abort 谓词/关闭句柄、错误分类、终止标记校验、`maxRetries(0)`；非流式回退先行）→ ② runner 重试环 + STREAM_* 事件发射（holdback 分段器、节流、attemptSeq、失败/中止终态）→ ③ 渲染端抑制 + streamedFinal 终态标记（IPC partialContent 回归锚）→ ④ GUI 合并器/卡片生命周期/实时组件渲染/竞态失效 + 测试脚手架扩展。每层独立可编译可回归，层间无 schema/持久化变更——回滚 = revert 对应层提交（或现场置 `jmeter.ai.streaming.enabled=false`），无数据迁移。

## Open Questions

- 节流常数最终值（50/80/100ms）与 EDT flush 的具体合并窗口：实现时以 EDT 压力实测微调，不影响契约
- 各网关 `include_usage` 与流式 thinking 增量的实际到达形态：SDK 探针（任务 1.4）以真实网关核对，影响 usage 回退分支与思考通道接法，不影响分段骨架
