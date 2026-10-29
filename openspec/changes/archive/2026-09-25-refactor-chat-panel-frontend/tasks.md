# Tasks: refactor-chat-panel-frontend

> 实施顺序 = 三阶段（对应 design.md Migration Plan）：§1-§2 并行新建（不接线）→ §3 接线切换 → §4 清理退役 + §5 回归收口。参照实现索引：`docs/featherwand-chat-frontend-analysis.md`（Feather Wand 源码 file:line）；规约：不执行 git add/commit/push，改动就绪报告待用户提交。

## 1. 主题令牌与设计令牌（并行新建）

- [x] 1.1 新建 `gui/theme/ThemeColors`：零缓存现取 UIManager、`luminance<0.5` 判暗、语义色（error/success/warning/accent/canvas/surface/elevatedSurface/subtleSurface/accentSoft/onAccent(WCAG)/separator/shadow/secondaryText/codeBackground/userBubbleBackground）。验证：新增 `ThemeColorsTest` 明暗两套非空 + `isDark` 判定单测通过
- [x] 1.2 新建 `gui/theme/UiTokens`：`SPACE_1..8`/`RADIUS_S/M/L`/控件高度/字号刻度（title/heading/body/label/caption，基于 LaF 字体 `deriveFont` 派生，下限 10）。验证：单测断言令牌常量与字号单调性通过

## 2. 渲染组件与控件（并行新建，不接线）

- [x] 2.1 新建 `gui/render/MarkdownRenderer`（移植 Feather Wand 正则状态机）：两阶段（代码块抽占位 → 按行 + 行内状态机）→ StyledDocument；`<br>` 变体换行保留反引号区间排除。验证：`MarkdownRendererTest` 覆盖标题/粗斜体/行内代码/链接 label/无序列表（含缩进子列表、有序列表、引用块按纯文本呈现的降级锚定用例）/分隔线/`<br>` 字面/HTML 标签字面，全部通过
- [x] 2.2 新建 `CodeBlockRenderer`（语言标签 + Copy 按钮 + 2000ms 反馈 + 等宽只读区，`setComponent` 嵌入）与 `TableBlockRenderer`（分隔行识别、`\|` 转义、GridLayout + 表头加粗 + plain JLabel 禁 HTML）。验证：并入 `MarkdownRendererTest` 的围栏代码/表格用例（含复制按钮存在性断言）通过
- [x] 2.3 新建 `gui/ChatScroller`：贴底容差判定 + `captureBeforeAppend/scrollToBottomIfPinned` 协议（平移现 `isChatAtBottom/scrollToBottom` 实现）。验证：`ChatScrollerTest`（贴底跟随/上滚不动/滚回恢复）通过
- [x] 2.4 新建 `gui/TranscriptView`（JPanel + BoxLayout(Y_AXIS) + glue + Scrollable；`relayout` max 钉扎；addUserMessage/addAssistantMarkdown/addSystemMessage/showThinking/hideThinking/addToolActivity/appendReasoningToken/addReasoningBlock/clearTranscript(逐个 dispose)/refreshTheme；全部变更方法内部走 ChatScroller 协议；EDT 断言）。验证：`TranscriptViewTest`（插入顺序、卡片计数、清屏 dispose、宽度 tracks viewport）通过
- [x] 2.5 新建 `gui/MessageCard`（USER 圆角气泡/accentSoft + 描边；ASSISTANT 扁平；头部 sender+Copy；正文 JTextPane+StyledDocument；包私有观测钩子）与折叠卡 `ToolActivityGroup`（spinner 120ms/finish 幂等自动折叠/新内容前自动收尾）、`ThinkingCard`（预览 60 字符）、`ThinkingRow`（400ms 点动画，dispose 停表）。验证：单测断言角色样式差异、finish 折叠态、Timer dispose 被调用
- [x] 2.6 新建控件：`QuietButton`（QUIET/OUTLINED/PRIMARY/文本/iconOnly）、圆形 `StopButton`、`ActionIcons`（send/copy/plus/chevron，GeneralPath 手绘，颜色随 foreground）。验证：单测（构造变体、图标非空尺寸）通过
- [x] 2.7 阶段验证：`mvn clean test` 全绿（新组件零接线，旧路径不受影响）

## 3. 接线切换（AiChatPanel + MessageProcessor）

