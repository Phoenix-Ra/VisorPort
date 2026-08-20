package org.vmstudio.visor.core.client.render.helpers;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.ScissorState;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.*;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.system.MemoryStack;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.joml.Vector4f;
import org.joml.Vector4fc;

import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The draw primitives Visor's own shaders are built on.
 * <p>
 * 1.21.9 removed {@code BufferUploader} and the global shader/uniform state that used to sit
 * around a draw. Geometry now goes through a {@link RenderPass}: bind a pipeline, bind buffers,
 * draw. Blend/depth/cull are properties of the pipeline rather than GL calls made beforehand,
 * so the state sandwich this class used to wrap every draw in is gone entirely - leaving those
 * calls in place would not fail, they would just be overwritten when the pass binds.
 */
public class RenderShaderHelper {
    private RenderShaderHelper() {
        throw new UnsupportedOperationException("This is an utility class and cannot be instantiated");
    }


    // ---------- shared NDC quad geometry ----------
    // Allocated once rather than per draw: these run twice a frame at headset refresh rate,
    // where a per-draw GPU allocation is a stall.

    private static GpuBuffer quadPos;
    private static GpuBuffer quadPosTex;


    public static void setup() {
        close();
        quadPos = buildQuad(DefaultVertexFormat.POSITION, "visor fullscreen quad (pos)");
        quadPosTex = buildQuad(DefaultVertexFormat.POSITION_TEX, "visor fullscreen quad (pos_tex)");
    }

    public static void close() {
        if (quadPos != null) {
            quadPos.close();
            quadPos = null;
        }
        if (quadPosTex != null) {
            quadPosTex.close();
            quadPosTex = null;
        }
        if (identityProjection != null) {
            identityProjection.close();
            identityProjection = null;
        }
    }

