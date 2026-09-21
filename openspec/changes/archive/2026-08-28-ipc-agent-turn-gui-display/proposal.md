# IPC 回合在目标实例 GUI 的显示与控制

## Why

经 IPC `/agent` 到达的回合（其他实例的 `delegate_to_instance` 委派、`jmeter-cli` 直连）在目标实例上**对用户不可见且不可控**：

- 委派消息与最终回复不进聊天面板——渲染链（"You: …" append、`AgentSwingWorker.done()` 落最终回复）只存在于 GUI 发送路径；`jmeter-cli` 的 help 文本声称 "the reply also appears in the GUI"，名不副实。
- 按钮状态只由本地发起的回合驱动（`activeWorker`）：委派回合运行期间面板停留在发送模式，STOP 按钮不出现——用户无法终止一个跑在自己实例上的任务（`cancelActiveTask(sessionKey)` 本身支持，只是 UI 没接）。
- 若用户此前发过消息，loop 级残留 `progressCallback` 会把委派回合的工具事件"漏"进面板——只看到工具在动、看不到消息的诡异半状态。
- 上下文与 UI 错位：委派回合正常完成会写入 `sessions/{instanceId}.jsonl`（LLM 记得），用户却从未见过。

多实例协作与 CLI 已落地（`multi-instance-session-ipc` 变更，33/33 未归档），"被委派实例是有人值守的" 这一交互缺口现在补齐。

## What Changes

- **完整对等显示**：跑在当前实例会话上的 IPC 回合（委派与 CLI 直连）在面板渲染完整消息流——来源消息（含前缀）、思考内容、工具事件、进度、最终回复——复用 `handleProgress` / `handleAgentResponse` 现有渲染链。
- **按钮状态对等**：任一当前会话回合在跑（不分发起方），面板进入 Stop 模式：STOP 可见，发送按钮重挂为注入。本地用户可像本地回合一样**注入**委派回合（mid-term injection 语义一致）。
- **手动终止 + 结构化反馈**：STOP 可终止委派/CLI 回合；委派方收到明确的"被目标用户终止"结构化状态与已流式产生的**部分内容**（`IpcResponse` 新增向后兼容字段），与既有 504 超时、409 取消区分；`DelegateToInstanceTool` 结果文案区分终止原因。
- **生命周期系统提示**：超时自取消（504）、busy 快速拒绝（session busy）、手动 STOP 回执，各在面板显示一行系统提示。
- **CLI 直连消息前缀**：`[from cli]` 轻量前缀在 `/agent` 处理器注入消息文本（随消息进 jsonl，单一事实源）；委派消息保持既有 `[delegated-from …]` 前缀原样。
- **废弃残留回调依赖**：IPC 回合不再依赖 loop 级残留 `progressCallback`（半状态根源），改走面板领养路径（复用 `republishListener` 已验证的"面板领养非本地回合"先例）。
- **STOP 的 EDT 阻塞顺带修**：`stopActiveTask` → `cancelActiveTask` 的 latch 等待（≤5s）挪出 EDT（`AgentLoop.java` 既有 TODO）。

**明确非目标**：会话历史回放（TODO 已 🈹）、委派方主动取消（断连检测/新 op）、被终止回合落盘（维持 `isAborted` 跳过，不碰关闭整合保护语义）、新气泡/徽章样式、自动弹出面板、非默认会话（`--session foo`）回合的显示。

## Capabilities

### New Capabilities

- `ipc-turn-gui-display`: IPC 发起回合（委派与 CLI 直连）在目标实例聊天面板的显示契约、按钮/注入控制契约、手动终止与结构化反馈契约、生命周期提示契约，及显示范围边界（仅当前实例会话；面板未创建保持 headless）。

### Modified Capabilities

（无。`multi-instance-session-ipc` 未归档，`instance-coordination` 尚不在 `openspec/specs/` 主树；本变更新增 capability 自含 `/agent` 响应契约的扩展——`cancelled`/`cancelReason`/`partialContent` 为新增可选字段，向后兼容，不修改既有需求语义。）

## Impact

- **IPC 层**：`IpcServer.handleAgent`（CLI 前缀注入、取消路径结构化响应）、`IpcResponse`（新增可选字段）。
- **Agent 层**：`AgentLoop`（回合进度与面板绑定的领养机制，替代 loop 级 `progressCallback` 残留依赖）、`cancelActiveTask`（EDT 阻塞修复）。
- **GUI 层**：`AiChatPanel`（受控访问点、领养渲染、按钮状态由"当前会话有活跃回合"驱动、系统提示行）、`AgentSwingWorker`（如有复用调整）。
- **调用方**：`JmeterCli`（打印终止原因与部分内容）、`DelegateToInstanceTool`（结果文案区分"被目标用户终止"）。
- **协议兼容性**：`IpcResponse` 仅新增可选字段，旧客户端/对端不受影响；无存储格式变更；无配置项新增。
