package org.vmstudio.visor.mixin.client.renderer.particle;

import org.vmstudio.visor.api.client.player.pose.PlayerPoseType;
import org.vmstudio.visor.core.client.ClientContext;
import org.vmstudio.visor.core.client.VisorState;
import net.minecraft.client.particle.ItemPickupParticle;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static org.vmstudio.visor.core.client.VisorClientImpl.MC;


@Mixin(ItemPickupParticle.class)
public class ItemPickupParticleMixin {

    @Final
    @Shadow
    private Entity target;

    // PORT-1.21.11: the particle no longer holds the item entity, only the render state it was
    // built from - boundingBoxHeight is the getBbHeight() the old offset was taken from.
    @Final
    @Shadow
    protected EntityRenderState itemRenderState;

    @Shadow
    protected double targetX;

    @Shadow
    protected double targetY;

    @Shadow
    protected double targetZ;

    // PORT-1.21.11: ItemPickupParticle#renderCustom is gone. The particle is extracted into
    // ItemPickupParticleGroup$ParticleInstance and submitted, and the Mth.lerp calls the three
    // redirects used to sit on moved with it, into a package-private record this mod cannot
    // register a mixin for. updatePosition() is the surviving seam: it is the one place the
    // flight target is written, and those lerps read nothing but its output, so steering it here
    // lands the particle on the headset exactly as before.
    // Consequence: the target is sampled from the tick pose rather than the render pose, and it
    // interpolates between ticks instead of being pinned to one render-time sample. The particle
    // lives three ticks and is drawn moving towards this point, so the difference is not visible.
    @Inject(method = "updatePosition", at = @At("TAIL"))
    private void visor$vrPickupTarget(CallbackInfo ci) {
        if (VisorState.get().isNotActive()
                || this.target != MC.player
                || ClientContext.localPlayer == null) {
            return;
        }

        Vec3 hmdPos = ClientContext.localPlayer
                .getPoseData(PlayerPoseType.TICK)
                .getHmd().getPositionVec3();

        this.targetX = hmdPos.x;
        // aim below the headset so the item flies into the chest, not into the face
        this.targetY = hmdPos.y - (0.5D + this.itemRenderState.boundingBoxHeight);
        this.targetZ = hmdPos.z;
    }
}
