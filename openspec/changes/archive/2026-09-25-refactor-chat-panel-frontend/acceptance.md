# 验收记录：refactor-chat-panel-frontend

> 依据 `specs/chat-transcript-render/spec.md` 逐 Scenario 验收。状态：**PASS（自动化测试证据）** / **PENDING（需 GUI 手测）**。
> 测试基线：`mvn clean test` 704 tests, 0 failures（2026-09-24，含手测反馈修复：横向滚动/选中复制/细滚动条/自然宽/滚轮穿透/列宽贴内容/控制行主题化/Send 浅色/Stop 浅红/统一输入卡+固定高度/输入卡三控件自绘化/输入卡紧凑化边距+转录细滚动条/展示区圆角卡+子组件圆角裁剪/header + 按钮同款自绘/代码块上下空白/输入卡 4 项 UI 调整/输入框 5 行可见+闭态模型文字正文色）。
> 手测观感修复（2026-09-23 二轮）：①ToAI 选中态换浅色调（accentSoft 底 + accent 描边/对勾）；②输入框空草稿引导占位文案（输入即隐、清空复现）；回归锚 AiChatPanelInputAreaTest 增至 9 项。二轮对抗审查 2 项 CONFIRMED 修复：③占位 ghost 改为先于 super 绘制（文本 UI 最后画 caret，后画会盖住闪烁 caret 柱；non-opaque 下 super 跳过背景填充不会擦掉 ghost）；④onAccent() 随勾选框改色孤儿化 → 连同其唯一消费链（contrastRatio/relativeLuminance/linearChannel）与 WCAG 单测一并清理。
> 自绘控件审查修复（对抗审查 7 项全 CONFIRMED，2026-09-23）：①LAF/缩放刷新冲掉自绘委托——`lookAndFeel` 事件先于 updateComponentTreeUI 扫描，事件回调重套是死代码 → 改为组件 `updateUI()` 内重钉（modelSelector 匿名子类 + SlimScrollBarUI bar/scroller 自愈工厂，内嵌容器同步迁移）；②聚焦闭态闪宿主选中色（BasicComboBoxUI.paintCurrentValue 覆写渲染器结果）→ paintCurrentValue/Background 覆写直绘卡面配色；③BasicComboPopup 黑色硬描边（非 UIResource）→ createPopup 覆写换卡面 separator 描边；④滚轮保留/扫描存活/渲染器闭态三项测试缺口补齐（InputAreaTest 增至 7 项）。
> 手测观感修复（2026-09-24 三轮）：信息展示区（header + 转录）从矩形 matte 描边收进圆角卡外壳（canvas 底色 token 由外壳圆角填充供底色；header 卡内条非不透明、去顶线留底部规则线；viewport 非不透明防矩形层糊角）。对抗审查 2 项 CONFIRMED（各 2/2 票，同一根因）：流内不透明系统便签方角会画进外壳圆角 cutout 区 → 外壳 paintChildren 以圆角形裁剪子组件；回归锚 AiChatPanelInputAreaTest 增至 11 项（展示区祖先卡/viewport 透明/canvas 像素/LAF 扫描存活/圆角裁剪像素）。
> 手测观感修复（2026-09-24 四轮）：header 右上角「+」新建按钮从原生 JButton（宿主 LaF 灰渐变底 + 硬描边）换为 QuietButton PRIMARY——与 Send 同款浅蓝底 + 主题色符号的自绘语言，LAF 扫描自愈由 QuietButton.updateUI 承担；回归锚 AiChatPanelInputAreaTest 增至 12 项（+ 按钮同语言断言 + 扫描存活）。
> 手测观感修复（2026-09-24 五轮，代码块上下空白）：根因 = fenced 块替换为占位行时无条件拼前后 `\n`（行独立所需），与源文本已带的空行叠加——首修只删了渲染处二次补插，标准 LLM 排版（fence 前后各一空行）仍上下各 2 空行。对抗审查工作流（2 视角 × 每发现 2 反驳验证者，14 agents）6 项发现全部 CONFIRMED：标准形态双倍空白（P2，两位审查者独立发现）、相邻代码块间空行堆叠 2-3、消息以 fence 开头顶部多一空行、表格/分隔线与代码块垂直节奏不统一、未闭合 fence 走 inline-code 反转（既有问题，非本次引入，2/2 票确认与修复前一致——记录不修）。完整修复 = 把 fence 紧邻的前后各 1 个 `\n` 并入正则匹配吸收，替换串恰好用这两个换行让占位行独立成行——净换行为零，**渲染空行数恒等于源文空行数**，与表格/段落/分隔线完全同构；前导换行仅在匹配确实吸收了换行或 fence 位于行中时补回（防吞并）。回归锚 codeBlockKeepsSingleBlankLineAboveAndBelow 升级为 codeBlockSpacingMirrorsSourceBlankLines（紧贴 0 空行/标准 1 空行/消息开头无空行/相邻块 1 空行四形态断言）；render() 测试助手补文档复位（多调用不累积）。
> 手测观感修复（2026-09-24 六轮，输入卡 4 项 UI 调整）：①ToAI 复选框改 iOS 风格胶囊开关（JCheckBox 语义保留，CheckBoxIcon 删除，换 ToggleSwitchIcon：off 静音灰轨道滑块居左 / on accent 主题蓝轨道滑块居右，paint 时取色随主题）；②删除「Model」文字标签（字段/布局/主题刷新三处清零）；③模型下拉高度钉 26px（比按钮行 32px 矮，setBounds 覆写在整行高内垂直居中）；④闭态选中模型文字改 accent 浅蓝（渲染器闭态分支与 paintCurrentValue 聚焦路径双点同步）。对抗审查（2 视角 × 每发现 2 反驳验证者，8 agents）3 项 CONFIRMED（2 独立缺陷）：⑤暗色主题（JMeter 默认 Darcula）下 separator 轨道 / elevatedSurface 滑块 / 输入卡底色三者收敛（逐通道差 ≤3），off 胶囊整体融进输入卡 → 暗色分支 off 轨道沉至 canvas−25（与卡面 canvas+10 固定 35 级差）、滑块提亮为白（深轨白钮，即参考形态）；⑥钉高 26 不随 JMeter 无上限缩放（Zoom In 每步 ×1.1）放大的字体生长，≳1.8× 时闭态文字纵向裁剪 → 钉高改为下限，字体行高越过即让位（不裁剪不变式：首选高 ≥ 行高 + 边框 inset）。回归锚 AiChatPanelInputAreaTest 增至 15 项（暗色开关两级像素分离 / 缩放字体让位双形态）。
> 手测反馈修复（2026-09-24 七轮，输入卡 2 项调整）：①消息输入框可见行数 3→5（JTextArea rows 即停靠高度预算——输入卡按首选栈高停靠、滚动区拿剩余，多行草稿不进滚动条即可读）；②闭态选中模型文字由 accent 浅蓝改回正文色（ThemeColors.foreground() 主题自适应：浅色即黑、暗色浅字；渲染器闭态分支与 SlimComboBoxUI.paintCurrentValue 双路径同步——六轮④的撤销，用户诉求「蓝色改黑色」）。对抗审查工作流（2 视角 × 每发现 2 反驳验证者，10 agents）4 条发现全部 CONFIRMED、归并 2 根因（均 P3 测试守护缺口，现行为正确）：a) 闭态颜色生效层零守护——paintCurrentValue 覆写内渲染器返回后又重设前景/背景，第 96 行才是最终上屏色，而渲染器断言测的是被覆盖层、像素测试只排除宿主选中色不排除 accent，单点回退浅蓝全套测试仍绿 → focusedClosedFieldKeepsCardColors 补双向锚（正向：位图存在 foreground() 字形像素，钉生效层真实上屏；负向：逐像素排除 accent）；b) 「5 行」只锚输入量（rows/首选高）且首选高断言为恒真式（JDK JTextArea.getPreferredSize 以 rows 为下限，被 rows≥5 蕴含）→ 停靠布局测试 cardStacksContextAboveInputAndButtonsBelow 补端到端可视高断言（getVisibleRect().height ≥ 5×行高+边框；两位验证者指出锚 field.getHeight() 会假绿——viewport 被钳短时 view 仍保持首选高、只缩视口）。回归锚仍 16 项（既有 2 测试升级强度，无新增方法）。
> 手测反馈修复（2026-09-23）：宽表格右侧内容被卡片裁剪不可达（参考实现同病，有意偏离）→ 表格/代码块包横向滚动容器 + MessageCard 钳宽 + getMaximumSize 活高度，回归 3 项。
> 审查修复（对抗审查工作流 2 confirmed + 429 死票人工复核 3 项，共 5 修复，全部带回归测试）：①终态（取消/出错/空回复）收尾活动卡/思考卡；②卡内增长（reasoning/工具行）滚动跟随；③主题/字号热切换重渲历史卡（内嵌代码块颜色、标题字号）+ 系统行 re-theme；④isChatAtBottom 孤儿删除 + armed 位 javadoc 更新；⑤refreshChatColors 全量 EDT 收口。