    private static GpuBuffer buildQuad(VertexFormat format, String label) {
        try (ByteBufferBuilder bytes = ByteBufferBuilder.exactlySized(4 * format.getVertexSize())) {
            BufferBuilder builder = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, format);
            boolean textured = format == DefaultVertexFormat.POSITION_TEX;
            putQuadVertex(builder, textured, -1f, -1f, 0f, 0f);
            putQuadVertex(builder, textured, 1f, -1f, 1f, 0f);
            putQuadVertex(builder, textured, 1f, 1f, 1f, 1f);
            putQuadVertex(builder, textured, -1f, 1f, 0f, 1f);
            try (MeshData mesh = builder.buildOrThrow()) {
                return RenderSystem.getDevice()
                        .createBuffer(() -> label, GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer());
            }
        }
    }

    private static void putQuadVertex(BufferBuilder builder, boolean textured,
                                      float x, float y, float u, float v) {
        VertexConsumer vertex = builder.addVertex(x, y, 0f);
        if (textured) {
            vertex.setUv(u, v);
        }
    }

    private static GpuBuffer quadFor(VertexFormat format) {
        if (format == DefaultVertexFormat.POSITION_TEX) return quadPosTex;
        if (format == DefaultVertexFormat.POSITION) return quadPos;
        throw new IllegalArgumentException("Unsupported fullscreen vertex format: " + format);
    }


    // ---------- draw primitives ----------

    /**
     * Draws the shared NDC quad over {@code target} with {@code pipeline}.
     * <p>
     * {@code bindings} runs after the pipeline is bound and is where textures and uniforms go.
     * The pipeline must be declared NO_DEPTH_TEST with {@code withDepthWrite(false)} and no
     * depth bias, otherwise it reports that it wants a depth texture and the command encoder
     * rejects the pass for not supplying one.
     * <p>
     * PORT-1.21.11: {@code bindings} may only call {@code pass} methods. The pass is open by the
     * time it runs, and an open pass makes the command encoder reject <em>every</em> other
     * command - buffer maps and writes, texture writes and copies, clears, fences - with
     * "Close the existing render pass before performing additional commands". That bans the
     * lazy-init idiom these lambdas invite: anything a bind needs (a uniform slice from
     * {@code RenderSystem.getDynamicUniforms()}, a buffer allocated on first use) has to be
     * produced by the caller beforehand and captured. Binding an already-written slice is fine;
     * writing one is not.
     * <p>
     * The old fullscreen quad was a 4-vertex TRIANGLE_STRIP; it is a QUADS primitive now
     * because the shared sequential index buffer is the one available for free. gl_VertexID
     * still runs 0..3, so shaders deriving UVs from it are unaffected.
     */
    public static void renderFullscreenQuad(@NotNull Supplier<String> label,
                                            @NotNull RenderPipeline pipeline,
                                            @NotNull Consumer<RenderPass> bindings,
                                            @NotNull GpuTextureView target) {
        GpuBuffer vertexBuffer = quadFor(pipeline.getVertexFormat());
        RenderSystem.AutoStorageIndexBuffer indices =
                RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
        GpuBuffer indexBuffer = indices.getBuffer(6);

        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
                .createRenderPass(label, target, OptionalInt.empty())) {
            pass.setPipeline(pipeline);
            pass.setVertexBuffer(0, vertexBuffer);
            bindings.accept(pass);
            pass.setIndexBuffer(indexBuffer, indices.type());
            pass.drawIndexed(0, 0, 6, 1);
        }
    }


    /**
     * Draws a screen-space quad over {@code target}: {@code x0..y1} are NDC, {@code u0..v1} are
     * source UVs.
     * <p>
     * The cached full-screen quad cannot serve here because both rectangles vary - the mirror
     * places each eye somewhere different and crops the source - so the geometry goes through the
     * shared immediate vertex buffer instead of getting its own VBO.
     * <p>
     * {@code bindings} carries the same pass-only contract as {@link #renderFullscreenQuad}.
     */
    public static void renderScreenQuad(@NotNull Supplier<String> label,
                                        @NotNull RenderPipeline pipeline,
                                        @NotNull Consumer<RenderPass> bindings,
                                        @NotNull GpuTextureView target,
                                        float x0, float y0, float x1, float y1,
                                        float u0, float v0, float u1, float v1) {
        VertexFormat format = pipeline.getVertexFormat();
        try (ByteBufferBuilder bytes = ByteBufferBuilder.exactlySized(4 * format.getVertexSize())) {
            BufferBuilder builder = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, format);
            builder.addVertex(x0, y0, 0f).setUv(u0, v0);
            builder.addVertex(x1, y0, 0f).setUv(u1, v0);
            builder.addVertex(x1, y1, 0f).setUv(u1, v1);
            builder.addVertex(x0, y1, 0f).setUv(u0, v1);

            try (MeshData mesh = builder.buildOrThrow()) {
                GpuBuffer vertexBuffer = format.uploadImmediateVertexBuffer(mesh.vertexBuffer());
                RenderSystem.AutoStorageIndexBuffer indices =
                        RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
                GpuBuffer indexBuffer = indices.getBuffer(6);

                try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
                        .createRenderPass(label, target, OptionalInt.empty())) {
                    pass.setPipeline(pipeline);
                    pass.setVertexBuffer(0, vertexBuffer);
                    bindings.accept(pass);
                    pass.setIndexBuffer(indexBuffer, indices.type());
                    pass.drawIndexed(0, 0, 6, 1);
                }
            }
        }
    }


    /**
     * Draws a world-space quad (the teleport landing pad), depth-tested against {@code depth}.
     * The geometry changes every frame, so it goes through the shared immediate vertex buffer
     * rather than a cached VBO.
     * <p>
     * {@code bindings} carries the same pass-only contract as {@link #renderFullscreenQuad}.
     */
    public static void renderWorldQuad(@NotNull Supplier<String> label,
                                       @NotNull RenderPipeline pipeline,
                                       @NotNull Consumer<RenderPass> bindings,
                                       @NotNull Matrix4f pose,
                                       float x0, float y, float z0, float x1, float z1,
                                       @NotNull GpuTextureView color,
                                       @NotNull GpuTextureView depth) {
        VertexFormat format = pipeline.getVertexFormat();
        try (ByteBufferBuilder bytes = ByteBufferBuilder.exactlySized(4 * format.getVertexSize())) {
            BufferBuilder builder = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, format);
            boolean textured = format == DefaultVertexFormat.POSITION_TEX;
            putWorldVertex(builder, textured, pose, x0, y, z0, 0f, 0f);
            putWorldVertex(builder, textured, pose, x1, y, z0, 1f, 0f);
            putWorldVertex(builder, textured, pose, x1, y, z1, 1f, 1f);
            putWorldVertex(builder, textured, pose, x0, y, z1, 0f, 1f);

            try (MeshData mesh = builder.buildOrThrow()) {
                GpuBuffer vertexBuffer = format.uploadImmediateVertexBuffer(mesh.vertexBuffer());
                RenderSystem.AutoStorageIndexBuffer indices =
                        RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
                GpuBuffer indexBuffer = indices.getBuffer(6);

                try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
                        .createRenderPass(label, color, OptionalInt.empty(),
                                depth, OptionalDouble.empty())) {
                    pass.setPipeline(pipeline);
                    pass.setVertexBuffer(0, vertexBuffer);
                    bindings.accept(pass);
                    pass.setIndexBuffer(indexBuffer, indices.type());
                    pass.drawIndexed(0, 0, 6, 1);
                }
            }
        }
    }

    private static void putWorldVertex(BufferBuilder builder, boolean textured, Matrix4f pose,
                                       float x, float y, float z, float u, float v) {
        VertexConsumer vertex = builder.addVertex(pose, x, y, z);
        if (textured) {
            vertex.setUv(u, v);
        }
    }


    /**
     * Draws an arbitrary built mesh into the current main target, colour and depth.
     * <p>
     * This is what {@code RenderType.draw} does, minus the parts a {@code RenderType} needs and
     * Visor does not (layering transforms, outlines, texture transforms) and plus the two parts a
     * {@code RenderType} cannot do: binding a texture that came from a {@link RenderTarget} rather
     * than from a registered {@code Identifier}, and passing a real {@code ColorModulator} instead
     * of the hardcoded white. Both are exactly why the overlay quads cannot use the type route.
     * <p>
     * Honours {@code outputColorTextureOverride}/{@code outputDepthTextureOverride} and the
     * render-type scissor, so it composes with vanilla the same way a {@code RenderType} draw does.
     * The mesh is closed on the way out.
     */
    public static void drawMesh(@NotNull Supplier<String> label,
                                @NotNull RenderPipeline pipeline,
                                @NotNull MeshData mesh,
                                @NotNull Vector4fc colorModulator,
                                @NotNull Consumer<RenderPass> bindings) {
        try (mesh) {
            GpuBufferSlice transforms = writeTransform(colorModulator);

            VertexFormat format = pipeline.getVertexFormat();
            GpuBuffer vertexBuffer = format.uploadImmediateVertexBuffer(mesh.vertexBuffer());

            MeshData.DrawState draw = mesh.drawState();
            GpuBuffer indexBuffer;
            VertexFormat.IndexType indexType;
            if (mesh.indexBuffer() == null) {
                // No sorted index data: the shared sequential buffer already indexes this mode.
                RenderSystem.AutoStorageIndexBuffer sequential =
                        RenderSystem.getSequentialBuffer(draw.mode());
                indexBuffer = sequential.getBuffer(draw.indexCount());
                indexType = sequential.type();
            } else {
                indexBuffer = format.uploadImmediateIndexBuffer(mesh.indexBuffer());
                indexType = draw.indexType();
            }

            RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
            GpuTextureView color = RenderSystem.outputColorTextureOverride != null
                    ? RenderSystem.outputColorTextureOverride
                    : main.getColorTextureView();
            GpuTextureView depth = RenderSystem.outputDepthTextureOverride != null
                    ? RenderSystem.outputDepthTextureOverride
                    : main.getDepthTextureView();

            try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
                    .createRenderPass(label, color, OptionalInt.empty(),
                            depth, OptionalDouble.empty())) {
                pass.setPipeline(pipeline);
                ScissorState scissor = RenderSystem.getScissorStateForRenderTypeDraws();
                if (scissor.enabled()) {
                    pass.enableScissor(scissor.x(), scissor.y(), scissor.width(), scissor.height());
                }
                bindTransformUniforms(pass, transforms);
                pass.setVertexBuffer(0, vertexBuffer);
                bindings.accept(pass);
                pass.setIndexBuffer(indexBuffer, indexType);
                pass.drawIndexed(0, 0, draw.indexCount(), 1);
            }
        }
    }

    /**
     * Writes the per-draw {@code DynamicTransforms} block - the model-view matrix and the colour
     * modulator - and returns the slice for {@link #bindTransformUniforms} to bind.
     * <p>
     * PORT-1.21.11: this has to run <em>before</em> the pass is opened, never from a
     * {@code bindings} lambda. See the contract on {@link #renderFullscreenQuad}.
     */
    public static GpuBufferSlice writeTransform(@NotNull Vector4fc colorModulator) {
        return RenderSystem.getDynamicUniforms().writeTransform(
                RenderSystem.getModelViewMatrix(), colorModulator,
                ZERO_OFFSET, IDENTITY);
    }

    /**
     * Binds the uniform blocks every Visor pipeline expects: the four
     * {@code RenderSystem.bindDefaultUniforms} covers (Projection, Fog, Globals, Lighting) plus
     * {@code DynamicTransforms}, which it does not - that one is produced per draw by
     * {@link #writeTransform}, outside the pass.
     */
    public static void bindTransformUniforms(@NotNull RenderPass pass, @NotNull GpuBufferSlice transforms) {
        RenderSystem.bindDefaultUniforms(pass);
        pass.setUniform("DynamicTransforms", transforms);
    }

    /** {@code ModelOffset} - Visor never uses it, but the std140 block still has the slot. */
    private static final Vector3fc ZERO_OFFSET = new Vector3f();

    /**
     * An identity matrix: {@code TextureMat} on every Visor draw (none transforms UVs), and
     * both {@code ModelViewMat} and {@code ProjMat} for the draws whose geometry is already
     * in NDC - see {@link #writeIdentityTransform} and {@link #identityProjection}.
     */
    private static final Matrix4fc IDENTITY = new Matrix4f();

    /** The {@code ColorModulator} a plain untinted draw wants. */
    public static final Vector4fc NO_TINT = new Vector4f(1f, 1f, 1f, 1f);


    /**
     * {@code DynamicTransforms} for a quad supplied in NDC: identity model-view, untinted.
     * <p>
     * Both blocks matter even for a draw that looks like a plain copy. {@code core/position_tex}
     * reads {@code ModelViewMat} and {@code ColorModulator} out of {@code DynamicTransforms} and
     * {@code ProjMat} out of {@code Projection}; a pipeline that leaves either undeclared gets
     * "Found unknown and unsupported uniform" from {@code GlProgram} and then no binding at all,
     * so the shader reads whatever the previous draw left at that binding point.
     * <p>
     * Like {@link #writeTransform}, this must be called before the pass is opened.
     */
    public static GpuBufferSlice writeIdentityTransform() {
        return RenderSystem.getDynamicUniforms()
                .writeTransform(IDENTITY, NO_TINT, ZERO_OFFSET, IDENTITY);
    }

    /** The matching {@code Projection} block. 64 bytes of constants, so it is built once. */
    public static GpuBufferSlice identityProjection() {
        if (identityProjection == null) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                identityProjection = RenderSystem.getDevice().createBuffer(
                        () -> "visor identity projection",
                        GpuBuffer.USAGE_UNIFORM,
                        Std140Builder.onStack(stack, RenderSystem.PROJECTION_MATRIX_UBO_SIZE)
                                .putMat4f(IDENTITY)
                                .get());
            }
        }
        return identityProjection.slice();
    }

    private static GpuBuffer identityProjection;


    // ---------- targets ----------

    /**
     * Clears {@code target}'s colour and depth attachments.
     * <p>
     * Replaces {@code GlStateManager._clear}, which still compiles but clears whatever framebuffer
     * happens to be bound - and nothing binds one deterministically any more, since the device
     * binds its own per pass.
     */
    public static void clearColorAndDepth(@NotNull RenderTarget target, int argb, double depth) {
        RenderSystem.getDevice().createCommandEncoder()
                .clearColorAndDepthTextures(target.getColorTexture(), argb,
                        target.getDepthTexture(), depth);
    }


    // ---------- binding helpers ----------

    /** Binds the colour attachment of {@code source} as a sampler on the pass. */
    public static void bindColor(@NotNull RenderPass pass, @NotNull String sampler,
                                 @NotNull RenderTarget source) {
        pass.bindTexture(sampler, source.getColorTextureView(),
                RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
    }

    /** Binds the depth attachment of {@code source} as a sampler on the pass. */
    public static void bindDepth(@NotNull RenderPass pass, @NotNull String sampler,
                                 @NotNull RenderTarget source) {
        pass.bindTexture(sampler, source.getDepthTextureView(),
                RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
    }

}
