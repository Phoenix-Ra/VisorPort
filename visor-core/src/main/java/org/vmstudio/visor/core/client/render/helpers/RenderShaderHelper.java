package org.vmstudio.visor.core.client.render.helpers;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.*;
import org.jetbrains.annotations.NotNull;
import org.joml.Matrix4f;

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
     * Draws a world-space quad (the teleport landing pad), depth-tested against {@code depth}.
     * The geometry changes every frame, so it goes through the shared immediate vertex buffer
     * rather than a cached VBO.
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