## R1 转录按消息卡片流呈现

| Scenario | 状态 | 证据 |
|---|---|---|
| 用户消息气泡 | PASS | TranscriptViewTest.messagesAppendInOrderAsCards；AiChatPanelIpcTurnPresenterTest 27 项（"You: [from cli] …" 回显/命令/领养/倒序） |
| 复制按钮反馈 | PASS（组件级）/ PENDING（点击观感） | TranscriptCardsTest.cardCarriesCopyButtonAndKeepsRawText（按钮存在 + raw markdown 保留）；剪贴板写入与 "Copied ✓" 2s 反馈需 GUI |
| 助手回复成卡 | PASS | IpcTurnPresenterTest.turnStartRendersYouLineAndStopsMode_completionResets（FINAL-ANSWER）等 |

## R2 Markdown 渲染进样式化文档

| Scenario | 状态 | 证据 |
|---|---|---|
| 代码块渲染与复制 | PASS（组件级）/ PENDING（复制点击） | MarkdownRendererTest.fencedCodeBlockEmbedsPanelWithCopyButton（语言标签 + Copy 按钮存在性断言）；宽代码行改为卡内横向滚动（TranscriptCardsTest.longCodeLineClampsInsteadOfClipping，有意偏离参考的裁剪行为） |
| 表格渲染 | PASS | markdownTableEmbedsGridComponent（网格/表头加粗/转义竖线 splitRow 用例）；宽表格不再右缘裁剪——包横向滚动容器并钳到卡宽（TranscriptCardsTest.wideTableClampsToCardBodyInsteadOfClipping / narrowTableKeepsNaturalWidth；实施期修复，见 design「Risks / Trade-offs」偏离记录）。单元格为只读 JTextArea 可选中 + Ctrl+C，右键 Copy Cell / Copy Table(TSV)（tableTsvJoinsCellsForPaste / copyPopupOffersCellAndTableActions）；嵌入滚动条为 SlimScrollBarUI（embeddedScrollersUseSlimScrollbars）；窄表不再拉伸吃满整行宽（maximumSize 钉到首选尺寸，宽/窄表两断言），列宽贴内容不被均分拉宽（tableColumnsHugTheirContentInsteadOfEqualSplit），滚轮悬停表格/代码块上穿透给聊天区垂直滚动（embeddedScrollersLetMouseWheelReachTheTranscript） |
| HTML 不执行 | PASS | htmlTagsStayLiteral + brVariantsConvertOutsideCodeSpansOnly（反引号区间排除） |

