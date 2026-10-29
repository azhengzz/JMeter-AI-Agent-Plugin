package org.gitee.jmeter.ai.gui;

import org.apache.jmeter.util.JMeterUtils;
import org.gitee.jmeter.ai.agent.AgentLoop;
import org.gitee.jmeter.ai.agent.AgentLoopFactory;
import org.gitee.jmeter.ai.agent.context.ContextBuilder;
import org.gitee.jmeter.ai.agent.memory.MemoryConsolidator;
import org.gitee.jmeter.ai.agent.memory.MemoryStore;
import org.gitee.jmeter.ai.agent.model.GenerationSettings;
import org.gitee.jmeter.ai.agent.model.LLMResponse;
import org.gitee.jmeter.ai.agent.model.LlmCallOptions;
import org.gitee.jmeter.ai.agent.model.Message;
import org.gitee.jmeter.ai.agent.model.ToolDefinition;
import org.gitee.jmeter.ai.agent.session.GoalState;
import org.gitee.jmeter.ai.agent.session.GoalStateEvents;
import org.gitee.jmeter.ai.agent.session.SessionManager;
import org.gitee.jmeter.ai.agent.testsupport.AwaitUtil;
import org.gitee.jmeter.ai.agent.tools.ToolRegistry;
import org.gitee.jmeter.ai.gui.theme.UiTokens;
import org.gitee.jmeter.ai.instance.InstanceContext;
import org.gitee.jmeter.ai.service.AiService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.RepaintManager;
import javax.swing.SwingUtilities;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GoalStrip/光晕的面板接线单测（goal-status-ui）：GoalStateEvents 通道驱动状态条与
 * 光晕、会话外过滤、/new 存活（active 与 paused）、终态链收起、cleanup 摘除、
 * hydrate（缓存腿；终态历史不回放）与 ⏸ 操作回调到引擎入口。
 */
class AiChatPanelGoalStripTest {

    @TempDir
    Path tempDir;

    AgentLoop loop;
    AiChatPanel panel;
    String sessionKey;
    String previousJMeterHome;

    @BeforeEach
    void setUp() throws Exception {
        sessionKey = InstanceContext.currentSessionKey();
        GoalStateEvents.resetForTest();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (panel != null) {
            loop.removeTurnSubscriber(panel);
            AgentLoopFactory.removeTurnSubscriber(panel);
            // 摘 goal 订阅并停状态条/光晕的真实 Swing Timer——面板级计时器泄漏进
            // 套件 JVM 会让后续用例全程承受 EDT 空转
            panel.cleanup();
        }
        AwaitUtil.awaitUntil(() -> loop == null || loop.activeTurn(sessionKey).isEmpty(),
                "active turn drained before teardown");
        if (loop != null) {
            loop.shutdown();
        }
        AgentLoopFactory.reset();
        GoalStateEvents.resetForTest();
        if (previousJMeterHome != null) {
            JMeterUtils.setJMeterHome(previousJMeterHome);
        }
    }

    /** 模板同 AiChatPanelContextRingTest（loadModels 落地后才注入测试 loop）。 */
    private void buildPanel() throws Exception {
        MemoryStore memoryStore = Mockito.mock(MemoryStore.class);
        Mockito.when(memoryStore.getMemoryContext()).thenReturn("");
        loop = new AgentLoop(new ToolRegistry(Runnable::run),
                memoryStore, Mockito.mock(MemoryConsolidator.class),
                new ContextBuilder(memoryStore, tempDir),
                new SessionManager(tempDir, sessionKey), new IdleAiService());

        previousJMeterHome = JMeterUtils.getJMeterHome();
        JMeterUtils.setJMeterHome(tempDir.toString());

        panel = new AiChatPanel();
        JComboBox<?> selector = field(panel, "modelSelector");
        AwaitUtil.awaitUntil(() -> selector.getSelectedItem() != null,
                "loadModelsInBackground.done() landed (model item selected)");
        SwingUtilities.invokeAndWait(() -> { });
        setField(panel, "agentLoop", loop);
        loop.addTurnSubscriber(panel);
    }

    private GoalStrip strip() {
        return field(panel, "goalStrip");
    }

    private GoalGlowAnimator glow() {
        return field(panel, "goalGlow");
    }

