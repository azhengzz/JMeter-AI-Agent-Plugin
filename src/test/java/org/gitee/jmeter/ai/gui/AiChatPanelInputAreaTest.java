package org.gitee.jmeter.ai.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.nio.file.Path;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JList;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.BorderFactory;
import javax.swing.border.Border;
import javax.swing.border.LineBorder;
import javax.swing.plaf.basic.ComboPopup;

import org.apache.jmeter.util.JMeterUtils;
import org.gitee.jmeter.ai.agent.AgentLoopFactory;
import org.gitee.jmeter.ai.agent.testsupport.AwaitUtil;
import org.gitee.jmeter.ai.gui.theme.SlimComboBoxUI;
import org.gitee.jmeter.ai.gui.theme.SlimScrollBarUI;
import org.gitee.jmeter.ai.gui.theme.ThemeColors;
import org.gitee.jmeter.ai.gui.theme.ToggleSwitchIcon;
import org.gitee.jmeter.ai.gui.theme.UiTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 输入区结构回归（对照参考插件的统一输入卡）：选中上下文条、消息输入框、
 * 模型/按钮行收进同一张圆角输入卡——上下文条仍在输入框上方、模型/按钮行仍在
 * 下方，位置不变；聊天区与输入区之间不再有可拖拽分割条（固定高度输入坞，
 * 转录吃满其余空间）。组件树 + 真实布局坐标断言，无回合事件参与。
 */
class AiChatPanelInputAreaTest {

    @TempDir
    Path tempDir;

    AiChatPanel panel;
    String previousJMeterHome;

    @BeforeEach
    void setUp() throws Exception {
        previousJMeterHome = JMeterUtils.getJMeterHome();
        JMeterUtils.setJMeterHome(tempDir.toString());

        panel = new AiChatPanel();
        JComboBox<?> selector = field(panel, "modelSelector");
        AwaitUtil.awaitUntil(() -> selector.getSelectedItem() != null,
                "loadModelsInBackground.done() landed (model item selected)");
        SwingUtilities.invokeAndWait(() -> { });
    }

    @AfterEach
    void tearDown() throws Exception {
        AgentLoopFactory.removeTurnSubscriber(panel);
        AgentLoopFactory.reset();
        if (previousJMeterHome != null) {
            JMeterUtils.setJMeterHome(previousJMeterHome);
        }
    }

    @Test
    void inputAreaUnifiesContextInputAndButtonsInOneCard() {
        Component messageField = field(panel, "messageField");
        Component contextBar = field(panel, "selectionContextBar");
        Component sendButton = field(panel, "sendButton");

        assertFalse(containsSplitPane(panel),
                "聊天区与输入区之间不得再有分割条（固定高度输入坞）");

        RoundedBorderPanel card = ancestorCard(messageField);
        assertNotNull(card, "消息输入框必须包在圆角输入卡（RoundedBorderPanel）里");
        assertTrue(isDescendant(contextBar, card),
                "选中上下文条须与输入框同处一张输入卡");
        assertTrue(isDescendant(sendButton, card),
                "发送按钮须与输入框同处一张输入卡（模型/按钮行收进卡内）");
    }

    @Test
    void inputCardControlsUseSelfDrawnStyling() {
        JComboBox<?> selector = field(panel, "modelSelector");
        assertTrue(selector.getUI() instanceof SlimComboBoxUI,
                "模型下拉用自绘 UI（脱离宿主 LaF 原生外观）");

        JCheckBox toAi = field(panel, "injectContextCheckBox");
        assertFalse(toAi.isOpaque(), "ToAI 开关不得透出宿主 LaF 的原生灰底");
        assertTrue(toAi.getIcon() instanceof ToggleSwitchIcon,
                "ToAI 用自绘胶囊开关图标（未选中态）");
        assertTrue(toAi.getSelectedIcon() instanceof ToggleSwitchIcon,
                "ToAI 用自绘胶囊开关图标（选中态）");

        JScrollPane scroller = ancestorScrollPane(field(panel, "messageField"));
        assertNotNull(scroller, "输入框包在滚动容器里");
        assertTrue(scroller.getVerticalScrollBar().getUI() instanceof SlimScrollBarUI,
                "输入框滚动条用细圆角覆盖条（宿主 LaF 箭头滚动条不进卡）");
        assertTrue(scroller.getMouseWheelListeners().length > 0,
                "输入框滚动容器必须保留滚轮处理（长草稿在指针下自滚，"
                        + "这正是与转录内嵌容器 stripWheel 路径的行为分野）");
    }

