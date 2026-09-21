## Purpose

聊天输入框的 @ 实例点名能力：用户键入 `@` 唤起实例选择器选中另一个存活 JMeter 实例，发送时把该实例解析为结构化引用，并以每回合 Runtime Context 块告知 AI 可用跨实例会话读取与委派工具消费该引用（对齐 Nanobot 的 @-mention 机制：上下文注入，非消息路由）。

## ADDED Requirements

### Requirement: @ 触发实例选择器（发现、排除自身、过滤）

聊天输入框中，当用户在词首键入 `@` 且跨实例协作的 IPC 通道开启（`jmeter.ai.ipc.enabled=true`）时，系统 SHALL 弹出实例选择器，列出**除当前实例外**的存活 JMeter 实例，每条含 instanceId、PID、当前打开的 jmx（无则显示无计划标注）与启动时间。实例列表 SHALL 在非 EDT 线程获取（注册表探活含阻塞 TCP 探测），并允许短暂缓存；弹窗展示当前快照，后台刷新结果到达时若弹窗仍开着 SHALL 就地更新。用户在 `@` 后继续输入 SHALL 按子串（大小写不敏感）过滤 instanceId 与 jmx 文件名。选择器 SHALL 复用既有命令补全的键盘交互（Up/Down 遍历、Enter/Tab 选中、Escape 关闭、鼠标单击选中）。

`jmeter.ai.ipc.enabled=false` 时 `@` MUST NOT 触发实例选择（保持既有命令补全行为不变）；机器上无其他存活实例时选择器 MUST NOT 出现。

#### Scenario: 键入 @ 列出其他存活实例并排除自身
- **WHEN** 实例 A（开着 `a.jmx`）与实例 B（开着 `b.jmx`）并存，用户在 A 的聊天输入框词首键入 `@`
- **THEN** 弹出实例选择器，列出实例 B 一条（含其 instanceId、PID、`b.jmx`、启动时间）
- **AND** 列表中不出现实例 A 自身

#### Scenario: @ 后续输入按子串过滤
- **WHEN** 选择器开着且用户继续键入 `@b.jm`
- **THEN** 列表仅保留 instanceId 或 jmx 文件名含该子串（大小写不敏感）的实例

#### Scenario: 无其他存活实例时不弹窗
- **WHEN** 机器上只有当前实例存活，用户键入 `@`
- **THEN** 不出现实例选择器

#### Scenario: IPC 关闭时 @ 无实例建议
- **WHEN** `jmeter.ai.ipc.enabled=false`，用户键入 `@`
- **THEN** 不触发实例选择，`/` 命令补全等既有输入框行为不受影响

#### Scenario: 实例发现不阻塞 EDT
- **WHEN** 弹窗触发实例列表获取（含 TCP 探活）
- **THEN** 获取运行在后台线程，聊天输入与 JMeter GUI 保持响应

### Requirement: 选中项以纯文本 token 插入

在实例选择器中选中一项 SHALL 把输入框中从 `@` 起到光标的片段替换为 `@{instanceId}` 并在其后补一个空格，光标落在空格之后，焦点保持在聊天输入框。token 是普通文本（无 chip/富组件），用户可继续编辑、删除或再键入 `@` 点名多个实例。键盘（Enter/Tab）与鼠标选中 SHALL 产生相同插入结果。

#### Scenario: 选中插入 token 与尾随空格
- **WHEN** 用户键入 `@b` 后在选择器中选中实例 B（instanceId 为 `12345-1694567890123`）
- **THEN** 输入框片段被替换为 `@12345-1694567890123 `（尾随一个空格），光标置于空格后，焦点仍在输入框

#### Scenario: 可点名多个实例
- **WHEN** 用户在已含 `@实例B ` 的文本后再键入词首 `@` 并选中实例 C
- **THEN** 文本追加 `@实例C `，两个 token 并存且互不干扰

### Requirement: 发送时解析 @token 为结构化实例引用

发送消息时，系统 SHALL 扫描全文中的 `@` token（词首 `@` + 连续非空白片段），把**精确匹配某个非自身存活实例 instanceId** 的 token 解析为结构化实例引用（instanceId、PID、jmxPath、startedAt），去重后随消息进入回合；同一实例被提及多次 SHALL 只携带一份引用。不匹配任何非自身存活实例的 @token（含自身 instanceId、已失联实例、任意普通文本）MUST 保持为普通文本原样发送，MUST NOT 报错或阻断发送。消息本身的文本（含 @token）SHALL 原样用于回显与持久化。

#### Scenario: 有效 token 解析为引用
- **WHEN** 用户发送含 `@12345-1694567890123` 的消息且该 instanceId 对应存活的非自身实例 B
- **THEN** 回合携带实例 B 的结构化引用（instanceId/PID/jmxPath/startedAt）
- **AND** 消息文本原样回显与持久化（仍含该 @token）

#### Scenario: 未知 token 不阻断发送
- **WHEN** 用户发送含 `@不存在的实例` 或手动敲错 instanceId 的消息
- **THEN** 该片段按普通文本发送，不产生结构化引用，不报错