    private static GoalState.GoalSnapshot snap(GoalState.Status status, String summary) {
        boolean terminal = status == GoalState.Status.COMPLETED
                || status == GoalState.Status.CANCELLED;
        return new GoalState.GoalSnapshot(status, summary, "objective", terminal ? "r" : null,
                1, 1, "2026-10-19T09:00:00", "k");
    }

    // ---- ① 真实通道驱动：publish → EDT → 状态条/光晕 ----

    @Test
    void activeSnapshotViaEventsChannelShowsStrip() throws Exception {
        buildPanel();
        assertFalse(strip().isVisible(), "前置：无 goal 时状态条隐藏");

        GoalStateEvents.publish(sessionKey, snap(GoalState.Status.ACTIVE, "login goal"));
        SwingUtilities.invokeAndWait(() -> { });

        assertTrue(strip().isVisible());
        assertEquals(GoalStrip.Phase.ACTIVE, strip().phase());
        assertTrue(strip().pauseButtonVisible(), "⏸ visible while ACTIVE");
        assertTrue(strip().getParent() instanceof RoundedBorderPanel,
                "goal 条贴输入卡外壳 NORTH（全宽色带，不再嵌在输入区内边距里）");
        // 面板未显示（无窗口）→ 呼吸不点火（SHOWING_CHANGED 恢复可见时续跑）
        assertEquals(GoalGlowAnimator.Mode.OFF, glow().mode());
    }

    @Test
    void pausedSnapshotGreysStripAndLightsNoGlow() throws Exception {
        buildPanel();
        GoalStateEvents.publish(sessionKey, snap(GoalState.Status.PAUSED, "p"));
        SwingUtilities.invokeAndWait(() -> { });

        assertEquals(GoalStrip.Phase.PAUSED, strip().phase());
        assertTrue(strip().resumeButtonVisible(), "▶ visible while PAUSED");
        assertFalse(strip().pauseButtonVisible());
        assertEquals(GoalGlowAnimator.Mode.OFF, glow().mode(), "PAUSED: glow stays dark");
    }

    @Test
    void foreignSessionSnapshotIsFiltered() throws Exception {
        buildPanel();
        GoalStateEvents.publish("other-instance-session", snap(GoalState.Status.ACTIVE, "x"));
        SwingUtilities.invokeAndWait(() -> { });

        assertFalse(strip().isVisible(), "会话外通知不得变更本面板呈现");
    }

    @Test
    void smokeCounterBumpKeepsStructureIntact() throws Exception {
        buildPanel();
        GoalStateEvents.publish(sessionKey,
                new GoalState.GoalSnapshot(GoalState.Status.ACTIVE, "s", "o", null,
                        1, 1, "2026-10-19T09:00:00", "k"));
        SwingUtilities.invokeAndWait(() -> { });
        int components = strip().getComponentCount();

        GoalStateEvents.publish(sessionKey,
                new GoalState.GoalSnapshot(GoalState.Status.ACTIVE, "s", "o", null,
                        2, 2, "2026-10-19T09:00:00", "k"));
        SwingUtilities.invokeAndWait(() -> { });

        assertEquals(GoalStrip.Phase.ACTIVE, strip().phase());
        assertEquals(components, strip().getComponentCount(),
                "计数刷新（高频路径）不得改变组件树结构");
        assertTrue(strip().isVisible());
    }

    // ---- ② /new 存活（active 与 paused 双锚） ----

    @Test
    void stripSurvivesNewResetForActiveAndPaused() throws Exception {
        buildPanel();
        GoalStateEvents.publish(sessionKey, snap(GoalState.Status.ACTIVE, "a"));
        SwingUtilities.invokeAndWait(() -> { });
        SwingUtilities.invokeAndWait(() -> invoke(panel, "startNewConversation"));
        assertEquals(GoalStrip.Phase.ACTIVE, strip().phase(),
                "goal 跨 /new 存活（Session.clear 保留 metadata）——状态条不随代数复位");

        GoalStateEvents.publish(sessionKey, snap(GoalState.Status.PAUSED, "p"));
        SwingUtilities.invokeAndWait(() -> { });
        SwingUtilities.invokeAndWait(() -> invoke(panel, "startNewConversation"));
        assertEquals(GoalStrip.Phase.PAUSED, strip().phase(), "paused 同样跨 /new 存活");
    }

    // ---- ③ 终态链：hold → 自动收起（真实 Timer，放宽到 5s） ----

