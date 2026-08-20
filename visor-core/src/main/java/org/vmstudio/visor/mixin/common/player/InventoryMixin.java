package org.vmstudio.visor.mixin.common.player;

import net.minecraft.world.Container;
import net.minecraft.world.Nameable;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.vmstudio.visor.api.VisorAPI;
import org.vmstudio.visor.api.common.player.VRPlayer;
import org.vmstudio.visor.api.server.VRServerSettings;

@Mixin(Inventory.class)
public abstract class InventoryMixin implements Container, Nameable {

    @Shadow
    @Final
    public Player player;


    /* ***************************************** *\
  //--------TWO HANDED VR (OFFHAND SUPPORT)--------\\
    \* ***************************************** */

    /*
     * PORT-1.21.11: Visor used to swap Inventory.offhand for an OffhandNonNullList so the offhand
     * always resolved to whichever hotbar slot the player is really holding in VR. That list is
     * gone - armor and offhand moved into EntityEquipment and Inventory keeps only the 36
     * non-equipment stacks - so the redirect happens on the two container accessors that still
     * reach the offhand. The entity-side half (getItemBySlot/setItemSlot, which is what
     * Player#getOffhandItem goes through) lives in Common_LivingEntityMixin.
     *
     * The save/load pair that used to flip the list back to vanilla behaviour went with it:
     * Inventory#save/#load only serialize the non-equipment stacks now, so there is nothing left
     * for them to guard.
     */
    @Inject(method = "getItem", at = @At("HEAD"), cancellable = true)
    public void visor$offhandGetItem(int slot, CallbackInfoReturnable<ItemStack> cir) {
        int hotbarSlot = visor$vrOffhandSlot(slot);
        if (hotbarSlot < 0) {
            return;
        }
        cir.setReturnValue(((Inventory) (Object) this).getItem(hotbarSlot));
    }

    @Inject(method = "setItem", at = @At("HEAD"), cancellable = true)
    public void visor$offhandSetItem(int slot, ItemStack stack, CallbackInfo ci) {
        int hotbarSlot = visor$vrOffhandSlot(slot);
        if (hotbarSlot < 0) {
            return;
        }
        ((Inventory) (Object) this).setItem(hotbarSlot, stack);
        ci.cancel();
    }

    /**
     * The hotbar slot standing in for the offhand, or -1 when the vanilla slot should be used
     * as-is. Mirrors the guards OffhandNonNullList applied before the equipment rework.
     */
    @Unique
    private int visor$vrOffhandSlot(int slot) {
        if (slot != Inventory.SLOT_OFFHAND || !VRServerSettings.isTwoHandedVR()) {
            return -1;
        }
        VRPlayer vrPlayer = VisorAPI.getVRPlayer(player);
        if (vrPlayer == null || vrPlayer.isRemote()) {
            return -1;
        }
        int hotbarSlot = vrPlayer.getOffhandSlot();
        return hotbarSlot >= 0
                && hotbarSlot < ((Inventory) (Object) this).getNonEquipmentItems().size()
                ? hotbarSlot
                : -1;
    }
}
