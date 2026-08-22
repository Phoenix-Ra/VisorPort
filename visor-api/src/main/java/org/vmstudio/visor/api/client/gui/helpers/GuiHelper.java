package org.vmstudio.visor.api.client.gui.helpers;

import org.joml.Matrix3x2fStack;
import net.minecraft.util.Util;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.NotNull;

public class GuiHelper {
    private GuiHelper() {
        throw new UnsupportedOperationException("This is an utility class and cannot be instantiated");
    }


    /**
     * Renders custom scaled text centered within bounds, scrolling if it overflows.
     * <p>
     * Overflowing text bounces between its ends with the same timing as vanilla's
     * {@code renderScrollingString} (period {@code max(overflow * 0.5s, 3s)}, eased at both ends).
     *
     * @param guiGraphics the gui graphics context
     * @param font        the font to use
     * @param text        the text to render
     * @param color       the text color
     * @param posX        left edge of the text area
     * @param posY        top edge of the text area
     * @param width       width of the text area
     * @param height      height of the text area
     * @param scale       fixed text scale (1.0 = default)
     * @param center      whether to center the text when it fits
     */
    public static void renderScrollableText(@NotNull GuiGraphicsExtractor guiGraphics,
                                            @NotNull Font font,
                                            @NotNull String text,
                                            int color,
                                            int posX, int posY,
                                            int width, int height,
                                            float scale,
                                            boolean center) {
        if (text.isEmpty() || width <= 0 || height <= 0) return;
        if (scale <= 0f) scale = 1f;

        int textWidth = font.width(text);
        float scaledTextWidth = textWidth * scale;
        float scaledLineHeight = font.lineHeight * scale;

        // Everything below is in screen space (the caller's coordinate system).
        float drawX = posX;
        if (scaledTextWidth <= width) {
            if (center) {
                drawX = posX + (width - scaledTextWidth) / 2f;
            }
        } else {
            drawX = posX - scrollOffset(scaledTextWidth - width);
        }
        float drawY = center ? posY + (height - scaledLineHeight) / 2f : posY;

        // PORT-1.21.11: enableScissor now maps the rectangle through the current pose (it ignored
        // the pose up to 1.21.4), so the scissor has to be pushed BEFORE the text scale transform.
        // Pushed after it, the clip region shrinks to scale * (width, height): labels that fit the
        // full width (and therefore never scroll) get cut off and look like a stuck marquee.
        guiGraphics.enableScissor(posX, posY, posX + width, posY + height);
        if (scale == 1f) {
            guiGraphics.text(font, text, Math.round(drawX), Math.round(drawY), color, false);
        } else {
            Matrix3x2fStack poseStack = guiGraphics.pose();
            poseStack.pushMatrix();
            poseStack.translate(drawX, drawY);
            poseStack.scale(scale, scale);
            guiGraphics.text(font, text, 0, 0, color, false);
            poseStack.popMatrix();
        }
        guiGraphics.disableScissor();
    }

    /**
     * Current horizontal scroll offset of an overflowing label, in whole screen pixels.
     * Same curve as vanilla {@code ActiveTextCollector#defaultScrollingHelper}.
     *
     * @param overflow how many screen pixels the text exceeds its area by (must be positive)
     */
    private static int scrollOffset(float overflow) {
        double d = (double) Util.getMillis() / 1000.0;
        double e = Math.max((double) overflow * 0.5, 3.0);
        double f = Math.sin((Math.PI / 2.0) * Math.cos((Math.PI * 2.0) * d / e)) / 2.0 + 0.5;
        return (int) Mth.lerp(f, 0.0, (double) overflow);
    }

    public static void renderScalableText(@NotNull GuiGraphicsExtractor guiGraphics,
                                          @NotNull Font font,
                                          @NotNull String text,
                                          int color,
                                          int posX, int posY,
                                          int width, int height,
                                          boolean center) {
        renderScalableText(
                guiGraphics,
                font,
                text,
                color,
                posX, posY,
                width, height,
                1.0f,
                center
        );
    }

    public static void renderScalableText(@NotNull GuiGraphicsExtractor guiGraphics,
                                          @NotNull Font font,
                                          @NotNull String text,
                                          int color,
                                          int posX, int posY,
                                          int width, int height,
                                          float maxScale,
                                          boolean center) {
        if (text.isEmpty()) return;

        float textWidth = font.width(text);
        float textHeight = font.lineHeight;

        float heightScale = (float) height / font.lineHeight;

        float scale = Math.min(maxScale, Math.min(width / textWidth, heightScale));


        float dispW = textWidth * scale;
        float dispH = textHeight * scale;

        float drawX = posX;
        float drawY = posY;
        if (center) {
            float targetX = posX + (width  - dispW) * 0.5f;
            float targetY = posY + (height - dispH) * 0.5f;
            drawX = targetX;
            drawY = targetY;
        }

        // Save current transform state
        Matrix3x2fStack poseStack = guiGraphics.pose();
        poseStack.pushMatrix();

        // Apply the transform FIRST, before scissoring
        poseStack.translate(drawX, drawY);
        poseStack.scale(scale, scale);
        poseStack.translate(-drawX, -drawY);

        // Calculate text position in the transformed space
        float baseX = drawX;
        float baseY = drawY;

        guiGraphics.text(font, text, Math.round(baseX), Math.round(baseY), color, false);


        // Restore transform state
        poseStack.popMatrix();

    }



}
