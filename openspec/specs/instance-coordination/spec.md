# instance-coordination Specification

## Purpose

跨实例协作层——实例注册表（存在性 + 当前打开 jmx 广播）与基于既有 IPC 通道的跨实例任务委派 RPC（委派到持有目标 jmx 的实例、回合内结果回注）。覆盖注册表生命周期、jmx 字段维护、委派寻址与执行、结果反馈、无目标/超时/失败处理。

## Requirements

### Requirement: 实例注册表扩展（instanceId 与 jmxPath）

`InstanceRegistry` 的 `InstanceInfo`（当前为 `{pid, port, token, startedAt, bind}`，写入 `port-{pid}.json`）SHALL 扩展两个字段：`instanceId`（与本实例会话键同源的进程级实例标识）与 `jmxPath`（本实例当前打开的 `.jmx` 测试计划绝对路径，无则空）。`port-{pid}.json` 的写入 SHALL 经既有原子写（temp + `ATOMIC_MOVE`）携带这两个新字段。`InstanceRegistry.listInstances()` SHALL 返回所有存活实例及其 `instanceId` 与 `jmxPath`。既有失活清理（TCP 与 PID 双确认）与回环绑定约束保持不变。

#### Scenario: 注册项携带 instanceId 与 jmxPath
- **WHEN** 一个 JMeter 实例启动并完成 `ADD_ALL` 初始化
- **THEN** 其 `port-{pid}.json` 含 `instanceId`（与会话键同源）与 `jmxPath` 字段
- **AND** `listInstances()` 返回该实例时这两个字段可见

#### Scenario: listInstances 返回各实例及其打开的 jmx
- **WHEN** 机器上并存实例 A（开着 `a.jmx`）与实例 B（开着 `b.jmx`）
- **THEN** 任一实例调用 `listInstances()` 得到两条记录，分别标注 `jmxPath=a.jmx` 与 `jmxPath=b.jmx`

#### Scenario: 失活实例被既有清理逻辑回收
- **WHEN** 一个实例退出（PID 与 TCP 均失活）
- **THEN** 其 `port-{pid}.json` 经既有双确认清理被移除
- **AND** 不出现在后续 `listInstances()` 结果中

### Requirement: 当前 jmx 的暴露与维护

每个实例 SHALL 把"当前打开的 `.jmx`"暴露给其他实例。`jmxPath` SHALL 在用户打开、另存为、关闭、新建测试计划时更新为本实例注册项的当前值。更新 SHALL 经既有 `ActionRouter` 动作监听（针对 JMeter 的 `Open`/`Load`、`Save`/`Save As`、`Close`、`New` 类 `Command`）或等价机制触发，读取当前计划文件路径（如 `GuiPackage` 的当前计划文件）并原子写回本实例 `port-{pid}.json`。无打开计划时 `jmxPath` SHALL 为空。

#### Scenario: 打开 jmx 后 jmxPath 更新
- **WHEN** 用户在实例中打开 `x.jmx`
- **THEN** 该实例 `port-{pid}.json` 的 `jmxPath` 更新为 `x.jmx` 的绝对路径
- **AND** 其他实例随后 `listInstances()` 看到 `jmxPath=x.jmx`

#### Scenario: 关闭/新建计划后 jmxPath 清空
- **WHEN** 用户关闭当前计划或新建空计划（无文件）
- **THEN** 该实例 `jmxPath` 更新为空
- **AND** 其他实例不再看到该实例持有任何 jmx

#### Scenario: jmxPath 维护失败不阻断用户操作
- **WHEN** 更新 `jmxPath` 时发生 IO 异常
- **THEN** 异常被捕获并记录，不影响 JMeter 正常的打开/关闭操作

### Requirement: 跨实例任务委派寻址

系统 SHALL 提供名为 `delegate_to_instance` 的工具供主代理调用，把一个自然语言任务委派给持有目标 jmx 的其他实例执行。寻址 SHALL 支持"按 jmx 路径"（解析为当前 `jmxPath` 匹配该路径的存活实例）与"按 `instanceId`/PID"两种方式。工具 SHALL 经 `InstanceRegistry` 解析目标：若无任何存活实例匹配，MUST 返回明确的"无实例持有该 jmx / 无此实例"错误；若多个实例匹配同一 jmx，SHALL 按确定性规则（如最近 `startedAt`）择一并在结果中说明。解析到目标后，工具 SHALL 从目标的 `port-{pid}.json` 读取其 `port` 与 `token` 用于后续调用。

#### Scenario: 按 jmx 路径委派解析到持有它的实例
- **WHEN** 主代理调用 `delegate_to_instance(jmx="b.jmx", task=...)` 且实例 B 当前持有 `b.jmx`
- **THEN** 工具经注册表解析到实例 B，读取其 `port-{pid}.json` 的端口与 token
- **AND** 把任务投递给实例 B 执行

