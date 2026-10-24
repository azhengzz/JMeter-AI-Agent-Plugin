package org.gitee.jmeter.ai.gui;

import org.gitee.jmeter.ai.agent.session.GoalState;
import org.gitee.jmeter.ai.gui.render.MarkdownRenderer;
import org.gitee.jmeter.ai.gui.theme.SlimScrollBarUI;
import org.gitee.jmeter.ai.gui.theme.ThemeColors;
import org.gitee.jmeter.ai.gui.theme.UiTokens;
import org.gitee.jmeter.ai.utils.AiConfig;

import javax.swing.JScrollPane;
import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import java.awt.AlphaComposite;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;

/**
 * 输入卡的 goal 状态条（goal-status-ui）：goal 活跃/受阻/暂停期间以<b>全宽色带</b>贴
 * 输入卡顶显示——状态色淡染铺满组件宽度（圆角外形由宿主 {@link RoundedBorderPanel}
 * 的圆角裁剪与描边统一负责，与卡身连成一体），底部一根状态色发丝线锚定与卡内其余
 * 行的分界；状态着色行（像素级截断）+ ⏸/▶ 互斥操作钮 + chevron 卡内内联展开完整目标。
 *
 * <p><b>更新纪律</b>（对齐 SelectionContextBar）：{@link #applySnapshot} 按内容 key
 * 去重后仅 repaint——冒烟计数/续跑轮次等高频路径绝不 revalidate（revalidate 上传会
 * 触发 MainFrame 布局、打断 JTable 单元格编辑）；仅离散边界（条出现/消失、展开/收起）
 * setVisible + 单次 revalidate。终态链（HOLD ~1.5s → FADING ~200ms alpha 渐隐，渐隐
 * 期间保持占位高度）为 repaint-only。
 *
 * <p><b>图标呼吸</b>：goal ACTIVE 时 ◎ 图标做正弦呼吸（与输入卡光晕同节奏），PAUSED/
 * BLOCKED/终态/清场一律静态（呼吸表停、alpha 复位 1）；宿主面板隐藏时同样停表（防
 * 后台空转），恢复可见续跑。停表保留相位帧（与光晕冻结-恢复语义一致），停/复不产生
 * 可见相位漂移。
 *
 * <p><b>状态机</b>（EDT 上唯一可变）：HIDDEN / ACTIVE / BLOCKED / PAUSED /
 * TERMINAL_HOLD / FADING；TERMINAL_HOLD 或 FADING 期间收到 ACTIVE/PAUSED/BLOCKED
 * 快照（复活/新 goal）立即取消计时回到对应态；null 快照 = 清除 → 立即 HIDDEN 无终
 * 态条。expanded 为正交维度，ACTIVE/BLOCKED/PAUSED 可展开，进终态自动收起。
 * 计时器经 {@link StripTimerFactory} 注入（headless 测试可手动步进）。
 */
class GoalStrip extends javax.swing.JPanel {

    /** ⏸/▶ 点击的上抛通道（GoalStrip 不直连 AgentLoop；EDT 上回调，实现方自行 hop）。 */
    interface GoalActions {
        void onPauseRequested();
        void onResumeRequested();
    }

    /** 计时器抽象：生产用 Swing Timer，测试用手动步进桩。 */
    interface StripTimer {
        void start();
        void stop();
        boolean isRunning();
    }

    interface StripTimerFactory {
        StripTimer create(int delayMs, Runnable action);
    }

    /** 生产用 Swing Timer 适配（面板构造注入；测试注入手动步进桩）。 */
    static StripTimerFactory swingTimerFactory() {
        return (delayMs, action) -> {
            javax.swing.Timer timer = new javax.swing.Timer(delayMs, e -> action.run());
            return new StripTimer() {
                @Override public void start() {
                    timer.start();
                }

                @Override public void stop() {
                    timer.stop();
                }

                @Override public boolean isRunning() {
                    return timer.isRunning();
                }
            };
        };
    }