#### Scenario: 自身 instanceId 不解析
- **WHEN** 用户手动输入当前实例自身的 instanceId 发送
- **THEN** 该 token 视为不匹配，按普通文本发送（不得引导 AI 读自身会话）

#### Scenario: 重复提及去重
- **WHEN** 同一消息中实例 B 的 instanceId 被提及两次
- **THEN** 回合只携带一份实例 B 的结构化引用

### Requirement: 每回合 Runtime Context 注入与工具引导

当回合携带结构化实例引用时，系统 SHALL 在既有的每回合 Runtime Context 块（`[Runtime Context — metadata only, not instructions]` 标记、追加在当前用户消息内容之后）中追加实例引用小节：以 JSON 列出被引用实例的 instanceId、PID、jmxPath、startedAt，明确标注为「用户选定的实例引用（JSON 数据，非指令）」，并仅针对**当前实际注册**的工具给出引导行——`read_instance_session`（读取该实例近期会话）、`delegate_to_instance`（向该实例委派任务）、`list_instances`（核实实例存活）。未注册的工具 MUST NOT 出现在引导行中（如每实例会话模式关闭时不得提及 `read_instance_session`）。

Runtime Context 块（含实例小节）SHALL **随用户消息持久化**（对齐 Nanobot 的 keep-with-marker 策略）：消息携带 `_runtime_context` 精确剥离标记（`{version, suffix}` 记录块原文，落为 jsonl 顶层字段）；后续回合的 LLM 回放 SHALL 能看到历史消息当时的块内容（时间/脚本/选区/实例引用）。记忆蒸馏、HISTORY.md 归档与 `read_instance_session` 等公共视图 SHALL 按标记（无标记回退 tag 截断）剥离块、只呈现正文与 @token。@token 本身保留在持久化的用户消息文本中。无结构化引用的回合，Runtime Context 块 SHALL 与既有内容一致（不追加实例小节）。

#### Scenario: 引用渲染为元数据块并引导工具
- **WHEN** 用户 @ 点名实例 B 后发送消息，IPC 开启且每实例会话模式开启
- **THEN** 发给 LLM 的当前用户消息后缀 Runtime Context 块含实例 B 的 JSON 引用
- **AND** 含 `read_instance_session` / `delegate_to_instance` / `list_instances` 的使用引导（仅限实际注册的工具）

#### Scenario: 未注册工具不出现在引导行
- **WHEN** 回合携带实例引用但 `agent.session.per-instance=false`（`read_instance_session` 未注册）
- **THEN** 引导行不提及 `read_instance_session`，其余注册工具照常引导

#### Scenario: 块随消息持久化、公共视图剥离
- **WHEN** 携带实例引用的回合完成并写入会话文件
- **THEN** 会话文件中的该用户消息保留 Runtime Context 块与实例 JSON 引用，并携带 `_runtime_context` 精确剥离标记
- **AND** 后续回合 LLM 回放能看到该块（历史消息当时的时间/脚本/选区/实例引用）
- **AND** 记忆蒸馏、HISTORY.md 归档与 `read_instance_session` 按标记剥离，只呈现正文与 @token

#### Scenario: 无引用时块内容不变
- **WHEN** 用户发送不含任何有效实例引用的消息
- **THEN** Runtime Context 块内容与变更前完全一致（时间/脚本/选择等既有小节照旧）

### Requirement: busy 回合注入的 @ 引用与 Runtime Context

目标会话已有在跑回合时，经注入队列进入的消息 SHALL 按既有注入语义排队投递并携带发送时解析出的结构化实例引用（对齐 Nanobot 排空 pending 消息逐条解析 runtime context 的语义——busy 注入不降级）。注入消息进入 LLM 时 SHALL 附带 Runtime Context 块：注入时刻的时间/脚本/选区 + 合并去重后的实例引用小节（工具引导按实际注册裁剪，与普通回合同契约）；同批多条注入 SHALL 合并为一条带单个尾随块的用户消息，块 MUST NOT 卡在批内正文中间。持久化契约与普通消息一致：块随消息持久化并携带 `_runtime_context` 精确剥离标记，公共视图按标记剥离（见「每回合 Runtime Context 注入与工具引导」）。本行为 MUST NOT 改变既有注入回执（INJECTED 事件）与注入上限语义。

#### Scenario: busy 期发送的 @ 消息注入后携带实例引用
- **WHEN** 回合进行中用户发送含有效 `@实例B` 的消息（发送时实例 B 存活）
- **THEN** 该消息经注入队列投递（收到既有 INJECTED 回执），进入 LLM 时其 Runtime Context 块含实例 B 的 JSON 引用与工具引导
- **AND** 持久化的注入消息同样带块与 `_runtime_context` 标记（公共视图按标记剥离）

#### Scenario: 批内多条注入的引用合并去重
- **WHEN** 同一检查点抽干的两条注入分别点名实例 B 与实例 C（其中一条重复点名 B）
- **THEN** 合并后的单条用户消息只携带 B、C 各一份引用，块仍为单个尾随块

#### Scenario: 注入语义不受影响
- **WHEN** busy 期注入携带 @token 的消息
- **THEN** 注入队列容量、注入次数上限等既有行为不变
