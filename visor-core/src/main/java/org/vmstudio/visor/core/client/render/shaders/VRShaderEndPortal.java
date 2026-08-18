package org.vmstudio.visor.core.client.render.shaders;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import lombok.Getter;
import net.minecraft.client.renderer.blockentity.AbstractEndPortalRenderer;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.jetbrains.annotations.NotNull;
import org.vmstudio.visor.api.compatibility.mcversion.McVersionUtils;

/**
 * Visor's VR end portal.
 * <p>
 * The spherical {@code proj_3d_to_2d} mapping that makes the portal look correct in stereo is
 * unchanged; only the plumbing moved. The layer count used to be a uniform, but portals and
 * gateways differ only in that number, so it is a shader define now and the two pipelines are
 * built from one snippet.
 */
public class VRShaderEndPortal implements VRShader {

    private static final RenderPipeline.Snippet SNIPPET = RenderPipeline.builder()
            .withVertexShader(McVersionUtils.newResourceLoc("visor", "core/vr_end_portal"))
            .withFragmentShader(McVersionUtils.newResourceLoc("visor", "core/vr_end_portal"))
            // declared by hand rather than reusing RenderPipelines.END_PORTAL_SNIPPET, which is
            // private and would need an extra accesswidener line for no real gain
            .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
            .withUniform("Projection", UniformType.UNIFORM_BUFFER)
            .withUniform("Globals", UniformType.UNIFORM_BUFFER)
            .withSampler("Sampler0")
            .withSampler("Sampler1")
            .withVertexFormat(DefaultVertexFormat.POSITION, VertexFormat.Mode.QUADS)
            .buildSnippet();

    public static final RenderPipeline PORTAL_PIPELINE = RenderPipeline.builder(SNIPPET)
            .withLocation(McVersionUtils.newResourceLoc("visor", "pipeline/vr_end_portal"))
            .withShaderDefine("PORTAL_LAYERS", 15)
            .build();

    public static final RenderPipeline GATEWAY_PIPELINE = RenderPipeline.builder(SNIPPET)
            .withLocation(McVersionUtils.newResourceLoc("visor", "pipeline/vr_end_gateway"))
            .withShaderDefine("PORTAL_LAYERS", 16)
            .build();


    /** 15 layers, as vanilla uses for an end portal block. */
    @Getter
    private RenderType portalRenderType;

    /** 16 layers, as vanilla uses for a gateway. Visor previously shared one type for both. */
    @Getter
    private RenderType gatewayRenderType;


    @Override
    public @NotNull RenderPipeline getPipeline() {
        return PORTAL_PIPELINE;
    }

    @Override
    public void init() {
        portalRenderType = createRenderType("visor_end_portal", PORTAL_PIPELINE);
        gatewayRenderType = createRenderType("visor_end_gateway", GATEWAY_PIPELINE);
    }

    private static RenderType createRenderType(String name, RenderPipeline pipeline) {
        return RenderType.create(name,
                RenderSetup.builder(pipeline)
                        .withTexture("Sampler0", AbstractEndPortalRenderer.END_SKY_LOCATION)
                        .withTexture("Sampler1", AbstractEndPortalRenderer.END_PORTAL_LOCATION)
                        .createRenderSetup());
    }
}