    private static final int HOLD_DELAY_MS = 1500;
    private static final int FADE_DELAY_MS = 20;
    private static final int FADE_STEPS = 10;
    /** 图标呼吸：与光晕同节奏（80ms 帧、~1.05s 周期正弦），仅在 ACTIVE 运行。 */
    private static final int BREATH_DELAY_MS = 80;
    private static final int BREATH_PERIOD_FRAMES = 13;
    private static final float ICON_ALPHA_BASE = 0.45f;
    private static final float ICON_ALPHA_SWING = 0.45f;
    /** 状态色淡染底的填充/描边 alpha（区别于输入卡内其它白底行的视觉锚）。 */
    private static final int TINT_FILL_ALPHA = 28;
    private static final int TINT_EDGE_ALPHA = 64;
    /** host 高度未知（未布局/headless）时展开体的固定上限。 */
    private static final int FALLBACK_MAX_BODY_HEIGHT = 200;

    private final GoalActions actions;
    private final StripTimerFactory timerFactory;
    private final IntSupplier hostHeight;

    private final GoalLabel label = new GoalLabel();
    private final QuietButton pauseButton;
    private final QuietButton resumeButton;
    private final QuietButton chevronButton;
    private final javax.swing.JPanel bodyWrap = new javax.swing.JPanel(new BorderLayout());
    private final JTextPane bodyPane = new JTextPane();
    private final JScrollPane bodyScroller;
    /** 收起态首行（文本 + 操作钮行）；字段化供行高守卫观测内容实需高。 */
    private final javax.swing.JPanel headerRow;

    private GoalState.GoalSnapshot snapshot;
    private String contentKey = "";
    private boolean expanded;

    private StripTimer holdTimer;
    private StripTimer fadeTimer;
    private int fadeStep;
    /** 图标呼吸计时器（懒创建复用；仅 ACTIVE 且宿主可见时运行，停表保留相位帧）。 */
    private StripTimer breathTimer;
    private int breathFrame;
    /** 销毁粘位（面板 cleanup 后置位）：迟到的快照/显隐桥不得再按「确保状态」语义重启呼吸表。 */
    private boolean disposed;
    /** 宿主面板可见性（面板 SHOWING_CHANGED 桥接）：false 时停呼吸表防后台空转。 */
    private boolean hostShowing = true;

    /** host 高度供给（展开 clamp 用；传 {@code panel::getHeight}）。 */
    interface IntSupplier {
        int get();
    }

    GoalStrip(GoalActions actions, StripTimerFactory timerFactory, IntSupplier hostHeight) {
        super(new BorderLayout());
        this.actions = actions;
        this.timerFactory = timerFactory;
        this.hostHeight = hostHeight;
        setOpaque(false);
        // 全宽色带贴卡片顶：内容与下方 composer 行共用侧边距对齐；色带本身仍铺满
        // 组件全宽（paintComponent 不受 border 内缩影响），圆角外形交给宿主裁剪
        setBorder(javax.swing.BorderFactory.createEmptyBorder(
                0, UiTokens.SPACE_3, 0, UiTokens.SPACE_3));

        pauseButton = new QuietButton("⏸", QuietButton.Kind.GHOST);
        resumeButton = new QuietButton("▶", QuietButton.Kind.GHOST);
        chevronButton = new QuietButton("▾", QuietButton.Kind.GHOST);
        styleStripButton(pauseButton, "Pause goal");
        styleStripButton(resumeButton, "Resume goal");
        styleStripButton(chevronButton, "Show full goal");
        pauseButton.addActionListener((ActionEvent e) -> actions.onPauseRequested());
        resumeButton.addActionListener((ActionEvent e) -> actions.onResumeRequested());
        chevronButton.addActionListener((ActionEvent e) -> setExpanded(!expanded));

        javax.swing.JPanel buttonRow = new javax.swing.JPanel(
                new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 2, 0));
        buttonRow.setOpaque(false);
        // FlowLayout 顶对齐：24px 操作钮用上下 2px 边距垫进色带光学中心——行高随之
        // 达到 GOAL_STRIP_HEIGHT（28），修掉 NORTH 首选高 24 顶贴造成的底部 4px 空洞
        buttonRow.setBorder(javax.swing.BorderFactory.createEmptyBorder(2, 0, 2, 0));
        buttonRow.add(pauseButton);
        buttonRow.add(resumeButton);
        buttonRow.add(chevronButton);

