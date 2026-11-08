# llm-streaming Specification

## Purpose

LLM 流式输出端到端契约：Provider 层真实增量流式（非打字机模拟）、流段事件协议与身份（含失败/中止终态）、think 标签增量分段（含尾部扣留）、失败重试与整段替换、去重渲染与终答标记、GUI 合并渲染与流式卡片生命周期，以及流式范围边界（永不流式路径、非流式回退与内部逃生开关）。

## Requirements

### Requirement: Provider 真实流式聚合

Claude 与 OpenAI 兼容（含 Ollama）两条 provider 路径 SHALL 使用各自 SDK 的原生流式 API 交付增量：正文与结构化 reasoning 增量在到达时即向上发射，MUST NOT 等待完整响应后再模拟增量。provider 流式调用 SHALL 在返回前收完整条流，并聚合出与既有非流式路径同构的完整响应对象（content/reasoning/toolCalls/usage/finishReason），Agent Loop、工具执行与持久化消费完整对象且 MUST NOT 感知流式差异。工具参数 JSON 分片 SHALL 仅在 provider 内部拼合，收齐并成功解析后才进入展示与执行路径。流式读取 SHALL 响应线程中断与回合 abort：chunk 之间轮询 abort，取消路径 SHALL 能主动关闭底层流句柄——服务端停滞（长时间无 chunk）时 Stop 仍可及时退出，MUST NOT 只能等待读超时。流提前结束（缺少协议终止标记，如 message_stop / finish_reason）SHALL 判为可重试失败，MUST NOT 作为成功聚合结果返回。

#### Scenario: 首个增量到达即上屏

- **WHEN** 模型经流式连接返回首个正文增量
- **THEN** 该增量经事件通道到达 GUI 并呈现，无需等待后续增量或流结束

#### Scenario: 流式与聚合结果一致

- **WHEN** 一次流式调用完成
- **THEN** provider 返回的完整响应对象与同请求走非流式路径的结果在结构上同构，工具调用参数为收齐解析后的完整对象

#### Scenario: Stop 打断停滞流

- **WHEN** 流式生成中服务端停滞不再发 chunk，用户点击 Stop
- **THEN** 取消路径关闭底层流句柄使读取循环及时退出，回合按既有取消语义收尾，屏幕保留已接收的部分内容

#### Scenario: 提前 EOF 判失败

- **WHEN** 流连接中断导致流在缺少终止标记的情况下结束（无异常抛出）
- **THEN** 该次尝试判为可重试失败而非成功，截断内容不作为完整响应进入持久化

### Requirement: 流段事件协议与身份

每次 LLM 调用（一次 agent 主循环内的模型请求）SHALL 构成一个独立流段：`STREAM_STARTED → CONTENT_DELTA/REASONING_DELTA（可多次）→ STREAM_ENDED`。流段事件 SHALL 作为进度载荷新类型挂现有回合事件 PROGRESS 种类交付（不新增回合事件 Kind），复用既有活回合集合与会话代数过滤。流段身份 `streamId` SHALL 稳定于一次逻辑 LLM 调用：自动重试 MUST NOT 更换 streamId，重试发起的 `STREAM_STARTED` SHALL 携带递增 `attemptSeq`。`STREAM_ENDED` SHALL 携带终态状态（SUCCESS / FAILED / ABORTED）与该流段各通道最新累计文本（思考通道在 showThinking 关闭的流段快照下置空——抑制在发射端完成，消费端无快照可过滤，携带即泄漏；正文通道恒为剥标签后累计）：失败/中止流段的结束事件 SHALL 先于下一次尝试的 `STREAM_STARTED(attemptSeq+1)`（如仍有尝试）或回合终态（全部尝试失败/中止）发射。**从未交付过任何可见增量的调用（非流式回退路径、被隐藏的纯思考、或内联 think 全被剥离的正文）不构成流段——MUST NOT 发射任何流段事件**（否则消费端会把该回合误标"已流式"而吞掉全文回放）。GUI SHALL 按"已结束流段"（含失败/中止）丢弃迟到 delta，并按回合身份键控流式卡片状态（重叠活回合的流 MUST NOT 互写对方卡片）。发射端 SHALL 按流段节流（约 100ms 量级）批量发射，且每次 delta 载荷携带该流段该通道的**累计文本**（渲染幂等；懒创建/领养面板收到后续 delta 时整段正确显示）。

#### Scenario: 工具轮次间流段独立

- **WHEN** 同一回合内首次 LLM 调用产生正文与工具调用、工具执行后发起第二次 LLM 调用
- **THEN** 两次调用各产生独立流段（各自 STREAM_STARTED/STREAM_ENDED），第二次调用的增量 MUST NOT 追加进第一次调用的卡片

#### Scenario: 迟到 delta 被丢弃

