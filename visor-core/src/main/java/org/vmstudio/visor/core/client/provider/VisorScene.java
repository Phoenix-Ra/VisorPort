package org.vmstudio.visor.core.client.provider;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import lombok.Getter;
import me.phoenixra.atumvr.api.enums.EyeType;
import me.phoenixra.atumvr.api.rendering.AtumVRRenderContext;
import me.phoenixra.atumvr.api.rendering.AtumVRScene;
import me.phoenixra.atumvr.api.utils.GLUtils;
import net.minecraft.util.profiling.Profiler;
import org.joml.Matrix4fStack;
import org.vmstudio.visor.api.client.render.VRRenderPass;
import org.vmstudio.visor.api.client.render.VRRenderer;
import org.vmstudio.visor.core.client.render.context.RenderContext;
import org.vmstudio.visor.core.client.ClientContext;
import org.vmstudio.visor.core.client.render.VRShaders;
import org.vmstudio.visor.core.client.render.VRRenderState;
import org.vmstudio.visor.extensions.client.render.GameRendererExtension;
import org.vmstudio.visor.compatibility.ShadersHelper;
import org.vmstudio.visor.core.client.render.helpers.RenderShaderHelper;
import org.vmstudio.visor.core.client.render.helpers.MirrorHelper;
import org.vmstudio.visor.api.client.settings.VRClientSettings;
import org.vmstudio.visor.core.client.utils.ClientUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.jetbrains.annotations.NotNull;

import static org.vmstudio.visor.core.client.VisorClientImpl.*;
import com.mojang.blaze3d.opengl.GlStateManager;


public class VisorScene implements AtumVRScene {

    @Getter
    private VRRenderer renderer;


    public VisorScene(VRRenderer renderer) {
        this.renderer = renderer;
    }

    @Override
    public void init() {

    }

    @Override
    public void render(@NotNull AtumVRRenderContext context) {

        var renderContext = (RenderContext) context;
        var profiler =  renderContext.profiler();

        // The model-view entry pushed in onGameRenderStart is already popped by
        // VRRendererBase.onGameRenderEnd (from VisorClientImpl.renderVR) before this runs.

        GlStateManager._depthMask(true);


        profiler.push("prepare VROverlays and cursor");
        ClientContext.overlayManager.prepareOverlaysAndCursor(
                context.partialTicks()
        );
        profiler.pop();

        profiler.push("VROverlay texturing");
        // PORT-1.21.11: a GuiGraphicsExtractor is no longer a thing you make once and flush repeatedly -
        // it records into a GuiRenderState that gets replayed as a unit, so each overlay opens
        // and closes its own. The manager does that per overlay now.
        ClientContext.overlayManager.renderOverlayTextures(
                Profiler.get(),
                renderContext.partialTicks()
        );
        profiler.pop();
        GLUtils.checkGLError("post VR Overlays texturing");

        ShadersHelper.bridge().beginFrame(
                renderContext.partialTicks(),
                renderContext.nanoTime()
        );

        for (VRRenderPass renderPass : VRRenderState.getActivePasses()) {
            profiler.push("VR render pass: "+renderPass.name());

            renderPass(
                    renderPass,
                    renderContext
            );
            GLUtils.checkGLError("post VR render pass: " + renderPass.name());


            if (ClientContext.renderer.isAskedForScreenShot()) {
                takeScreenshot(renderPass);
            }
            profiler.pop();
        }


        ShadersHelper.bridge().endFrame();

        profiler.push("VR mirror");
        VRRenderState.startVRMirrorPhase();
        MirrorHelper.drawMirror();
        profiler.pop();
        GLUtils.checkGLError("post mirror");


    }

    private void takeScreenshot(VRRenderPass currentStage) {

        boolean flag;
        if (currentStage == VRRenderPass.CENTER) {
            flag = true;
        } else {
            flag = VRClientSettings.getMirrorEye() == EyeType.LEFT ?
                    currentStage == VRRenderPass.EYE_LEFT
                    : currentStage == VRRenderPass.EYE_RIGHT;
        }

        if (flag) {
            RenderTarget rendertarget = MC.mainRenderTarget;

            ClientUtils.takeScreenshot(rendertarget);
            // PORT-26.1: Window.updateDisplay() is gone; the swap is RenderSystem.flipFrame() now
            RenderSystem.flipFrame(null);
            ClientContext.renderer.setAskedForScreenShot(false);
        }
    }

    @Override
    public void destroy() {

    }

    private void renderPass(VRRenderPass renderPass,
                            RenderContext context
    ) {
        VRRenderState.startVRWorldPhase(renderPass);

        if (MC.mainRenderTarget == null) {
            LOGGER.warn("Visor: no render target for pass {}; requesting renderer reinit.", renderPass);
            VRRenderState.startVanillaPhase();
            ClientContext.renderer.prepareReinit("Missing target for pass " + renderPass);
            return;
        }

        // Opaque black, full depth. The old sequence set a clear colour, cleared colour only,
        // and re-enabled the depth test; clearing is a command on the encoder now and the depth
        // test belongs to whichever pipeline draws next.
        RenderShaderHelper.clearColorAndDepth(MC.mainRenderTarget, 0xFF000000, 1.0);

        ShadersHelper.bridge().beginEye(renderPass.getEyeOrLeft());

        // PORT-26.1: GameRenderer.render(deltaTracker, renderLevel) became the update / extract /
        // render triple. The camera (VRGameCamera) answers update() with this pass's pose and
        // projection, extract() records the level from it, and render() draws it - the GUI half
        // of render() is cut off by GameRendererMixin#visor$onRenderGUI. The camera entity is
        // parked on the VR camera for the whole sequence, as it was for renderLevel() before.
        var gameRenderer = (GameRendererExtension) MC.gameRenderer;
        gameRenderer.visor$beginWorldPass(context.partialTicks());
        try {
            MC.gameRenderer.update(MC.getDeltaTracker(), context.renderLevel());
            MC.gameRenderer.extract(MC.getDeltaTracker(), context.renderLevel());
            MC.gameRenderer.render(MC.getDeltaTracker(), context.renderLevel());
        } finally {
            gameRenderer.visor$endWorldPass();
        }

        if (ShadersHelper.isShaderActive()) {
            Matrix4fStack modelView = RenderSystem.getModelViewStack();
            modelView.pushMatrix();
            modelView.identity();
            ClientContext.decorationRenderer.renderShaderUi(new PoseStack(), context.partialTicks());
            modelView.popMatrix();
        }

        if (renderPass.isEye()) {

            // PORT-1.21.11: there is no framebuffer to bind any more - the destination is the
            // texture handed to the render pass, so the eye target is passed to finishEye().
            RenderTarget eyeTarget = (renderPass == VRRenderPass.EYE_LEFT)
                    ? ClientContext.renderer.getTextureLeftEye().getRenderTarget()
                    : ClientContext.renderer.getTextureRightEye().getRenderTarget();

            VRShaders.getPostProcess().finishEye(
                    renderPass == VRRenderPass.EYE_LEFT
                            ? EyeType.LEFT : EyeType.RIGHT,
                    MC.mainRenderTarget,
                    eyeTarget,
                    context.partialTicks()
            );

        }

        ShadersHelper.bridge().endEye();
    }



}
