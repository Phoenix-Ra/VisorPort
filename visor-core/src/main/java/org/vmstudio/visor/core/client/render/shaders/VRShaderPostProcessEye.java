package org.vmstudio.visor.core.client.render.shaders;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import java.util.Optional;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.MappableRingBuffer;
import org.jetbrains.annotations.NotNull;
import me.phoenixra.atumvr.api.enums.EyeType;
import me.phoenixra.atumvr.api.misc.color.AtumColor;
import me.phoenixra.atumvr.api.utils.GLUtils;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.CustomModelData;
import org.vmstudio.visor.core.client.render.helpers.RenderShaderHelper;
import org.vmstudio.visor.api.client.settings.VRClientSettings;
import net.minecraft.util.Util;
import net.minecraft.util.Mth;
import org.vmstudio.visor.api.compatibility.mcversion.McVersionUtils;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

import static org.vmstudio.visor.core.client.VisorClientImpl.MC;
import net.minecraft.world.entity.EquipmentSlot;


public class VRShaderPostProcessEye implements VRShader{

    private static final AtumColor PUMPKIN_VIGNETTE_COLOR
            = AtumColor.ORANGE.blend(AtumColor.BLACK, 0.5f);

    /** PORT-26.2: Visor's own uniform block needs a bind group layout of its own. */
    private static final BindGroupLayout LAYOUT = BindGroupLayout.builder()
            .withUniform("VisorPostProcess", UniformType.UNIFORM_BUFFER)
            .build();