- **WHEN** 某 streamId 的 STREAM_ENDED（含失败/中止状态）已被面板处理后该流段的迟到 delta 到达
- **THEN** 迟到 delta 被丢弃，不产生任何渲染变化

#### Scenario: 累计载荷幂等重渲

- **WHEN** 面板收到同一流段携带累计文本的多次 delta（含重复或乱序后到达的旧载荷）
- **THEN** 以累计文本整体渲染，最终呈现与到达顺序无关

#### Scenario: 全败时失败流段先收敛

- **WHEN** 一次逻辑调用的全部尝试均失败
- **THEN** 最后一次尝试的 STREAM_ENDED(FAILED)（携带最新非空累计文本）先于回合终态发射，屏幕按其载荷收敛为最新非空部分

### Requirement: think 标签增量分段

内联 think/thought 标签的分段 SHALL 在发射端完成：维护内容通道累计缓冲，对完整累计串做整串标签分段，与上次已发射状态差分后产生双通道增量（新增思考片段 → REASONING_DELTA，新增正文 → CONTENT_DELTA）。分段器 SHALL 对累计串尾部可能是标签前缀的片段做**尾部扣留**（如 `<thi`、`</think`）：未决前缀 MUST NOT 作为正文发射，扣留在流段结束/中止时冲刷。分段 MUST NOT 依赖对片段的裁剪/去空行为（保证前缀差分不丢段首缩进等空白）。跨 delta 边界的标签碎片 MUST NOT 以字面形式出现在正文通道，标签内容 MUST NOT 泄漏进正文。结构化 reasoning 增量 SHALL 直入思考通道，不经标签分段。`showThinking` 关闭时思考通道增量 SHALL 在发射端抑制（分段照常运转，保证正文不含标签痕迹），且该开关以流段为粒度快照（流段中途的开关变更自下一流段生效）。

#### Scenario: 碎片标签不泄漏

- **WHEN** 模型输出经流式分片为 `"我用<thi"` / `"nk>先分析"` / `"</thi"` / `"nk>答案"`
- **THEN** 正文卡片只见"我用答案"，思考片段完整进入思考卡片，无 `<thi`/`nk>` 字面碎片出现，段首缩进与空白在差分中保留

#### Scenario: showThinking 关闭仅见正文

- **WHEN** showThinking=false 的会话中模型输出含 think 标签的内容
- **THEN** 思考增量被抑制，正文卡片呈现剥离标签后的完整答案

### Requirement: 失败重试与整段替换

LLM 调用失败 SHALL 经结构化错误分类（可重试标记 + 状态码/错误类别，由 provider 从 SDK 异常填充）判定重试资格：429/5xx/网络/超时/提前 EOF 可重试；4xx 参数与鉴权类、context-length、调用中断与已置 abort 的调用 MUST NOT 重试。重试环 SHALL 位于 agent 主循环的 LLM 调用入口（两条 provider 路径共用），总尝试上限 3 次、指数退避，退避间隔 SHALL 轮询 abort 与 IPC deadline（Stop/超时能即刻打断退避）；外部重试环 SHALL 是唯一的重试控制点（流式调用禁用 SDK 内部自动重试，避免预算放大）。已产生可见增量后失败重试时：重试尝试自身的增量 SHALL 屏外缓冲不实时显示（屏幕保留上一次的部分内容）；重试成功 SHALL 以最新完整响应**整段替换**该流段的正文与思考（不追加、不拼接）；全部尝试失败 SHALL 依据最后一次 STREAM_ENDED(FAILED) 的累计载荷保留最新非空部分并按既有错误路径提示。抑制与终答标记的事实源 SHALL 为"该逻辑调用是否曾发射过增量"的**粘滞标记**（跨尝试、跨回退保留）；非流式回退 MUST NOT 发生在已发射过增量的尝试上（已发射增量后的失败只能走同 streamId 重试或失败收尾）。

#### Scenario: 鉴权错误不重试

- **WHEN** provider 返回 401 鉴权失败（分类不可重试）
- **THEN** 不发起重试，直接按既有错误路径呈现

#### Scenario: 重试成功整段替换

- **WHEN** 首次尝试已显示部分正文后网络失败，自动重试成功返回完整响应
- **THEN** 该流段卡片以最新完整响应整体替换旧内容，屏幕不出现两次尝试内容的拼接

#### Scenario: 全部尝试失败保留部分

- **WHEN** 3 次尝试均失败且首次尝试已显示部分正文
- **THEN** 屏幕保留最新非空部分内容，随后呈现既有错误提示

#### Scenario: 已发射增量后不回退双渲染

- **WHEN** 某次尝试已发射可见增量后失败，后续尝试经非流式路径成功
- **THEN** 粘滞抑制标记仍生效，全文回放不被渲染为重复内容，终答按原位重渲呈现

#### Scenario: 退避期间 Stop 即刻生效

