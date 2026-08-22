package org.vmstudio.visor.core.client.render.helpers;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import me.phoenixra.atumvr.api.misc.color.AtumColor;
import org.vmstudio.visor.api.client.player.pose.VRPlayerPoseClient;
import org.vmstudio.visor.api.client.player.pose.PlayerPoseType;
import org.vmstudio.visor.api.client.gui.overlays.VROverlay;
import org.vmstudio.visor.api.client.gui.overlays.VROverlayPose;
import org.vmstudio.visor.compatibility.ShadersHelper;
import org.vmstudio.visor.extensions.client.render.GameRendererExtension;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.vmstudio.visor.core.client.render.VRRenderState;
import org.vmstudio.visor.core.client.render.VisorPipelines;
import org.vmstudio.visor.mixin.client.accessors.GameRendererAccessor;
import org.vmstudio.visor.core.client.utils.ClientUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import org.vmstudio.visor.core.client.ClientContext;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import static org.vmstudio.visor.core.client.VisorClientImpl.MC;

public class RenderGuiHelper {
    private RenderGuiHelper() {
        throw new UnsupportedOperationException("This is an utility class and cannot be instantiated");
    }







    /**
     * Starts an off-schedule GUI recording and returns the {@link GuiGraphicsExtractor} to draw into.
     * <p>
     * PORT-1.21.11: {@code new GuiGraphicsExtractor(mc, bufferSource)} plus {@code flush()} is gone. GUI
     * drawing now appends to a {@link GuiRenderState} that the {@link GuiRenderer} replays in one
     * pass, so anything drawing a GUI outside vanilla schedule has to reset the state, record, and
     * ask the renderer to replay it. The replay lands on whatever {@code Minecraft.mainRenderTarget}
     * currently is, which is exactly how Visor points it at an overlay texture or at the mirror.
     * <p>
     * Always pair with {@link #flushGui()}.
     */
    public static GuiGraphicsExtractor beginGui() {
        return beginGui(0, 0);
    }

    public static GuiGraphicsExtractor beginGui(int mouseX, int mouseY) {
        GuiRenderState state = MC.gameRenderer.getGameRenderState().guiRenderState;
        state.reset();
        return new GuiGraphicsExtractor(MC, state, mouseX, mouseY);
    }

    /** Replays everything recorded since {@link #beginGui} onto the current main render target. */
    public static void flushGui() {
        GameRendererAccessor gameRenderer = (GameRendererAccessor) MC.gameRenderer;
        // PORT-26.1: GUI item/entity draws sample GameRenderer.lightmap(), which is the flat UI
        // lightmap only while useUiLightmap is set - vanilla sets it around its own GUI draw in
        // GameRenderer.render(). Visor flushes outside that window, so mirror it here or the
        // overlay textures get lit by the world lightmap (dark at night, tinted in the Nether).
        boolean uiLightmap = MC.gameRenderer.useUiLightmap;
        MC.gameRenderer.useUiLightmap = true;
        try {
            gameRenderer.visor$getGuiRenderer().render(
                    gameRenderer.visor$getFogRenderer().getBuffer(FogRenderer.FogMode.NONE));
            gameRenderer.visor$getGuiRenderer().endFrame();
        } finally {
            MC.gameRenderer.useUiLightmap = uiLightmap;
        }
    }


