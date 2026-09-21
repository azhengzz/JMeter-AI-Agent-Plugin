## ADDED Requirements

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