    public static final RenderPipeline PIPELINE = RenderPipeline.builder()
            .withLocation(McVersionUtils.newResourceLoc("visor", "pipeline/vr_post_process_eye"))
            .withVertexShader(McVersionUtils.newResourceLoc("visor", "core/vr_post_process_eye"))
            .withFragmentShader(McVersionUtils.newResourceLoc("visor", "core/vr_post_process_eye"))
            .withBindGroupLayout(LAYOUT)
            .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
            .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            // PORT-26.2: ColorTargetState carries the target format now (DEFAULT uses RGBA8_UNORM).
            .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM,
                    ColorTargetState.WRITE_COLOR))
            .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
            .withCull(false)
            .build();

    /** Must match the VisorPostProcess block in the fragment shader, member for member. */
    private static final int UBO_SIZE = new Std140SizeCalculator()
            .putInt()
            .putFloat().putFloat().putFloat()
            .putFloat().putFloat().putFloat()
            .putVec4()
            .get();

    // One ring buffer per eye. A UBO cannot be partially patched, so the whole block is
    // re-emitted per eye; separate buffers keep each rotated exactly once per frame.
    private MappableRingBuffer uboLeft;
    private MappableRingBuffer uboRight;

    // Computed once per frame on the left eye and replayed for the right, which is what the
    // single updateUniforms() call used to guarantee.
    private float redTint;
    private float blueTint;
    private float blackTint;
    private float vignetteRadius;
    private float vignetteOffset;
    private float vignetteBorder;
    private float vignetteR;
    private float vignetteG;
    private float vignetteB;
    private float vignetteA;


    @Override
    public @NotNull RenderPipeline getPipeline() {
        return PIPELINE;
    }

    @Override
    public void init() {
        uboLeft = new MappableRingBuffer(() -> "Visor PostProcess UBO left",
                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, UBO_SIZE);
        uboRight = new MappableRingBuffer(() -> "Visor PostProcess UBO right",
                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, UBO_SIZE);
    }

    @Override
    public void close() {
        if (uboLeft != null) {
            uboLeft.close();
            uboLeft = null;
        }
        if (uboRight != null) {
            uboRight.close();
            uboRight = null;
        }
    }

    @Override
    public void endFrame() {
        if (uboLeft != null) uboLeft.rotate();
        if (uboRight != null) uboRight.rotate();
    }


    public void finishEye(EyeType eye,
                          RenderTarget source,
                          RenderTarget dest,
                          float partialTicks) {
        if (eye == EyeType.LEFT) {
            // update state only for the first rendered eye,
            // to have synchronized effects for both
            updateUniforms(partialTicks);
        }

        MappableRingBuffer ubo = (eye == EyeType.LEFT) ? uboLeft : uboRight;
        try (GpuBufferSlice.MappedView view = ubo.currentBuffer().map(false, true)) {
            Std140Builder.intoBuffer(view.data())
                    .putInt(eye == EyeType.LEFT ? 1 : -1)
                    .putFloat(redTint)
                    .putFloat(blueTint)
                    .putFloat(blackTint)
                    .putFloat(vignetteRadius)
                    .putFloat(vignetteOffset)
                    .putFloat(vignetteBorder)
                    .putVec4(vignetteR, vignetteG, vignetteB, vignetteA);
        }

        RenderShaderHelper.renderFullscreenQuad(
                () -> "visor post process eye",
                PIPELINE,
                pass -> {
                    pass.setUniform("VisorPostProcess", ubo.currentBuffer());
                    RenderShaderHelper.bindColor(pass, "Sampler0", source);
                },
                dest.getColorTextureView()
        );

        GLUtils.checkGLError("post process eye: "+ eye.name());
    }


    private void updateUniforms(float partialTicks){

        boolean canApplyEffects = MC.level != null
                && MC.player != null
                && !MC.player.isSpectator();


        float time = (float) Util.getMillis() / 1000.0F;

        float redTint = 0.0F;
        float blueTint = 0.0F;
        float blackTint = 0.0F;

        float vignetteRadius = 1.0f;
        float vignetteBorder = 0.06f;

        AtumColor vignetteColor = AtumColor.BLACK;

        if (canApplyEffects) {

            // --- Damage & low health effects ---
            if (MC.player.isCreative()) {
                redTint = 0.0F;
            }else{
                float hurtTimer = (float) MC.player.hurtTime - partialTicks;
                float healthPercent = 1.0F - MC.player.getHealth() / MC.player.getMaxHealth();
                healthPercent = (healthPercent - 0.5F) * 0.75F;
                if (VRClientSettings.isHitIndicatorEnabled()
                        && hurtTimer > 0.0F) {
                    // red flash
                    hurtTimer = hurtTimer / (float) MC.player.hurtDuration;
                    hurtTimer = healthPercent +
                            Mth.sin(hurtTimer * hurtTimer * hurtTimer * hurtTimer * Mth.PI) * 0.5F;
                    redTint = hurtTimer;
                } else if(VRClientSettings.isLowHealthIndicatorEnabled()){
                    //low health red indicator
                    redTint = healthPercent * Mth.abs(Mth.sin((2.5F * time) / (1.0F - healthPercent + 0.1F)));
                }
            }


            // --- Freeze effect ---
            if(VRClientSettings.isFreezeEffectEnabled()) {
                float freeze = MC.player.getPercentFrozen();
                boolean hasFreezeEffect = freeze > 0;
                if (hasFreezeEffect) {
                    blueTint = redTint;
                    blueTint = Math.max(freeze / 2, blueTint);
                    redTint = 0;
                }
            }

            // --- Sleep effect ---
            if (MC.player.isSleeping()) {
                blackTint = 0.5F + 0.3F * MC.player.getSleepTimer() * 0.01F;
            }


            // --- Vignette ---
            ItemStack headItem = MC.player.getItemBySlot(EquipmentSlot.HEAD);

            if(VRClientSettings.isPumpkinEffectEnabled()) {
                boolean hasPumpkin = headItem.getItem() == Blocks.CARVED_PUMPKIN.asItem()
                        && !headItem.has(DataComponents.CUSTOM_MODEL_DATA);
                if (hasPumpkin) {
                    vignetteColor = PUMPKIN_VIGNETTE_COLOR;
                    vignetteRadius = 0.3f;
                    vignetteBorder = 0f;

                }
            }

        }

        // --- Finalize ---
        // Held as fields rather than pushed straight at the GPU: the block is written once per
        // eye in finishEye(), because a UBO has to be re-emitted whole.

        //tints
        this.redTint = redTint;
        this.blueTint = blueTint;
        this.blackTint = blackTint;

        //vignette
        this.vignetteRadius = vignetteRadius;
        this.vignetteBorder = vignetteBorder;
        this.vignetteOffset = 0.1f;
        this.vignetteR = vignetteColor.getRed();
        this.vignetteG = vignetteColor.getGreen();
        this.vignetteB = vignetteColor.getBlue();
        this.vignetteA = vignetteColor.getAlpha();
    }

}