        javax.swing.JPanel headerRow = new javax.swing.JPanel(new BorderLayout()) {
            @Override
            public Dimension getPreferredSize() {
                Dimension d = super.getPreferredSize();
                d.height = Math.max(d.height, UiTokens.GOAL_STRIP_HEIGHT);
                return d;
            }
        };
        this.headerRow = headerRow;
        headerRow.setOpaque(false);
        // 行高地板：终态时 ⏸/▶/chevron 全部隐藏，FlowLayout 的首选高塌缩到边距
        // 4px，而 GoalLabel 无 UI 委托、首选高只有 1×1——行高随之塌成 4px，状态
        // 文字被裁成顶部残条（收起态 28px 色带里只剩 2px 字迹）。地板钉住色带高度，
        // 让行高只由色带常量与内容实需的较大者决定，与钮的显隐正交
        headerRow.add(label, BorderLayout.CENTER);
        headerRow.add(buttonRow, BorderLayout.EAST);

        bodyPane.setEditable(false);
        bodyPane.setFocusable(false);
        bodyPane.setOpaque(false);
        bodyScroller = SlimScrollBarUI.scroller(bodyPane,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        bodyScroller.setOpaque(false);
        bodyScroller.getViewport().setOpaque(false);
        bodyScroller.setBorder(javax.swing.BorderFactory.createEmptyBorder(
                UiTokens.SPACE_1, 0, UiTokens.SPACE_1, 0));
        bodyWrap.setOpaque(false);
        bodyWrap.add(bodyScroller, BorderLayout.CENTER);
        bodyWrap.setVisible(false);

        add(headerRow, BorderLayout.NORTH);
        add(bodyWrap, BorderLayout.CENTER);
        applyTheme();
        setVisible(false);
        refreshActionButtons();
    }

