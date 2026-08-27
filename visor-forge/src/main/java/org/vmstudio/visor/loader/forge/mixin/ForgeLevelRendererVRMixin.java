package org.vmstudio.visor.loader.forge.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.vmstudio.visor.api.client.render.RenderPipelineStage;
import org.vmstudio.visor.loader.forge.ForgeModLoader;

/**
 * Fires Visor's AFTER_WORLD render stage on Forge.
 * <p>
 * 1.21.4: Forge 54 dropped {@code RenderLevelStageEvent} when level rendering moved to the frame
 * graph, and the only hook left ({@code AddFramePassEvent}) appends a pass after every vanilla one,
 * so it cannot express the individual stages. They are fired from mixins instead - this one for
 * AFTER_WORLD (the old {@code AFTER_LEVEL}), and {@link ForgeChunkSectionsVRMixin} for the two
 * terrain-relative stages.
 * <p>
 * TAIL is still the right anchor in 1.21.11: {@code renderLevel} ends by running the frame graph
 * ({@code frameGraphBuilder.execute(...)}), so everything the level draws has happened by then.
 * <p>
 * The callbacks get a fresh {@link PoseStack} - the decoration renderers build their own camera
 * transform, the same contract the Fabric and NeoForge implementations rely on.
 */
@Mixin(LevelRenderer.class)
public class ForgeLevelRendererVRMixin {

    @Inject(method = "render", at = @At("TAIL"))
    private void visor$afterLevel(CallbackInfo ci) {
        ForgeModLoader.fireRenderPipelineStage(
                RenderPipelineStage.AFTER_WORLD, new PoseStack(), visor$partialTicks());
    }

    @Unique
    private static float visor$partialTicks() {
        return Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true);
    }
}
