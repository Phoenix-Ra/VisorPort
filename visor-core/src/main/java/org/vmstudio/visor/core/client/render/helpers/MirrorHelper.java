package org.vmstudio.visor.core.client.render.helpers;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.textures.FilterMode;
import me.phoenixra.atumvr.api.enums.EyeType;
import org.vmstudio.visor.extensions.client.WindowExtension;
import org.vmstudio.visor.core.client.render.VRShaders;
import org.vmstudio.visor.api.client.settings.VRClientSettings;
import org.vmstudio.visor.core.client.utils.ClientUtils;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;

import org.vmstudio.visor.core.client.ClientContext;

import static org.vmstudio.visor.core.client.VisorClientImpl.MC;

/**
 * Composes the desktop mirror.
 * <p>
 * Runs during the VR_MIRROR phase, after every VR pass of the frame: {@code MC.mainRenderTarget}
 * is the window-sized mirror target by then, each mode copies its source(s) onto it, and
 * {@code MinecraftMixin} presents that target to the window in place of vanilla's.
 * <p>
 * PORT-1.21.11: the copies were raw {@code glBlitFramebuffer} calls on the targets' framebuffer
 * ids. A {@link RenderTarget} no longer owns one, so they go through
 * {@link RenderShaderHelper#blit}, which keeps the blit's argument shape and semantics
 * (bottom-left origin, every channel copied as-is, scaled with a filter) on top of the GL
 * backend's per-texture framebuffers. The mirror target is cleared opaque here first as well:
 * vanilla's {@code presentTexture} copies the whole texture to the window, and with
 * {@code createTargets()} able to rebuild the mirror mid-frame nothing else guarantees the
 * parts a mode does not cover hold anything sensible.
 */
public class MirrorHelper {
    private MirrorHelper() {
        throw new UnsupportedOperationException("This is an utility class and cannot be instantiated");
    }

    /** The mirror sits in the frame where the window was; nothing shows through it. */
    private static final int MIRROR_CLEAR_COLOR = 0xFF000000;


    public static void drawMirror() {
        RenderTarget mirror = MC.getMainRenderTarget();
        if (mirror == null || mirror.getColorTexture() == null) {
            return;
        }
        // Opaque black underneath everything; depth too, the text mode draws GUI text over it.
        RenderShaderHelper.clear(mirror, MIRROR_CLEAR_COLOR, 1.0);

        switch (VRClientSettings.getMirrorMode()){
            case OFF -> drawTextMirror("Mirror is OFF");
            case GUI -> drawGuiMirror();
            case CROPPED -> drawCroppedMirror();
            case SINGLE -> drawSingleMirror();
            case DUAL -> drawDualMirror();
            case FIRST_PERSON -> drawFirstPersonMirror();
            case THIRD_PERSON -> drawThirdPersonMirror();
            case MIXED_REALITY -> VRShaders.getMixedReality().drawMirror();
        }
    }


    private static void drawGuiMirror(){
        RenderTarget source = ClientContext.renderer.guiTarget.getTarget();

        int screenWidth = ((WindowExtension) (Object) MC.getWindow()).visor$getActualScreenWidth();
        int screenHeight = ((WindowExtension) (Object) MC.getWindow()).visor$getActualScreenHeight();
        blit(
                source,
                0,0,
                screenWidth,
                screenHeight
        );
    }

    private static void drawCroppedMirror(){
        RenderTarget source = getMirrorEyeTarget();

        float xCrop = VRClientSettings.getMirrorCrop();
        float yCrop = VRClientSettings.getMirrorCrop();

        int screenWidth = ((WindowExtension) (Object) MC.getWindow()).visor$getActualScreenWidth();
        int screenHeight = ((WindowExtension) (Object) MC.getWindow()).visor$getActualScreenHeight();

        blitCropped(
                source,
                0,0,
                screenWidth, screenHeight,
                xCrop, yCrop,
                true
        );
    }
    private static void drawSingleMirror(){
        RenderTarget source = getMirrorEyeTarget();

        int screenWidth = ((WindowExtension) (Object) MC.getWindow()).visor$getActualScreenWidth();
        int screenHeight = ((WindowExtension) (Object) MC.getWindow()).visor$getActualScreenHeight();
        blit(
                source,
                0,0,
                screenWidth,
                screenHeight
        );
    }




