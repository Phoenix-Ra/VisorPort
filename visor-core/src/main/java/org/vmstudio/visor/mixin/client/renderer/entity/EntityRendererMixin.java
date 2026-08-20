package org.vmstudio.visor.mixin.client.renderer.entity;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import org.vmstudio.visor.api.client.player.VRClientPlayer;
import org.vmstudio.visor.core.client.ClientContext;
import org.vmstudio.visor.core.client.player.VRClientPlayers;
import org.vmstudio.visor.core.client.render.VRRenderState;
import org.vmstudio.visor.extensions.client.entity.EntityRenderDispatcherExtension;
import org.vmstudio.visor.extensions.client.entity.EntityRenderStateExtension;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.vmstudio.visor.api.client.player.pose.PlayerPoseType;
import org.vmstudio.visor.api.common.HandType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderer.class)
public class EntityRendererMixin {

    @Shadow
    @Final
    protected EntityRenderDispatcher entityRenderDispatcher;


    @Inject(method = "extractRenderState", at = @At("RETURN"))
    private void visor$extractVRPlayer(Entity entity, EntityRenderState renderState,
                                       float partialTick, CallbackInfo ci) {
        boolean trackable = entity instanceof Player
                && ClientContext.localPlayer != null;
        ((EntityRenderStateExtension) renderState).visor$setVRPlayer(
                trackable ? VRClientPlayers.getPlayer(entity) : null
        );
    }

    // PORT-1.21.11: renderNameTag -> submitNameTag, and it no longer asks the dispatcher for a
    // billboard orientation - the quaternion is read straight off CameraRenderState.orientation
    // inside the submit call (NameTagFeatureRenderer$Storage#add multiplies it into the pose and
    // copies the resulting Matrix4f into the submit node right there). One camera state is shared
    // by every submit of the frame, so the per-name-tag override has to be a swap around this one
    // call; nothing reads the field in between, and it is restored before returning.
    @WrapOperation(method = "submitNameTag",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitNameTag(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/phys/Vec3;ILnet/minecraft/network/chat/Component;ZIDLnet/minecraft/client/renderer/state/CameraRenderState;)V"))
    private void visor$vrNameTagCameraOrient(SubmitNodeCollector collector, PoseStack poseStack,
                                             Vec3 nameTagAttachment, int yOffset, Component text,
                                             boolean seeThrough, int lightCoords,
                                             double distanceToCameraSq, CameraRenderState cameraState,
                                             Operation<Void> original,
                                             @Local(argsOnly = true) EntityRenderState renderState) {
        float heightScale = 1.0f;
        VRClientPlayer vrPlayer = ((EntityRenderStateExtension) renderState).visor$getVRPlayer();
        if (vrPlayer != null) {
            heightScale = vrPlayer.getFullHeightScale();
        }

        // The anchor used to come from the entity LevelRenderer was drawing; the render state
        // carries the same thing (interpolated position, getBbHeight()) and outlives extraction.
        Quaternionf vanillaOrientation = cameraState.orientation;
        cameraState.orientation = ((EntityRenderDispatcherExtension) this.entityRenderDispatcher)
                .visor$getVRBillboardOrientation(
                        renderState.x,
                        renderState.y + renderState.boundingBoxHeight * heightScale
                                + 0.5f * heightScale,
                        renderState.z
                );
        try {
            original.call(collector, poseStack, nameTagAttachment, yOffset, text, seeThrough,
                    lightCoords, distanceToCameraSq, cameraState);
        } finally {
            cameraState.orientation = vanillaOrientation;
        }
    }

    @Inject(method = "submitNameTag", at = @At("HEAD"), cancellable = true)
    private void visor$hideSpectatedVRNameTag(EntityRenderState renderState, PoseStack poseStack,
                                              SubmitNodeCollector collector,
                                              CameraRenderState cameraState, CallbackInfo ci) {
        VRClientPlayer vrPlayer = ((EntityRenderStateExtension) renderState).visor$getVRPlayer();
        if (vrPlayer != null
                && VRRenderState.isSpectatedVRView(vrPlayer.getMcPlayer())) {
            ci.cancel();
        }
    }


    @Redirect(at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getRopeHoldPosition(F)Lnet/minecraft/world/phys/Vec3;"), method = "extractRenderState")
    public Vec3 visor$vrRenderLeash(Entity instance, float partialTick) {
        if (VRRenderState.getPhase().isNotVRWorld()) {
            return instance.getRopeHoldPosition(partialTick);
        }

        if (!(instance instanceof Player player)) {
            return instance.getRopeHoldPosition(partialTick);
        }

        var vrPlayer = VRClientPlayers.getPlayer(player);
        if (vrPlayer == null) {
            return instance.getRopeHoldPosition(partialTick);
        }

        return new Vec3(
                new Vector3f(
                        vrPlayer.getPoseData(PlayerPoseType.RENDER)
                                .getHand(HandType.MAIN)
                                .getPosition()
                )
        );
    }
}
