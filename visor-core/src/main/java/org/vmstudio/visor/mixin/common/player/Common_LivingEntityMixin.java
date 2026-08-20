package org.vmstudio.visor.mixin.common.player;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BlocksAttacks;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.vmstudio.visor.api.VisorAPI;
import org.vmstudio.visor.api.common.player.VRPlayer;
import org.vmstudio.visor.api.server.VRServerSettings;
import org.vmstudio.visor.core.common.CommonUtils;

@Mixin(LivingEntity.class)
public abstract class Common_LivingEntityMixin extends Common_EntityMixin {


    @Shadow protected ItemStack useItem;

    @Shadow protected int useItemRemaining;

    @Shadow public abstract boolean isFallFlying();

    @Shadow public float zza;

    @Shadow public abstract void remove(Entity.RemovalReason reason);

    @Shadow public abstract void onEquipItem(EquipmentSlot slot, ItemStack oldStack, ItemStack newStack);

    @Inject(at = @At("HEAD"), method = "spawnItemParticles", cancellable = true)
    protected void visor$spawnVRItemParticles(ItemStack itemStack,
                                              int count,
                                              CallbackInfo ci){}


    /* ***************************************** *\
  //--------------ROOMSCALE BLOCKING---------------\\
    \* ***************************************** */

    /*
     * PORT-1.21.11: LivingEntity#isDamageSourceBlocked is gone. 1.21.5 moved blocking into the
     * BlocksAttacks item component and folded the whole decision into
     * applyItemBlocking(ServerLevel, DamageSource, float), which hurtServer now subtracts from the
     * incoming damage. It still opens with getItemBlockingWith(), so the roomscale hooks keep the
     * same two seams - "is this hit blocked" and "what came of it" - only the verdict is an amount
     * of absorbed damage now instead of a boolean.
     */
    @ModifyExpressionValue(method = "applyItemBlocking",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/LivingEntity;getItemBlockingWith()Lnet/minecraft/world/item/ItemStack;"))
    protected ItemStack visor$roomscaleShieldBlocking(ItemStack blockingWith,
                                                      @Local(argsOnly = true) DamageSource damageSource,
                                                      @Share("roomscaleBlocked") LocalBooleanRef roomscaleBlocked) {
        return blockingWith;
    }

    @ModifyReturnValue(method = "applyItemBlocking", at = @At("RETURN"))
    private float visor$roomscaleShieldBlocked(float blockedDamage,
                                               @Local(argsOnly = true) float damageAmount,
                                               @Share("roomscaleBlocked") LocalBooleanRef roomscaleBlocked) {
        // a roomscale block swallows the hit whole, which is what returning true used to mean
        return roomscaleBlocked.get()
                ? Math.max(blockedDamage, damageAmount)
                : blockedDamage;
    }

    /*
     * PORT-1.21.11: both of these used to sit on Player#hurtCurrentlyUsedShield, which no longer
     * exists - the blocking item's durability loss is BlocksAttacks#hurtBlockingItem, called from
     * applyItemBlocking, so the hooks follow it down to LivingEntity. The hand fed to that call is
     * still where roomscale decides which controller is actually holding the shield.
     */
    @ModifyExpressionValue(method = "applyItemBlocking",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/LivingEntity;getUsedItemHand()Lnet/minecraft/world/InteractionHand;"))
    protected InteractionHand visor$roomscaleShieldHand(InteractionHand original) {
        return original;
    }

    @WrapOperation(method = "applyItemBlocking",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/item/component/BlocksAttacks;hurtBlockingItem(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/InteractionHand;F)V"))
    protected void visor$roomscaleShieldItemDamage(BlocksAttacks blocksAttacks,
                                                   Level level,
                                                   ItemStack blockingWith,
                                                   LivingEntity blocker,
                                                   InteractionHand hand,
                                                   float damageAmount,
                                                   Operation<Void> original) {
        original.call(blocksAttacks, level, blockingWith, blocker, hand, damageAmount);
    }


    @WrapOperation(method = "hurtServer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;knockback(DDD)V"))
    private void visor$vrHurtKnockbackDirection(LivingEntity instance, double strength, double x, double z,
                                                Operation<Void> original,
                                                @Local(argsOnly = true) DamageSource damageSource) {
        Vec3 knockBack = CommonUtils.calcVRKnockback(damageSource.getEntity(), instance);
        if (knockBack != null) {
            x = knockBack.x;
            z = knockBack.z;
        }
        original.call(instance, strength, x, z);
    }


    /* ***************************************** *\
  //--------TWO HANDED VR (OFFHAND SUPPORT)--------\\
    \* ***************************************** */

    /*
     * PORT-1.21.11: Inventory no longer owns an offhand NonNullList - the stack moved into
     * EntityEquipment - so Visor can no longer install an OffhandNonNullList in its place. Every
     * offhand read and write funnels through getItemBySlot/setItemSlot, so the redirect to the
     * hotbar slot the player is really holding in VR lives on those two instead. The guards are
     * the ones OffhandNonNullList applied.
     */
    @Inject(method = "getItemBySlot", at = @At("HEAD"), cancellable = true)
    private void visor$vrOffhandItem(EquipmentSlot slot, CallbackInfoReturnable<ItemStack> cir) {
        int hotbarSlot = visor$vrOffhandSlot(slot);
        if (hotbarSlot < 0) {
            return;
        }
        cir.setReturnValue(((Player) (Object) this).getInventory().getItem(hotbarSlot));
    }

    @Inject(method = "setItemSlot", at = @At("HEAD"), cancellable = true)
    private void visor$vrSetOffhandItem(EquipmentSlot slot, ItemStack stack, CallbackInfo ci) {
        int hotbarSlot = visor$vrOffhandSlot(slot);
        if (hotbarSlot < 0) {
            return;
        }
        Player player = (Player) (Object) this;
        ItemStack previous = player.getInventory().getItem(hotbarSlot);
        player.getInventory().setItem(hotbarSlot, stack);
        // vanilla reports the swap after the write, and so did the old offhand list
        onEquipItem(slot, previous, stack);
        ci.cancel();
    }

    /**
     * The hotbar slot standing in for the offhand, or -1 when the vanilla equipment slot should
     * be used as-is.
     */
    @Unique
    private int visor$vrOffhandSlot(EquipmentSlot slot) {
        if (slot != EquipmentSlot.OFFHAND || !VRServerSettings.isTwoHandedVR()) {
            return -1;
        }
        if (!((Object) this instanceof Player player)) {
            return -1;
        }
        VRPlayer vrPlayer = VisorAPI.getVRPlayer(player);
        if (vrPlayer == null || vrPlayer.isRemote()) {
            return -1;
        }
        int hotbarSlot = vrPlayer.getOffhandSlot();
        return hotbarSlot >= 0 && hotbarSlot < player.getInventory().getNonEquipmentItems().size()
                ? hotbarSlot
                : -1;
    }
}