## R3 加载指示为独立组件

| Scenario | 状态 | 证据 |
|---|---|---|
| 武装与移除 | PASS | ContextRingTest.usageProgressUpdatesRingWithoutTouchingChatOrLoading；IpcTurnPresenterTest 倒序/交叠残留系列（loadingIndicatorCount 终态归零） |
| 交叠回合不误删 | PASS | IpcTurnPresenterTest.overlappingLiveTurnsDoNotDuplicateOrStrandLoadingIndicator |

## R4 工具活动折叠卡

| Scenario | 状态 | 证据 |
|---|---|---|
| 运行中累计 | PASS | TranscriptViewTest.toolActivityAccumulatesInOneGroupAndFinishesOnNewMessage；IpcTurnPresenterTest.concurrentLiveTurnsToolCallSummary…（交叠两回合互不吞没/不重复） |
| 终态折叠（完成/取消） | PASS | **新增** cancelledTerminalFinishesActivityGroupAndThinkingCard（取消收尾，审查修复①的回归锚） |

## R5 思考内容折叠卡

| Scenario | 状态 | 证据 |
|---|---|---|
| 思考内容折叠展示 | PASS | IpcTurnPresenterTest.thinkingProgressRoutesReasoningToThinkingCardAndBodyToAssistantCard（思考段→卡、正文→助手卡、正文到达自动折叠）；TranscriptViewTest.addReasoningBlockRendersPreCollapsed |

## R6 智能滚动跟随

| Scenario | 状态 | 证据 |
|---|---|---|
| 上滚不被打扰 | PASS（模型数学）/ PENDING（端到端观感） | ChatScrollerTest（贴底判定/容差/clamp/wasPinned=false 不滚）+ TranscriptView 全插入与增长路径内建协议（审查修复②）；实机滚动交互需 GUI |

## R7 主题跟随与热切换

| Scenario | 状态 | 证据 |
|---|---|---|
| 切换 LAF 历史重绘 | PASS（组件级）/ PENDING（端到端） | TranscriptCardsTest.assistantCardRerendersBakedColorsOnThemeChange（审查修复③：内嵌代码块背景重渲）；ThemeColorsTest（明暗两套语义色 + 判暗）；实机切 LAF 全卡重绘需 GUI |

## R8 渲染通道与线程护栏不变

| Scenario | 状态 | 证据 |
|---|---|---|
| 迟到事件不渲染 | PASS | deliveryAfterSlashNewIsDropped / turnStartQueuedBehindSlashNewIsDroppedByGenerationSnapshot / progressBeforeTurnStartIsDropped（代数 + 活回合集合双滤） |

## 遗留手测清单（PENDING，需用户执行）