    @Test
    void lafSweepKeepsSelfDrawnControls() throws Exception {
        // JMeter 的 LaF/缩放刷新对全部窗口跑 updateComponentTreeUI（逐组件
        // updateUI 换回宿主委托），而 "lookAndFeel" 事件在该扫描之前触发，
        // 任何「事件回调里重套」的守卫都在扫描前就位、随即被冲掉——自绘
        // 控件必须在自身 updateUI 内重钉才活得过扫描。此处驱动真实扫描
        // 链路（不经反射直达私有方法），断言委托与滚轮契约在扫描后仍在。
        SwingUtilities.invokeAndWait(() -> SwingUtilities.updateComponentTreeUI(panel));

        JComboBox<?> selector = field(panel, "modelSelector");
        assertTrue(selector.getUI() instanceof SlimComboBoxUI,
                "宿主 LaF 扫描后模型下拉仍是自绘 UI");
        JScrollPane scroller = ancestorScrollPane(field(panel, "messageField"));
        assertNotNull(scroller);
        assertTrue(scroller.getVerticalScrollBar().getUI() instanceof SlimScrollBarUI,
                "宿主 LaF 扫描后输入框滚动条仍是细圆角覆盖条");
        assertTrue(scroller.getMouseWheelListeners().length > 0,
                "宿主 LaF 扫描后输入框滚轮处理仍保留");
        JScrollPane chatScroller = field(panel, "chatScrollPane");
        assertTrue(chatScroller.getVerticalScrollBar().getUI() instanceof SlimScrollBarUI,
                "宿主 LaF 扫描后转录滚动条仍是细圆角覆盖条（与输入卡/内嵌卡同一设计语言）");
        assertNotNull(ancestorCard(chatScroller),
                "宿主 LaF 扫描后转录展示区仍在圆角卡外壳里");
        assertFalse(chatScroller.getViewport().isOpaque(),
                "宿主 LaF 扫描后滚动 viewport 仍非不透明（圆角外壳供底色）");
        assertTrue(selector.getPreferredSize().height < UiTokens.CONTROL_HEIGHT,
                "宿主 LaF 扫描后模型下拉仍是比按钮行矮的自绘钉高");
        JCheckBox toAi = field(panel, "injectContextCheckBox");
        assertTrue(toAi.getIcon() instanceof ToggleSwitchIcon,
                "宿主 LaF 扫描后 ToAI 仍是自绘开关图标（图标不受 UI 委托替换影响）");
        QuietButton newChat = findButtonByText(panel, "+");
        assertNotNull(newChat, "宿主 LaF 扫描后 header 新建按钮仍是自绘按钮");
        assertEquals(QuietButton.Kind.PRIMARY, newChat.kind(),
                "宿主 LaF 扫描后 header 新建按钮仍与 Send 同为 PRIMARY 自绘语言");
    }

    @Test
    void closedComboRendererUsesCardColors() {
        // 闭态（index == -1）渲染必须用输入卡配色，防止弹出列表的宿主
        // 选中色闪进闭合框；空模型项映射为加载占位文案。
        JComboBox<String> selector = field(panel, "modelSelector");
        Component rendered = selector.getRenderer().getListCellRendererComponent(
                new JList<>(), null, -1, false, false);
        assertTrue(rendered instanceof JLabel, "闭态渲染产物是标签");
        JLabel label = (JLabel) rendered;
        assertEquals("Loading models...", label.getText(), "空模型项映射为加载占位文案");
        assertEquals(ThemeColors.elevatedSurface(), label.getBackground(),
                "闭态底色用输入卡卡面色（不用列表选中色）");
        assertEquals(ThemeColors.foreground(), label.getForeground(),
                "闭态选中模型文字用正文色（阅读值，不做高亮链接）");
    }

