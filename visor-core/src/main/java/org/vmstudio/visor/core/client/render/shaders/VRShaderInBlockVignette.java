package org.vmstudio.visor.core.client.render.shaders;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.MappableRingBuffer;
import org.jetbrains.annotations.NotNull;
import org.vmstudio.visor.api.compatibility.mcversion.McVersionUtils;
import org.vmstudio.visor.core.client.render.helpers.RenderShaderHelper;

public class VRShaderInBlockVignette implements VRShader {

    public static final RenderPipeline PIPELINE = RenderPipeline.builder()
            .withLocation(McVersionUtils.newResourceLoc("visor", "pipeline/vr_in_block_vignette"))
            .withVertexShader(McVersionUtils.newResourceLoc("visor", "core/vr_in_block_vignette"))
            .withFragmentShader(McVersionUtils.newResourceLoc("visor", "core/vr_in_block_vignette"))
            .withUniform("VisorInBlockVignette", UniformType.UNIFORM_BUFFER)
            .withVertexFormat(DefaultVertexFormat.POSITION_TEX, VertexFormat.Mode.QUADS)
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
            .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
            .withCull(false)
            .build();

    /** Must match the VisorInBlockVignette block in the fragment shader. */
    private static final int UBO_SIZE = new Std140SizeCalculator()
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
        ubo = new MappableRingBuffer(() -> "Visor InBlockVignette UBO",
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
     * Darkens the edges of the view when the head is inside a block.
     * <p>
     * The old call site built an oversized +/-1.5 NDC quad with UVs -0.25..1.25; the visible
     * -1..1 region of that maps to exactly UV 0..1, so the overhang was always clipped away
     * and the shared full-screen quad draws the same pixels.
     */
    public void draw(float proximity, @NotNull GpuTextureView target) {
        try (GpuBuffer.MappedView view = RenderSystem.getDevice().createCommandEncoder()
                .mapBuffer(ubo.currentBuffer(), false, true)) {
            Std140Builder.intoBuffer(view.data()).putFloat(proximity);
        }

        RenderShaderHelper.renderFullscreenQuad(
                () -> "visor in-block vignette",
                PIPELINE,
                pass -> pass.setUniform("VisorInBlockVignette", ubo.currentBuffer()),
                target
        );
    }
}
