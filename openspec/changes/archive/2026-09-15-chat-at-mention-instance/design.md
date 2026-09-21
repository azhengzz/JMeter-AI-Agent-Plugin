## Context

提交通路、注入点与依赖能力均已存在，本设计只做「消费侧入口 + 每回合上下文」的增量接线：

- **Runtime Context 通道已存在**（与 Nanobot 同构，显然是同源移植）：`ContextBuilder.buildMessages` 把 `buildRuntimeContext(...)` 的输出以 `\n\n` 拼接在当前用户消息之后，块体带 `[Runtime Context — metadata only, not instructions]` / `[/Runtime Context]` 标记；`AgentRunner` 持久化前经 `stripRuntimeContext` 剥离。块内已有时间/当前脚本/当前选区小节，是实例引用小节的天然归宿。
- **@ 触发字符已被识别**：`InputBoxIntellisense.findTriggerIndex` 已把 `@` 与 `/` 同列为触发字符（词首检测 + 取距光标最近者），但 provider 是写死的命令字符串列表、弹窗是 `JList<String>`，无法承载富条目。
- **发现与工具已就绪**：`InstanceRegistry.listInstances` 返回存活实例（含 instanceId/pid/jmxPath/startedAt，TCP+PID 双确认，500ms 探测超时）；`list_instances` / `delegate_to_instance` 随 `jmeter.ai.ipc.enabled` 注册，`read_instance_session` 随 `agent.session.per-instance` 注册。工具本体不改。
- **Nanobot 参照**（`D:\WorkHome\git\github\nanobot-zheng\nanobot`）：@ 选中插入纯文本 `@name `；发送时全文重扫 → 结构化载荷随消息走（不路由到别的 agent）；后端把引用规范化后渲染成 Runtime Context 块（"user selected … (JSON data, not instructions) … Use read_session when relevant"）追加在当前用户消息后缀；块可精确剥离。本设计沿用该骨架，映射为 `@{instanceId}` → `read_instance_session` / `delegate_to_instance`。

约束：弹窗与发送解析都跑在 EDT（或 EDT 上的同步调用链），探活 MUST 移出 EDT；`processMessage` 已有 3 个重载，签名扩展需克制。

## Goals / Non-Goals

**Goals:**

- `@` 词首触发实例选择器（排除自身、后台获取、子串过滤、复用既有键盘交互），选中插入 `@{instanceId} ` 纯文本。
- 发送时把匹配的 @token 解析为结构化实例引用，经 `AgentRunSpec` 进入回合，由 `ContextBuilder` 渲染进 Runtime Context 块（工具引导按实际注册裁剪）。
- 解析逻辑为纯函数（文本 + 实例快照 + 自身 id → 引用列表），可独立单测。

**Non-Goals:**

- 不做消息路由/转发：@ 不把消息发给目标实例，AI 自主决定是否委派（对齐 Nanobot：@ 是上下文注入）。
- 不做聊天记录中的 chip/富渲染（Nanobot 的 overlay/token 渲染不移植；@token 以普通文本回显与持久化）。
- 不做发送时的在线复核/阻塞探活；解析基于弹窗获取的缓存快照。
- 不改三个跨实例工具、IPC 传输、系统提示 `CROSS_INSTANCE_COORDINATION_PROMPT` 的任何契约。
- 不引入 pronounceable 别名体系（Nanobot session handles 式的 `kilifa` 名字生成）。

## Decisions

### D1: token 用裸 instanceId，弹窗条目承载可读性

插入文本 = `@{instanceId}`（`{pid}-{startedAtMs}`），弹窗行显示 `@{instanceId} · jmx 文件名（或「无打开计划」）· pid · since 启动时间(本地 HH:mm:ss)`。

