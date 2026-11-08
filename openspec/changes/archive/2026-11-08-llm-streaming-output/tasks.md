# Tasks

## 1. Provider 流式契约与聚合

- [x] 1.1 `LLMResponse` 增结构化字段（粘滞 `emittedAnyDelta` 语义的 `streamed`、`retryable`、状态码/错误类别）并保持既有构造路径默认值兼容；验证：新增单测断言默认值与既有 `LLMResponse` 相关测试全绿
- [x] 1.2 `LlmCallOptions` 增唯一 seam 三件：delta 消费者、abort 谓词（BooleanSupplier）、流关闭句柄注册点；验证：单测断言无消费者时调用路径零开销、句柄注册/调用幂等
- [x] 1.3 逃生开关 `jmeter.ai.streaming.enabled`（默认 true）接入 `AgentRunner.callLLM` 与 `wantsStreaming()` 联合判定，默认值同步 `jmeter-ai-sample.properties`；验证：单测断言开关关闭时 options 不挂消费者、全部调用走非流式
- [ ] 1.4 SDK/网关探针：以真实网关各写一个临时最小流式调用，核对 chunk 形态、thinking 增量到达形态、`include_usage` 兼容性；验证：探针结论回写 design.md Open Questions，探针代码不入库
- [x] 1.5 `OpenAICompatibleProvider` 流式实现：`stream=true` + `stream_options.include_usage`（网关 400 时去参重试保持流式）、按 index 拼合工具参数分片、**usage-only 尾包（空 choices）显式接受不误判**、content/reasoning_content 双通道增量发射、**终止标记（finish_reason）缺失判可重试失败**、收完整流聚合完整 `LLMResponse`（`streamed=true`）、catch 按 SDK 状态码填充错误分类（`SocketTimeoutException` 先于中断判别）、流式调用 SDK `maxRetries(0)`；验证：脚本化 chunk 单测覆盖拼合/分类/usage/EOF/中断响应
- [x] 1.6 `ClaudeService` 流式实现：`createStreaming` 事件迭代、text/thinking 增量发射、`input_json_delta` 工具参数拼合、**message_stop 缺失判可重试失败**、完整聚合 + 错误分类、SDK `maxRetries(0)`；验证：对齐 `ClaudeServiceToolCallingTest` 风格的单测覆盖聚合同构、EOF 判失败与流式标记
- [x] 1.7 abort 关闭机制：provider 开流时注册 `StreamResponse::close` 句柄，`AgentLoop.signalCancel`（置 flag + interrupt + cancel 之外）经注册表主动 close；验证：fake 流 + 停滞流单测断言 Stop 后读取循环及时退出、回合按取消语义收尾、屏幕保留部分内容
- [x] 1.8 文档对齐：`AGENTS.md` 依赖表 anthropic-java 版本改 2.53.0 与 pom 一致；验证：文档与 `pom.xml` 版本一致

## 2. Runner 重试环与 STREAM_* 事件发射

- [x] 2.1 `AgentHook` 三件套改造为 StreamDelta 形态（替换/删除死签名），`ProgressUpdate` 增 `STREAM_STARTED/CONTENT_DELTA/REASONING_DELTA/STREAM_ENDED` 类型与结构化载荷（含 STREAM_ENDED 终态状态 SUCCESS/FAILED/ABORTED）；验证：单测断言载荷字段完整、旧 hook 无残留调用方
- [x] 2.2 `AgentRunner.callLLM` 重试环：仅 `retryable` 分类重试（提前 EOF 归可重试；`"Interrupted"`/已 abort 永不）、总尝试 3 次、指数退避为 abort/deadline 分片轮询（外层环为唯一重试控制点）；验证：单测覆盖可重试成功、鉴权不重试、退避中 Stop 即刻中止、全败产出 FAILED 终态
- [x] 2.3 streamId/attemptSeq 铸造（同一逻辑调用内稳定）+ 发射端 think 分段器：**独立实现（不复用 `splitThink`）**——不裁剪分段 + 尾部 holdback（`<think`/`</think` 前缀扣留，流段结束/中止冲刷）、`showThinking` 每流快照并在关闭时抑制思考增量；验证：单测覆盖碎片 `<thi`/`nk>` 不泄漏、跨 chunk `</thi`、标签属性、段首缩进保留、结构化 reasoning 直通、showThinking=false 仅正文
- [x] 2.4 发射端节流器（约 100ms、载荷带累计文本、STREAM_ENDED(含 FAILED/ABORTED)/失败/abort 时强制冲刷最新累计）+ `AgentLoop.buildProgressHook` 接线；验证：`RecordingSubscriber` 单测断言 STREAM_ENDED 严格先于工具进度与回合终态、失败流段结束先于下一次 STREAM_STARTED(attemptSeq+1)/回合终态、迟到 delta 可辨识
- [x] 2.5 `GatedScriptAiService` 扩展 delta 脚本化（逐调用脚本：交错 reasoning/content、迟到 delta、流中 Stop、停滞流、attemptSeq 重试序列，可选发射线程）；验证：testsupport 单测覆盖脚本回放与时序控制

