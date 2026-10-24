package org.gitee.jmeter.ai.gui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GoalGlowPainter 像素契约：光环全部落在卡片矩形外侧的静区里（外延 ≤ 页边距 6px、
 * d=6 只容亚可见残影）、OFF/空参数零开销早退、ACTIVE 扫掠头亮尾隐。
 * 直接在 BufferedImage 上离屏绘制验证（无组件、无 EDT 依赖）。
 */
class GoalGlowPainterTest {

    /** 画布与卡片矩形：卡片四周留 ≥40px 静区，够采样到 d=7。 */
    private static final Rectangle CARD = new Rectangle(50, 60, 300, 120);
    private static final int CANVAS_W = 400;
    private static final int CANVAS_H = 260;

    private GoalGlowAnimator glow;

    @AfterEach
    void disposeGlow() {
        if (glow != null) {
            glow.dispose();
        }
    }

    @Test
    void blockedRingsPaintOutsideCardAndOffIsANoOp() {
        glow = new GoalGlowAnimator(() -> { });
        glow.setGoalBlocked(); // 静态琥珀：alpha 恒定、无扫掠，逐像素确定性

        BufferedImage img = newCanvas();
        Graphics2D g2 = img.createGraphics();
        try {
            GoalGlowPainter.paint(g2, glow, CARD);
        } finally {
            g2.dispose();
        }
        int midY = CARD.y + CARD.height / 2;
        assertTrue(alphaAt(img, CARD.x - 2, midY) > 0, "offset 2 处必须画到环");
        assertTrue(alphaAt(img, CARD.x - 4, midY) > 0, "offset 4 处必须画到环");
        assertTrue(alphaAt(img, CARD.x - 5, midY) > 0, "offset 5 处必须画到环");

        // OFF 的契约是对「脏画布」的零接触：残留像素由宿主对光晕带的 repaint 清除
        // （清残影走 repaint，不走 OFF 自身落笔），OFF 必须一笔不画也不改一笔——
        // 同一画布上再画 OFF 后逐像素不变（在净画布上断言「OFF 不留像素」是恒真同义反复）
        int[] before = pixels(img);
        glow.off();
        Graphics2D gOff = img.createGraphics();
        try {
            GoalGlowPainter.paint(gOff, glow, CARD);
        } finally {
            gOff.dispose();
        }
        assertTrue(java.util.Arrays.equals(before, pixels(img)),
                "OFF 对脏画布零接触：清残影是宿主 repaint 的职责，paint(OFF) 不碰像素");
    }

    @Test
    void ringBandFitsInsideSixPixelQuietZone() {
        glow = new GoalGlowAnimator(() -> { });
        glow.setGoalBlocked();

        BufferedImage img = newCanvas();
        Graphics2D g2 = img.createGraphics();
        try {
            GoalGlowPainter.paint(g2, glow, CARD);
        } finally {
            g2.dispose();
        }
        int midY = CARD.y + CARD.height / 2;
        // 扫描线：d=1..5 逐级画到，d=6 只容亚可见抗锯齿残影（≤8/255），d≥7 必须干净——
        // d=7 单元只钉到外延 ≤6.5px，d=6 容差把钉住界收紧到 ~6.1px：环表单边加宽
        // （如环2 线宽 1f→3f，外延越出 6px 页边距）时此处先红，不必等到可见裁切
        for (int d = 1; d <= 5; d++) {
            assertTrue(alphaAt(img, CARD.x - d, midY) > 0, "d=" + d + " 应有环像素");
        }
        assertTrue(alphaAt(img, CARD.x - 6, midY) <= 8,
                "d=6 只容亚可见残影（环带外延越出页边距时先红）");
        for (int d = 7; d <= 12; d++) {
            assertEquals(0, alphaAt(img, CARD.x - d, midY), "d=" + d + " 不得越出静区");
        }
    }

    @Test
    void sweepHeadIsBrighterThanTail() {
        glow = new GoalGlowAnimator(() -> { });
        glow.setGoalActive(); // 新动画器 sweepAngle=0：亮头在 +x 方向

        BufferedImage img = newCanvas();
        Graphics2D g2 = img.createGraphics();
        try {
            GoalGlowPainter.paint(g2, glow, CARD);
        } finally {
            g2.dispose();
        }
        int midY = CARD.y + CARD.height / 2;
        int headSum = 0;
        int tailSum = 0;
        for (int d = 1; d <= 5; d++) {
            headSum += alphaAt(img, CARD.x + CARD.width - 1 + d, midY);
            tailSum += alphaAt(img, CARD.x - d, midY);
        }
        assertTrue(headSum > tailSum,
                "扫掠头侧（+x）alpha 和必须大于尾侧: head=" + headSum + " tail=" + tailSum);
    }

    @Test
    void degenerateInputsAreNoOps() {
        glow = new GoalGlowAnimator(() -> { });
        BufferedImage img = newCanvas();
        Graphics2D g2 = img.createGraphics();
        try {
            GoalGlowPainter.paint(g2, null, CARD); // null glow：早退不画
            GoalGlowPainter.paint(g2, glow, new Rectangle(0, 0, 1, 1)); // 退化矩形
            GoalGlowPainter.paint(g2, glow, null); // null 矩形
            assertEquals(new Rectangle(CARD.x - 8, CARD.y - 8, CARD.width + 16, CARD.height + 16),
                    GoalGlowPainter.bandAround(CARD), "band = 卡片矩形四周外扩 8");
        } finally {
            g2.dispose();
        }
    }

    private static BufferedImage newCanvas() {
        return new BufferedImage(CANVAS_W, CANVAS_H, BufferedImage.TYPE_INT_ARGB);
    }

    private static int[] pixels(BufferedImage img) {
        return img.getRGB(0, 0, CANVAS_W, CANVAS_H, null, 0, CANVAS_W);
    }

    private static int alphaAt(BufferedImage img, int x, int y) {
        return img.getRGB(x, y) >>> 24;
    }
}
