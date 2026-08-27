package org.vmstudio.visor.core.client.render.shaders;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import me.phoenixra.atumvr.api.misc.color.AtumColor;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.MappableRingBuffer;
import org.jetbrains.annotations.NotNull;
import org.vmstudio.visor.api.compatibility.mcversion.McVersionUtils;

public class VRShaderTeleportPoint implements VRShader {

    /** PORT-26.2: Visor's own uniform block needs a bind group layout of its own. */
    private static final BindGroupLayout LAYOUT = BindGroupLayout.builder()
            .withUniform("VisorTeleportPoint", UniformType.UNIFORM_BUFFER)
            .build();

    public static final RenderPipeline PIPELINE = RenderPipeline.builder()
            .withLocation(McVersionUtils.newResourceLoc("visor", "pipeline/vr_teleport_point"))
            .withVertexShader(McVersionUtils.newResourceLoc("visor", "core/vr_teleport_point"))
            .withFragmentShader(McVersionUtils.newResourceLoc("visor", "core/vr_teleport_point"))
            // PORT-26.2: DynamicTransforms + Projection together are BindGroupLayouts
            // .MATRICES_PROJECTION; Visor's own block needs a layout of its own.
            .withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
            .withBindGroupLayout(LAYOUT)
            // PORT-1.21.11: POSITION_TEX now, so the shader reads real UVs instead of
            // reconstructing them from gl_VertexID.
            .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
            // PORT-26.2: reversed depth buffer - tested against the world with GEQUAL now.
            .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
            .withCull(false)
            .build();

    /** Must match the VisorTeleportPoint block in the fragment shader. */
    private static final int UBO_SIZE = new Std140SizeCalculator()
            .putVec4()
            .putFloat()
            .align(16)
            .get();

    private MappableRingBuffer ubo;


    @Override
    public @NotNull RenderPipeline getPipeline() {
        return PIPELINE;
    }

    @Override
    public void init() {
        ubo = new MappableRingBuffer(() -> "Visor TeleportPoint UBO",
                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, UBO_SIZE);
    }

    @Override
    public void close() {
        if (ubo != null) {
            ubo.close();
            ubo = null;
        }
    }

    @Override
    public void endFrame() {
        if (ubo != null) {
            ubo.rotate();
        }
    }


    /**
     * Uploads this shader's own uniform block.
     * <p>
     * The model-view and projection matrices are no longer set here: they travel in the
     * vanilla {@code DynamicTransforms} / {@code Projection} blocks, which the caller binds
     * on the render pass.
     */
    public void writeUniforms(float time, @NotNull AtumColor color) {
        try (GpuBufferSlice.MappedView view = ubo.currentBuffer().map(false, true)) {
            Std140Builder.intoBuffer(view.data())
                    .putVec4(color.getRed(), color.getGreen(), color.getBlue(), 1.0f)
                    .putFloat(time);
        }
    }

    /** The buffer written by {@link #writeUniforms}, for binding on the pass. */
    public GpuBuffer buffer() {
        return ubo.currentBuffer();
    }
}