    @Test
    void focusedClosedFieldKeepsCardColors() throws Exception {
        // 基础委托在渲染器之后把聚焦闭态改刷为列表选中色；自绘 UI 的
        // paintCurrentValue 覆写是闭态文字的最终绘制出口——渲染器闭态分支
        // 设的颜色在这里被重新覆盖，故生效层须双向钉住：正文色字形真实
        // 上屏（正向），宿主选中色与 accent 浅蓝都不得出现（负向，拦住
        // 单点回退浅蓝的回归）。
        JComboBox<String> selector = field(panel, "modelSelector");
        Color selectionBackground = UIManager.getColor("ComboBox.selectionBackground");
        assertNotNull(selectionBackground, "前置：当前主题定义了 ComboBox.selectionBackground");
        BufferedImage image = new BufferedImage(80, 20, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            Rectangle bounds = new Rectangle(0, 0, image.getWidth(), image.getHeight());
            SwingUtilities.invokeAndWait(() ->
                    ((SlimComboBoxUI) selector.getUI()).paintCurrentValue(graphics, bounds, true));
        } finally {
            graphics.dispose();
        }
        assertTrue(containsPixel(image, ThemeColors.foreground()),
                "闭态选中文字以正文色真实上屏（最终绘制出口的正向锚）");
        for (int x = 0; x < image.getWidth(); x++) {
            for (int y = 0; y < image.getHeight(); y++) {
                Color pixel = new Color(image.getRGB(x, y));
                assertNotEquals(selectionBackground, pixel,
                        "聚焦闭态不得出现宿主列表选中色（x=" + x + ", y=" + y + "）");
                assertNotEquals(ThemeColors.accent(), pixel,
                        "聚焦闭态不得回退主题 accent 浅蓝（x=" + x + ", y=" + y + "）");
            }
        }
    }

    @Test
    void popupFrameUsesCardSeparatorBorder() {
        // BasicComboPopup 写死的黑色描边不可被宿主主题化（非 UIResource）；
        // 自绘 UI 用卡面 separator 色替换弹层边框（行样式仍由宿主列表渲染）。
        JComboBox<String> selector = field(panel, "modelSelector");
        ComboPopup popup = ((SlimComboBoxUI) selector.getUI()).popup();
        Border border = ((JComponent) popup).getBorder();
        assertTrue(border instanceof LineBorder, "弹层边框是线描边");
        assertEquals(ThemeColors.separator(), ((LineBorder) border).getLineColor(),
                "弹层边框用卡面 separator 色（不用 BasicComboPopup 的黑色）");
    }

    @Test
    void toggleSwitchIconsUseThemeTokens() {
        // ToAI 开关两态均为胶囊轨道 + 圆滑块（浅色路径）：off 轨道用静音
        // separator 灰、on 轨道用 accent 主题蓝，滑块取 elevatedSurface 卡面
        // 色（对比靠轨道色差）；暗色路径见 toggleSwitchStaysLegibleInDarkTheme。
        // 采样点取滑块圆心与对侧轨道中部，均远离滑块描边的 1px 抗锯齿圈
        //（滑块圆心距对侧采样点 21px > 半径 7 + 描边 1）。
        assertSwitchPixel(ToggleSwitchIcon.off(), 9, 9,
                ThemeColors.elevatedSurface(), "off 滑块用卡面色");
        assertSwitchPixel(ToggleSwitchIcon.off(), 30, 9,
                ThemeColors.separator(), "off 轨道用静音灰");
        assertSwitchPixel(ToggleSwitchIcon.on(), 25, 9,
                ThemeColors.elevatedSurface(), "on 滑块用卡面色");
        assertSwitchPixel(ToggleSwitchIcon.on(), 4, 9,
                ThemeColors.accent(), "on 轨道用 accent 主题蓝");
    }