    public static void renderOverlayQuad(VROverlay overlay,
                                         PoseStack poseStack,
                                         Vector3fc position,
                                         Matrix4fc orientation,
                                         boolean depthAlways,
                                         boolean useLight,
                                         boolean drawDragHandle,
                                         float scale
    ) {
        VRPlayerPoseClient renderPose = ClientContext.localPlayer
                .getPoseData(PlayerPoseType.RENDER);

        var eye = RenderPoseHelper.getCameraPosition(
                VRRenderState.getRenderPass(),
                renderPose
        );
        scale = scale * renderPose.getWorldScale();

        GpuBufferSlice fogCache = RenderSystem.getShaderFog();
        var color = AtumColor.WHITE.asMutable();

        boolean dragging = overlay.isBeingDragged();
        boolean resizing = overlay.isBeingResized();
        var barColor = (resizing
                ? AtumColor.immutable(120, 220, 255, 160)
                : (dragging
                ? AtumColor.immutable(220, 220, 220, 110)
                : AtumColor.immutable(190, 190, 190, 85))).asMutable();

        var renderTarget = overlay.getRenderTarget();
        assert renderTarget != null;

        // Blend, depth and cull are chosen by picking a pipeline rather than by poking GL. Fog is
        // still ambient state, because it is a uniform block that every pass binds from
        // RenderSystem - and the lit panel samples it, so a world scene has to silence it.
        boolean inWorld = VRRenderState.getSceneType().isWorld();
        if (inWorld) {
            RenderSystem.setShaderFog(((GameRendererAccessor) MC.gameRenderer)
                    .visor$getFogRenderer()
                    .getBuffer(FogRenderer.FogMode.NONE));
        }

        // --- Pose ---
        poseStack.pushPose();
        poseStack.translate(position.x() - eye.x(), position.y() - eye.y(), position.z() - eye.z());
        poseStack.mulPose((Matrix4f) orientation);
        poseStack.scale(scale, scale, scale);

        // --- Quad + light ---
        int packedLight = -1;
        boolean useLitPath = MC.level != null && useLight && !ShadersHelper.isShaderActive();
        if (useLitPath) {
            Vector3fc lightPos = position;
            if (RenderHelper.isInSolidBlock(position)
                    || ((GameRendererExtension) MC.gameRenderer).visor$isInBlock()) {
                lightPos = ClientContext.localPlayer
                        .getPoseData(PlayerPoseType.RENDER)
                        .getHmd()
                        .getPosition();
            }
            int minLight = ShadersHelper.shaderLight();
            packedLight = ClientUtils.getCombinedLightWithMin(
                    MC.level,
                    BlockPos.containing(new Vec3((Vector3f) lightPos)),
                    minLight
            );
            RenderHelper.renderDisplayQuadWithLight(
                    VisorPipelines.overlayQuadFor(inWorld, true, depthAlways),
                    poseStack.last().pose(),
                    renderTarget,
                    color,
                    (float) overlay.getWidth(),
                    (float) overlay.getHeight(),
                    VROverlayPose.QUAD_SCALE,
                    packedLight,
                    false
            );
        } else {
            RenderHelper.renderDisplayQuad(
                    VisorPipelines.overlayQuadFor(inWorld, false, depthAlways),
                    poseStack.last().pose(),
                    renderTarget,
                    color,
                    (float) overlay.getWidth(),
                    (float) overlay.getHeight(),
                    VROverlayPose.QUAD_SCALE
            );
        }

        // --- Drag handle bar + resize handle
        if (drawDragHandle && overlay.supportsDragging()) {
            float brightness = 1f;
            if (packedLight >= 0) {
                int blockLight = (packedLight >> 4) & 0xF;
                int skyLight   = (packedLight >> 20) & 0xF;
                brightness = Math.max(0.2f, Math.max(blockLight, skyLight) / 15f);
            }
            drawDragHandleBar(overlay, poseStack, barColor, brightness);
            if (overlay.supportsResizing()) {
                drawResizeHandle(overlay, poseStack, barColor, brightness);
            }
            if (resizing) {
                drawResizeOutline(overlay, poseStack, barColor, brightness);
            }
        }

        // --- Restore ---
        if (inWorld) {
            RenderSystem.setShaderFog(fogCache);
        }

        poseStack.popPose();
    }

    private static void drawDragHandleBar(VROverlay overlay,
                                          PoseStack poseStack,
                                          AtumColor barColor,
                                          float brightness) {

        float aspect = overlay.getAspectRatio();
        float halfWidth  = VROverlayPose.QUAD_SCALE * 0.5f;
        float halfHeight = halfWidth * aspect;

        int width = overlay.getWidth();
        int height = overlay.getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        int edgeX = overlay.getCursorBoundsX();
        int edgeY = overlay.getCursorBoundsY();
        int edgeWidth = overlay.getCursorBoundsWidth();
        int edgeHeight = overlay.getCursorBoundsHeight();
        // -1 in any bound means "use the full overlay"
        if (edgeX < 0) edgeX = 0;
        if (edgeY < 0) edgeY = 0;
        if (edgeWidth < 0) edgeWidth = width;
        if (edgeHeight < 0) edgeHeight = height;

        float nx0 = -halfWidth + ((float) edgeX / width) * (2f * halfWidth);
        float nx1 = -halfWidth + ((float) (edgeX + edgeWidth) / width) * (2f * halfWidth);
        float regionBottom = halfHeight - ((float) (edgeY + edgeHeight) / height) * (2f * halfHeight);
        float barCenterX = (nx0 + nx1) * 0.5f;
        float barHalfWidth = (nx1 - nx0) * 0.18f;

        float barHalfHeight = halfHeight * 0.025f;
        float barGap        = halfHeight * 0.04f;
        float barCenterY    = regionBottom - barGap - barHalfHeight;

        var pose = poseStack.last().pose();
        BufferBuilder buf;
        buf = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        float r = barColor.getRed()   * brightness;
        float g = barColor.getGreen() * brightness;
        float b = barColor.getBlue()  * brightness;
        float a = barColor.getAlpha();
        float left   = barCenterX - barHalfWidth;
        float right  = barCenterX + barHalfWidth;
        float top    = barCenterY + barHalfHeight;
        float bottom = barCenterY - barHalfHeight;
        buf.addVertex(pose, left,  bottom, 0f).setColor(r, g, b, a);
        buf.addVertex(pose, right, bottom, 0f).setColor(r, g, b, a);
        buf.addVertex(pose, right, top,    0f).setColor(r, g, b, a);
        buf.addVertex(pose, left,  top,    0f).setColor(r, g, b, a);

        VisorPipelines.POSITION_COLOR_NO_DEPTH_TYPE.draw(buf.buildOrThrow());
    }