    @Test
    void terminalSnapshotHoldsThenCollapses() throws Exception {
        buildPanel();
        GoalStateEvents.publish(sessionKey, snap(GoalState.Status.ACTIVE, "a"));
        SwingUtilities.invokeAndWait(() -> { });

        GoalStateEvents.publish(sessionKey, snap(GoalState.Status.COMPLETED, "a"));
        SwingUtilities.invokeAndWait(() -> { });
        assertTrue(strip().isHoldTimerRunning(), "终态到达先进入 ~1.5s 终态展示");

        AwaitUtil.awaitUntil(() -> edtRead(() -> !strip().isVisible()),
                "terminal strip collapses after the hold+fade chain");
        assertEquals(GoalGlowAnimator.Mode.OFF, glow().mode());
    }

    // ---- ④ ⏸ 操作回调 → 引擎入口（真实 store 通道 + PAUSED 收敛） ----

    @Test
    void pauseActionRoutesToEngineAndConverges() throws Exception {
        buildPanel();
        var session = loop.getSessionManager().getOrCreate(sessionKey);
        GoalState.store(session, GoalState.create("wired objective", "wired"));
        SwingUtilities.invokeAndWait(() -> { });
        assertTrue(strip().isVisible(), "store 的 publish 通道已点亮状态条（全链路）");

        invoke(panel, "requestGoalPause");
        AwaitUtil.awaitUntil(() -> GoalState.load(
                        loop.getSessionManager().getOrCreate(sessionKey)).getStatus()
                        == GoalState.Status.PAUSED,
                "⏸ → pauseActiveGoal → 引擎在 loop 执行器上收敛 PAUSED");
        AwaitUtil.awaitUntil(() -> edtRead(() -> strip().phase() == GoalStrip.Phase.PAUSED),
                "收敛的 publish 回传面板：状态条转灰 + ▶ 出现");
    }

    // ---- ⑤ cleanup 摘除 ----

    @Test
    void cleanupDetachesGoalListener() throws Exception {
        buildPanel();
        GoalStateEvents.publish(sessionKey, snap(GoalState.Status.ACTIVE, "a"));
        SwingUtilities.invokeAndWait(() -> { });
        assertTrue(strip().isVisible());

        panel.cleanup();
        GoalStateEvents.publish(sessionKey, snap(GoalState.Status.PAUSED, "x"));
        SwingUtilities.invokeAndWait(() -> { });

        assertEquals(GoalStrip.Phase.ACTIVE, strip().phase(),
                "cleanup 后 publish 不再触达面板（监听器按登记实例摘除）");
        assertEquals(GoalGlowAnimator.Mode.OFF, glow().mode(), "cleanup 熄灭光晕动画");
    }

    // ---- 光晕零布局锚 + 粘性 dispose ----

    @Test
    void glowPaintsFromPanelWithoutShellComponent() throws Exception {
        buildPanel();
        JComponent card = (JComponent) strip().getParent();
        assertInstanceOf(RoundedBorderPanel.class, card, "goal 条贴输入卡外壳 NORTH");
        assertSame(field(panel, "inputCard"), card, "面板持有输入卡字段（paint 换算锚）");
        assertSame(panel, card.getParent().getParent(),
                "输入卡直接驻面板 SOUTH 坞——光晕画进面板既有静区（零布局），"
                        + "不得再有任何中间壳组件挤窄卡片");
        assertEquals(GoalGlowAnimator.Mode.OFF, glow().mode(), "无 goal 光晕熄灭");
    }

