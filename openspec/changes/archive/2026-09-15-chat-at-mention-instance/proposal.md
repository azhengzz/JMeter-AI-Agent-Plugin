## Why

跨实例协作工具（`list_instances` / `read_instance_session` / `delegate_to_instance`）与实例注册表已就绪，但唯一入口是 AI 自主发现——用户想针对另一个 JMeter 实例工作时，只能口头描述并依赖 AI 自己调用发现工具，交互链路长、目标不可控。参照 Nanobot 的 @-mention 机制（输入框 `@` 唤起选择器 → 选中项以结构化元数据随消息发送 → 构造 Runtime Context 块告知 AI 用 `read_session` 等工具消费引用），本变更让用户在聊天输入框直接 `@` 点名另一个存活实例，把"目标实例"作为每回合结构化上下文交给 AI。

## What Changes

- 输入框键入 `@`（词首）唤起**实例选择弹窗**：列出除自身外的存活 JMeter 实例（instanceId、PID、打开的 jmx、启动时间）。复用既有 intellisense 骨架并小幅泛化（触发字符路由到对应 provider；弹窗条目从纯字符串升级为「显示文本 + 插入文本」）。实例列表经后台线程获取（`InstanceRegistry.listInstances` 含 TCP 探活，MUST NOT 在 EDT 同步执行）并短暂缓存。
- 选中后向输入框插入纯文本 token `@{instanceId} `（Nanobot 式，无 chip 组件）；发送时扫描全文，把匹配存活实例的 @token 解析为**结构化实例引用**随消息传递（不匹配的 @token 保持普通文本）。
- 新增**每回合 Runtime Context 注入**：把用户选定的实例引用渲染进既有的 `[Runtime Context — metadata only, not instructions]` 块（追加在当前用户消息后缀，经 `ContextBuilder.buildRuntimeContext` 输出）；块随消息持久化并挂 `_runtime_context` 精确剥离标记（对齐 Nanobot keep-with-marker：LLM 回放可见历史块，记忆/归档/跨实例读取等公共视图按标记剥离）。块内告知 AI 可用 `read_instance_session` 读取该实例会话、`delegate_to_instance` 向其委派任务；引导语按实际注册的工具裁剪。
- 消息**不路由**到目标实例——仍在本地会话内执行，@ 仅做上下文注入与工具引导（对齐 Nanobot：@ 是 context injection，不是转发）。
- 门控与降级：`jmeter.ai.ipc.enabled=false` 时 @ 不触发实例选择；busy 回合经注入队列进入的消息只携带 @token 纯文本（无结构化块）；无其他存活实例时弹窗不出现。

## Capabilities

### New Capabilities
- `chat-instance-mention`: 聊天输入框 @ 实例选择的完整行为契约——触发与发现（后台获取、排除自身、缓存）、token 插入格式、发送时解析、每回合 Runtime Context 块的内容与工具引导、门控与降级路径。

### Modified Capabilities

（无——`instance-coordination` 既有工具契约不变，本变更只新增消费侧入口；`agent-turn-events` 事件流不变。）

## Impact

- **intellisense 包**：`InputBoxIntellisense`（触发字符路由）、`IntellisensePopup`（条目模型 `String` → 显示/插入二元组）、新增实例 provider 类。
- **提交通路**：`AiChatPanel.sendMessage/submitToLoop`（发送时解析 @token）→ `AgentLoop.processMessage` 新重载（携带实例引用）→ `AgentRunSpec` 新字段 → `AgentRunner` → `ContextBuilder.buildRuntimeContext`（渲染块）。
- **复用不改动**：`InstanceRegistry` / `InstanceContext` / IPC 传输 / 三个跨实例工具本体 / 系统提示 `CROSS_INSTANCE_COORDINATION_PROMPT`。
- **无新配置项、无新外部依赖**：沿用 `jmeter.ai.ipc.enabled` 与 `agent.session.per-instance` 既有门控语义。
- **测试**：intellisense provider/解析为纯逻辑可单测；ContextBuilder 块渲染可单测（项目已有 `agent/testsupport` 脚手架与 intellisense 测试先例）。
