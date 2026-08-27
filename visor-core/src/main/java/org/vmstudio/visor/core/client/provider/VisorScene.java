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

            // PORT-26.2: end the GPU frame for this pass.
            //
            // 26.2 tied fences to the command encoder's submit index: a GlFence now records
            // which submit produced it, and awaiting one from the *current* submit throws
            // "Cannot wait on a fence for the current submit". Vanilla's per-frame ring
            // buffers (MappableRingBuffer, BUFFER_COUNT = 3) rotate once per pass and assume
            // each rotation crosses a submit. Visor renders three passes - both eyes and the
            // mirror - inside one vanilla frame, so the third landed back on a fence created
            // during this submit and CloudRenderer took the whole frame down.
            //
            // On 26.1 this was invisible: GlFence was a bare GL sync object and awaiting it
            // mid-frame merely blocked. Submitting per pass restores the invariant the ring
            // buffers were written against, and is what a pass boundary means now.
            //
            // The ring's actual contract is only "at most BUFFER_COUNT - 1 rotations may share
            // a submit", so one submit before the loop plus one every other pass would also
            // satisfy it and would spare the per-eye CPU/GPU stall this costs. Left per-pass
            // for now: it is the conservative reading and this is not the frame budget's
            // current problem.
            RenderSystem.getDevice().createCommandEncoder().submit();


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
            RenderTarget rendertarget = MC.gameRenderer.mainRenderTarget;

            ClientUtils.takeScreenshot(rendertarget);
            // PORT-26.1: Window.updateDisplay() is gone; the swap is RenderSystem.flipFrame() now
            // PORT-26.2: and flipFrame is gone in turn - presentation moved onto the swapchain,
            // so the frame is handed over by the window's own GpuSurface.
            MC.windowSurface().present();
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

        if (MC.gameRenderer.mainRenderTarget == null) {
            LOGGER.warn("Visor: no render target for pass {}; requesting renderer reinit.", renderPass);
            VRRenderState.startVanillaPhase();
            ClientContext.renderer.prepareReinit("Missing target for pass " + renderPass);
            return;
        }

        // Opaque black, empty depth. The old sequence set a clear colour, cleared colour only,
        // and re-enabled the depth test; clearing is a command on the encoder now and the depth
        // test belongs to whichever pipeline draws next.
        RenderShaderHelper.clearColorAndDepth(MC.gameRenderer.mainRenderTarget, 0xFF000000,
                RenderShaderHelper.CLEAR_DEPTH_FAR);

        ShadersHelper.bridge().beginEye(renderPass.getEyeOrLeft());

        // PORT-26.1: GameRenderer.render(deltaTracker, renderLevel) became the update / extract /
        // render triple. The camera (VRGameCamera) answers update() with this pass's pose and
        // projection, extract() records the level from it, and render() draws it - the GUI half
        // of render() is cut off by GameRendererMixin#visor$onRenderGUI. The camera entity is
        // parked on the VR camera for the whole sequence, as it was for renderLevel() before.
        var gameRenderer = (GameRendererExtension) MC.gameRenderer;
        gameRenderer.visor$beginWorldPass(context.partialTicks());
        try {
            // PORT-26.2: gizmos are collected through a ThreadLocal that is only installed for
            // the duration of a Gizmos.TemporaryCollection, and Gizmos.addGizmo throws
            // "Gizmos cannot be created here! No GizmoCollector has been registered." when it is
            // not. Minecraft.renderFrame wraps its own update/extract in the extractor's
            // main-thread collection and render() in the level renderer's render-thread one; a VR
            // pass drives those three calls itself, so it has to open the same two scopes or any
            // emitter reached from them (DebugRenderer.emitGizmos, GameTestBlockHighlightRenderer)
            // takes the pass down.
            try (var ignored = MC.levelExtractor.collectPerFrameMainThreadGizmos()) {
                // PORT-26.2: update() takes only the DeltaTracker now.
                MC.gameRenderer.update(MC.getDeltaTracker());
                MC.gameRenderer.extract(MC.getDeltaTracker(), context.renderLevel());
            }
            try (var ignored = MC.levelRenderer.collectPerFrameRenderThreadGizmos()) {
                MC.gameRenderer.render(MC.getDeltaTracker(), context.renderLevel());
            }
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
                    MC.gameRenderer.mainRenderTarget,
                    eyeTarget,
                    context.partialTicks()
            );

        }

        ShadersHelper.bridge().endEye();
    }



}