    @Test
    void toggleSwitchStaysLegibleInDarkTheme() {
        // 暗色主题下 separator 轨道、elevatedSurface 滑块与输入卡底色三者
        // 数值收敛（默认 Darcula 下逐通道差 ≤3），胶囊会整体融进输入卡。
        // 暗色分支必须拉开两级分离：off 轨道沉到 canvas−25（卡面是
        // canvas+10，固定 35 级差）、滑块提亮为白（深轨白钮，正是参考形态）。
        Color darkCanvas = new Color(0x45, 0x49, 0x4A); // Darcula 的 TextPane.background
        Object originalTextPane = UIManager.get("TextPane.background");
        Object originalPanel = UIManager.get("Panel.background");
        try {
            UIManager.put("TextPane.background", darkCanvas);
            UIManager.put("Panel.background", darkCanvas);
            assertTrue(ThemeColors.isDark(), "前置：暗色画布被判定为暗色主题");

            Color card = ThemeColors.elevatedSurface();
            Color knob = switchPixel(ToggleSwitchIcon.off(), 9, 9);
            Color track = switchPixel(ToggleSwitchIcon.off(), 30, 9);
            assertEquals(Color.WHITE, knob, "暗色下滑块提亮为白");
            assertNotEquals(card, track, "off 轨道不得与卡面重合（防融进输入卡）");
            assertTrue(Math.abs(track.getRed() - card.getRed()) >= 20
                    && Math.abs(track.getGreen() - card.getGreen()) >= 20,
                    "off 轨道与卡面须有可见级差（≥20/255 每通道）");
            assertNotEquals(track, knob, "滑块不得与轨道重合（位置指示可见）");
            assertEquals(Color.WHITE, switchPixel(ToggleSwitchIcon.on(), 25, 9),
                    "暗色 on 滑块同为白");
            assertEquals(ThemeColors.accent(), switchPixel(ToggleSwitchIcon.on(), 4, 9),
                    "on 轨道仍用 accent 主题蓝");
        } finally {
            UIManager.put("TextPane.background", originalTextPane);
            UIManager.put("Panel.background", originalPanel);
        }
    }

    private static Color switchPixel(ToggleSwitchIcon icon, int x, int y) {
        BufferedImage image = new BufferedImage(icon.getIconWidth(),
                icon.getIconHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            icon.paintIcon(null, graphics, 0, 0);
        } finally {
            graphics.dispose();
        }
        return new Color(image.getRGB(x, y));
    }

    private static void assertSwitchPixel(ToggleSwitchIcon icon, int x, int y,
            Color expected, String message) {
        assertEquals(expected, switchPixel(icon, x, y), message);
    }

    @Test
    void modelLabelRemovedAndSelectorSlimmerThanButtons() throws Exception {
        // 控制行不再带 "Model" 文字标签（下拉自身已表达用途）；下拉高度钉在
        // 比按钮更矮的字段高（默认字体下 26 < CONTROL_HEIGHT 32），布局把下拉
        // 拉伸到整行高时其 setBounds 覆写改取自身首选高并垂直居中。
        assertFalse(containsLabelWithText(panel, "Model"),
                "控制行不再渲染 Model 文字标签");

        JComboBox<String> selector = field(panel, "modelSelector");
        assertTrue(selector.getPreferredSize().height < UiTokens.CONTROL_HEIGHT,
                "下拉首选高比按钮行矮（轻量内联控件）");
        SwingUtilities.invokeAndWait(() ->
                selector.setBounds(0, 0, 200, UiTokens.CONTROL_HEIGHT));
        int slim = selector.getHeight();
        assertEquals(selector.getPreferredSize().height, slim,
                "布局拉伸后下拉保持自身矮高度");
        assertEquals((UiTokens.CONTROL_HEIGHT - slim) / 2, selector.getY(),
                "下拉在整行高内垂直居中");
    }