    private static void drawResizeHandle(VROverlay overlay,
                                         PoseStack poseStack,
                                         AtumColor color,
                                         float brightness) {

        float aspect = overlay.getAspectRatio();
        float halfWidth  = VROverlayPose.QUAD_SCALE * 0.5f;
        float halfHeight = halfWidth * aspect;

        int width = overlay.getWidth();
        int height = overlay.getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        int edgeX = overlay.getCursorBoundsX();
        int edgeY = overlay.getCursorBoundsY();
        int edgeWidth = overlay.getCursorBoundsWidth();
        int edgeHeight = overlay.getCursorBoundsHeight();
        // -1 in any bound means "use the full overlay"
        if (edgeX < 0) edgeX = 0;
        if (edgeY < 0) edgeY = 0;
        if (edgeWidth < 0) edgeWidth = width;
        if (edgeHeight < 0) edgeHeight = height;

        float nx0 = -halfWidth + ((float) edgeX / width) * (2f * halfWidth);
        float nx1 = -halfWidth + ((float) (edgeX + edgeWidth) / width) * (2f * halfWidth);
        float regionBottom = halfHeight - ((float) (edgeY + edgeHeight) / height) * (2f * halfHeight);
        float barCenterX   = (nx0 + nx1) * 0.5f;
        float barHalfWidth = (nx1 - nx0) * 0.18f;

        float barHalfHeight = halfHeight * 0.025f;
        float barGap        = halfHeight * 0.04f;
        float barCenterY    = regionBottom - barGap - barHalfHeight;

        float gap  = barHalfWidth * 0.20f;
        float side = barHalfHeight * 1.3f;
        float left   = barCenterX + barHalfWidth + gap;
        float right  = left + side * 2f;
        float bottom = barCenterY - side;
        float top    = barCenterY + side;

        float r = color.getRed()   * brightness;
        float g = color.getGreen() * brightness;
        float b = color.getBlue()  * brightness;
        float a = color.getAlpha();

        var pose = poseStack.last().pose();
        BufferBuilder buf;
        buf = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        buf.addVertex(pose, left,  bottom, 0f).setColor(r, g, b, a);
        buf.addVertex(pose, right, bottom, 0f).setColor(r, g, b, a);
        buf.addVertex(pose, right, top,    0f).setColor(r, g, b, a);
        buf.addVertex(pose, left,  top,    0f).setColor(r, g, b, a);

        VisorPipelines.POSITION_COLOR_NO_DEPTH_TYPE.draw(buf.buildOrThrow());
    }

    private static void drawResizeOutline(VROverlay overlay,
                                          PoseStack poseStack,
                                          AtumColor color,
                                          float brightness) {

        float aspect = overlay.getAspectRatio();
        float halfWidth  = VROverlayPose.QUAD_SCALE * 0.5f;
        float halfHeight = halfWidth * aspect;
        float thickness  = halfWidth * 0.012f;

        float r = color.getRed()   * brightness;
        float g = color.getGreen() * brightness;
        float b = color.getBlue()  * brightness;
        float a = color.getAlpha();

        var pose = poseStack.last().pose();
        BufferBuilder buf;
        buf = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        // top edge
        emitRect(buf, pose, -halfWidth, halfHeight - thickness, halfWidth, halfHeight, r, g, b, a);
        // bottom edge
        emitRect(buf, pose, -halfWidth, -halfHeight, halfWidth, -halfHeight + thickness, r, g, b, a);
        // left edge
        emitRect(buf, pose, -halfWidth, -halfHeight + thickness, -halfWidth + thickness, halfHeight - thickness, r, g, b, a);
        // right edge
        emitRect(buf, pose, halfWidth - thickness, -halfHeight + thickness, halfWidth, halfHeight - thickness, r, g, b, a);

        VisorPipelines.POSITION_COLOR_NO_DEPTH_TYPE.draw(buf.buildOrThrow());
    }

    private static void emitRect(BufferBuilder buf, Matrix4f pose,
                                 float left, float bottom, float right, float top,
                                 float r, float g, float b, float a) {
        buf.addVertex(pose, left,  bottom, 0f).setColor(r, g, b, a);
        buf.addVertex(pose, right, bottom, 0f).setColor(r, g, b, a);
        buf.addVertex(pose, right, top,    0f).setColor(r, g, b, a);
        buf.addVertex(pose, left,  top,    0f).setColor(r, g, b, a);
    }

}
