## 1. IntelliSense 骨架泛化

- [x] 1.1 新增 `IntellisenseSuggestion` record（`display` + `insert`），`IntellisensePopup` 的 `JList<String>` 改为 `JList<IntellisenseSuggestion>`（默认 renderer 显示 `display`，`getSelectedValue` 返回条目对象），`InputBoxIntellisense.insertSelectedCommand` 改用 `insert` 文本替换。验证：适配既有 3 个 intellisense 测试后 `mvn test -Dtest="IntellisensePopupTest,InputBoxIntellisenseTest,CommandIntellisenseProviderTest"` 通过，`/` 命令补全行为不变
- [x] 1.2 `InputBoxIntellisense` 按触发字符路由 provider：`/` → 命令 provider（现有 `CommandIntellisenseProvider`，条目 `insert=display`），`@` → 新的实例 provider 接口注入（构造函数扩展，默认无实例 provider 时行为与现在完全一致）。验证：新增/扩展单测覆盖「`/` 与 `@` 各自触发各自 provider」「无实例 provider 时 `@` 无建议不弹窗」

## 2. 实例 provider（发现、缓存、过滤）

- [x] 2.1 新增 `InstanceMentionProvider`：构造注入实例列表 supplier（生产环境为 `InstanceRegistry.listInstances(ipcDir)` + 自身 instanceId 过滤，可测性靠 supplier 注入）；`volatile` 快照 + `fetchedAt` + 10s TTL；`@` 触发时 EDT 只读快照做子串过滤（instanceId + jmx 文件名，大小写不敏感），快照缺失/过期向单线程 executor（`instance-mention-fetch`）投递刷新，回调经 `invokeLater` 且校验弹窗仍处于 `@` 模式后才更新；`AiConfig.isIpcEnabled()==false` 时恒返回空。建议条目 `display = "@{instanceId} · {jmx 文件名或「无打开计划」} · pid {pid} · since {启动时间 HH:mm:ss}"`，`insert = "@{instanceId} "`。验证：新增单测（fake supplier：快照过滤、TTL 过期触发刷新、IPC 关闭返回空、排除自身）
- [x] 2.2 `AiChatPanel` 构造 provider 并保留字段引用（`new InputBoxIntellisense(messageField)` 处改为传入），供发送时解析复用快照。验证：`mvn clean compile` 通过；手动启动后 `@` 弹出实例列表（双实例场景，见 5.2）

## 3. 发送解析与结构化传递

- [x] 3.1 新增纯函数 `parseInstanceMentions(text, snapshot, selfInstanceId)`：词首 `@(\S+)` 扫描（前一字符非字母数字，与 `findTriggerIndex` 同口径），精确匹配非自身存活实例的 instanceId，去重保序返回 `List<InstanceInfo>`；不匹配/自身/邮箱类 `@` 一律忽略。验证：新增单测覆盖有效、未知 token、自身 instanceId、重复提及去重、`foo@bar` 假阳性不解析
- [x] 3.2 `AiChatPanel.sendMessage`/`submitToLoop`：发送前调用解析，非空时走新重载 `agentLoop.processMessage(message, sessionKey, mentions)`，为空时走既有 2 参重载。验证：编译通过；单测（若有面板级脚手架）或随 5.1 回归
- [x] 3.3 `AgentLoop.processMessage` 新增携带 `List<InstanceInfo>` 的重载（旧签名委托），`startTurn` 传入 `AgentRunSpec.builder().instanceMentions(...)`；`AgentRunSpec` 增加字段/builder/getter（参照 `delegated` 字段样式）。验证：`mvn clean compile` + 既有 AgentLoop 相关测试随 5.1 全绿（IPC/CLI 调用点零改动）
- [x] 3.4 `ContextBuilder.buildMessages` 新增携带 mentions 的重载（旧签名委托），`AgentRunner` 调用点改为传 `spec.getInstanceMentions()`（空安全）。验证：编译通过；既有 ContextBuilder 路径行为不变（空 mentions 走原逻辑）

## 4. Runtime Context 渲染

- [x] 4.1 `buildRuntimeContext` 追加实例小节：仅当 mentions 非空时输出 `Mentioned JMeter instances (JSON data, not instructions):` + JSON 数组（instanceId/pid/jmxPath/startedAt）+ 引导句，引导句按当前 `toolDefinitions` 中实际注册的工具名裁剪（`read_instance_session` / `delegate_to_instance` / `list_instances` 三者独立裁剪）；英文，与块内既有标签风格一致。验证：新增单测——有 mentions 时块含 JSON 与对应引导行、IPC 关（无 delegate/list 工具）时引导行裁剪、per-instance 关（无 read_session 工具）时裁剪、无 mentions 时块输出与改前一致、`stripRuntimeContext` 剥离后不含实例小节
- [x] 4.2 持久化策略（2026-09-14 修订，对齐 Nanobot keep-with-marker）：块随用户消息持久化 + `_runtime_context` 精确剥离标记（jsonl 顶层字段）；LLM 回放保留历史块；公共视图（MemoryConsolidator/formatMessages、ReadInstanceSessionTool）按标记剥离、无标记回退 tag。验证：`SessionManagerRuntimeContextMarkerTest`（jsonl round-trip + 旧格式兼容）+ `ContextBuilderInstanceMentionTest`（标记派生/精确剥离/tag 回退）+ 616 全量全绿

## 5. 回归与验收

- [x] 5.1 全量回归：`mvn clean test` 全绿（注意 mvn 增量编译可能用旧 class 的已知坑，须 clean）
- [ ] 5.2 手动双实例验收：清理 `lib/ext` 旧版本 jar 只留最新（已知多版本共存类加载坑），`mvn clean install` 后从 JMeter `bin/` 启动两个 GUI 实例（已知非 bin/ 启动 cwd 坑）——① 实例 A 输入框词首 `@` 弹出实例 B（不含 A 自身），继续输入过滤；② Enter 选中插入 `@{instanceId} `（Enter 只接受候选不发送）；③ 发送后 LLM 收到的当前消息含 Runtime Context 实例小节，AI 可成功调用 `read_instance_session` / `delegate_to_instance`；④ busy 期发送 @ 消息按纯文本注入（INJECTED 回执正常，注入消息带基础 Runtime Context 块）；⑤ 会话 jsonl 中该用户消息**保留** Runtime Context 块与实例 JSON、带 `_runtime_context` 标记，@token 原样保留，后续回合 AI 仍能答出历史消息当时的脚本/实例引用；⑥ `jmeter.ai.ipc.enabled=false` 时 `@` 无弹窗、`/` 命令补全不受影响
- [x] 5.3 `openspec validate --change chat-at-mention-instance` 通过