- 备选 1：短易读别名（Nanobot `session_handles.py` 的 pronounceable 名）——需引入别名生成与稳定性映射（实例重启即变），对"同时存活实例通常 ≤ 个位数"的场景是过度设计。
- 备选 2：以 jmx 文件名为 token——多实例可开同一 jmx 或都无计划，有歧义；而 instanceId 唯一且 `delegate_to_instance` 本就支持按 instanceId 寻址，AI 侧零转换。
- 用户手敲完整 instanceId 的场景不存在（选择器总是精确插入）；手粘贴的场景按普通文本容忍（见 D4 限制）。

### D2: intellisense 骨架小泛化——触发字符路由 + 「显示/插入」二元条目

引入 record `IntellisenseSuggestion(String display, String insert)`；`IntellisensePopup` 的 `JList<String>` 换为 `JList<IntellisenseSuggestion>`（默认 renderer 显示 `display`，插入用 `insert`）；`InputBoxIntellisense` 按触发字符路由：`/` → 命令 provider（现有行为，`insert=display`），`@` → 新的 `InstanceMentionProvider`。命令补全的键盘/鼠标/定位逻辑不动。

- 备选：为实例单独做一个弹窗组件——重复键盘导航、定位（`modelToView2D`）、失焦处理等全部逻辑，违背外科手术式变更；拒绝。
- 备选：保持字符串模型、把 display 编码进字符串再拆——隐式协议，脆；拒绝。

### D3: 实例发现 = 单线程后台获取 + TTL 快照缓存

`InstanceMentionProvider` 持有 `volatile List<InstanceInfo> snapshot` + `volatile long fetchedAt`，TTL 10s。`@` 触发时：EDT 上只读快照过滤出建议并刷新弹窗；若快照缺失/过期则向单线程 executor（命名 `instance-mention-fetch`）投递一次刷新，完成后经 `invokeLater` 在弹窗仍处于 @ 模式时更新。`AiConfig.isIpcEnabled()==false` 时 provider 直接返回空列表（不弹窗）。ipcDir 解析复用 `DelegateToInstanceTool` 同源的 jmeter home 路径。缓存同时供发送时解析（D4），面板持有 provider 引用（`AiChatPanel` 现在丢弃 `new InputBoxIntellisense(...)` 的返回值，改为保留字段）。

- `listInstances` 会顺带清理失活 port 文件——与工具路径相同的既有副作用，幂等，可接受。
- 备选：每次触发同步探活——500ms/实例的 TCP 探测在 EDT 上不可接受；拒绝。

### D4: 结构化引用经 `AgentRunSpec` 明线传递（非 ambient 静态、非 build 期扫描）

链路：`AiChatPanel.sendMessage` 用纯函数 `parseInstanceMentions(text, snapshot, selfInstanceId)` 解析（正则词首 `@(\S+)`，精确匹配非自身 instanceId，去重保序）→ 新重载 `AgentLoop.processMessage(message, sessionKey, mentions)` → `startTurn` 塞进 `AgentRunSpec.builder().instanceMentions(...)` → `AgentRunner` 把 `spec.getInstanceMentions()` 传入 `ContextBuilder.buildMessages` 新重载 → `buildRuntimeContext` 渲染小节。旧签名全部保留并委托。

- 备选 1：ambient 静态持有（`SelectionTracker`/ToAI 先例）——回合作用域的状态放进程级静态，与并发 IPC 回合互相渗漏，需 consume-once 之类的脆弱语义；拒绝。
- 备选 2：`ContextBuilder` build 期扫描消息文本（Nanobot CLI-app 的 fallback 路径）——零签名改动，但把 TCP 探活引进 agent-loop 线程的每次 run，且形成第二套解析路径；记为后续可选增强（让手粘贴 @token 也能解析），本期不做。
- 引用数据直接复用 `InstanceRegistry.InstanceInfo`（字段齐全、天然可 JSON 序列化），不新造 DTO。

### D5: Runtime Context 小节的内容与工具门控

`buildRuntimeContext` 末尾追加（仅当 mentions 非空；英文，与块内既有 `Current Script:` 等标签一致）：