    @Test
    void inputFieldShowsAtLeastFiveFullRows() {
        // 输入框可见行数由 JTextArea rows 决定（首选高 = 行数×行高 + 边框，
        // 输入卡按首选栈高停靠，行数就是输入框的高度预算）：至少 5 行完整
        // 可见，多行草稿不进滚动条即可读。
        JTextArea field = field(panel, "messageField");
        assertTrue(field.getRows() >= 5, "输入框至少 5 行");
        int lineHeight = field.getFontMetrics(field.getFont()).getHeight();
        int insets = field.getBorder().getBorderInsets(field).top
                + field.getBorder().getBorderInsets(field).bottom;
        assertTrue(field.getPreferredSize().height >= 5 * lineHeight + insets,
                "首选高覆盖 5 行正文（含上下边框）");
    }

    @Test
    void selectorHeightYieldsToScaledFontMetrics() throws Exception {
        // JMeter 缩放（Zoom In 每步 ×1.1、无上限）会放大 UIManager 字体，
        // 钉死的矮字段在高倍率下会裁掉闭态选中文字的上下沿：钉高只作默认
        // 字体的下限，字体行高一旦越过它就让位。不裁剪不变式钉在两个形态
        // 上（默认字体 / 2.5× 放大字体）：首选高 ≥ 字体行高 + 边框 inset。
        JComboBox<String> selector = field(panel, "modelSelector");
        Insets insets = selector.getBorder().getBorderInsets(selector);
        Font baseFont = selector.getFont();
        try {
            assertTrue(selector.getPreferredSize().height >= selector
                    .getFontMetrics(baseFont).getHeight() + insets.top + insets.bottom,
                    "默认字体下满足不裁剪不变式");

            Font scaled = baseFont.deriveFont(baseFont.getSize2D() * 2.5f);
            SwingUtilities.invokeAndWait(() -> selector.setFont(scaled));
            int scaledPreferred = selector.getPreferredSize().height;
            assertTrue(scaledPreferred > UiTokens.CONTROL_HEIGHT,
                    "放大字体下首选高越过钉高下限（跟随字体生长，不恒为 26）");
            assertTrue(scaledPreferred >= selector.getFontMetrics(scaled).getHeight()
                    + insets.top + insets.bottom,
                    "放大字体下仍满足不裁剪不变式（闭态文字完整呈现）");
        } finally {
            SwingUtilities.invokeAndWait(() -> selector.setFont(baseFont));
        }
    }

    @Test
    void transcriptDisplayAreaIsRoundedCanvasCard() throws Exception {
        // 信息展示区（header + 转录）与输入卡同为圆角卡语言：滚动容器包在
        // canvas 底色的圆角外壳里——外壳沿用转录区原有的 canvas 底色 token
        //（此前由不透明 viewport 提供），卡片对比与改前一致；viewport 非不
        // 透明，不透明的矩形层会糊掉外壳圆角。
        JScrollPane chatScroller = field(panel, "chatScrollPane");
        RoundedBorderPanel displayCard = ancestorCard(chatScroller);
        assertNotNull(displayCard, "转录展示区必须包在圆角卡外壳里");
        assertFalse(chatScroller.getViewport().isOpaque(),
                "滚动 viewport 非不透明（底色由圆角外壳提供）");

        BufferedImage image = paintCard(displayCard);
        assertEquals(ThemeColors.canvas(), new Color(image.getRGB(image.getWidth() / 2, 4)),
                "展示区卡内底色沿用转录区的 canvas token");
    }