- **WHEN** 重试退避等待中用户点击 Stop
- **THEN** 退避被中止，不再发起下一次尝试，回合按取消语义收尾

### Requirement: 去重渲染与终答标记

对已流式呈现的调用，面板 MUST NOT 将 THINKING 全文回放与 INTERMEDIATE_RESPONSE 全文载荷**渲染**为重复内容（按回合的流式状态过滤，事实源为粘滞"曾发射过增量"标记）；等价全文载荷 SHALL 继续向发起方程序化回调交付（IPC partialContent 累积口径不变）；未发射增量的调用（非流式回退）SHALL 照常全文渲染。回合完成事件 SHALL 携带终答流式标记（含最终流段身份）：有标记时面板 SHALL 按**最终流段身份查找**流式卡并以权威终答文本**原位重渲**（覆盖式，MUST NOT 以文本相等性判断，MUST NOT 新增重复卡）——终答与流文本的合法差异（goal 后缀、标签剥离）由覆盖自动吸收；查无卡片（终答剥标签后为空、纯思考流正文卡未创建、面板晚领养）SHALL 回退既有新增卡路径（含空内容占位处理）；无标记（占位/错误/回退终答）SHALL 照既有路径新增卡渲染。

#### Scenario: 流式回合完成无重复卡

- **WHEN** 流式回合自然完成（终答已流式呈现）
- **THEN** 面板不新增重复正文卡；最终流式卡内容为权威终答文本

#### Scenario: goal 后缀并入原卡

- **WHEN** goal 活跃使终答尾部被追加运行状态后缀
- **THEN** 后缀经原位重渲并入最后一张流式卡，同一回复仍是单卡呈现

#### Scenario: IPC partialContent 口径不变

- **WHEN** IPC 委派回合流式执行中被对端超时/取消
- **THEN** 取消/超时响应信封的 partialContent 仍由发起方回调全文载荷累积，与流式改造前口径一致

#### Scenario: 非流式回退保留旧行为

- **WHEN** 不支持流式的服务以非流式回退完成调用（未发射增量）
- **THEN** 思考/正文经既有全文载荷路径渲染，行为与流式改造前一致

#### Scenario: 终答为空回退既有路径

- **WHEN** 流式回合的权威终答经剥标签后为空（或面板晚领养未赶上任何流段）
- **THEN** 面板回退既有新增卡/占位路径呈现，内容不丢失、不出空流式卡

### Requirement: GUI 流式渲染与卡片生命周期

流式正文 SHALL 在当前流式卡内累计原始 Markdown 并经 EDT 合并重渲（50–100ms 量级节流；每面板单一合并器，一次 EDT 通道渲染全部脏流段；流段终态立即同步冲刷）。流段终态（STREAM_ENDED 或终答原位重渲）SHALL 使该流段待执行的合并刷新失效：flush 任务 SHALL 校验流段未终态，MUST NOT 用旧累计文本回写覆盖已收敛的权威内容。流式进行中的渲染 SHALL 与终态同路径：已闭合的围栏代码块与成形的表格即时渲染为内嵌组件（复制按钮/网格随流出现），尚未闭合的尾部围栏按等宽纯文本呈现、闭合后即时升级。卡片生命周期时序：加载指示 SHALL 保留至**首条可见增量**（任一通道）到达（流段开始信号不早于首个增量发射，避免首字等待期失去活动指示）；正文卡 SHALL 懒建于首条剥标签后非空的 CONTENT_DELTA（纯思考流 MUST NOT 产生空正文卡）；同流段首条可见正文 SHALL 触发既有"折叠思考卡 + 收尾活动组"不变式，其后新到思考增量开新思考卡。转录内部的单槽"当前思考卡/活动组" SHALL 改为按回合身份映射，重叠活回合互不串卡。整段替换 SHALL 原地复用同一对卡片组件（位置不变；思考卡支持原地替换/清空；正文卡整篇重写）；思考卡折叠态 SHALL 区分自动折叠与用户手动折叠——整段替换可重新展开自动折叠卡，MUST NOT 强制改动用户手动折叠**或展开**选择。会话清屏（/new）SHALL 在清空转录的同一 EDT 步骤取消待执行刷新任务并丢弃全部流式缓冲（清屏后 MUST NOT 出现旧会话孤儿卡）。

#### Scenario: 增量渐进呈现且组件实时渲染

- **WHEN** 长回复经多次 delta 到达、内容含代码块与表格
- **THEN** 正文在当前卡内渐进增长；已闭合的代码块与成形的表格随流即时渲染为内嵌组件（复制按钮/网格），数据行随流增长，未闭合的尾部围栏按等宽纯文本呈现直至闭合

#### Scenario: 终态后旧 flush 不回写

