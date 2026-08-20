package org.vmstudio.visor.mixin.client.renderer.entity.player;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.vmstudio.visor.core.client.render.VRRenderState;
import org.vmstudio.visor.extensions.client.render.ItemInHandRendererExtension;


@Mixin(value = ItemInHandRenderer.class, priority = 999)
public abstract class ItemInHandRendererMixin implements ItemInHandRendererExtension {

    @Shadow
    private float oMainHandHeight;
    @Shadow
    private float mainHandHeight;
    @Shadow
    private float oOffHandHeight;
    @Shadow
    private float offHandHeight;



    // PORT-1.21.11: renderMap takes a SubmitNodeCollector instead of a MultiBufferSource, and
    // it is private on ItemInHandRenderer now - a shadow may not widen the target's visibility.
    @Shadow
    private void renderMap(PoseStack poseStack,
                           SubmitNodeCollector collector,
                           int combinedLight,
                           ItemStack itemStack) {
        throw new AssertionError("shadow");
    }


    // PORT-1.21.11: renderHandsWithItems' third parameter went MultiBufferSource.BufferSource ->
    // SubmitNodeCollector with the extract/submit split. An @Inject handler's parameters must
    // mirror the target's exactly, so the stale type was an apply-time crash, not a warning.
    @Inject(method = "renderHandsWithItems", at = @At("HEAD"), cancellable = true)
    private void visor$noFirstPersonHandsInVR(float tickDelta,
                                              PoseStack poseStack,
                                              SubmitNodeCollector collector,
                                              LocalPlayer player,
                                              int light,
                                              CallbackInfo ci) {
        if (VRRenderState.getPhase().isNotVanilla()) {
            ci.cancel();
        }
    }

    @Override
    public void visor$renderMap(PoseStack poseStack,
                                SubmitNodeCollector collector,
                                int pCombinedLight,
                                ItemStack itemStack) {
        renderMap(poseStack, collector, pCombinedLight, itemStack);
    }

    @Unique
    public float visor$getEquipProgress(InteractionHand hand, float partialTicks) {
        return hand == InteractionHand.MAIN_HAND
                ? 1.0F - (this.oMainHandHeight + (this.mainHandHeight - this.oMainHandHeight) * partialTicks)
                : 1.0F - (this.oOffHandHeight + (this.offHandHeight - this.oOffHandHeight) * partialTicks);
    }

}