    @Test
    void shellClipsChildrenToTheRoundedShape() throws Exception {
        // 展示卡外壳必须把子组件裁剪进圆角填充形：转录里的不透明系统便签是
        // 满宽矩形，若不被裁剪，其方角会画进圆角 cutout 区（角部应透出页面
        // 底色，1px 描边沿弧线走遮不住弧外溢出）。采样点 (1, 高-2) 距左下
        // 圆弧圆心 (19, 高-19) 约 25.5px，远在半径 18 之外，是纯 cutout。
        JPanel opaqueChild = new JPanel();
        opaqueChild.setBackground(Color.RED);
        RoundedBorderPanel shell = new RoundedBorderPanel(opaqueChild);
        BufferedImage image = paintCard(shell);
        assertEquals(Color.RED, new Color(image.getRGB(image.getWidth() / 2, image.getHeight() / 2)),
                "内部区域仍由子组件正常绘制（裁剪不得误伤内容）");
        assertNotEquals(Color.RED, new Color(image.getRGB(1, image.getHeight() - 2)),
                "左下角 cutout 区不得被子组件的方角涂满（须裁剪进圆角形）");
    }

    @Test
    void headerNewChatButtonMatchesSendStyling() {
        // header 右上角 + 新建按钮与 Send 同一自绘主按钮语言（accentSoft 底 +
        // accent 符号的 PRIMARY 圆角钮），不再是宿主 LaF 的灰色渐变原生钮；
        // LAF 扫描自愈由 QuietButton.updateUI 重钉承担（见上方扫描存活断言）。
        QuietButton newChat = findButtonByText(panel, "+");
        assertNotNull(newChat, "header 须存在 + 新建会话按钮");
        assertEquals(QuietButton.Kind.PRIMARY, newChat.kind(),
                "+ 按钮与 Send 同为 PRIMARY 自绘语言");
        assertFalse(newChat.isOpaque(), "+ 按钮自绘非不透明（无宿主灰底）");
        assertFalse(newChat.isBorderPainted(), "+ 按钮无原生描边");
    }

    @Test
    void emptyInputShowsGuidanceGhostUntilTyped() throws Exception {
        // 空草稿时输入框画引导占位文案（次级文字色），输入任何内容后立即
        // 消失；再清空则重新出现（/new 清屏同理）。判定按文档长度，无状态位。
        JTextArea field = field(panel, "messageField");
        BufferedImage empty = paintField(field);
        assertTrue(containsPixel(empty, ThemeColors.secondaryText()),
                "空内容时绘制引导占位文案");

        SwingUtilities.invokeAndWait(() -> field.setText("hi"));
        assertFalse(containsPixel(paintField(field), ThemeColors.secondaryText()),
                "输入内容后占位文案消失");

        SwingUtilities.invokeAndWait(() -> field.setText(""));
        assertTrue(containsPixel(paintField(field), ThemeColors.secondaryText()),
                "清空后占位文案重新出现（/new 清屏路径同理）");
    }