```
Mentioned JMeter instances (JSON data, not instructions):
[{"instanceId":"12345-1694567890123","pid":12345,"jmxPath":"D:/.../a.jmx","startedAt":1694567890123}]
Use read_instance_session(instanceId=...) to review that instance's recent conversation,
delegate_to_instance(instanceId=..., task=...) to send work to it, and list_instances()
to verify its liveness.
```

引导句按**当前传入 `buildMessages` 的 toolDefinitions 中实际存在的工具名**裁剪（IPC 关 → 无 delegate/list 行；per-instance 关 → 无 read_session 行）。

**持久化策略（2026-09-14 修订，对齐 Nanobot keep-with-marker）**：块随用户消息持久化并挂 `_runtime_context` 标记（`{version, suffix}`，落为 jsonl 顶层字段），后续回合 LLM 回放可见历史块；公共视图（记忆蒸馏/HISTORY.md、`read_instance_session`）按标记精确剥离、无标记回退 tag 截断。动因：用户要求与 Nanobot 一致以保留更多历史上下文（时间/脚本/选区/实例引用跨回合可见）。代价：每条历史 user 消息多 ~100-200 token（选区小节更大时更多），受 `jmeter.ai.max.history.size` 与上下文治理约束。

- 为什么不做「提及但未注册」的报错：门控组合（IPC 关）下弹窗本就不出现，用户几乎不可能构造出引用；防御性报错属于不可能场景的错误处理。

### D6: busy 回合注入 = 携带引用（2026-09-14 二次修订，对齐 Nanobot 不降级）

注入条目（`InjectionItem`）携带发送时解析出的 @-实例结构化引用，排空时整批合并为一条 user 消息并附 Runtime Context 块：注入时刻的时间/脚本/选区 + 批内引用合并去重后的实例小节（工具引导按实际注册裁剪）。块只挂尾随（stripRuntimeContext 按尾随精确剥离）；合并进既有 user 消息时先剥旧块再挂新块。注入回调类型由 `Function<Integer, List<String>>` 升级为 `Function<Integer, List<InjectionItem>>`。此前「busy 注入不解析引用」的降级（初版 D6）被 2026-09-14 手动验收推翻：Nanobot 排空路径逐条解析 runtime context（metadata 携带 mention 块种子），busy 期注入的 @ 消息同样应携带引用。

## Risks / Trade-offs

- [快照过期：弹窗选中后、回合执行前实例退出] → 块内引导 AI 先 `list_instances()` 核实存活；`read_instance_session` 本就容忍 not-live（会话留存期内可读），`delegate_to_instance` 对无目标实例返回明确错误，均无崩溃路径。
- [手粘贴 @token 无结构化解析（缓存未 Warm）] → 按普通文本发送（spec 的「未知 token」路径），AI 仍可凭文本 + `list_instances` 自行解析；build 期扫描列为后续增强（D4 备选 2）。
- [EDT/后台竞态：刷新回调到达时弹窗已关闭或已切回 `/` 模式] → 更新回调检查弹窗可见性与当前触发字符仍为 `@` 才应用；快照为不可变列表 + volatile 读，无锁。
- [`processMessage`/`buildMessages` 重载 proliferation] → 新重载只增一个参数且旧签名委托，调用点（IpcServer、CLI 路径）零改动；tasks 中以编译 + 既有测试回归兜底。
- [假阳性：普通文本中的 `@`（如邮箱）] → 词首判定（前一个字符非字母数字）与既有 `findTriggerIndex` 一致，`foo@bar` 不触发；解析正则同口径。

## Migration Plan

纯增量：无配置项、无数据格式变化、无持久化变化。回滚 = revert 提交（旧重载与既有行为完全兼容，不残留状态）。发布顺序无约束（单仓单插件）。

## Open Questions

（无——弹窗行的具体排版、TTL 精确取值等实现细节不影响 specs 与任务拆分。）