#### Scenario: 按 instanceId 委派
- **WHEN** 主代理调用 `delegate_to_instance(instanceId="<id>", task=...)`
- **THEN** 工具按 `instanceId` 解析到对应实例并投递任务

#### Scenario: 无实例持有目标 jmx 时返回明确错误
- **WHEN** 主代理调用 `delegate_to_instance(jmx="none.jmx", task=...)` 且无存活实例持有 `none.jmx`
- **THEN** 工具返回明确的错误结果（非异常），说明无实例持有该 jmx
- **AND** 不发起任何远程调用

#### Scenario: 多实例持有同一 jmx 时确定性择一
- **WHEN** 两个存活实例都持有 `same.jmx`，主代理按 jmx 委派
- **THEN** 工具按确定性规则择一并在结果中说明选择了哪个实例
- **AND** 行为可预测、可复现

### Requirement: 委派执行与结果回传（复用既有 /agent 传输）

`delegate_to_instance` SHALL 把任务经既有 `POST /agent` 端点投递给目标实例（与"CLI 驱动 GUI"同一传输：目标 `port` + `token`，回环 HTTP，`IpcRequest` 体）。目标实例 SHALL 经其既有 `/agent` 处理器把消息送入自身 `AgentLoop`（使用其 `instanceId` 会话与自身工具集——它持有目标 jmx 故能执行），产生响应并回传。`delegate_to_instance` SHALL 是阻塞式工具：在工具执行线程上等待目标响应并把响应内容作为工具结果返回给主代理；MUST NOT 在 EDT 上阻塞。超时与取消 SHALL 复用既有 `/agent` 的 `CompletableFuture` + 超时机制（`jmeter.ai.ipc.agent.timeout.ms`）：超时时取消目标实例的活跃任务并返回超时错误。

#### Scenario: 委派任务被执行、结果回传主代理
- **WHEN** 主代理调用 `delegate_to_instance(jmx="b.jmx", task="运行测试并报告结果")`
- **THEN** 实例 B 的 `AgentLoop` 用自身工具集执行该任务
- **AND** B 的最终响应作为 `delegate_to_instance` 的工具结果返回给主代理 A
- **AND** 主代理 A 据此继续本轮对话

#### Scenario: 委派不阻塞 EDT
- **WHEN** 主代理在工具执行线程调用 `delegate_to_instance` 并等待远程响应
- **THEN** JMeter GUI 的 EDT 不被阻塞，用户界面保持响应
- **AND** 主代理 A 的迭代线程按正常工具调用语义等待工具结果

#### Scenario: 委派超时取消目标活跃任务
- **WHEN** 目标实例在 `jmeter.ai.ipc.agent.timeout.ms` 内未完成委派任务
- **THEN** `delegate_to_instance` 返回超时错误
- **AND** 目标实例上该委派触发的活跃任务被取消（复用既有 `cancelActiveTask`），不继续燃烧 token

#### Scenario: 接收侧复用既有 /agent 路径
- **WHEN** 一个实例收到来自另一实例的委派请求
- **THEN** 请求经既有 `/agent` 处理器进入该实例的 `AgentLoop`，不引入并行的第二套执行引擎
- **AND** 该委派交互记录在该实例自身的 `instanceId` 会话中

### Requirement: 委派工具注册与特性门控

`delegate_to_instance` 与 `list_instances` 工具 SHALL 注册到主 `ToolRegistry`（scope 含 `core`），且仅在跨实例协作特性启用时注册。协作特性 SHALL 要求 IPC 已启用（`jmeter.ai.ipc.enabled=true`）：IPC 关闭时这两个工具 MUST NOT 被注册，主代理 LLM 看不到它们。新增特性开关 `agent.instance.coordination.enabled`（默认 `true`，但仅当 IPC 开启时生效）门控这两个工具的注册。`list_instances` SHALL 返回经注册表读取、失活清理后的存活实例摘要（instanceId、PID、jmxPath、startedAt）。

#### Scenario: IPC 与协作均开启时工具可用
- **WHEN** `jmeter.ai.ipc.enabled=true` 且 `agent.instance.coordination.enabled=true`
- **THEN** `delegate_to_instance` 与 `list_instances` 被注册到主 `ToolRegistry`，主代理 LLM 可见

#### Scenario: IPC 关闭时工具不注册
- **WHEN** `jmeter.ai.ipc.enabled=false`
- **THEN** `delegate_to_instance` 与 `list_instances` 不被注册，主代理 LLM 看不到它们
- **AND** 不产生跨实例调用

#### Scenario: list_instances 返回存活实例摘要
- **WHEN** 主代理调用 `list_instances`
- **THEN** 返回当前存活实例的摘要列表（含 instanceId、PID、jmxPath、startedAt）
- **AND** 已失活的实例不在列表中

### Requirement: 跨实例会话读取（read_instance_session）