    /** 离屏渲染字段（headless 无 peer，printAll 不要求可显示）。 */
    private BufferedImage paintField(JTextArea field) throws Exception {
        BufferedImage[] holder = new BufferedImage[1];
        SwingUtilities.invokeAndWait(() -> {
            field.setSize(300, 60);
            BufferedImage image = new BufferedImage(300, 60, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            try {
                field.printAll(graphics);
            } finally {
                graphics.dispose();
            }
            holder[0] = image;
        });
        return holder[0];
    }

    /** 离屏渲染圆角卡外壳（headless 无 peer，printAll 不要求可显示）。 */
    private BufferedImage paintCard(JComponent component) throws Exception {
        BufferedImage[] holder = new BufferedImage[1];
        SwingUtilities.invokeAndWait(() -> {
            component.setSize(240, 120);
            layoutDeep(component);
            BufferedImage image = new BufferedImage(240, 120, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            try {
                component.printAll(graphics);
            } finally {
                graphics.dispose();
            }
            holder[0] = image;
        });
        return holder[0];
    }

    private static boolean containsPixel(BufferedImage image, Color color) {
        for (int x = 0; x < image.getWidth(); x++) {
            for (int y = 0; y < image.getHeight(); y++) {
                if (color.equals(new Color(image.getRGB(x, y)))) {
                    return true;
                }
            }
        }
        return false;
    }

    @Test
    void cardStacksContextAboveInputAndButtonsBelow() throws Exception {
        // 真实布局一次后按面板坐标断言三行的上下次序（位置不变约束）。
        // 不可 display 的容器上 validate() 是 no-op，须手工递归 doLayout
        //（先父后子：父布局先定子边界，再进子容器继续布局）。
        SwingUtilities.invokeAndWait(() -> {
            panel.setSize(500, 600);
            layoutDeep(panel);
        });

        Point context = convertToPanel(field(panel, "selectionContextBar"));
        JTextArea inputField = field(panel, "messageField");
        Point input = convertToPanel(inputField);
        Point send = convertToPanel(field(panel, "sendButton"));
        assertTrue(context.y < input.y, "上下文条保持在输入框上方");
        assertTrue(input.y < send.y, "模型/按钮行保持在输入框下方");

        // 「至少完整展示 5 行」的端到端锚：停靠链（输入卡按首选栈高停靠、
        // 滚动区拿剩余空间）把输入量兑现为可视结果。锚可视矩形而非
        // field.getHeight()——输入区被钳短时 field 作为视口视图仍保持
        // 首选高、只缩视口，getHeight 会假绿。
        int lineHeight = inputField.getFontMetrics(inputField.getFont()).getHeight();
        int fieldInsets = inputField.getBorder().getBorderInsets(inputField).top
                + inputField.getBorder().getBorderInsets(inputField).bottom;
        assertTrue(inputField.getVisibleRect().height >= 5 * lineHeight + fieldInsets,
                "停靠后输入框可视区域至少完整展示 5 行正文");
    }

    /** 深层布局桩：先布局本容器，再递归进入子容器（headless 无 peer，validate 无效）。 */
    private static void layoutDeep(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child instanceof Container) {
                layoutDeep((Container) child);
            }
        }
    }

    // ---- helpers ----

    private Point convertToPanel(Component component) {
        return SwingUtilities.convertPoint(component, 0, 0, panel);
    }

    private static JScrollPane ancestorScrollPane(Component component) {
        for (Container c = component.getParent(); c != null; c = c.getParent()) {
            if (c instanceof JScrollPane) {
                return (JScrollPane) c;
            }
        }
        return null;
    }

    private static boolean containsSplitPane(Container container) {
        for (Component child : container.getComponents()) {
            if (child instanceof JSplitPane) {
                return true;
            }
            if (child instanceof Container && containsSplitPane((Container) child)) {
                return true;
            }
        }
        return false;
    }

    private static RoundedBorderPanel ancestorCard(Component component) {
        for (Container c = component.getParent(); c != null; c = c.getParent()) {
            if (c instanceof RoundedBorderPanel) {
                return (RoundedBorderPanel) c;
            }
        }
        return null;
    }

    private static QuietButton findButtonByText(Container container, String text) {
        for (Component child : container.getComponents()) {
            if (child instanceof QuietButton && text.equals(((JButton) child).getText())) {
                return (QuietButton) child;
            }
            if (child instanceof Container) {
                QuietButton found = findButtonByText((Container) child, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static boolean containsLabelWithText(Container container, String text) {
        for (Component child : container.getComponents()) {
            if (child instanceof JLabel && text.equals(((JLabel) child).getText())) {
                return true;
            }
            if (child instanceof Container && containsLabelWithText((Container) child, text)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isDescendant(Component candidate, Container ancestor) {
        for (Container c = candidate.getParent(); c != null; c = c.getParent()) {
            if (c == ancestor) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object target, String name) {
        try {
            Field f = null;
            for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
                try {
                    f = c.getDeclaredField(name);
                    break;
                } catch (NoSuchFieldException ignore) {
                    // walk up
                }
            }
            if (f == null) {
                throw new NoSuchFieldException(name);
            }
            f.setAccessible(true);
            return (T) f.get(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot read field " + name, e);
        }
    }
}
