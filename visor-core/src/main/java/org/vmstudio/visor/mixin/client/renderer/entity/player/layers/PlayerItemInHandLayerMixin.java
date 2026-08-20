package org.vmstudio.visor.mixin.client.renderer.entity.player.layers;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.PlayerItemInHandLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.vmstudio.visor.api.common.HandType;
import org.vmstudio.visor.core.client.ClientContext;
import org.vmstudio.visor.core.client.render.VRRenderState;
import org.vmstudio.visor.extensions.client.entity.EntityRenderStateExtension;


@Mixin(value = PlayerItemInHandLayer.class, priority = 900)
public class PlayerItemInHandLayerMixin {

    // PORT-1.21.11: renamed to submitArmWithItem, gained an ItemStack third argument, and the
    // MultiBufferSource became a SubmitNodeCollector. The full descriptor is kept deliberately -
    // PlayerItemInHandLayer carries a synthetic bridge overload taking ArmedEntityRenderState, so
    // a bare name is ambiguous.
    private static final String SUBMIT_ARM_WITH_ITEM =
            "submitArmWithItem(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;"
                    + "Lnet/minecraft/client/renderer/item/ItemStackRenderState;"
                    + "Lnet/minecraft/world/item/ItemStack;"
                    + "Lnet/minecraft/world/entity/HumanoidArm;"
                    + "Lcom/mojang/blaze3d/vertex/PoseStack;"
                    + "Lnet/minecraft/client/renderer/SubmitNodeCollector;I)V";

    @Inject(method = SUBMIT_ARM_WITH_ITEM, at = @At("HEAD"), cancellable = true)
    private void visor$noItemInGui(AvatarRenderState renderState, ItemStackRenderState itemState,
                                   ItemStack itemStack, HumanoidArm arm, PoseStack poseStack,
                                   SubmitNodeCollector collector, int packedLight, CallbackInfo ci)
    {
        EntityRenderStateExtension ext = (EntityRenderStateExtension) renderState;
        var vrPlayer = ext.visor$getVRPlayer();
        if (vrPlayer != null && VRRenderState.isSpectatedVRView(vrPlayer.getMcPlayer())) {
            ci.cancel();
            return;
        }
        if (ext.visor$isSelfModelRender()) {
            if (!ext.visor$isSelfModelHandsRender()) {
                ci.cancel();
            }
            boolean leftHanded = ClientContext.localPlayer.isLeftHanded();
            if (!ClientContext.decorationRenderer
                    .getHandState(HandType.fromMcArm(arm, leftHanded)).isWithItem()) {
                ci.cancel();
            }
        }
    }

    // ordinal 1 is the heldOnHead check that selects the held-to-eye branch; ordinal 0 is the
    // "is there an item at all" early-out and must be left alone.
    @ModifyExpressionValue(
            method = SUBMIT_ARM_WITH_ITEM,
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/item/ItemStackRenderState;isEmpty()Z",
                    ordinal = 1))
    private boolean visor$noHeldToEye(
            boolean isEmpty, @Local(argsOnly = true) AvatarRenderState renderState)
    {
        return isEmpty
                || ((EntityRenderStateExtension) renderState).visor$isSelfModelHandsRender();
    }
}
