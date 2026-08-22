package org.vmstudio.visor.core.client.render.helpers;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import me.phoenixra.atumvr.api.enums.EyeType;
import org.vmstudio.visor.api.client.render.VRRenderPass;
import org.vmstudio.visor.compatibility.ShadersHelper;
import org.vmstudio.visor.compatibility.immportals.ImmPortalsCompatHelper;
import org.vmstudio.visor.core.client.ClientContext;
import org.vmstudio.visor.core.client.render.VRRenderState;
import org.vmstudio.visor.core.client.render.VRRendererBase;
import org.vmstudio.visor.core.client.render.VisorPipelines;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL11C;
import org.vmstudio.visor.core.client.render.VRShaders;
import org.vmstudio.visor.core.client.render.shaders.VRShaderInBlockVignette;
import com.mojang.blaze3d.ProjectionType;

import static org.vmstudio.visor.core.client.VisorClientImpl.MC;

public class VREffectsHelper {
    private VREffectsHelper() {
        throw new UnsupportedOperationException("This is an utility class and cannot be instantiated");
    }

    public record NearestOpaqueBlock(float distance, BlockState state, BlockPos position) {}


    /**
     * Whether the per-eye hidden-area stencil mask is drawn.
     * <p>
     * <b>Off, and this is a real VR framerate regression, not a cosmetic one.</b> The mask culls
     * the corners of each eye render that the headset optics never show, which on a wide-FOV HMD
     * is a measurable fill-rate saving.
     * <p>
     * 1.21.9 removed stencil from the rendering API outright: {@code RenderPipeline.Builder} has no
     * stencil methods, {@code RenderPass} has no stencil methods, {@code RenderSystem.stencilOp} /
     * {@code stencilMask} / {@code clearStencil} are gone, and no class with "stencil" in its name
     * survives in the jar. Raw {@code glEnable(GL_STENCIL_TEST)} from outside a pass does not
     * reliably survive either, because the device binds its own framebuffer per pass and keeps a
     * cached GL state that has no stencil in it. Vivecraft reached the same conclusion and hard
     * disabled theirs.
     * <p>
     * Everything below is left intact and wired up so re-enabling is this one constant plus a
     * mixin on the command encoder's pipeline-state application. Visor's {@code RenderTargetMixin}
     * still allocates a {@code GL_DEPTH24_STENCIL8} attachment when asked, so the bits exist.
     */
    public static final boolean STENCIL_SUPPORTED = false;


    public static void renderInBlockEffect() {
        // orthographic matrix
        Matrix4f mat = new Matrix4f();
        mat.m00(1.0F);
        mat.m11(1.0F);
        mat.m22(-1.0F);
        mat.m33(1.0F);
        mat.m32(-1.0F);

        // The black came from setShaderColor, which no longer exists on this route, so the
        // geometry carries it: POSITION_COLOR with black vertices rather than bare POSITION.
        BufferBuilder bufferbuilder = Tesselator.getInstance()
                .begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        bufferbuilder.addVertex(mat, -1.5F, -1.5F, 0.0F).setColor(0, 0, 0, 255);
        bufferbuilder.addVertex(mat, 1.5F, -1.5F, 0.0F).setColor(0, 0, 0, 255);
        bufferbuilder.addVertex(mat, 1.5F, 1.5F, 0.0F).setColor(0, 0, 0, 255);
        bufferbuilder.addVertex(mat, -1.5F, 1.5F, 0.0F).setColor(0, 0, 0, 255);

        VisorPipelines.POSITION_COLOR_NO_DEPTH_TYPE.draw(bufferbuilder.buildOrThrow());
    }


    public static void renderInBlockVignette(float proximity) {
        if (proximity <= 0.0f) return;

        VRShaderInBlockVignette wrap = VRShaders.getInBlockVignette();
        if (wrap == null) return;

        // PORT-1.21.11: blend/depth/cull are pipeline properties now, so the GL sandwich that
        // used to wrap this draw is gone - leaving it would just be overwritten by the pass.
        wrap.draw(proximity, MC.mainRenderTarget.getColorTextureView());
    }


    private static boolean stencilEnabledByVisor;

    /** Long-lived, because the projection buffer owns GPU memory and must not be per-call. */
    private static ProjectionMatrixBuffer stencilProjection;


    public static void drawEyeStencil() {
        if (!STENCIL_SUPPORTED) {
            return;
        }
        if (ShadersHelper.isShaderActive()) {
            return;
        }
        stencilEnabledByVisor = GL11C.glIsEnabled(GL11C.GL_STENCIL_TEST);
        VRRenderPass renderPass = VRRenderState.getRenderPass();
        if (renderPass.isEye()
                && !ImmPortalsCompatHelper.isRenderingPortalWorld()
                && !ImmPortalsCompatHelper.dropEyeMask()) {
            doStencil(false);
        }
    }

