package org.vmstudio.visor.core.client.render.shaders;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import me.phoenixra.atumvr.api.enums.EyeType;
import net.minecraft.client.renderer.MappableRingBuffer;
import com.mojang.blaze3d.shaders.UniformType;
import org.jetbrains.annotations.NotNull;
import org.vmstudio.visor.api.client.player.pose.PlayerPoseType;
import org.vmstudio.visor.core.client.ClientContext;
import org.vmstudio.visor.extensions.client.render.GameRendererExtension;
import org.vmstudio.visor.core.client.render.helpers.MirrorHelper;
import org.vmstudio.visor.core.client.render.helpers.ProjectionHelper;
import org.vmstudio.visor.core.client.render.helpers.RenderShaderHelper;
import org.vmstudio.visor.api.client.settings.VRClientSettings;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.vmstudio.visor.api.compatibility.mcversion.McVersionUtils;

import static org.vmstudio.visor.core.client.VisorClientImpl.MC;

public class VRShaderMixedReality implements VRShader {

    /** PORT-26.2: Visor's own uniform block and its two named samplers, as bind group layouts. */
    private static final BindGroupLayout UNIFORM_LAYOUT = BindGroupLayout.builder()
            .withUniform("VisorMixedReality", UniformType.UNIFORM_BUFFER)
            .build();

    private static final BindGroupLayout SAMPLER_LAYOUT = BindGroupLayout.builder()
            .withSampler("SamplerColor")
            .withSampler("SamplerDepth")
            .build();

    public static final RenderPipeline PIPELINE = RenderPipeline.builder()
            .withLocation(McVersionUtils.newResourceLoc("visor", "pipeline/vr_mixed_reality"))
            .withVertexShader(McVersionUtils.newResourceLoc("visor", "core/vr_mixed_reality"))
            .withFragmentShader(McVersionUtils.newResourceLoc("visor", "core/vr_mixed_reality"))
            // PORT-26.2: uniform group first, then the samplers - vanilla's order everywhere.
            .withBindGroupLayout(UNIFORM_LAYOUT)
            .withBindGroupLayout(SAMPLER_LAYOUT)
            .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            // PORT-1.21.11: this used to inherit whatever blend state was already set, which is
            // no longer expressible - blend is baked into the pipeline. It writes an opaque
            // full-screen composite, so no blend is the intended behaviour; verify on screen.
            .withColorTargetState(ColorTargetState.DEFAULT)
            .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
            .withCull(false)
            .build();

    /** Must match the VisorMixedReality block in the fragment shader, member for member. */
    private static final int UBO_SIZE = new Std140SizeCalculator()
            .putMat4f()
            .putVec4()
            .putVec4()
            .putVec4()
            .putInt()
            .putInt()
            .align(16)
            .get();

    private MappableRingBuffer ubo;


    @Override
    public @NotNull RenderPipeline getPipeline() {
        return PIPELINE;
    }

    @Override
    public void init() {
        ubo = new MappableRingBuffer(() -> "Visor MixedReality UBO",
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


    public void drawMirror() {
        // --- Prepare ---
        boolean asGrid2x2 = VRClientSettings.isMixedRealityAsGrid2x2();
        boolean alphaMask = asGrid2x2
                && VRClientSettings.isMixedRealityAlphaMask();
        boolean withFirstPerson = VRClientSettings.isMixedRealityWithFirstPerson();


        var relativePose = ClientContext.localPlayer.getPoseData(PlayerPoseType.ROOM);
        var cameraElement = relativePose.getThirdPersonCamera();
        Vector3f cameraPos = relativePose.getHeadPivot()
                .sub(cameraElement.getPosition(), new Vector3f());


        var cameraRotation = cameraElement.getRotation().transpose(new Matrix4f());
        var cameraDir = cameraElement.getDirection();

        var proj = ((GameRendererExtension) MC.gameRenderer).visor$getThirdPersonProjection();
        Matrix4f invProjView = new Matrix4f(proj)
                .mul(cameraRotation)
                .invert();
        // PORT-26.2: the shader unprojects the raw [0,1] depth value as clip-space z. That is
        // exact on a zero-to-one device; on a -1..1 device the remap (z*2-1) is baked in here
        // so the shader stays convention-free.
        if (!ProjectionHelper.isZZeroToOne()) {
            invProjView.mul(new Matrix4f().translation(0f, 0f, -1f).scale(1f, 1f, 2f));
        }

        float keyR = 0f;
        float keyG = 0f;
        float keyB = 0f;
        if (!alphaMask) {
            var color = VRClientSettings.getMixedRealityKeyColor();
            keyR = color.getRed();
            keyG = color.getGreen();
            keyB = color.getBlue();
        }


        // --- Update Uniforms ---
        // Put order here is load-bearing: it has to match the std140 block declaration exactly.
        try (GpuBufferSlice.MappedView view = ubo.currentBuffer().map(false, true)) {
            Std140Builder.intoBuffer(view.data())
                    .putMat4f(invProjView)
                    .putVec4(cameraPos.x, cameraPos.y, cameraPos.z, 0f)
                    .putVec4(-cameraDir.x(), 0.0F, -cameraDir.z(), 0f)
                    .putVec4(keyR, keyG, keyB, 0f)
                    .putInt(asGrid2x2 ? 1 : 0)
                    .putInt(alphaMask ? 1 : 0);
        }


        // --- Render ---
        // The pass viewport comes from the target texture, so the explicit _viewport() call the
        // old path needed is gone; during VR_MIRROR the main target is already the mirror target.
        RenderTarget thirdPerson = ClientContext.renderer.thirdPersonTarget.getTarget();
        RenderShaderHelper.renderFullscreenQuad(
                () -> "visor mixed reality",
                PIPELINE,
                pass -> {
                    pass.setUniform("VisorMixedReality", ubo.currentBuffer());
                    RenderShaderHelper.bindColor(pass, "SamplerColor", thirdPerson);
                    RenderShaderHelper.bindDepth(pass, "SamplerDepth", thirdPerson);
                },
                MC.gameRenderer.mainRenderTarget.getColorTextureView()
        );

        if (asGrid2x2) {
            RenderTarget source;
            if (withFirstPerson) {
                source = ClientContext.renderer.firstPersonTarget.getTarget();
            } else {
                if (VRClientSettings.getMirrorEye() == EyeType.LEFT) {
                    source = ClientContext.renderer.getTextureLeftEye().getRenderTarget();
                } else {
                    source = ClientContext.renderer.getTextureRightEye().getRenderTarget();
                }
            }
            MirrorHelper.blit(source,
                    MC.gameRenderer.mainRenderTarget.width / 2,
                    0,
                    MC.gameRenderer.mainRenderTarget.width,
                    MC.gameRenderer.mainRenderTarget.height / 2
            );
        }
    }
}