    private static void drawFirstPersonMirror(){
        RenderTarget source = ClientContext.renderer.firstPersonTarget.getTarget();

        int screenWidth = ((WindowExtension) (Object) MC.getWindow()).visor$getActualScreenWidth();
        int screenHeight = ((WindowExtension) (Object) MC.getWindow()).visor$getActualScreenHeight();
        blit(
                source,
                0,0,
                screenWidth, screenHeight
        );
    }
    private static void drawThirdPersonMirror(){
        RenderTarget source = ClientContext.renderer.thirdPersonTarget.getTarget();

        int screenWidth = ((WindowExtension) (Object) MC.getWindow()).visor$getActualScreenWidth();
        int screenHeight = ((WindowExtension) (Object) MC.getWindow()).visor$getActualScreenHeight();
        blit(
                source,
                0,0,
                screenWidth, screenHeight
        );
    }
    private static void drawDualMirror(){
        RenderTarget leftEye = ClientContext.renderer
                .getTextureLeftEye().getRenderTarget();
        RenderTarget rightEye = ClientContext.renderer
                .getTextureRightEye().getRenderTarget();

        int screenWidth = ((WindowExtension) (Object) MC.getWindow()).visor$getActualScreenWidth() / 2;
        int screenHeight = ((WindowExtension) (Object) MC.getWindow()).visor$getActualScreenHeight();

        blit(
                leftEye,
                0,0,
                screenWidth, screenHeight
        );

        blit(
                rightEye,
                screenWidth,0,
                MC.mainRenderTarget.width, screenHeight
        );

    }

    /** The eye the single-eye modes show, per the mirror-eye setting. */
    private static RenderTarget getMirrorEyeTarget() {
        return VRClientSettings.getMirrorEye() == EyeType.LEFT
                ? ClientContext.renderer.getTextureLeftEye().getRenderTarget()
                : ClientContext.renderer.getTextureRightEye().getRenderTarget();
    }



    /**
     * Draws {@code text} on the (already cleared) mirror, at {@code TEXT_SCALE} mirror pixels per
     * font pixel.
     * <p>
     * PORT-1.21.11: GUI drawing is recorded and replayed by {@code GuiRenderer}, which derives its
     * own ortho projection from {@code Window.getWidth() / Window.getGuiScale()} - during the
     * mirror phase that is the mirror size over the VR GUI scale. The explicit ortho/model-view
     * sandwich this used to build is therefore gone, and the one thing left to do for parity is
     * to divide the GUI scale back out of the pose, or the text comes out {@code guiScale} times
     * larger than on 1.21.4.
     */
    private static void drawTextMirror(String text) {
        final int TEXT_COLOR       = 0xFFFFFF;
        final int CHAR_WIDTH       = 22;
        final int TEXT_X_OFFSET    = 1;
        final float TEXT_SCALE     = 2f;

        var window  = (WindowExtension)(Object)MC.getWindow();
        int vrWidth = window.visor$getActualScreenWidth();

        int guiScale = Math.max(1, MC.getWindow().getGuiScale());
        float scale = TEXT_SCALE / guiScale;

        GuiGraphics gui = RenderGuiHelper.beginGui();
        gui.pose().scale(scale, scale);

        int wrapWidth = vrWidth / CHAR_WIDTH;
        var lines = (text == null)
                ? List.<String>of()
                : ClientUtils.wrapText(text, wrapWidth);

        int lineHeight = MC.font.lineHeight + 1;
        int y = lineHeight;
        for (String line : lines) {
            gui.drawString(MC.font, line, TEXT_X_OFFSET, y, TEXT_COLOR);
            y += lineHeight;
        }

        RenderGuiHelper.flushGui();
    }


    /**
     * Copies the whole of {@code source} onto the mirror, into the pixel rectangle
     * {@code left..right} x {@code top..bottom} (bottom-left origin, like {@code glBlitFramebuffer}).
     */
    public static void blit(RenderTarget source,
                            int left, int top,
                            int right, int bottom) {
        if (source == null) {
            return;
        }
        RenderShaderHelper.blit(
                () -> "visor mirror blit",
                source, 0, 0, source.width, source.height,
                MC.getMainRenderTarget(), left, top, right, bottom,
                FilterMode.LINEAR);
    }

    /**
     * Copies the centre of {@code source}, with {@code xCropFactor}/{@code yCropFactor} of it cut
     * off each side, onto the mirror rectangle; with {@code keepAspect} the crop is widened on one
     * axis so the result is not stretched.
     */
    public static void blitCropped(RenderTarget source,
                                   int left, int top,
                                   int right, int bottom,
                                   float xCropFactor, float yCropFactor,
                                   boolean keepAspect) {
        if (source == null) {
            return;
        }
        RenderTarget mirror = MC.getMainRenderTarget();
        if (keepAspect) {
            float targetAspect = (float) mirror.width / (float) mirror.height;
            float sourceAspect = (float) source.width / (float) source.height;
            if (targetAspect > sourceAspect) {
                yCropFactor = 0.5F
                        - (sourceAspect / targetAspect) * (0.5F - yCropFactor);
            } else {
                xCropFactor = 0.5F
                        - (targetAspect / sourceAspect) * (0.5F - xCropFactor);
            }
        }

        int xMin = (int) (xCropFactor * source.width);
        int yMin = (int) (yCropFactor * source.height);
        int xMax = source.width - xMin;
        int yMax = source.height - yMin;

        RenderShaderHelper.blit(
                () -> "visor mirror blit (cropped)",
                source, xMin, yMin, xMax, yMax,
                mirror, left, top, right, bottom,
                FilterMode.LINEAR);
    }

}