    @Test
    void cardBoundsAreInvariantAcrossGlowModes() throws Exception {
        buildPanel();
        SwingUtilities.invokeAndWait(() -> {
            panel.setSize(500, 600);
            layoutTree(panel); // doLayout 只布直接子层，输入卡在两层级之下——沿树下推
        });
        Rectangle idle = inputCardBounds();
        assertTrue(idle.width > 0 && idle.height > 0, "前置：面板已布局出输入卡");

        // 离散边界：BLOCKED 首现——状态条出现使输入卡长高一格，是规格允许的布局变化
        GoalStateEvents.publish(sessionKey, snap(GoalState.Status.BLOCKED, "b"));
        SwingUtilities.invokeAndWait(() -> {
            assertEquals(GoalGlowAnimator.Mode.STATIC_BLOCKED, glow().mode());
            layoutTree(panel);
        });
        Rectangle shown = inputCardBounds();
        assertEquals(idle.height + UiTokens.GOAL_STRIP_HEIGHT, shown.height,
                "前置：状态条首现 = 离散布局边界（恰好一行状态条高）");

        // 零布局光晕：BLOCKED→PAUSED→BLOCKED 只迁光晕模式（状态条可见性恒定、
        // 内容更新 repaint-only）——卡片矩形必须逐像素恒定
        GoalStateEvents.publish(sessionKey, snap(GoalState.Status.PAUSED, "p"));
        SwingUtilities.invokeAndWait(() -> {
            assertEquals(GoalGlowAnimator.Mode.OFF, glow().mode());
            layoutTree(panel);
        });
        assertEquals(shown, inputCardBounds(), "光晕熄灭 ⇒ 卡片布局逐像素不变（零布局光晕）");

        GoalStateEvents.publish(sessionKey, snap(GoalState.Status.BLOCKED, "b"));
        SwingUtilities.invokeAndWait(() -> {
            assertEquals(GoalGlowAnimator.Mode.STATIC_BLOCKED, glow().mode());
            layoutTree(panel);
        });
        assertEquals(shown, inputCardBounds(), "光晕复燃 ⇒ 卡片布局逐像素不变");
    }

    private Rectangle inputCardBounds() {
        RoundedBorderPanel card = field(panel, "inputCard");
        return new Rectangle(card.getBounds());
    }

    @Test
    void paintSelfHealsExposedGlowBandWhenCardRectMoves() throws Exception {
        // 光晕环画进面板的既有静区（页边距 + 输入坞上方空隙），落在组件界外——
        // 展开/收起挪动卡片矩形时，布局变化的自动脏区域只盖组件自身界内，旧环
        // 像素没人清。绘制侧契约：绘制帧发现矩形相对上一绘制帧已变 → 对旧带补
        // 一次 repaint；矩形稳定后不得再补（防无限重绘循环）
        buildPanel();
        RepaintManager previous = RepaintManager.currentManager(panel);
        RecordingRepaintManager recorder = new RecordingRepaintManager(panel);
        RepaintManager.setCurrentManager(recorder);
        try {
            SwingUtilities.invokeAndWait(() -> {
                // 安装窗口期（setCurrentManager 到本 lambda 分发之间）EDT 上无关
                // 组件的迟到 repaint 会落进记录器，先清零再断言
                recorder.lastBand = null;
                panel.setSize(500, 600);
                layoutTree(panel);
                RoundedBorderPanel card = field(panel, "inputCard");
                Rectangle firstCard = SwingUtilities.convertRectangle(
                        card.getParent(), card.getBounds(), panel);
                assertTrue(firstCard.width > 0 && firstCard.height > 0,
                        "前置：面板已布局出输入卡");

                Graphics2D g = new BufferedImage(panel.getWidth(), panel.getHeight(),
                        BufferedImage.TYPE_INT_RGB).createGraphics();
                try {
                    panel.paintComponent(g);
                    assertNull(recorder.lastBand, "首帧无已绘制矩形，无旧带可清");

                    card.setBounds(card.getX(), card.getY() + 40,
                            card.getWidth(), card.getHeight() - 40);
                    panel.paintComponent(g);
                    assertEquals(GoalGlowPainter.bandAround(firstCard), recorder.lastBand,
                            "矩形变化帧须对上一帧的光晕带（外扩 8px）补 repaint");

                    recorder.lastBand = null;
                    panel.paintComponent(g);
                    assertNull(recorder.lastBand, "矩形稳定的帧不补 repaint");
                } finally {
                    g.dispose();
                }
            });
        } finally {
            RepaintManager.setCurrentManager(previous);
        }
    }

    /** 记录目标组件的脏区域请求（全局管理器换装，测试后必须还原；他组件请求忽略）。 */
    private static final class RecordingRepaintManager extends RepaintManager {
        private final JComponent source;
        Rectangle lastBand;

        RecordingRepaintManager(JComponent source) {
            this.source = source;
        }

        @Override
        public void addDirtyRegion(JComponent c, int x, int y, int w, int h) {
            if (c == source) {
                lastBand = new Rectangle(x, y, w, h);
            }
        }
    }