- **WHEN** TURN_COMPLETED 权威覆盖完成后，此前排队的合并 flush 任务才获得执行机会
- **THEN** 该 flush 因流段已终态被跳过，权威终答不被旧累计文本覆盖

#### Scenario: 纯思考流无空正文卡

- **WHEN** 模型仅产生思考增量后直接发起工具调用（无正文）
- **THEN** 不出现空正文卡，思考卡与工具活动卡正常呈现

#### Scenario: 清屏后无孤儿卡

- **WHEN** 流式进行中用户执行 /new 清屏
- **THEN** 待执行刷新被取消、缓冲被丢弃，新会话转录不出现旧会话内容

#### Scenario: 重试替换保留用户折叠选择

- **WHEN** 用户已手动折叠思考卡后重试成功触发整段替换
- **THEN** 卡片内容被替换且保持折叠（用户手动折叠不被强制展开）

### Requirement: 流式范围与回退边界

凡带回合身份的主循环回合（本地面板、面板可见 IPC/委派、goal 续跑 re-publish）SHALL 流式，回合来源不同 MUST NOT 要求订阅者区分接入方式；流式闸门 SHALL 以"进度钩子请求流式且调用选项携带增量消费者"为判据。子代理运行与记忆整合器调用 MUST NOT 流式（无消费者，自然落在非流式路径）。IPC/CLI 对外 HTTP 响应 SHALL 保持阻塞终态语义不变（流式增量仅进 GUI 事件通道，不进传输层信封；partialContent 累积口径不变）。流式 MUST NOT 设 UI 配置项，但 SHALL 提供内部逃生开关（JMeter 属性，默认开启）：现场禁用后全部调用回退非流式，行为等价改造前。不支持流式或流式协商失败的服务 SHALL 静默回退非流式调用（仅限未发射增量的尝试），回退 MUST NOT 把完整结果切片伪装成流式增量。

#### Scenario: 委派回合本地流式且对端阻塞

- **WHEN** 委派回合在目标实例运行
- **THEN** 目标面板流式呈现其增量；委派方 HTTP 响应仍阻塞至终态、信封字段与流式改造前一致

#### Scenario: 不支持流式的服务静默回退

- **WHEN** 某 OpenAI 兼容服务不支持流式
- **THEN** 调用回退非流式完成，GUI 经既有全文载荷路径一次性渲染

#### Scenario: 逃生开关现场禁用

- **WHEN** 运维将内部流式开关置为禁用并重启
- **THEN** 全部 LLM 调用走非流式路径，GUI 行为与流式改造前一致

### Requirement: 流式 usage 与追踪语义

流式请求 SHALL 请求 usage 回传（OpenAI 兼容路径带 include_usage），usage 于流末聚合进完整响应对象；该选项 MUST NOT 使调用失败（网关拒绝该选项时去掉选项重试，保持流式；usage-only 尾包（choices 为空且 usage 非空）SHALL 被聚合循环接受而非误判错误）。上下文用量指示 SHALL 仍由 USAGE 载荷驱动且每次调用至多发射一次（流末），流式未返回 usage 时用量指示 MUST NOT 被虚构或清零（保持前值）。追踪层 SHALL 经由调用选项透传自然携带流式（不引入独立流式入口），每次调用记录一条 run，MUST NOT 按 delta 记录，MUST NOT 因追踪开启而静默失去流式能力。

#### Scenario: 流式回合用量更新一次

- **WHEN** 流式调用在流末返回 usage
- **THEN** 上下文环形指示器经 USAGE 载荷更新一次，无逐 delta 抖动

#### Scenario: usage-only 尾包不误判

- **WHEN** 流末到达的 usage-only chunk（choices 为空）
- **THEN** 聚合循环接受其 usage，不触发"空 choices 错误"分支

#### Scenario: 开启追踪流式不失效

- **WHEN** LangSmith 追踪开启且模型支持流式
- **THEN** 面板仍流式呈现；追踪记录为每调用一条完整 run

### Requirement: 增量仅显示层与持久化不变

流式增量 SHALL 严格限于显示/观察通道：会话持久化以完整消息为唯一单元（既有三阶段落盘与 RESET 恢复语义不变），jsonl MUST NOT 写入 delta 记录；Stop/失败保留的屏幕部分内容 MUST NOT 作为正常 assistant 消息落盘（按既有中止恢复规则处理悬空状态）。模型历史回传 MUST NOT 因流式改变（增量不进上下文）。

#### Scenario: 流式回合落盘与改造前一致

- **WHEN** 流式回合正常完成
- **THEN** jsonl 新增的是完整 assistant/tool 消息记录，无任何 delta 行

#### Scenario: Stop 后部分内容不污染会话

- **WHEN** 流式中途 Stop（屏幕保留部分内容）
- **THEN** 该部分内容不作为正常 assistant 消息进入会话历史，悬空工具调用按既有中止规则补齐
