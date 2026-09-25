# Design: refactor-chat-panel-frontend

## Context

现状（对照 `docs/featherwand-chat-frontend-analysis.md` §13）：

- 转录 = 单 `JTextPane`（`text/html`），全部消息共享一个 `HTMLDocument`，经 `MessageProcessor.appendHtml → insertBeforeEnd(body)` 追加（MessageProcessor.java:115-149）
- markdown = Flexmark `renderToHtml`（`MarkdownParserHolder`）；主题 = StyleSheet + 组件底色（规避 Swing HTML view 缓存，AiChatPanel.java:250-255）
- loading 指示 = HTML div + 全文档文本扫描移除（O(N)，`loadingIndicatorArmed` 拦截重复，AiChatPanel.java:123-129）
- 显示通道 = `onTurnEvent → dispatch`（回合事件流唯一显示通道，liveTurnIds/conversationGeneration 过滤，AiChatPanel.java:681-776）——**本次完全不动**
- 智能滚动 = `isChatAtBottom/scrollToBottom` + MessageProcessor 内 capture-before-append（AiChatPanel.java:326-329）
- 启动无历史回放，仅 markdown 欢迎消息（AiChatPanel.java:982-1000）；输入框 Enter/Shift+Enter 经 KeyAdapter，无 IME 组合态防护（AiChatPanel.java:380-399）