    /** 快照驱动的主入口（EDT；高频路径去重后 repaint-only）。null = goal 清除。 */
    void applySnapshot(GoalState.GoalSnapshot next) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> applySnapshot(next));
            return;
        }
        if (next == null) {
            hideImmediately();
            return;
        }
        boolean terminal = isTerminalStatus(next.status());
        boolean inTerminalChain = phase() == Phase.TERMINAL_HOLD || phase() == Phase.FADING;
        String key = keyOf(next);
        if (inTerminalChain && terminal && key.equals(contentKey)) {
            return; // 同一终态的重复投递（⏸/收敛双路径 no-op 后的残留）
        }
        if (!inTerminalChain && key.equals(contentKey) && phase() != Phase.HIDDEN) {
            return; // 高频去重：内容与计数均未变 → 不重绘、不重挂 tooltip
        }
        cancelTerminalTimers(); // 复活/状态迁移：取消终态链
        fadeStep = 0;
        GoalState.GoalSnapshot previous = snapshot;
        snapshot = next;
        contentKey = key;
        boolean wasHidden = !isVisible();
        boolean wasExpanded = expanded;
        if (terminal) {
            setExpandedInternal(false); // 终态自动收起
            startHoldTimer();
        } else {
            setVisible(true);
        }
        refreshActionButtons();
        refreshBreathing();
        updateTooltip();
        // 展开体跟随 objective：replace 场景 header 已切新摘要，正文若不重渲染会
        // 停留旧目标（同屏两份目标信息，仅在收起再展开时自愈）。objective 未变的
        // 计数更新不重渲染（高频路径保持 repaint-only）
        boolean bodyRerendered = false;
        if (bodyWrap.isVisible() && !java.util.Objects.equals(
                previous == null ? null : previous.objective(), next.objective())) {
            renderBody(next);
            bodyRerendered = true;
        }
        if ((wasHidden && isVisible()) || (wasExpanded && !expanded) || bodyRerendered) {
            revalidate(); // 离散边界：条出现 / 终态收起展开体 / objective 替换（正文内容+高度边界）
        } else {
            repaint();
        }
    }

    private void updateTooltip() {
        GoalState.GoalSnapshot s = snapshot;
        if (s == null) {
            setToolTipText(null);
            return;
        }
        setToolTipText("<html>Smoke attempts: " + s.smokeAttempts()
                + "/" + AiConfig.getGoalSmokeLimit()
                + " · Continuation rounds: " + s.continuationRounds()
                + "/" + AiConfig.getGoalMaxContinuations()
                + (s.startedAt() == null ? "" : " · Started: " + s.startedAt())
                + "</html>"); // setToolTipText 不触发 revalidate
        getAccessibleContext().setAccessibleName(
                "Goal · " + (s.uiSummary() == null ? "" : s.uiSummary()));
    }

    /** 展开/收起（仅 ACTIVE/BLOCKED/PAUSED；离散边界，单次 revalidate）。 */
    void setExpanded(boolean value) {
        if (phase() == Phase.HIDDEN || phase() == Phase.TERMINAL_HOLD || phase() == Phase.FADING) {
            return;
        }
        if (expanded == value) {
            return;
        }
        setExpandedInternal(value);
        revalidate();
        repaint();
    }

    boolean isExpanded() {
        return expanded;
    }

    /** LAF/缩放切换时重钉字体（由面板 applyControlBarTheme 调用；repaint-only）。 */
    void applyTheme() {
        Font base = javax.swing.UIManager.getFont("Label.font");
        // 收起态 header 与展开正文同用 body 字号：同一状态条两处字号不一致是视觉
        // 割裂；GOAL_STRIP_HEIGHT（28px）容纳 body 字号绰绰有余
        label.plainFont = UiTokens.body(base);
        label.boldFont = label.plainFont.deriveFont(Font.BOLD);
        bodyPane.setFont(label.plainFont);
        // markdown 的标题/代码块字号在渲染时按当时字体烘焙进文档，换主题后须重渲染，
        // 否则展开体停留旧字号直到收起再展开
        if (bodyWrap.isVisible() && snapshot != null) {
            renderBody(snapshot);
        }
        repaint();
    }

    /** 停掉全部计时器（面板 cleanup；幂等且不可逆——置销毁粘位，此后 refreshBreathing
     * 不再重启呼吸表，cleanup 后迟到的快照/显隐桥不复活计时器）。 */
    void dispose() {
        disposed = true;
        cancelTerminalTimers();
        if (breathTimer != null) {
            breathTimer.stop();
        }
    }

    /** 宿主面板显隐桥接（面板 SHOWING_CHANGED 调用）：隐藏停呼吸表，恢复续跑。 */
    void setHostShowing(boolean showing) {
        if (hostShowing != showing) {
            hostShowing = showing;
            refreshBreathing();
        }
    }

    // ---- 测试观测面 ----

    enum Phase {
        HIDDEN, ACTIVE, BLOCKED, PAUSED, TERMINAL_HOLD, FADING
    }

    Phase phase() {
        if (!isVisible()) {
            return Phase.HIDDEN;
        }
        if (holdTimer != null && holdTimer.isRunning()) {
            return Phase.TERMINAL_HOLD;
        }
        if (fadeTimer != null && fadeTimer.isRunning()) {
            return Phase.FADING;
        }
        GoalState.GoalSnapshot s = snapshot;
        if (s == null) {
            return Phase.HIDDEN;
        }
        return switch (s.status()) {
            case ACTIVE -> Phase.ACTIVE;
            case PAUSED -> Phase.PAUSED;
            case BLOCKED -> Phase.BLOCKED;
            case COMPLETED, CANCELLED -> Phase.TERMINAL_HOLD;
        };
    }

    boolean isHoldTimerRunning() {
        return holdTimer != null && holdTimer.isRunning();
    }

    boolean isFadeTimerRunning() {
        return fadeTimer != null && fadeTimer.isRunning();
    }

    boolean isBreathTimerRunning() {
        return breathTimer != null && breathTimer.isRunning();
    }

    /** 测试钩子：图标呼吸 alpha（静止态恒 1）。 */
    float iconBreathAlphaForTest() {
        return iconBreathAlpha();
    }

    GoalState.Status status() {
        return snapshot == null ? null : snapshot.status();
    }

    boolean pauseButtonVisible() {
        return pauseButton.isVisible();
    }

    boolean resumeButtonVisible() {
        return resumeButton.isVisible();
    }

    boolean chevronButtonVisible() {
        return chevronButton.isVisible();
    }

    boolean bodyVisible() {
        return bodyWrap.isVisible();
    }

    void clickPauseForTest() {
        pauseButton.doClick();
    }

    void clickResumeForTest() {
        resumeButton.doClick();
    }

    /** 测试观测面：展开体当前渲染的纯文本（objective replace 重渲染契约）。 */
    String bodyTextForTest() {
        return bodyPane.getText();
    }

    /** 测试观测面：收起态行的内容实需高（24px 钮 + 上下边距）。守卫断言用：
     * 实需高越过 {@link org.gitee.jmeter.ai.gui.theme.UiTokens#GOAL_STRIP_HEIGHT} 时
     * NORTH 布局会按实需高越出条底界、绘制被裁——钮径/边距/常量单边调整须先过此门。 */
    int headerRowPreferredHeightForTest() {
        return headerRow.getPreferredSize().height;
    }

    // ---- internals ----

    private void styleStripButton(QuietButton button, String tooltip) {
        // 24px 方形命中区：20px 对鼠标不够友好（⏸/▶ 是 goal 生命周期的主要操作点）
        button.iconOnly(24);
        button.setToolTipText(tooltip);
        button.setVisible(false);
    }

    private void refreshActionButtons() {
        GoalState.GoalSnapshot s = snapshot;
        boolean active = s != null && s.status() == GoalState.Status.ACTIVE;
        boolean paused = s != null && s.status() == GoalState.Status.PAUSED;
        pauseButton.setVisible(active);
        resumeButton.setVisible(paused);
        chevronButton.setVisible(active || paused || (s != null && s.status() == GoalState.Status.BLOCKED));
    }

    private void setExpandedInternal(boolean value) {
        expanded = value;
        bodyWrap.setVisible(value);
        chevronButton.setText(value ? "▸" : "▾");
        chevronButton.setToolTipText(value ? "Collapse goal" : "Show full goal");
        if (value && snapshot != null) {
            renderBody(snapshot);
        }
    }

    private void renderBody(GoalState.GoalSnapshot s) {
        // 展开体只渲染 objective：uiSummary 已在收起态 header 常驻，正文再重复一遍
        // 会以两种字号呈现同一段文字（视觉割裂的根源），分隔线随之失去意义
        String md = s.objective() == null ? "" : s.objective();
        try {
            bodyPane.setText("");
            MarkdownRenderer.process(bodyPane.getStyledDocument(), md, bodyPane.getFont());
        } catch (javax.swing.text.BadLocationException e) {
            bodyPane.setText(md);
        }
        bodyPane.setCaretPosition(0);
    }

    private void startHoldTimer() {
        cancelTerminalTimers();
        holdTimer = timerFactory.create(HOLD_DELAY_MS, this::onHoldTimerFired);
        holdTimer.start();
    }

    private void onHoldTimerFired() {
        if (snapshot == null || !isTerminalStatus(snapshot.status())) {
            return; // 复活已取消/串台防护
        }
        // Swing Timer 默认 repeats=true：fire 后必须显式停表再换 fade，否则本表
        // 每 1.5s 僵尸复燃，还会在 fade 运行中覆盖 fadeTimer 引用造成孤儿表
        holdTimer.stop();
        holdTimer = null;
        fadeStep = 0;
        fadeTimer = timerFactory.create(FADE_DELAY_MS, this::onFadeTick);
        fadeTimer.start();
    }

    private void onFadeTick() {
        fadeStep++;
        if (fadeStep >= FADE_STEPS) {
            hideImmediately(); // 渐隐结束才移除占位（渐隐中缩高度会抖动转录区）
        } else {
            repaint();
        }
    }

    private void hideImmediately() {
        cancelTerminalTimers();
        snapshot = null;
        contentKey = "";
        setExpandedInternal(false);
        boolean wasVisible = isVisible();
        setVisible(false);
        setToolTipText(null);
        refreshActionButtons();
        refreshBreathing(); // 呼吸表随隐藏停止
        label.repaint();
        if (wasVisible) {
            revalidate(); // 离散边界：条消失
        }
    }

    /** 状态行摘要文本：ui_summary 优先，缺失降级 objective 首行（包私有可测）。 */
    String summaryText(GoalState.GoalSnapshot s) {
        if (s.uiSummary() != null && !s.uiSummary().isBlank()) {
            return s.uiSummary().strip();
        }
        String objective = s.objective() == null ? "" : s.objective();
        int nl = objective.indexOf('\n');
        return (nl >= 0 ? objective.substring(0, nl) : objective).strip();
    }

    private void cancelTerminalTimers() {
        if (holdTimer != null) {
            holdTimer.stop();
            holdTimer = null;
        }
        if (fadeTimer != null) {
            fadeTimer.stop();
            fadeTimer = null;
        }
    }

    /**
     * 图标呼吸启停（EDT）：仅 ACTIVE、可见且宿主面板可见时运行（与光晕同节奏的正弦），
     * PAUSED/BLOCKED/终态/宿主隐藏/清场一律停表。停表保留相位帧——与光晕的冻结-恢复
     * 语义一致（光晕 OFF 只停表不清帧），停/复与状态切换不回卷正弦起点，两条呼吸链
     * 不产生相位漂移。计时器懒创建复用（一次创建不重建，对齐 GoalGlowAnimator）。
     */
    private void refreshBreathing() {
        if (disposed) {
            return; // 销毁粘位：cleanup 后迟到的快照/显隐桥不重启呼吸表（dispose 已停表）
        }
        boolean breathing = snapshot != null
                && snapshot.status() == GoalState.Status.ACTIVE
                && isVisible()
                && hostShowing;
        if (breathing) {
            if (breathTimer == null) {
                breathTimer = timerFactory.create(BREATH_DELAY_MS, this::onBreathTick);
            }
            if (!breathTimer.isRunning()) {
                breathTimer.start();
            }
        } else if (breathTimer != null) {
            breathTimer.stop();
        }
    }

    private void onBreathTick() {
        breathFrame = (breathFrame + 1) % BREATH_PERIOD_FRAMES;
        label.repaint(); // repaint-only：图标 alpha 变化绝不 revalidate
    }

    /** 图标当前 alpha：呼吸中在 [0.45, 0.90] 正弦起伏，静止态恒 1。 */
    private float iconBreathAlpha() {
        if (breathTimer == null || !breathTimer.isRunning()) {
            return 1f;
        }
        float wave = 0.5f + 0.5f * (float) Math.sin(breathFrame * 2 * Math.PI / BREATH_PERIOD_FRAMES);
        return ICON_ALPHA_BASE + ICON_ALPHA_SWING * wave;
    }

    private static boolean isTerminalStatus(GoalState.Status status) {
        return status == GoalState.Status.COMPLETED || status == GoalState.Status.CANCELLED;
    }

    private float fadeAlpha() {
        if (phase() != Phase.FADING || fadeTimer == null) {
            return 1f;
        }
        float progress = Math.min(1f, (float) fadeStep / FADE_STEPS);
        return 1f - progress;
    }

    private String keyOf(GoalState.GoalSnapshot s) {
        // startedAt 入键：replace 逐字同目标且计数归零时内容键仍相等会短路掉
        // tooltip 的 Started 刷新；startedAt 只进 tooltip，入键仅让 replace 这类
        // 离散事件多走一次 update，无高频代价
        return s.status() + "|" + (s.uiSummary() == null ? "" : s.uiSummary())
                + "|" + (s.objective() == null ? "" : s.objective())
                + "|" + s.smokeAttempts() + "|" + s.continuationRounds()
                + "|" + (s.startedAt() == null ? "" : s.startedAt());
    }

    @Override
    public Dimension getPreferredSize() {
        int width = super.getPreferredSize().width;
        if (expanded && bodyWrap.isVisible()) {
            int host = hostHeight.get();
            int maxBody = host > 0 ? host * 2 / 5 : FALLBACK_MAX_BODY_HEIGHT;
            int body = Math.max(FALLBACK_MAX_BODY_HEIGHT / 2,
                    Math.min(bodyScroller.getPreferredSize().height, maxBody));
            return new Dimension(width, UiTokens.GOAL_STRIP_HEIGHT + body);
        }
        return new Dimension(width, UiTokens.GOAL_STRIP_HEIGHT);
    }

    @Override
    public Dimension getMinimumSize() {
        return new Dimension(super.getMinimumSize().width, UiTokens.GOAL_STRIP_HEIGHT);
    }

    /**
     * 状态色淡染<b>全宽色带</b>：goal 条贴输入卡顶整宽铺染（fillRect 到组件边界），
     * 不再自绘圆角与描边——外形由宿主 {@link RoundedBorderPanel} 的圆角裁剪与描边
     * 统一负责，与卡身连成一体（消除原「圆角芯片内嵌白底」的盒中盒割裂感）。底部
     * 一根状态色发丝线锚定色带与卡内其余行的分界。颜色 paint 时实时取自
     * {@link #styleFor}（ThemeColors，LAF 自适应）；仅半透明淡染，下层卡底色透出。
     */
    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        GoalState.GoalSnapshot s = snapshot;
        if (s == null) {
            return;
        }
        Color accent = styleFor(s.status()).color();
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(),
                    TINT_FILL_ALPHA));
            g2.fillRect(0, 0, getWidth(), getHeight());
            g2.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(),
                    TINT_EDGE_ALPHA));
            g2.fillRect(0, getHeight() - 1, getWidth(), 1);
        } finally {
            g2.dispose();
        }
    }

    @Override
    public void paint(Graphics g) {
        float alpha = fadeAlpha();
        if (alpha >= 1f) {
            super.paint(g);
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
            super.paint(g2);
        } finally {
            g2.dispose();
        }
    }

    /** 自绘状态行：图标 + 粗体前缀 + 摘要（像素级截断，颜色 paint 时实时读）。 */
    private final class GoalLabel extends javax.swing.JComponent {

        private Font plainFont;
        private Font boldFont;

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            GoalState.GoalSnapshot s = snapshot;
            if (s == null) {
                return;
            }
            StatusStyle style = styleFor(s.status());
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                FontMetrics boldFm = g2.getFontMetrics(boldFont);
                FontMetrics plainFm = g2.getFontMetrics(plainFont);
                int x = 2;
                // 字形 ascent 带在整条色带内取中（右侧操作钮以 2px 边距同此光学中心；
                // 旧 -2 微调是 24px 行高时代的余量，行高归 28 后会把文字顶高 2px）
                int y = (getHeight() + Math.max(boldFm.getAscent(), plainFm.getAscent())) / 2;

                g2.setFont(boldFont);
                g2.setColor(style.color());
                String icon = style.icon();
                float iconAlpha = iconBreathAlpha();
                if (iconAlpha < 1f) {
                    g2.setComposite(java.awt.AlphaComposite.getInstance(
                            java.awt.AlphaComposite.SRC_OVER, iconAlpha));
                }
                g2.drawString(icon, x, y);
                g2.setComposite(java.awt.AlphaComposite.SrcOver);
                x += boldFm.stringWidth(icon) + 4;
                String prefix = style.prefix();
                g2.drawString(prefix, x, y);
                x += boldFm.stringWidth(prefix) + 3;

                String summary = summaryText(s);
                if (!summary.isEmpty()) {
                    g2.setFont(plainFont);
                    g2.setColor(style.bodyColor());
                    drawTruncated(g2, plainFm, summary, x, y, getWidth() - x - 4);
                }
            } finally {
                g2.dispose();
            }
        }

        /** 像素级截断 + 省略号；切点落在代理对高位时回退一位（防孤代理对进渲染）。 */
        private void drawTruncated(Graphics2D g2, FontMetrics fm, String text, int x, int y, int maxWidth) {
            if (maxWidth <= 0 || text.isEmpty()) {
                return;
            }
            if (fm.stringWidth(text) <= maxWidth) {
                g2.drawString(text, x, y);
                return;
            }
            int ellipsisWidth = fm.stringWidth("…");
            int fit = 0; // chars that fit before the ellipsis
            for (int i = 0; i < text.length(); i++) {
                int w = fm.stringWidth(text.substring(0, i + 1));
                if (w > maxWidth - ellipsisWidth) {
                    break;
                }
                fit = i + 1;
            }
            if (fit > 0 && Character.isHighSurrogate(text.charAt(fit - 1))) {
                fit--;
            }
            String cut = fit > 0 ? text.substring(0, fit) + "…" : "…";
            g2.drawString(cut, x, y);
        }
    }

    /** 终态与中间态的行内样式（颜色每次 paint 实时读 ThemeColors，LAF 自适应；包私有可测）。 */
    static StatusStyle styleFor(GoalState.Status status) {
        return switch (status) {
            case ACTIVE -> new StatusStyle("◎", "Goal", () -> ThemeColors.info());
            case PAUSED -> new StatusStyle("◎", "Goal paused", () -> ThemeColors.secondaryText());
            case BLOCKED -> new StatusStyle("◎", "Goal blocked", () -> ThemeColors.warning());
            case COMPLETED -> new StatusStyle("✓", "Goal completed", () -> ThemeColors.success());
            case CANCELLED -> new StatusStyle("✕", "Goal cancelled", () -> ThemeColors.error());
        };
    }

    record StatusStyle(String icon, String prefix,
            java.util.function.Supplier<Color> colorSource) {
        Color color() {
            return colorSource.get();
        }

        /** 摘要正文色：前缀色的柔和版（主色直接当正文过重）。 */
        Color bodyColor() {
            return ThemeColors.blend(colorSource.get(), ThemeColors.foreground(), 0.35f);
        }
    }
}