1. `mvn clean install`（配 JMETER_HOME；按记忆清 lib/ext 旧 jar）后启动 JMeter GUI：完整回合（发送→工具进度折叠卡→思考卡→回复 markdown/代码块复制→Stop→/new）。
2. 中文拼音输入法：选字确认 Enter 不误发送（IME 防护）。
3. 运行中切 JMeter 明暗 LAF：全部历史卡（含代码块/表格/系统行）即时重绘。
4. 上滚阅读时流式内容不打扰；滚回底部恢复跟随。
5. §5.2 双实例 IPC 抽验：委派/CLI 回合全流显示、Stop 回执、注入回显、busy 拒绝提示（单实例事件等价已由 27 项面板测试覆盖）。
6. 宽表格/超长代码行：卡内出现细圆角横向滚动条（无箭头），滚动后最右列（如 elementId）/行尾完整可见；表格整体与各列宽度均贴合内容（短列如「分类」不留大片空白）；滚轮悬停在表格/代码块上时滚动的是聊天区垂直条；表格单元格可鼠标选中 + Ctrl+C 复制，右键出 Copy Cell / Copy Table 菜单（多轮修复的 GUI 确认项）。**代码块上下空白确认：AI 回复中的代码卡与前后正文的间距为一个普通段落断行距离（不再有大段空白）；代码块紧跟正文（无空行）时贴合；两段连续代码块之间间距与段落间一致（本轮修复的 GUI 确认项）。**
7. 控制行样式：选中上下文条文字/聚焦行（蓝）随明暗主题、ToAI 开关文字为灰色小字（「Model」文字标签已删除，不再出现）、模型下拉字体收小一致；Send 按钮浅蓝色调底 + 主题色文字；回合运行中 Stop 为浅红圆盘深红方块；切 LAF 后以上全部即时重刷（本轮修复的 GUI 确认项）。
8. 统一输入卡与固定高度：上下文条、输入框、模型/按钮行三行同处一张圆角卡（上下文在卡内上方、输入框居中、模型下拉与按钮在卡内下方，位置不变）；聊天区与输入区之间无分割条，输入卡高度固定（转录吃满其余空间），输入框默认完整可见 5 行（多行草稿不进滚动条即可读）；点击输入框整卡亮聚焦环；Ctrl+滚轮字体缩放 / 切 LAF 后输入卡高度自适应且样式即时重刷；智能提示弹窗、@ 提及、IME 选字、Shift+Enter 换行、ToAI 开关、模型切换均不受影响（本轮调整的 GUI 确认项）。**紧凑化确认：输入卡近乎吃满面板宽度（四周 6px 边距，与转录卡、header 左右缘对齐），不再有大片灰白留白；圆角与聚焦环不被裁剪；转录区滚动条与输入卡/内嵌卡同为细圆角覆盖条（透明轨道不再贴死规则线）；消息多到出滚动条时卡片正常换行不裁内容。**
9. 输入卡内三控件自绘样式：ToAI 为胶囊开关（浅色主题：off 静音灰轨道 + 卡面色圆滑块居左、on 浅蓝轨道 + 滑块居右；暗色主题如 Darcula：off 轨道加深 + 白色滑块，胶囊不融进卡面；点击切换状态即滑块左右移动；无宿主灰底与点线焦点框）；输入框空内容时显示灰色引导占位文案（输入任何字符即消失，清空或 /new 后复现），滚动条为细圆角拇指（无箭头、透明轨道），滚轮仍可滚动长草稿；模型下拉为扁平浅底 + 圆角描边（聚焦亮环、静音 chevron，无原生渐变箭头），高度比按钮行矮且在行内垂直居中，闭态选中模型文字为正文色（黑色/暗色浅字，阅读值不做高亮链接）；下拉弹出列表可用（边框为卡面灰描边而非黑色）、模型切换正常；**聚焦模型下拉后选完/Esc/Tab 闭态不闪宿主选中蓝色；高倍 Ctrl+滚轮缩放下拉随字体长高、闭态文字不被裁剪**；切 LAF 或 Ctrl+滚轮缩放后以上三件（含表格/代码块内嵌细滚动条）仍保持自绘样式（本轮修复的 GUI 确认项）。
10. 信息展示区圆角卡（header + 转录收进与输入卡同一张圆角卡语言）：展示区四角为圆角（旧矩形 matte 描边消失），卡内底色与改前转录区一致（canvas token 未变）；header 无顶线，其底部规则线是卡内分隔（距卡片边缘）；header 右上角「+」新建按钮为与 Send 同款的浅蓝圆角自绘钮（不再是灰色渐变原生钮，hover 变色、无硬描边）；滚动到底与中途时，系统便签（如"已加载历史会话"）与消息卡片不把展示区四角方角化（圆角裁剪生效，cutout 角透出面板底色）；转录细滚动条不溢出右下圆角；切 LAF 后以上全部保持（本轮修复的 GUI 确认项）。