    public static void disableStencilTest() {
        if (!STENCIL_SUPPORTED) {
            return;
        }
        if (!stencilEnabledByVisor) {
            GL11C.glDisable(GL11C.GL_STENCIL_TEST);
        }
    }

    /** Releases the projection buffer. Safe to call when the stencil never ran. */
    public static void close() {
        if (stencilProjection != null) {
            stencilProjection.close();
            stencilProjection = null;
        }
    }


    public static void doStencil(boolean inverse) {
        if (!STENCIL_SUPPORTED) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        RenderTarget rt = mc.getMainRenderTarget();

        // 1) backup matrices
        RenderSystem.backupProjectionMatrix();
        RenderSystem.getModelViewStack().pushMatrix();

        try {
            enableStencilTest();
            configureStencilWrite(inverse);
            clearStencilAndDepth();

            setupMaskDrawState();
            applyOrthoProjection(rt, inverse);

            // draw hidden-area triangles into the stencil
            VRRenderPass eye = VRRenderState.getRenderPass();
            float[] maskVerts = getStencilMask(eye);
            drawStencilMask(maskVerts);

        } finally {
            // 2) restore matrices
            RenderSystem.getModelViewStack().popMatrix();
            RenderSystem.restoreProjectionMatrix();

            // 3) restore GL state for regular rendering
            restorePostStencilState();
        }
    }

    // PORT-1.21.11: RenderSystem lost every stencil wrapper, so these drop to raw GL. They are
    // only reachable when STENCIL_SUPPORTED is turned back on.
    private static void enableStencilTest() {
        GL11.glEnable(GL11.GL_STENCIL_TEST);
        GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_REPLACE);
        GL11.glStencilMask(0xFF);
    }

    private static void configureStencilWrite(boolean inverse) {
        if (inverse) {
            // clear stencil to 0xFF then write zero inside mask
            GL11.glClearStencil(0xFF);
            GL11.glClearDepth(0);
            GL11.glStencilFunc(GL11.GL_ALWAYS, 0, 0xFF);
            GL11.glColorMask(false, false, false, true);
        } else {
            // clear stencil to 0 then write one inside mask
            GL11.glClearStencil(0);
            GL11.glClearDepth(1);
            GL11.glStencilFunc(GL11.GL_ALWAYS, 0xFF, 0xFF);
            GL11.glColorMask(true, true, true, true);
        }
    }

    private static void clearStencilAndDepth() {
        GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_STENCIL_BUFFER_BIT);
    }

    private static void setupMaskDrawState() {
        GL11.glDepthMask(true);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthFunc(GL11.GL_ALWAYS);
        GL11.glDisable(GL11.GL_CULL_FACE);
    }

    private static void applyOrthoProjection(RenderTarget rt, boolean inverse) {
        if (stencilProjection == null) {
            stencilProjection = new ProjectionMatrixBuffer("visor stencil projection");
        }
        Matrix4f ortho = new Matrix4f()
                .setOrtho(0, rt.width, 0, rt.height, 0, 20f);
        RenderSystem.setProjectionMatrix(stencilProjection.getBuffer(ortho), ProjectionType.ORTHOGRAPHIC);

        if (inverse) {
            RenderSystem.getModelViewStack().translate(0, 0, -20);
        }
    }

    private static float[] getStencilMask(VRRenderPass eye) {
        if (eye != VRRenderPass.EYE_LEFT && eye != VRRenderPass.EYE_RIGHT) {
            return null;
        }
        VRRendererBase renderer = ClientContext.renderer;
        return (eye == VRRenderPass.EYE_LEFT)
                ? renderer.getHiddenAreaVertices(EyeType.LEFT)
                : renderer.getHiddenAreaVertices(EyeType.RIGHT);
    }

    private static void drawStencilMask(float[] verts) {
        if (verts == null || verts.length < 2) return;

        BufferBuilder buf = Tesselator.getInstance()
                .begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION);

        float scale = ClientContext.renderer.renderScale;
        for (int i = 0; i < verts.length; i += 2) {
            buf.addVertex(verts[i] * scale, verts[i + 1] * scale, 0f);
        }

        VisorPipelines.POSITION_TRIANGLES_TYPE.draw(buf.buildOrThrow());
    }

    private static void restorePostStencilState() {
        // stencil: only pass where stencil != 255
        GL11.glStencilFunc(GL11.GL_NOTEQUAL, 255, 0xFF);
        GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
        GL11.glStencilMask(0);
    }
}
