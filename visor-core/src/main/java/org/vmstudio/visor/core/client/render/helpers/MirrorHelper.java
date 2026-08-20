package org.vmstudio.visor.core.client.render.helpers;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import me.phoenixra.atumvr.api.enums.EyeType;
import org.vmstudio.visor.extensions.client.WindowExtension;
import org.vmstudio.visor.core.client.render.VRShaders;
import org.vmstudio.visor.core.client.render.VisorPipelines;
import org.vmstudio.visor.api.client.settings.VRClientSettings;
import org.vmstudio.visor.core.client.utils.ClientUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;

import org.vmstudio.visor.core.client.ClientContext;

import static org.vmstudio.visor.core.client.VisorClientImpl.MC;

public class MirrorHelper {
    private MirrorHelper() {
        throw new UnsupportedOperationException("This is an utility class and cannot be instantiated");
    }


    public static void drawMirror() {
        switch (VRClientSettings.getMirrorMode()){
            case OFF -> drawTextMirror("Mirror is OFF", true);
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
        RenderTarget source;
        if (VRClientSettings.getMirrorEye() == EyeType.LEFT) {
            source = ClientContext.renderer.getTextureLeftEye().getRenderTarget();
        }else {
            source = ClientContext.renderer.getTextureRightEye().getRenderTarget();
        }

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
        RenderTarget source;
        if (VRClientSettings.getMirrorEye() == EyeType.LEFT) {
            source = ClientContext.renderer.getTextureLeftEye().getRenderTarget();
        }else {
            source = ClientContext.renderer.getTextureRightEye().getRenderTarget();
        }

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



    private static void drawTextMirror(String text, boolean clearBackground) {
        final int TEXT_COLOR       = 0xFFFFFF;
        final int CHAR_WIDTH       = 22;
        final int LINE_HEIGHT      = 5;
        final int TEXT_X_OFFSET    = 1;
        final float TEXT_SCALE     = 2f;

        // 1) get the VR mirror dimensions
        var window  = (WindowExtension)(Object)MC.getWindow();
        int vrWidth = window.visor$getActualScreenWidth();
        int vrHeight= window.visor$getActualScreenHeight();

        // PORT-1.21.11: the viewport is owned by the render pass now, and the projection the
        // GUI renderer uses is its own - neither is ours to set here. The whole ortho/model-view
        // sandwich this method used to build is what GuiRenderer does internally.
        RenderTarget target = MC.getMainRenderTarget();
        if (clearBackground) {
            RenderShaderHelper.clearColorAndDepth(target, 0, 1.0);
        }

        GuiGraphics gui = RenderGuiHelper.beginGui();
        gui.pose().scale(TEXT_SCALE, TEXT_SCALE);

        int wrapWidth = vrWidth / CHAR_WIDTH;
        var lines = (text == null)
                ? List.<String>of()
                : ClientUtils.wrapText(text, wrapWidth);

        int y = LINE_HEIGHT;
        for (String line : lines) {
            gui.drawString(MC.font, line, TEXT_X_OFFSET, y, TEXT_COLOR);
            y += LINE_HEIGHT;
        }

        RenderGuiHelper.flushGui();
    }


    /**
     * Copies {@code source} onto the window, into the pixel rectangle {@code left..bottom}.
     * <p>
     * PORT-1.21.11: this was {@code glBlitFramebuffer}. A {@link RenderTarget} no longer owns a
     * framebuffer object - only textures - so there is nothing to bind as a read target and the
     * copy is a textured quad now. The rectangle arithmetic is the same; it just ends up in NDC.
     */
    public static void blit(RenderTarget source,
                            int left, int top,
                            int right, int bottom) {
        drawQuad(source, left, top, right, bottom, 0f, 0f, 1f, 1f);
    }

    public static void blitCropped(RenderTarget source,
                                   int left, int top,
                                   int right, int bottom,
                                   float xCropFactor, float yCropFactor,
                                   boolean keepAspect) {
        if (keepAspect) {
            float targetAspect = (float) MC.mainRenderTarget.width / (float) MC.mainRenderTarget.height;
            float sourceAspect = (float) source.width / (float) source.height;
            if (targetAspect > sourceAspect) {
                yCropFactor = 0.5F
                        - (sourceAspect / targetAspect) * (0.5F - yCropFactor);
            } else {
                xCropFactor = 0.5F
                        - (targetAspect / sourceAspect) * (0.5F - xCropFactor);
            }
        }

        // The crop was a source pixel rectangle; it is the same rectangle expressed as UVs.
        drawQuad(source, left, top, right, bottom,
                xCropFactor, yCropFactor, 1f - xCropFactor, 1f - yCropFactor);
    }


    private static void drawQuad(RenderTarget source,
                                 int left, int top, int right, int bottom,
                                 float u0, float v0, float u1, float v1) {
        RenderTarget destination = MC.getMainRenderTarget();
        float width = destination.width;
        float height = destination.height;

        // The quad is in NDC, so both matrices are identity. Produced before the pass opens:
        // a dynamic uniform write goes through the command encoder, which rejects any command
        // while a pass is open.
        GpuBufferSlice transforms = RenderShaderHelper.writeIdentityTransform();
        GpuBufferSlice projection = RenderShaderHelper.identityProjection();

        RenderShaderHelper.renderScreenQuad(
                () -> "visor mirror blit",
                VisorPipelines.MIRROR_BLIT,
                pass -> {
                    pass.setUniform("DynamicTransforms", transforms);
                    pass.setUniform("Projection", projection);
                    RenderShaderHelper.bindColor(pass, "Sampler0", source);
                },
                destination.getColorTextureView(),
                2f * left / width - 1f,
                2f * top / height - 1f,
                2f * right / width - 1f,
                2f * bottom / height - 1f,
                u0, v0, u1, v1);
    }






}