参照实现：Feather Wand（`D:\WorkHome\git\github\jmeter-ai\src\main\java\org\qainsights\jmeter\ai\gui\`，分析文档 §2-§10 有 file:line 索引）。

## Goals / Non-Goals

**Goals:**

- 转录渲染层换轨为「组件即消息」，消灭共享 HTMLDocument 的偏移记账类缺陷（段落吸收、O(N) 指示移除、view 缓存主题坑）
- 视觉语言对齐 Feather Wand（圆角气泡、折叠卡、自绘按钮/图标、设计令牌），功能面不增不减
- `dispatch()` 事件契约、4 处刻意 UX 差异、智能滚动协议、测试脚手架全部保持

**Non-Goals:**

- 不引入附件/Record/Donate/模型弹层/Jev 卡/树发光/宠物/旋转渐变动画/导出（proposal 已列）
- 不改回合事件流、会话持久化、命令路由、IPC 显示行为
- 输入区交互结构初版仅重皮肤（JTextArea + intellisense + @ 提及 + 拖拽调高 splitPane 不动）；实施期后段经用户对照参考插件提出**统一输入卡 + 移除分割条**的追加调整（见 Risks/Trade-offs 偏离记录 ⑧），IME 防护作为既有输入框的缺陷修复顺手移植

## Decisions

### D1 渲染路线：组件即消息 + 终态整卡重渲（不复活 AST 增量路线）

转录容器为 `TranscriptView extends JPanel implements Scrollable`：`BoxLayout(Y_AXIS)` + 底部 `verticalGlue`，一切内容 `insertBeforeGlue` 追加；每消息一张 `MessageCard`（内含自己的 `JTextPane + StyledDocument`）。

- **为什么不是继续单 HTMLDocument**：loading 移除的 O(N) 扫描、段落终止符吸收（记忆 htmldoc-paragraph-terminator-absorption）、主题 view 缓存三类问题都根植于共享文档；组件路线把渲染单位缩小到卡，增删即组件树操作。
- **为什么不是「JTextPane + AST 增量」**（项目曾废弃，记忆 jmeter-chat-render-architecture）：废弃的根因是在单一共享文档上做增量解析的偏移记账；本路线每卡独立小文档、回合终态一次渲染，无增量记账。分析文档 §4.4 验证了同构方案在 Feather Wand 的稳定性。
- **BoxLayout 关键配套**：每次内容变化 `relayout()` 把卡片 `maximumSize` 钉回 `(Integer.MAX_VALUE, preferredSize.height)`，否则 BoxLayout 会把卡片拉伸成最大高度（分析文档 §4.1）。
- **`Scrollable` 契约**：`tracksViewportWidth=true`（宽随视口、长行换行）/`tracksViewportHeight=false`（高由内容驱动）。

### D2 markdown 渲染器：整体移植 Feather Wand 手写正则状态机，Flexmark 退役

`MarkdownRenderer` 直接按对方实现移植（正则四件套 + 两阶段：先抽围栏代码块为占位符，再按行处理 + 逐字符行内状态机），输出进 `StyledDocument`；`MessageProcessor` 保持现有公开方法签名（`appendMessage/appendStyled/appendMarkdown/appendLoadingIndicator/removeLoadingIndicator`），内部委托新渲染器——`AiChatPanel` 调用点平移成本最小。

- **为什么不用 Flexmark→AST→StyledDocument**：AST 遍历路线即被废弃路线；手写渲染器与 Feather Wand 逐行为对齐，便于对照排障。
- **能力取舍（记录在案，与参考实现逐项核对过源码 MarkdownRenderer.java:22-47,135-179）**：新渲染器支持标题/粗斜体/行内代码/链接(仅显示 label)/无序列表（仅第 0 列 `^[-*]\s+`，缩进子列表不识别）/围栏代码/表格/分隔线；**嵌套列表、有序列表（`1. item`）、引用块将以普通文本呈现**——比 Flexmark HTML 弱，但与参考实现完全一致（参考项目同样不支持这三类，bullet 正则锚定第 0 列、无 `>` 分支、无 ordered list 分支）。可接受：换取解析器简单与行为可控，且对照排障时两边行为一致。`<br>` 变体→换行的转换保留反引号区间排除（分析文档 §4.3）。
- 代码块/表格经 `StyleConstants.setComponent` 以活组件嵌入卡内文档（`CodeBlockRenderer`：语言标签 + Copy + 等宽 JTextArea；`TableBlockRenderer`：GridLayout + 转义竖线处理）。**无 HTML 注入面**：新路线天然满足 spec「HTML 不执行」要求；承载模型输出的 JLabel 一律禁 HTML。
- `MarkdownParserHolder` 与 pom 的 flexmark 依赖在切换完成后移除（先确认无他处引用）。

### D3 dispatch() 只换渲染原语，事件契约与过滤逻辑逐行保留

`onTurnEvent/dispatch` 的 switch 结构、`liveTurnIds`/`conversationGeneration`/`loadingIndicatorArmed` 语义原样保留；变化仅是各分支调用的渲染原语：

| 现状原语 | 新原语 |
|---|---|
| `appendYouLine`（"You: " 文本行） | `transcript.addUserMessage(text)` → 用户气泡卡 |
| `armActiveTurn` 的 loading div | `transcript.showThinking()` → `ThinkingRow` 组件 |
| `removeLoadingIndicator`（O(N) 扫描） | `transcript.hideThinking()`（组件 remove，armed 逻辑保留） |
| PROGRESS 工具摘要行（progressiveToolCallTurnIds 判重） | `transcript.addToolActivity(line)` → `ToolActivityGroup` 折叠卡（per-turn 判重语义不变：新回合换新组） |
| reasoning 渲染 | `transcript.appendReasoningToken/addReasoningBlock` → `ThinkingCard` |
| `handleAgentResponse` markdown | `transcript.addAssistantMarkdown(text)` → 助手卡终态渲染 |
| INJECTED/REJECTED_BUSY/取消回执 `appendStyled` | `transcript.addSystemMessage(text, 语义色)` → 系统行卡（左侧色条样式） |
| USAGE 进度 | 不变（`ContextUsageRing` repaint-only，早退路径原样） |

4 处刻意 UX 差异全部落在 dispatch 分支逻辑里，原样通过。`appendYouLine` 的空文档判断（首行无前导换行）随之消失——卡片流无此问题。

### D4 智能滚动：协议平移为独立 `ChatScroller`

`isChatAtBottom/scrollToBottom` 现有实现收进 `ChatScroller`（贴底容差沿用现状 48px 量级；capture-before-append 协议不变：**插入内容前取 pinned 快照，插入后条件滚底**）。`TranscriptView` 的所有变更方法内部统一走该协议，`MessageProcessor.setAutoScroll` 接线删除（滚动职责移入 TranscriptView）。保留「不能在 append 后再探测」（会把跟随判废，记忆 chat-smart-scroll）。

### D5 主题：ThemeColors 零缓存 + UiTokens 令牌 + refreshTheme 递归

- `theme/ThemeColors`：所有色值每次调用从 UIManager 现取（零缓存），`luminance(Panel.background)<0.5` 判暗（不绑 FlatLaf），语义色（error/success/warning/accent/canvas/surface/subtleSurface/separator/secondaryText…）明暗两套；替换现有 `getThemeColor` 散用与 StyleSheet。
- `theme/UiTokens`：`SPACE_1..8`、`RADIUS_S/M/L`、字号刻度（title/heading/body/label/caption 基于当前 LaF 字体派生，下限 10）。
- `propertyChange("lookAndFeel")` → `refreshTheme()`：递归对各卡/折叠卡调用 `applyTheme()`；记忆 chat-theme-adaptation 的「组件底色技巧」随之退役（不再有共享 HTML view 缓存问题，但 welcome/历史卡仍需逐卡 applyTheme 才能重绘）。
- 字体：沿用 `ensureCjkSupport` + `deriveFont`（禁 `new Font(family,…)`，保 CJK 回退链）。

### D6 控件：QuietButton/StopButton/矢量图标 + 圆角输入容器

- `QuietButton`（QUIET/OUTLINED/PRIMARY/文本/图标变体）统一替换头部与操作行按钮；Stop 换圆形自绘 `StopButton`（现有 send↔stop 互斥逻辑与 `setButtonToStopMode/SendMode` 调用点不变，只换组件构造）。
- 图标（send/copy/plus/chevron/spinner 除外）用 Graphics2D `GeneralPath` 手绘，不用 emoji/字体字形（跨 LaF 稳定）。
- 输入区包圆角边框容器（静态描边 + 聚焦环，FocusListener 联动）；**不含** thinking 旋转渐变（Non-Goal）。
- spinner 动画统一 ASCII 帧 + `javax.swing.Timer`（120ms），组件 `dispose()` 停表；`TranscriptView.clearTranscript` 逐个 dispose 防泄漏（对应 /new、远程重置清屏、关闭整合清空三处调用点）。

### D7 系统消息与欢迎消息的呈现

- INJECTED（"[Injected] You: …" 绿斜体）、REJECTED_BUSY、取消回执、Stop 的 "Stopped." 行 → `addSystemMessage(text, 语义色)`：独立行样式（左色条 + subtleSurface 底），不进会话存储（现状语义不变）。
- 欢迎消息（markdown）→ 渲染为一张扁平卡（走 assistant markdown 路径）；`setText("")` 清屏 → `transcript.clearTranscript()` + 重加欢迎卡。

### D8 测试策略

- `agent/testsupport`（RecordingSubscriber/GatedScriptAiService 等）不动；面板注入竞态记忆（aichatpanel-test-loop-injection-race）约束保留：`loadModelsInBackground` 不动。
- 新增单测：`MarkdownRendererTest`（各语法 + `<br>` 字面 + 表格转义）、`TranscriptViewTest`（插入/glue/清屏 dispose/卡片计数）、`ChatScrollerTest`（pinned 协议）、`ThemeColorsTest`（明暗两套非空 + 判暗）。
- 引用 `chatArea.getStyledDocument()` 的既有面板测试改为经 `MessageProcessor` 门面或组件观测钩子（包私有 getter，沿用 Feather Wind 可测试性做法，分析文档 §4.1）。

## Risks / Trade-offs

- [JTextPane/AST 路线污名——评审质疑「不是废弃过吗」] → design D1 已划清界限（废弃的是共享文档增量记账，本路线是组件即消息 + 终态重渲）；对照分析文档 §4.1/§4.4 实施与验收。
- [嵌套列表/有序列表/引用块降级为纯文本] → D2 记录取舍（与参考实现一致，非单方面降级）；如后续需要，可在渲染器内按行缩进扩展 bullet 层级 / 加 `^>` 与 `^\d+\.` 分支，纯增量不影响契约。
- [宽表格/超长代码行被卡片右缘静默裁剪] → 实施期发现（验收截图：elementId 列截断不可达），**有意偏离参考实现**（参考 TableBlockRenderer 无任何宽度处理，同样裁剪）。修复：表格网格/代码面板包横向滚动 JScrollPane，MessageCard 布局时把嵌入滚动容器钳到卡宽（窄内容保持自然宽度），并覆写 getMaximumSize 报告活高度防 BoxLayout 陈旧上限裁底。回归：TranscriptCardsTest 3 项（宽表钳制/窄表自然宽/长代码行钳制）。手测追加两项优化：①单元格 JLabel→只读 JTextArea（点击选中 + Ctrl+C；另加右键 Copy Cell / Copy Table(TSV) 补跨单元格无法拖选的批量复制路径）②嵌入滚动条换 SlimScrollBarUI（细圆角拇指/无箭头/透明轨道，颜色绘制时现取 ThemeColors 随主题）。回归：MarkdownRendererTest 3 项（TSV/菜单项/细滚动条安装）。二轮手测反馈再补两项：③窄表被拉伸吃满整行宽、右侧大片空白 → 根因是 JTextPane 段落布局会拉伸 maximum>preferred 的嵌入组件（JViewport 再补一刀），钳宽时把 maximumSize 一并钉到同一尺寸即消除拉伸（TranscriptCardsTest 宽/窄表两断言追加 max==pref）；④滚轮悬停在表格上被内嵌滚动容器吞掉 → SlimScrollBarUI.install 同时移除该容器的 MouseWheelListener，滚轮事件冒泡给外层转录区垂直滚动条（MarkdownRendererTest.embeddedScrollersLetMouseWheelReachTheTranscript）；⑤整表已自适应但列宽仍被 GridLayout 均分、短列（如分类/命令名列）内部大片空白 → 表格网格换 GridBagLayout，列宽 = 该列最大内容宽，单元格横向填充保持边框连贯（MarkdownRendererTest.tableColumnsHugTheirContentInsteadOfEqualSplit）；⑥输入区周边控制行（选中上下文条/ToAI/Model 标签/模型下拉）样式接入聊天设计令牌——SelectionContextBar 增 applyTheme()（依旧只 repaint 不 revalidate，硬编码钢蓝→ThemeColors.info()），ToAI/Model 标签 caption 字体 + secondaryText，下拉 caption 字体，AiChatPanel.applyControlBarTheme() 构造与 LAF 切换统一重刷（ThemeColorsTest.errorSoftIsAPaleRedTintDistinctFromError / TranscriptCardsTest.primaryButtonUsesAccentTextOnSoftTint）；⑦Send 按钮深色实底太重 → PRIMARY 变体改 accentSoft 浅色调底 + accent 文字；Stop 圆盘同系改 errorSoft 浅红底 + error 方块（StopButton/QuietButton 均绘制时现取，随主题）；⑧用户对照参考插件的统一输入区再提追加调整 → **移除 verticalSplitPane（拖拽调高能力随之移除，用户明确要求固定高度）**，上下文条/消息输入框/模型按钮三行收进同一张 RoundedBorderPanel 圆角输入卡（inputArea BorderLayout：NORTH=contextRow / CENTER=messageScrollPane / SOUTH=controlsRow，位置不变），卡内三行 setOpaque(false) 露出卡的 elevated 圆角底、外衬 UiTokens 间距，聚焦环仍由 messageField 焦点驱动（整卡亮环，同参考输入卡）；面板主布局改 `add(chatPanel, CENTER) + add(bottomPanel, SOUTH)` 固定高度输入坞（BorderLayout SOUTH 取 preferred 高，字体缩放/LAF 切换后自适应）。回归：AiChatPanelInputAreaTest 2 项（无分割条 + 三行同卡；真实布局坐标验证上下次序）；⑨统一卡内的宿主 LaF 原生控件（Metal 系勾选框/箭头滚动条/渐变下拉）与自绘设计语言冲突 → 三个控件全部收进令牌体系：ToAI 复选框换自绘 CheckBoxIcon（未选中=separator 圆角描边、选中=accent 填充 + onAccent 对勾，绘制时现取色随主题、setFocusPainted(false) 去点线焦点框）；输入框滚动条套 SlimScrollBarUI（新增 installScrollbars 入口——只换条 UI 不剥 MouseWheelListener，输入框必须保留滚轮滚长草稿的能力，与转录内嵌容器的「滚轮穿透」路径分开）；模型下拉换自绘 SlimComboBoxUI（BasicComboBoxUI 子类：elevated 扁平场 + 圆角 separator 描边、聚焦亮 focusRing、静音 chevron 按钮替代原生渐变箭头；闭态渲染器 index==-1 强制卡色防列表选中色闪现，弹出列表保持宿主样式；applyControlBarTheme 在 LAF 切换重置 UI 委托后重新套用）。回归：AiChatPanelInputAreaTest 增至 4 项（自绘样式断言 + LAF 切换重套断言）；对抗审查 7 项全 CONFIRMED 后修正：⑨a **「lookAndFeel」事件先于 updateComponentTreeUI 扫描触发**（JMeter 的 DynamicStyle.updateLaf 先 setLookAndFeel 同步发事件、再逐组件 updateUI 换回宿主委托）→ 事件回调里的 instanceof 重套守卫必然在扫描前执行而被冲掉，改用标准 Swing 重钉模式：modelSelector 以匿名子类在自身 `updateUI()` 内重装 SlimComboBoxUI、滚动条经 `SlimScrollBarUI.bar()/scroller()` 工厂自愈（内嵌表格/代码块滚动容器同步换工厂，保留 install() 的滚轮剥离），测试锚改为驱动真实扫描（updateComponentTreeUI 后断言委托与滚轮契约，废弃反射直达私有方法的旧锚）；⑨b 聚焦闭态仍闪宿主选中色（`BasicComboBoxUI.paintCurrentValue` 在渲染器之后无条件覆写列表选中色）→ SlimComboBoxUI 覆写 paintCurrentValue/paintCurrentValueBackground 以绘制时取色直绘卡面配色，回归用像素断言（全图无 ComboBox.selectionBackground 色）；⑨c `BasicComboPopup` 写死的黑色描边（非 UIResource，宿主不可主题化）→ createPopup 覆写换卡面 separator 线描边（弹层行样式仍由宿主列表渲染），popup() 观测钩子供断言。回归：AiChatPanelInputAreaTest 增至 7 项；⑩手测截图反馈两处观感：①ToAI 选中态 accent 深实底太重 → 改浅色调（accentSoft 填充 + accent 细描边与对勾，对齐 ⑦ 主按钮的浅色语言，绘制时现取色随主题）；②消息输入框空草稿缺引导 → 匿名子类 paintComponent 在文档为空时画占位文案（次级文字色，绘制时现取随主题；可见性纯由文档长度驱动——首字符（含 IME 组合态）落入即隐、清空（/new 同理）即现，无状态位无监听器）。回归：AiChatPanelInputAreaTest 增至 9 项（选中图标像素断言 accentSoft/accent；占位 ghost 显→隐→复现）。二轮对抗审查 2 项 CONFIRMED 修复：⑩a 占位 ghost 原画在 super 之后，而文本 UI 在 paintSafely 最后才画 caret——聚焦空字段时占位字形墨迹盖住闪烁 caret 柱（1-2px 缺口）→ ghost 改为先于 super 绘制，caret 与输入文字浮于占位符之上（原生占位观感）；安全性在于该字段 setOpaque(false) 时 super 跳过背景填充、不会擦掉先画的 ghost。⑩b 复选框改色使 ThemeColors.onAccent() 失去唯一生产消费者（仅剩单测在测死 API）→ 按孤儿规则连同其唯一消费链 contrastRatio/relativeLuminance/linearChannel 与 WCAG 单测一并删除（QuietButton 注释同步去引用）；⑩c 手测截图反馈输入卡四周面板留白过大（左右/下各 18px）不够紧凑 → 统一水平基准 6px：面板级页边距 10→6、聊天滚动区横向 5→0、转录卡流横向 4→0、输入坞左右下→0（仅留上方 8px 与转录分隔）、header 内衬 12→6——转录卡/输入卡/header 左缘全部对齐，输入卡左右留白 18→6px、下留白 18→6px、转录↔输入卡间隙 25→16px；聚焦环向内收（inset=1）不因小边距裁剪。卡内衬距不动（卡内呼吸感保留）。紧凑化对抗审查 3 项 CONFIRMED 修复：⑩c-a 转录滚动容器原本仍是宿主 LaF 滚动条，横向边距归零后 17px 不透明轨道直接贴住聊天区 1px 规则线、且与输入卡/内嵌卡的细条语言不一致 → 换 SlimScrollBarUI.scroller（透明轨道覆盖条，滚轮保留、LAF 扫描自愈，水平策略显式 NEVER）；⑩c-b 对齐注释原写「双方同继承 6px 页边距」机制有误——实际是卡流列 6+1（聊天区 matte 描边）与输入卡 6+1（RoundedBorderPanel 自绘 1px 内缩）两条独立路径的巧合重合，注释已改写为准确机制（两条 +1 任一被改动都会单侧错位）；⑩c-c 6px/4px 是 SPACE 刻度（4/8/12…）外的裸字面量 → 新增 UiTokens.PAGE_MARGIN=6 语义 token（页面级边距，刻意紧于 SPACE_2），页边距/header/滚动区间隙全部 token 化；⑩d 手测截图反馈信息展示区（header + 转录）仍是矩形描边（chatPanel 的 1px matte），与下方圆角输入卡语言不一致 → 展示区整体收进圆角外壳：chatPanel 去 matte 改非不透明，包进 RoundedBorderPanel 的 canvas 变体（canvasShell=true——外壳沿用转录区原 canvas 底色 token，由外壳的圆角填充供底色，卡内对比与改前一致；浅色主题下 canvas 与 elevated 可同值，卡片层次本就靠描边），header setOpaque(false) 露出壳底、matte (1,0,1,0)→(0,0,1,0)（顶线由卡轮廓替代，底部分隔线留作卡内规则线）；viewport 随之 setOpaque(false)（不透明的矩形 viewport 会糊掉外壳圆角；复绘锚链上移至面板根——流式增长的卡片全部非不透明，脏区条带经外壳 paint 全链重画；流内仅系统便签/表格单元格不透明且为一次性静态渲染，无自我复绘擦描边的路径）。绘制合同变更：RoundedBorderPanel 的描边/聚焦环从 paintComponent 移到 paint() 末尾叠画（子组件之上）——展示卡内容按设计顶到卡缘（外壳 1px 内容衬距），先画描边会被子组件不透明填充擦掉内半边；输入卡内容衬 12px 无重叠、观感不变。回归：AiChatPanelInputAreaTest 增至 10 项（展示区祖先卡/viewport 透明/canvas 像素锚，LAF 扫描后存活锚）。本轮对抗审查 2 项 CONFIRMED（各 2/2 票，同一根因的两个视角）：流内不透明系统便签（note.setOpaque(true) + subtleSurface，满宽矩形）滚进底部 18px 圆角带时方角直接画进外壳圆角的 cutout 区（钉底时 0-3px 楔形、手动滚动任意便签可整角溢出，右下滚动条 thumb 同理；描边沿弧线走遮不住弧外溢出）——viewport 去不透明只防了一层，便签是同一机制沉在视图内 → 外壳 paintChildren 以圆角形 clip 裁剪子组件（与损伤区 clip 取交不替换；scrollpane 及以下全非不透明，子组件锚定脏区经面板根全链下传必经该方法，clip 无法被绕过）。回归：shellClipsChildrenToTheRoundedShape（离屏像素——内部仍由子组件正常绘制 + 角部 cutout 不被涂满），AiChatPanelInputAreaTest 增至 11 项。
- [长会话卡片数量增长导致 BoxLayout 布局开销] → 卡片数量与现 HTML 路线同量级（会话内消息数不变）；`relayout` 只钉 max 不重排全文；动画 Timer 全部 dispose 管理。已核对参考实现（TranscriptView.java:34-36,412-433）：**无上限、无虚拟化、无旧卡回收**，仅新会话全清；每次插入是全面板 repaint + 单卡局部 revalidate，且其启动可整会话恢复重放（最坏情况比我们更重）依然无截断——无上限卡片流有实战背书。本项目最坏情况（单会话、无历史回放、`/new` 即清）严格小于参考项目，故本次不做截断；若实测卡顿，后续可加上限截断（超出视口很远的旧卡折叠），纯增量不影响契约。
- [渲染回归（样式/时机）] → spec 场景即验收清单；`dispatch` 分支逐条对照迁移（D3 表格）；4 处刻意 UX 差异在 tasks 中列为显式回归项。
- [测试期 mvn 增量旧 class（记忆 mvn-stale-classes）与 lib/ext 旧 jar（记忆 lib-ext-stale-jars）] → 验证统一 `mvn clean test`；联调前清 lib/ext 旧版本 jar。
- [flexmark 移除牵连未知引用] → 移除前全局 grep flexmark/MarkdownParserHolder 引用；pom 依赖删除与代码删除同一 task 内完成。
- [IME 防护移植引入新按键行为] → 仅对「组合中」的 Enter 兜底不发送（InputMethodListener 计数判据，与 Feather Wand 同实现）；现无防护即为潜在缺陷，风险净减。

## Migration Plan

单变更内分三阶段（tasks 对应分组）：

1. **并行新建**：theme/渲染组件/滚动/控件类落库（不接线），配单测——旧路径不受影响，随时可弃。
2. **接线切换**：`AiChatPanel` 构造与 `dispatch` 各分支换新原语；`MessageProcessor` 转门面；清屏/欢迎/拖拽/字体路径平移。此 task 完成后 HTML 路线不再可达。
3. **清理退役**：删 `chatArea`/StyleSheet 主题代码/`MarkdownParserHolder`/HTML 专用工具与 flexmark 依赖；测试收口。

回滚：三阶段各自成 commit-able 单元（按项目规约由用户提交），阶段 2 前回滚零影响；阶段 2 后回滚 = revert 接线 commit。

## Open Questions

（无——范围、路线、取舍均已在 proposal/design 定案）