- [x] 3.1 `MessageProcessor` 转门面：公开签名保持，内部委托 TranscriptView/MarkdownRenderer；删除 `setAutoScroll` 接线与 `appendHtml/insertBeforeEnd` 路径、O(N) `removeLoadingIndicator`（armed 逻辑留在面板）。验证：编译通过 + 既有调用点无签名变更（grep `messageProcessor.` diff 为零或纯删）。【实施偏差记录】3.3 全量换轨后 MessageProcessor 生产零调用点，按 4.2 孤儿清零原则整体删除（含门面测试），MessageCard 直接使用 MarkdownRenderer
- [x] 3.2 `AiChatPanel` 构造换轨：`chatArea`(JTextPane/html) → `TranscriptView` + JScrollPane；删除 StyleSheet/applyChatTheme 底色路径；`propertyChange("lookAndFeel")` → `refreshTheme()` 递归 applyTheme；字体路径（ensureCjkSupport/deriveFont/baseChatFontSize 缩放）平移。验证：面板启动显示欢迎 markdown 卡；`mvn clean test` 编译/既有面板测试通过
- [x] 3.3 `dispatch()` 渲染原语逐分支替换（design D3 表格）：You 行→气泡卡、arm/removeLoadingIndicator→showThinking/hideThinking（armed+liveTurnIds 判据原样）、工具进度→ToolActivityGroup（progressiveToolCallTurnIds per-turn 判重语义映射为换组条件）、reasoning→ThinkingCard、handleAgentResponse→addAssistantMarkdown、INJECTED/REJECTED_BUSY/取消回执→addSystemMessage、USAGE→ContextUsageRing 不动。验证：对照 AGENTS.md 4 处刻意 UX 差异逐条手测脚本通过（注入回显、busy 命令 You 行、空闲 /new 短暂武装、/new 回执事件驱动）
- [x] 3.4 清屏/重置路径平移：`setText("")`→`clearTranscript()`+欢迎卡；`clearTranscriptForRemoteReset` 翻代数不变；关闭整合 `INSTANCE` 清空消息区调用点适配。验证：`/new`、远程 /new、"+"、关闭整合四路径手测清屏正确
- [x] 3.5 输入区重皮肤 + IME 防护：回形针不加（无附件）；按钮换 QuietButton/StopButton（send↔stop 互斥与 setButtonToStopMode/SendMode 调用点不变）；输入区圆角边框容器（FocusListener 聚焦环）；移植 IME 组合态防护（InputMethodListener 计数判据，Enter 发送前检查）。验证：中文拼音选字确认 Enter 不误发送（手测）；Shift+Enter 换行/intellisense Enter 接受候选/@ 提及不受影响（现有 InputBoxIntellisense 测试通过）
- [x] 3.6 阶段验证：`mvn clean test` 全绿；`mvn clean install -DskipTests` 后按记忆规约清 lib/ext 旧 jar，GUI 手测一轮完整回合（发送→工具进度折叠卡→思考卡→回复 markdown/代码块复制→Stop→/new）

## 4. 清理退役

- [x] 4.1 删除 HTML 路线残留：`chatArea` 字段与遗留引用、`MarkdownParserHolder`、`gui/render/UiThemeUtil` 中仅服务 HTML 的方法；全局 grep flexmark 确认无他处引用后移除 pom 依赖。验证：`mvn clean test` 全绿 + `grep -r flexmark src/ pom.xml` 零命中
- [x] 4.2 死代码扫描：本次改动产生的孤儿方法/导入清零（不触碰既有无关死代码）。验证：IDE 无未使用警告新增；`openspec validate refactor-chat-panel-frontend` 通过

## 5. 回归验收（跨任务集成）

- [x] 5.1 对照 `specs/chat-transcript-render/spec.md` 全部 8 条 Requirement 的 Scenario 逐条验收（含 LAF 明暗热切换历史卡重绘、代码块复制、HTML 字面呈现、交叠回合不误删 loading、上滚不打扰）。验证：逐条记录通过/失败清单，失败项修复后复验
- [x] 5.2 对照 `openspec/specs/ipc-turn-gui-display/spec.md` 抽验 IPC 显示行为（委派/CLI 回合全流显示、Stop 回执、注入回显、busy 拒绝提示）在新渲染下不回归。验证：双实例手测脚本（记忆 WSL2-IPC-NETWORKING 之外的常规 loopback 场景）通过