## 3. 渲染端去重与终态标记

- [x] 3.1 渲染端抑制：adapter 照常发射 THINKING/INTERMEDIATE_RESPONSE 全文，`AiChatPanel` 按回合粘滞"曾发射增量"状态忽略已流式回合的全文回放、未流式回合照常渲染；同步更新 `TurnContentAccumulator` 类注释；验证：`AgentRunnerLoopSequencingTest` 更新为流式/非流式双形态锚全绿 + **IPC partialContent 回归**（流式回合取消/超时回执的 partialContent 与改造前口径一致，`TurnContentAccumulatorTest`/`IpcServerCancelledResponseTest`）
- [x] 3.2 `AgentResponse`/完成事件增 `streamedFinal` 标记（携带最终 streamId，事实源为粘滞 emittedAnyDelta——跨尝试/回退保留）；验证：`EventParityTest`/`UsageProgressEventTest` 对齐断言载荷与 USAGE 流末恰一次 + 单测断言"已发射增量后经非流式路径成功"仍带标记（无双渲染窗口）

## 4. GUI 流式渲染

- [x] 4.1 `ThinkingCard` 增 `replaceText/clear`，折叠态拆自动/手动两 bit（`collapseAutomatically()` 可被替换重展，手势路径置手动位，双向保护折叠与展开）；验证：单测断言整段替换后自动折叠卡可重展、手动折叠保持折叠、手动展开不被自动收尾折叠
- [x] 4.2 `TranscriptView` 合并器：per-stream 脏标志 + 单个自重投 EDT flush、计时器经工厂注入、flush 执行前校验流段未终态（防旧累计回写权威终答）、`clearTranscript` 同一 EDT 步骤取消 pending flush 并弃缓冲；验证：手动步进计时器单测（fire 后自停、终态后旧 flush 被跳过、clear 后无孤儿卡、多脏流一次通道刷完）
- [x] 4.3 `TranscriptView` 单槽 `thinkingCard`/`activityGroup` 改造为按 turnId 映射（`finishReasoningIfRunning` 等收尾按回合执行；STREAM_STARTED 遇新 streamId 先收尾上一段再建新卡片对，同 streamId+attemptSeq>1 复用）+ 生命周期时序（**STREAM_STARTED 懒发射不早于首增量**、加载指示保留至首条可见增量、正文卡懒建于首条剥标签后非空 CONTENT_DELTA、首条可见正文触发"折叠思考卡+收尾活动组"、endedStreamIds 丢迟到 delta、`handleProgressNow` 为 STREAM_* 加显式分支不走 default）；验证：`TranscriptViewTest`/面板测试断言纯思考流无空正文卡、首字等待期 loading 不消失、重叠回合不串卡
- [x] 4.4 `MessageCard` 流式渲染与 `MarkdownRenderer`（2026-11-08 修订）：流式 tick 与终态渲染同路径——已闭合围栏/成形表格**即时渲染内嵌组件**（复制按钮/网格随流出现、流段结束无升级跳变）、未闭合尾部围栏按纯文本直至闭合、每 tick 渲染后执行 `fitEmbeddedScrollers`（初版"流式降级纯文本、终态升级"已废弃，见 design.md D10 修订）；验证：`MessageCardStreamingTest` 重写（闭合即组件/未闭合纯文本/主题与字号重渲保持组件）
- [x] 4.5 `streamedFinal` 原位重渲 + 重试整段替换：**按 streamId 查卡**（查无——终答为空/纯思考流/晚领养——回退 `processAiResponse` 既有路径含空占位）、有卡以权威 finalContent 覆盖（goal 后缀并入同卡）；重试期间增量进屏外缓冲、成功原地复用组件对、全败按 STREAM_ENDED(FAILED) 载荷收敛最新非空部分；验证：面板测试断言流式回合完成无重复卡、同一组件实例跨 delta/替换、无卡回退路径、`AiChatPanelIpcTurnPresenterTest` 等既有回归全绿
- [x] 4.6 滚动跟随确认：流式 tick 复用既有 capture-before-mutation 协议；验证：`ChatScrollerTest` 增流式高频 append 用例（贴底跟随、上滚不动）

