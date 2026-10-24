package org.gitee.jmeter.ai.gui;

import org.gitee.jmeter.ai.gui.theme.UiTokens;

import java.awt.BasicStroke;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.Point2D;

/**
 * goal 呼吸光晕的绘制器：把光环画在输入卡圆角矩形<b>外侧</b>的既有静区里——左右
 * 下是聊天面板的页边距（{@link UiTokens#PAGE_MARGIN}=6px），上方是输入坞与转录卡
 * 之间的空隙（SPACE_2=8px）；环带最大外延 ≤5.5px 恰好放得下，<b>零布局变化</b>。
 * 早期的「壳层动态留边」方案（独立壳组件、光晕点亮时四向留边距）在固定像素宽度的
 * 分割面板下拿不到新像素，多出的边距反而从卡片身上扣——光晕一点亮输入卡肉眼可见
 * 地缩小，已废弃。
 *
 * <p>绘制由宿主面板（{@link AiChatPanel}）在自身 {@code paintComponent} 里调用：
 * 面板刚铺完自身底色、尚未画任何子组件，输入卡随后铺下的圆角填充会盖住环带内侧的
 * 重叠，环看起来只从卡片边缘向外生长。绘制完全无状态：颜色、alpha、扫掠角每次
 * paint 实时读 {@link GoalGlowAnimator}，动画帧与模式边沿只负责触发宿主对光晕带
 * 的区域 repaint（熄灭沿复用同一 repaint 清掉残留像素，故卡片矩形取值与光晕模式
 * 无关）。
 */
final class GoalGlowPainter {

    /** 与卡片描边同心：RoundedBorderPanel 的圆角同样取 RADIUS_LARGE 的两倍。 */
    private static final int ARC = UiTokens.RADIUS_LARGE * 2;
    /** 光晕带 repaint 在卡片矩形四周外扩的余量（容纳最外环 5.5px 外延 + 抗锯齿）。 */
    private static final int BAND = 8;
    /** 呼吸环 {外扩、线宽、alpha 折减}：贴卡描边主透明度，向外逐级减淡成光环。 */
    private static final float[][] GLOW_RINGS = {
            {1, 2.5f, 1.00f}, {3, 1.5f, 0.55f}, {5, 1f, 0.25f}};
    /** 扫掠弧 {外扩、线宽、alpha 折减}：贴卡描边的高亮头 + 一圈向外的淡回声。 */
    private static final float[][] SWEEP_RINGS = {{1, 2f, 1.00f}, {3, 1f, 0.40f}};

    private GoalGlowPainter() {
    }

    /** 画一圈光晕；glow 未挂接、模式熄灭或卡片矩形无效时零开销早退。 */
    static void paint(Graphics g, GoalGlowAnimator glow, Rectangle cardRect) {
        if (glow == null || glow.mode() == GoalGlowAnimator.Mode.OFF
                || cardRect == null || cardRect.width <= 0 || cardRect.height <= 0) {
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            java.awt.Color base = glow.glowColor();
            for (int i = 0; i < GLOW_RINGS.length; i++) {
                float alpha = glow.ringAlpha(i) * GLOW_RINGS[i][2];
                if (alpha <= 0f) {
                    continue;
                }
                g2.setStroke(new BasicStroke(GLOW_RINGS[i][1]));
                g2.setColor(withAlpha(base, alpha));
                drawRing(g2, cardRect, Math.round(GLOW_RINGS[i][0]));
            }
            if (glow.sweeping()) {
                paintSweep(g2, glow, cardRect, base);
            }
        } finally {
            g2.dispose();
        }
    }

    /** 光晕带的 repaint 区域：卡片矩形四周外扩 {@link #BAND}。 */
    static Rectangle bandAround(Rectangle cardRect) {
        return new Rectangle(cardRect.x - BAND, cardRect.y - BAND,
                cardRect.width + BAND * 2, cardRect.height + BAND * 2);
    }

    /** 扫掠高亮：梯度轴取直径方向、正端朝 sweepAngle，环带全部落在卡外静区。 */
    private static void paintSweep(Graphics2D g2, GoalGlowAnimator glow,
            Rectangle cardRect, java.awt.Color base) {
        float angle = glow.sweepAngle();
        double cx = cardRect.getCenterX();
        double cy = cardRect.getCenterY();
        double r = Math.max(cardRect.width, cardRect.height) / 2.0 + 8;
        Point2D head = new Point2D.Double(cx + r * Math.cos(angle), cy + r * Math.sin(angle));
        Point2D tail = new Point2D.Double(cx - r * Math.cos(angle), cy - r * Math.sin(angle));
        float[] fractions = {0f, 0.55f, 0.88f, 1f};
        for (float[] ring : SWEEP_RINGS) {
            // 每环独立梯度：折减列缩放头/尾亮度，回声环按 0.40 减淡而非与头环同亮
            g2.setPaint(new LinearGradientPaint(tail, head, fractions, new java.awt.Color[] {
                    withAlpha(base, 0f), withAlpha(base, 0f),
                    withAlpha(base, 0.35f * ring[2]), withAlpha(base, 0.95f * ring[2])}));
            g2.setStroke(new BasicStroke(ring[1]));
            drawRing(g2, cardRect, Math.round(ring[0]));
        }
    }

    /** 卡片圆角矩形的外扩同心环：原点外扩 o、弧径 +2o+2，角弧圆心与卡片描边严格
     * 重合（描边 inset 1、弧径 ARC → 圆心 (19,19)，环圆心 = −o + (ARC+2o+2)/2 =
     * 卡原点 + 19 恒等），各环与卡缘的间距直边与圆角处处一致；直边外延 o 与弧径
     * 无关，环带外延 ≤5.5px 的静区结论不受影响。 */
    private static void drawRing(Graphics2D g2, Rectangle cardRect, int offset) {
        int arc = ARC + offset * 2 + 2;
        g2.drawRoundRect(cardRect.x - offset, cardRect.y - offset,
                cardRect.width + offset * 2 - 1, cardRect.height + offset * 2 - 1, arc, arc);
    }

    private static java.awt.Color withAlpha(java.awt.Color base, float alpha) {
        return new java.awt.Color(base.getRed(), base.getGreen(), base.getBlue(),
                Math.round(255 * Math.min(1f, Math.max(0f, alpha))));
    }
}
