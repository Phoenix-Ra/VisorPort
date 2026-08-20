package org.vmstudio.visor.mixin.client.renderer.entity.player.layers;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.world.entity.EquipmentSlot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.vmstudio.visor.core.client.render.VRRenderState;

@Mixin(HumanoidArmorLayer.class)
public class HumanoidArmorLayerMixin {

    // PORT-1.21.11: renderArmorPiece now takes the render state as its last argument
    // (PoseStack, SubmitNodeCollector, ItemStack, EquipmentSlot, int, S), so the state can be read
    // straight off the frame instead of being stashed from a render/submit HEAD hook into a field.
    // That drops the second injector along with the risk of the field going stale between entities.
    // Cancelling here means the helmet is never submitted, which suppresses it exactly as
    // cancelling the old draw did.
    @Inject(method = "renderArmorPiece", at = @At("HEAD"), cancellable = true)
    private void visor$noHelmetInFirstPerson(CallbackInfo ci,
                                             @Local(argsOnly = true) EquipmentSlot slot,
                                             @Local(argsOnly = true) HumanoidRenderState renderState)
    {
        if (slot == EquipmentSlot.HEAD && VRRenderState.isSelfOrSpectatedVRView(renderState)) {
            ci.cancel();
        }
    }
}
