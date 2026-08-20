package org.vmstudio.visor.loader.forge.mixin;

import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.vmstudio.visor.api.client.render.RenderPipelineStage;
import org.vmstudio.visor.loader.forge.ForgeModLoader;

/**
 * Fires Visor's terrain-relative render stages on Forge.
 * <p>
 * PORT-1.21.11: {@code LevelRenderer#renderSectionLayer(RenderType, ...)} is gone - terrain is no
 * longer drawn one {@code RenderType} at a time. {@code ChunkSectionsToRender#renderGroup} draws a
 * whole {@link ChunkSectionLayerGroup} (OPAQUE = the SOLID + CUTOUT layers, then TRANSLUCENT, then
 * TRIPWIRE) and is called three times per frame.
 * <p>
 * The hook lives here rather than on {@code LevelRenderer#renderLevel} because those three calls
 * are <em>not</em> in {@code renderLevel} - level rendering is a frame graph now and the terrain
 * pass body is compiled into an unmapped synthetic lambda ({@code method_62214} in the current
 * mappings). Injecting into a lambda by its intermediary name would break on any mapping change
 * and does not survive the SRG remap on Forge at all, so the stages are driven from the mapped,
 * public method the pass calls instead. Same point in the frame, stable name.
 */
@Mixin(ChunkSectionsToRender.class)
public class ForgeChunkSectionsVRMixin {

    @Inject(method = "renderGroup", at = @At("TAIL"))
    private void visor$afterSectionGroup(ChunkSectionLayerGroup group, GpuSampler sampler,
                                         CallbackInfo ci) {
        RenderPipelineStage stage;
        if (group == ChunkSectionLayerGroup.OPAQUE) {
            // the old AFTER_CUTOUT_BLOCKS: after terrain, before entities
            stage = RenderPipelineStage.AFTER_SOLID;
        } else if (group == ChunkSectionLayerGroup.TRANSLUCENT) {
            stage = RenderPipelineStage.AFTER_TRANSLUCENT;
        } else {
            // TRIPWIRE had no equivalent stage before and gets none now
            return;
        }
        ForgeModLoader.fireRenderPipelineStage(stage, new PoseStack(), visor$partialTicks());
    }

    @Unique
    private static float visor$partialTicks() {
        return Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true);
    }
}