## 5. 集成与回归

- [x] 5.1 全量 `mvn clean test`（单独运行、不与其他任务并行）并修复全部回归；验证：输出文件中 Tests run 全绿、BUILD SUCCESS
- [ ] 5.2 手动联调清单逐项过：本地流式回合真延迟增量、Stop 中断保留部分内容（含停滞流场景）、`/new` 清屏无孤儿卡、重试整段替换、全败保留部分、非流式回退照旧、逃生开关禁用后行为等价改造前、IPC 委派本地流式 + 对端阻塞终态 + 取消/超时回执仅带终止通知（partialContent 窄口径——仅注入驱动的无工具终答场景非空——2026-11-08 用户确认保持现状，不扩展流式终段累积）、goal 续跑流式；验证：逐项在 JMeter GUI 实测并记录
- [x] 5.3 `openspec validate llm-streaming-output --strict` 通过；验证：命令零告警退出

## 7. 第二轮复审修复（2026-11-08，docs/agent-sessions/2026-11-08-…-rereview.md R1–R12）

- [x] 7.1 R4 `StreamCloseRegistry` 线性化：register 的「检查取消位→加入列表」与 closeAll 的「置位→快照清空」同锁互斥（确定性并发探针：GatedList 注入 + 双线程交错）；验证：`StreamCloseRegistryTest.registerRacingCloseAllCannotLoseHandle`
- [x] 7.2 R5/R6 发射端可见性重构：`segmentVisible` 事实源移到分段产出处（flush/end 的 takeNew 结果；offer 只入缓冲，原始到达不构成可见）——内联 think 全被剥离的正文（含屏外重试变体）零可见增量不发事件；ENDED 思考载荷在 showThinking=false 快照下置空（抑制在发射端，携带即泄漏）；验证：`StreamEmitterTest`（hiddenInlineThinkOnlyEmitsNothing / retryAttemptWithOnlyHiddenThinkEmitsNothing / visibleContentAfterHiddenThinkStillConstitutesSegment + 既有 ENDED 断言翻转）
- [x] 7.3 R1 活动组按回合键控：`TranscriptView.activityGroups` Map（无回合身份路由 0L 槽），`addToolActivity(turnId,…)`/`finishActivityIfRunning(turnId)`，面板 TOOL_CALL/终态补显路径全程携带回合身份；兄弟回合新流段/首条正文只收尾本回合组；验证：`TranscriptStreamingTest.overlappingTurnsKeepSeparateActivityGroups`
- [x] 7.4 R2 正文后新思考开新卡：段内 `thinkingCards` 列表 + 当前指针；首条可见正文收尾旧卡后，后续思考增量（delta 与 ENDED 尾部，判据 `contentStarted` 非 isRunning——ENDED 入口先收尾指针卡）落新卡；验证：`TranscriptStreamingTest.postContentReasoningOpensNewThinkingCard`
- [x] 7.5 R3 终答覆盖失效 pending flush：`renderStreamedFinal` 置 `segment.ended` + 收尾思考 spinner（与 STREAM_ENDED 同防御）；验证：`TranscriptStreamingTest.streamedFinalInvalidatesPendingFlush`
- [x] 7.6 R11 整段替换空通道清理：ENDED(attemptSeq>1) 权威载荷空通道 = 新尝试无该通道内容——移除正文卡/dispose 全部思考卡（不再静默保留上一尝试残留）；验证：`TranscriptStreamingTest.retryEndedWithEmptyChannelsClearsOldContent` / `retryEndedWithEmptyReasoningClearsThinkingOnly`
- [x] 7.7 R9/R10 非自然终答清除 streamedFinal：工具错误（failOnToolError）与迭代耗尽占位分支与 LLM 错误分支同口径清 `lastResponseStreamed`；验证：`AgentRunnerStreamingLoopTest.toolErrorFinalDoesNotInheritStreamedFinal` / `maxIterationsPlaceholderDoesNotInheritStreamedFinal`
- [x] 7.8 R7/R8 回退资格与分类：401/403/404/429 定性错误不再静默回退（不浪费第二次请求、不掩盖配置错误）；两 provider 非流式 catch 改用与流式同构的分类（429/5xx/网络/超时可重试——回退失败不再丢重试资格）；Claude 侧 `friendlyClaudeError` 保留用户可读文案；验证：`StreamingSilentFallbackTest.fallbackFailureKeepsRetryableClassification` / `authErrorDoesNotFallBackToNonStreaming`
- [x] 7.9 R12 Claude usage 缺失不以 0/0 冒充：聚合器 `usageSeen` 标志（message_start/message_delta usage 置位），缺失时空 map（onUsage 只对非空发射→上下文指示保持前值）且不记用量统计；验证：`ClaudeServiceStreamingTest.missingUsageIsNotFabricatedAsZero`
- [x] 7.10 spec 措辞一致化：流段事件协议的 ENDED 思考载荷快照口径 + 零可见增量构成条件（内联 think 剥离变体）补入 `specs/llm-streaming/spec.md`；验证：`openspec validate --strict` 通过
- [x] 7.11 修复引入回归的根因修复与 Standards 清理：R5 重构暴露「offer 不分段、contentCumulative 在 takeNew 前为空」——可见性统一由分段产出置位（flush 命中即置）；GatedList clear 支持；`AgentRunnerStreamingLoopTest` 重复静态导入删除、`AiChatPanelStreamingPresenterTest` DIAG 诊断块（含状态突变）移除；验证：定向 163 项 + 全量 `mvn clean test`（1309 项，EventParityTest 1 例 Windows 临时目录删除竞态单独复跑绿——已知模式）
- [x] 7.12 Stop 后孤儿思考卡（2026-11-08 手测复现）：回合终态后到达的 STREAM_ENDED 被面板活回合过滤丢弃，流段 ended 永不置位——已排队的合并 flush（TranscriptView 内部计时器，不经 dispatch 过滤）在 "Stopped." 行之下为缓冲中的思考增量新建运行中的卡，无人收尾永转；修复 = TURN_COMPLETED/TURN_CANCELLED 分支调 `TranscriptView.settleStreamTurn(turnId)`（该回合全部流段置 ended + 收尾 spinner，与清屏失效同族防御、单回合粒度）；验证：`TranscriptStreamingTest.turnCancelSettlePreventsPendingFlushFromCreatingOrphanThinkingCard` / `turnCancelSettleFinishesAlreadyRunningThinkingSpinner` + 全量 1311 项全绿

## 8. 追加需求（2026-11-08）：工具调用开始即呈现

- [x] 8.1 工具运行态先行呈现：`ProgressUpdate.Type.TOOL_STARTED`（载荷 = 本批 `List<ToolCall>`，message 恒空）经 adapter `beforeExecuteTools` 发布（空列表不发）；面板逐工具 `addRunningToolActivity`（"🔧 ⋯ 名称 + Args 预览"），完成事件 `TOOL_CALL(ToolEvent)` 经 `completeToolActivity` **原位替换**占位行（同名并行按开始序 FIFO 配对；无占位回退普通追加——迟领养/开始信号被过滤）；`ToolActivityGroup` 改逻辑行模型（join 全量重渲，支持原位更新），`finish()` 对未完成占位行补记 "(not recorded — turn ended)"；验证：`TranscriptViewTest` 三例（原位替换/FIFO/收尾补记）+ `ProgressCallbackHookAdapterStreamingTest.toolStartPublishedBeforeExecutionWithToolCallList` + `EventParityTest` 序列更新（每批工具 +1 PROGRESS）+ 全量 1315 项全绿
