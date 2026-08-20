package org.vmstudio.visor.mixin.client.renderer.entity.player.layers;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.renderer.entity.layers.CustomHeadLayer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.vmstudio.visor.core.client.render.VRRenderState;

@Mixin(CustomHeadLayer.class)
public class CustomHeadLayerMixin {
    // PORT-1.21.11: RenderLayer#render became RenderLayer#submit. Cancelling at HEAD now means the
    // head geometry is never handed to the SubmitNodeCollector, which suppresses it just as
    // cancelling the old draw did. The descriptor pins the LivingEntityRenderState overload so the
    // synthetic EntityRenderState bridge can never be picked instead.
    @Inject(method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;FF)V", at = @At("HEAD"), cancellable = true)
    private void visor$noHelmetInFirstPerson(CallbackInfo ci,
                                             @Local(argsOnly = true) LivingEntityRenderState renderState)
    {
        if (VRRenderState.isSelfOrSpectatedVRView(renderState)) {
            ci.cancel();
        }
    }
}