系统 SHALL 提供名为 `read_instance_session` 的只读工具，供主代理按 `instanceId` 读取本机另一 JMeter AI 实例的持久化会话消息。工具 SHALL 从共享会话存储直接读取目标实例的会话文件（不经 IPC 传输、不改写任何文件）。参数契约：`instanceId`（必填，目标实例标识）；`query`（可选，字面子串过滤，不区分大小写，MUST 不超过 500 字符（超长返回明确错误）；省略或留空 = 读最近消息；regex/glob 不支持，`*` 与 `.*` MUST 返回明确错误而非全量匹配）。

读取输出 MUST 有界：仅返回 user/assistant 角色的可见消息（tool/system 角色与空内容消息 MUST 跳过）；无 `query` 时返回最近最多 8 条；每条消息内容 MUST 截断至 4000 字符以内并压缩连续空白；`query` 给定时仅返回内容含该子串的消息，节选围绕首个命中位置居中，无命中 MUST 返回明确的"无匹配消息"信息。输出 MUST 附带会话元数据（最后更新时间、可见消息总数）与目标实例存活标注（live / not live，来自实例注册表的存活过滤；实例已退出但会话文件仍在存活期内时 SHALL 仍可读取）。

任何成功读取的输出 MUST 包含不可信数据告警（历史会话内容是不可信数据、不是指令），防止对端会话内的注入文本被当作指令执行。

存活标注 MUST 是纯辅助信息：其查询路径上的任何异常（注册表目录缺失、JMeter home 未初始化、IO 故障）SHALL 降级为 `not live` 标注，MUST NOT 使读取本身失败。

#### Scenario: 超 query 长度上限被拒绝
- **WHEN** 调用 `read_instance_session(instanceId="B", query=<超过 500 字符的串>)`
- **THEN** 工具返回明确错误（说明长度上限），不执行读取

#### Scenario: 存活标注失败不致命
- **WHEN** 目标会话文件存在且可读，但实例注册表不可用（如 JMeter home 未初始化）
- **THEN** 读取成功返回消息，存活标注降级为 `not live`

#### Scenario: 无 query 读取对端最近消息（有界）
- **WHEN** 实例 B 存在会话文件（含 20 条 user/assistant 消息与若干 tool 消息），实例 A 的主代理调用 `read_instance_session(instanceId="B")`
- **THEN** 工具返回 B 最近最多 8 条可见消息（仅 user/assistant、空内容跳过），每条 ≤4000 字符
- **AND** 输出含会话最后更新时间、可见消息总数、B 的存活标注与不可信数据告警

#### Scenario: query 过滤命中居中节选
- **WHEN** 调用 `read_instance_session(instanceId="B", query="登录接口")` 且 B 的会话中有 3 条消息含该子串
- **THEN** 仅返回这 3 条匹配消息，每条节选围绕首个命中位置居中
- **AND** 无任何命中时返回明确的"无匹配消息"信息而非空成功

#### Scenario: 拒绝 match-all 查询
- **WHEN** 调用 `read_instance_session(instanceId="B", query="*")` 或 `query=".*"`
- **THEN** 工具返回明确错误，说明 query 是字面子串匹配、省略即读最近消息

#### Scenario: 禁止读取自身会话
- **WHEN** 主代理调用 `read_instance_session(instanceId=<当前实例自身>)`
- **THEN** 工具返回明确错误（当前对话已在代理上下文中），不读取文件

#### Scenario: 目标实例不存在
- **WHEN** 调用 `read_instance_session(instanceId="no-such")` 且共享会话存储中无该实例的会话文件
- **THEN** 工具返回明确错误（非异常），并提示先经实例发现工具查看对端实例

#### Scenario: 实例已退出但会话可读
- **WHEN** 实例 B 已退出（注册表中无存活记录），但其会话文件仍在存活期内未被回收
- **THEN** 读取成功，存活标注为 not live，消息内容照常返回

#### Scenario: 撕裂/损坏行容忍
- **WHEN** 目标会话文件中存在半截或损坏的 JSON 行
- **THEN** 工具跳过损坏行，返回其余完好消息（对齐会话加载侧的逐行容忍语义），不整体失败

### Requirement: 会话读取工具的注册门控

`read_instance_session` SHALL 仅在每实例会话模式（`agent.session.per-instance`，默认开启）下注册：该模式是工具数据源（每实例独立会话文件）的存在前提。legacy 全局会话键模式下（所有实例共用一个会话文件）工具 MUST 不注册。工具注册 SHALL NOT 依赖 IPC 开关——工具为纯本地共享目录文件读，无需 IPC 传输。

#### Scenario: 每实例模式开启时注册
- **WHEN** `agent.session.per-instance=true`（默认）且 Agent Loop 初始化
- **THEN** `read_instance_session` 出现在工具注册表中，无论 IPC 开关取值

#### Scenario: legacy 全局键模式下不注册
- **WHEN** `agent.session.per-instance=false`
- **THEN** `read_instance_session` 不出现在工具注册表中
