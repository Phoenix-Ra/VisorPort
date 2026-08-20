package org.vmstudio.visor.loader.fabric.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.vmstudio.visor.api.client.render.RenderPipelineStage;
import org.vmstudio.visor.loader.fabric.FabricModLoader;

/**
 * Fires Visor's AFTER_WORLD render stage on Fabric.
 * <p>
 * PORT-1.21.11: the other two stages still come from {@code WorldRenderEvents} (see
 * {@link FabricModLoader#addToRenderPipeline}), but {@code WorldRenderEvents.END} did not survive
 * the extract/submit rework and nothing replaced it. The closest survivor, {@code END_MAIN}, ends
 * the main pass <em>before</em> particles, clouds and weather, so it is the successor to the old
 * {@code AFTER_TRANSLUCENT} and is already used for that - reusing it here would fire AFTER_WORLD
 * at the same instant as AFTER_TRANSLUCENT and lose the stage's meaning entirely.
 * <p>
 * TAIL of {@code renderLevel} is where {@code END} used to sit: the method ends by running the
 * frame graph ({@code frameGraphBuilder.execute(...)}), so everything the level draws - particles,
 * clouds, weather and late debug included - has happened by then.
 * <p>
 * The callback gets a fresh {@link PoseStack}, matching what the Forge and NeoForge
 * implementations hand their AFTER_WORLD callbacks; the decoration renderers build their own
 * camera transform regardless.
 */
@Mixin(LevelRenderer.class)
public class FabricLevelRendererVRMixin {

    @Inject(method = "renderLevel", at = @At("TAIL"))
    private void visor$afterLevel(CallbackInfo ci) {
        FabricModLoader.fireRenderPipelineStage(
                RenderPipelineStage.AFTER_WORLD, new PoseStack(), visor$partialTicks());
    }

    @Unique
    private static float visor$partialTicks() {
        return Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true);
    }
}
