# Proposal: refactor-chat-panel-frontend

## Why

当前聊天前端是「单 `JTextPane` + `text/html` + Flexmark→HTML + HTMLEditorKit」路线：全部消息共享一个 HTMLDocument，历史包袱集中在渲染层——loading 指示的移除靠全文档 O(N) 文本扫描（`removeLoadingIndicator`，MessageProcessor.java:218-257）、HTMLDocument 段落终止符吸收类缺陷反复复发（见项目记忆 htmldoc-paragraph-terminator-absorption）、主题适配受 Swing HTML view 缓存制约（只能靠组件底色规避）。对标分析（`docs/featherwand-chat-frontend-analysis.md`）验证了另一条在同类插件中成熟落地的路线：**每消息一张 Swing 卡片 + 手写 markdown→StyledDocument 渲染 + 代码块/表格内嵌活组件**，渲染复杂度从「共享文档的偏移记账」降为「组件树增删」，天然规避上述三类问题，并带来现代视觉（圆角气泡、折叠卡、自绘按钮）。

## What Changes

- **转录容器换轨**：单 `JTextPane` HTMLDocument → `TranscriptView`（`JPanel + BoxLayout(Y_AXIS) + 底部 glue`，实现 `Scrollable`，组件即消息）；每消息一张 `MessageCard`（用户=圆角气泡、助手=扁平全宽，正文 `JTextPane + StyledDocument`）
- **markdown 渲染换轨**：Flexmark→HTML→insertBeforeEnd → 手写正则状态机直接渲染进 StyledDocument；代码块/表格/分隔线经 `StyleConstants.setComponent` 以活组件（含 Copy 按钮、等宽代码区、网格表格）嵌入文档。**保留流式「裸文本追加 + 终态整卡重渲」语义等价物**（当前为终局一次性渲染，换轨后语义不变）
- **loading 指示换组件**：HTML div + O(N) 文本扫描移除 → `ThinkingRow` 组件（动画省略号，add/remove 即 O(1)）；`loadingIndicatorArmed` 防重复武装逻辑保留
- **进度呈现升级（既有能力的重呈现，非新功能）**：回合内工具调用进度行 → `ToolActivityGroup` 折叠卡（"Agent activity · N tool calls"，运行中 spinner/结束自动折叠）；reasoning 思考内容 → `ThinkingCard` 折叠卡（"✦ Thoughts" + 预览）。数据源不变：仍是 TurnEvent PROGRESS 载荷
- **主题系统换轨**：StyleSheet + 组件底色技巧 → `ThemeColors`（零缓存现取 UIManager、亮度判暗、语义色 token）+ `UiTokens`（间距/圆角/字号刻度）；`lookAndFeel` PropertyChange → `refreshTheme()` 递归 applyTheme
- **控件视觉升级**：新增自绘 `QuietButton`/圆形 `StopButton`/Graphics2D 矢量图标（send/copy/plus/chevron）；输入区包圆角边框容器（焦点环；不含旋转渐变动画）
- **不变项（明确）**：`onTurnEvent/dispatch` 回合事件流为唯一显示通道的契约、liveTurnIds/conversationGeneration 过滤、AGENTS.md 记载的 4 处刻意 UX 差异（①竞态注入补画 You 行 ②busy 期命令补画 You 行 ③空闲 /new 短暂武装 loading ④/new 回执事件驱动渲染）、智能滚动 capture-before-append 协议、模型下拉选择器（JComboBox，不引入搜索/pin 弹层）、Shift+Enter/拖拽调高/intellisense/@ 提及/SelectionContextBar/ContextUsageRing、命令路由与 IPC 显示行为（ipc-turn-gui-display spec 全部要求原样满足）
- **不添加（明确排除）**：附件系统、Record 模式、Donate、模型选择弹层（搜索/置顶/最近/CLI 登录态）、Jev 卡片、树发光特效、桌面宠物、旋转渐变动画（AnimatedGradientPainter）、导出聊天

## Capabilities

### New Capabilities

- `chat-transcript-render`: 聊天转录的组件化渲染契约——转录容器结构（卡片流 + glue + Scrollable）、用户/助手消息卡、markdown→StyledDocument 渲染（含活组件代码块/表格）、loading 指示与工具/思考折叠卡的组件化生命周期、主题 token 热切换

### Modified Capabilities

（无——`agent-turn-events`、`ipc-turn-gui-display`、`context-usage-indicator` 等既有 spec 均为行为级要求，本次重构保持其行为不变，仅换实现层）

## Impact

- **重写/新增**（`org.gitee.jmeter.ai.gui`）：`MessageProcessor`（重构为卡片渲染门面，保留对外方法签名供 AiChatPanel 调用点平移）、新增 `TranscriptView`/`MessageCard`/`MarkdownRenderer`/`CodeBlockRenderer`/`TableBlockRenderer`/`ToolActivityGroup`/`ThinkingCard`/`ThinkingRow`/`ChatScroller`（协议从 MessageProcessor 平移）/`theme/ThemeColors`/`theme/UiTokens`/`QuietButton`/`StopButton`/矢量图标类
- **改造**：`AiChatPanel`——`chatArea`(JTextPane) 及 StyleSheet 主题路径替换为 TranscriptView；`dispatch()` 各分支的渲染原语换为卡片 API（过滤逻辑不动）；`MessageProcessor.setAutoScroll` 接线平移
- **可删除**：`gui/render/UiThemeUtil` 中仅服务 HTML 路线的部分（`ensureCjkSupport` 等通用能力保留）、`MarkdownParserHolder`（Flexmark 依赖随 HTML 路线退役；pom 中 flexmark 依赖若无他处引用一并移除）
- **测试**：`agent/testsupport`（RecordingSubscriber 等）不动；引用 `chatArea`/文档内容的面板测试需同步改造；新增渲染组件单测（MarkdownRenderer 语法、TranscriptView 插入/清理、ChatScroller 协议）
- **风险与对策**：项目历史上「JTextPane/AST 增量渲染」路线曾被废弃（记忆 jmeter-chat-render-architecture）——本次不是复活该路线：废弃点是**单一共享文档上的 AST 增量偏移记账**，本次是**组件即消息 + 终态整卡重渲**，与 Feather Wand 已验证方案一致；实施时以 `docs/featherwand-chat-frontend-analysis.md` §4/§12 为对照