    /** 无窗口的 headless 全树布局：父定界后逐层下推 doLayout（Container.doLayout 只布直接子层）。 */
    private static void layoutTree(java.awt.Container c) {
        c.doLayout();
        for (int i = 0; i < c.getComponentCount(); i++) {
            if (c.getComponent(i) instanceof java.awt.Container child) {
                layoutTree(child);
            }
        }
    }

    @Test
    void glowDisposeIsStickyAgainstPostCleanupRevival() throws Exception {
        buildPanel();
        GoalStateEvents.publish(sessionKey, snap(GoalState.Status.BLOCKED, "b"));
        SwingUtilities.invokeAndWait(() -> { });
        assertEquals(GoalGlowAnimator.Mode.STATIC_BLOCKED, glow().mode(), "前置：光晕已点亮");

        panel.cleanup(); // teardown dispose（粘位幂等：tearDown 的二次 cleanup 亦安全）

        // cleanup 不摘层级监听、不清 goalGlow 字段——显隐桥/迟到迁移路径须有粘位兜底
        SwingUtilities.invokeAndWait(() -> glow().setGoalActive());
        assertEquals(GoalGlowAnimator.Mode.OFF, glow().mode(),
                "dispose 粘位：setGoalActive 不复活动画");
        assertFalse(glow().isAnimating());
        SwingUtilities.invokeAndWait(() -> glow().setGoalBlocked());
        assertEquals(GoalGlowAnimator.Mode.OFF, glow().mode(),
                "dispose 粘位：setGoalBlocked 同样不迁移");
        assertFalse(glow().isAnimating());
    }

    // ---- ⑥ hydrate（缓存腿）：ACTIVE 恢复、终态不回放 ----

    @Test
    void hydrateFromCacheShowsActiveGoal() throws Exception {
        GoalStateEvents.publish(sessionKey, snap(GoalState.Status.ACTIVE, "from cache"));
        buildPanel();
        AwaitUtil.awaitUntil(() -> edtRead(() -> strip().isVisible()),
                "hydrate 从缓存恢复 ACTIVE goal");
        assertEquals(GoalStrip.Phase.ACTIVE, strip().phase());
    }

    @Test
    void hydrateIgnoresTerminalHistory() throws Exception {
        GoalStateEvents.publish(sessionKey, snap(GoalState.Status.COMPLETED, "stale"));
        buildPanel();
        SwingUtilities.invokeAndWait(() -> { });
        Thread.sleep(200);
        assertFalse(strip().isVisible(),
                "终态历史不回放终态展示链（recap 已在聊天流承载）");
    }

    // ---- helpers（接线模式同 AiChatPanelContextRingTest）----

    /** EDT 读屏障：Swing 字段由 EDT 写入，轮询线程须经 invokeAndWait 取值才可见。 */
    private static boolean edtRead(java.util.function.BooleanSupplier supplier) {
        try {
            boolean[] out = new boolean[1];
            SwingUtilities.invokeAndWait(() -> out[0] = supplier.getAsBoolean());
            return out[0];
        } catch (Exception e) {
            throw new IllegalStateException("cannot read on EDT", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object target, String name) {
        try {
            Field f = reachableField(target.getClass(), name);
            f.setAccessible(true);
            return (T) f.get(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot read field " + name, e);
        }
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = reachableField(target.getClass(), name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot set field " + name, e);
        }
    }

    private static Field reachableField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignore) {
                // walk up
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static Object invoke(Object target, String name) {
        try {
            Method m = null;
            for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
                try {
                    m = c.getDeclaredMethod(name);
                    break;
                } catch (NoSuchMethodException ignore) {
                    // walk up
                }
            }
            if (m == null) {
                throw new NoSuchMethodException(name);
            }
            m.setAccessible(true);
            return m.invoke(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot invoke " + name, e);
        }
    }

    /** 立即应答的 fake（AiChatPanelContextRingTest 同款）。 */
    private static final class IdleAiService implements AiService {
        @Override
        public LLMResponse generateResponseWithTools(
                List<Message> messages, List<ToolDefinition> tools, LlmCallOptions options) {
            return LLMResponse.text("unused");
        }

        @Override public String getName() {
            return "idle-fake";
        }

        @Override public GenerationSettings getGenerationSettings() {
            return new GenerationSettings(0.7, 4096, "medium");
        }

        @Override public void setGenerationSettings(GenerationSettings settings) { }

        @Override public boolean supportsToolCalling() {
            return true;
        }
    }
}
