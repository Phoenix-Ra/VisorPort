package org.vmstudio.visor.mixin.client.renderer.entity.player;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.client.renderer.entity.*;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.vmstudio.visor.api.client.player.VRClientPlayer;
import org.vmstudio.visor.api.client.player.pose.PlayerPoseType;
import org.vmstudio.visor.api.client.render.decoration.VRBodyRenderer;
import org.vmstudio.visor.core.client.ClientContext;
import org.vmstudio.visor.core.client.VisorState;
import org.vmstudio.visor.core.client.player.VRClientPlayers;
import org.vmstudio.visor.core.client.render.VRRenderState;
import org.vmstudio.visor.extensions.client.entity.EntityRenderDispatcherExtension;
import org.vmstudio.visor.extensions.client.entity.EntityRenderStateExtension;
import org.vmstudio.visor.extensions.client.entity.PlayerRendererExtension;

public class PlayerRenderMixins {
    @Mixin(EntityRenderDispatcher.class)
    public abstract static class EntityRenderDispatcherMixin implements ResourceManagerReloadListener, EntityRenderDispatcherExtension {

        @Shadow
        public Camera camera;

        // PORT-1.21.11: EntityRenderDispatcher.cameraOrientation()/overrideCameraOrientation()
        // are gone, and with them the single seam that turned EVERY billboard (xp orbs, the
        // fishing bobber, item frames' text, ...) towards the headset instead of the flat camera
        // plane. 1.21.11 reads one org.joml.Quaternionf off CameraRenderState.orientation, shared
        // by the whole submit pass, so there is no per-entity look-at left to hook here - the
        // injector that used to do it is removed rather than left dangling, because Mixin resolves
        // @Inject targets by name at apply time and throws InvalidInjectionException for a name
        // that matches nothing (visor.mixins.json sets "required": true).
        // The name tag case is restored per submit in EntityRendererMixin, which swaps
        // CameraRenderState.orientation around the one submitNameTag call. Every other billboard
        // now uses the plain camera orientation until each submit path gets the same treatment.

        // 1.21.11: distanceToSqr(double,double,double) is gone, so the trailing wildcard that used
        // to disambiguate the two overloads now only ever resolves distanceToSqr(Entity).
        @Inject(method = "distanceToSqr", at = @At("HEAD"), cancellable = true)
        private void visor$checkCameraNull(CallbackInfoReturnable<Double> cir) {
            if (this.camera == null) {
                cir.setReturnValue(0.0D);
            }
        }

        // 1.21.11: getRenderer is overloaded (Entity / EntityRenderState), so the entity one
        // has to be named by descriptor or the injector matches both.
        @Inject(method = "getRenderer(Lnet/minecraft/world/entity/Entity;)Lnet/minecraft/client/renderer/entity/EntityRenderer;",
                at = @At("HEAD"), cancellable = true)
        private void visor$getVRPlayerRenderer(
                Entity entity, CallbackInfoReturnable cir)
        {
            if(ClientContext.visor == null) {
                return;
            }

            if (entity instanceof AbstractClientPlayer player)
            {
                var vrPlayer = VRClientPlayers.getPlayer(player);
                if(vrPlayer == null){
                    return;
                }
                // 1.21.1: getModelName() removed; PlayerSkin.Model#id() is "slim"/"default"
                // 1.21.11: PlayerSkin.model() is the PlayerModelType enum and has no id() -
                // its serialized name is "slim"/"wide", so map the enum onto the renderer keys
                // instead of feeding a name the body registry does not know.
                String modelName = player.getSkin().model() == PlayerModelType.SLIM
                        ? VRBodyRenderer.MODEL_NAME_SLIM
                        : VRBodyRenderer.MODEL_NAME_DEFAULT;
                var model = vrPlayer.getBodyType().getRenderer().getModelRenderer(
                        vrPlayer, modelName
                );
                if(model != null) {
                    cir.setReturnValue(model);
                }
            }
        }


        @Inject(method = "onResourceManagerReload", at = @At(value = "HEAD"))
        private void visor$clearVRPlayerRenderer(CallbackInfo ci) {
            if(ClientContext.visor == null) {
                return;
            }
            ClientContext.decorationRenderer.getVrBodyTypeRegistry().getAllComponents().forEach(
                    it-> it.getRenderer().clearModels()
            );

        }

        // 1.21.11: createPlayerRenderers -> createAvatarRenderers, and it is now called twice
        // (players, then mannequins) - ordinal 0 keeps this a single fire on the player pass.
        @Inject(method = "onResourceManagerReload", at = @At(value = "INVOKE", ordinal = 0, target = "Lnet/minecraft/client/renderer/entity/EntityRenderers;createAvatarRenderers(Lnet/minecraft/client/renderer/entity/EntityRendererProvider$Context;)Ljava/util/Map;"))
        private void visor$reloadVRPlayerRenderer(CallbackInfo ci, @Local EntityRendererProvider.Context context) {
            if(ClientContext.visor == null) {
                VisorState.setDelayedVrBodyInit(context);
                return;
            }
            ClientContext.decorationRenderer.getVrBodyTypeRegistry().getAllComponents().forEach(
                    it-> it.getRenderer().initModels(context)
            );

        }


        // PORT-1.21.11: the anchor used to be looked up here, from the entity LevelRenderer was
        // busy drawing. Extraction and submission are separate passes now and nothing is "being
        // rendered" while a billboard is submitted, so the caller passes the point instead.
        @Override
        @Unique
        public Quaternionf visor$getVRBillboardOrientation(double targetX, double targetY,
                                                           double targetZ) {
            if (this.camera == null) {
                return new Quaternionf();
            }
            Vec3 source;
            if (VRRenderState.getRenderPass().isThirdPerson()) {
                // 1.21.11: Camera.getPosition() -> Camera.position()
                source = this.camera.position();
            } else if (ClientContext.localPlayer != null) {
                source = ClientContext.localPlayer.getPoseData(PlayerPoseType.TICK).getHmd().getPositionVec3();
            } else {
                // no tracked player yet (world load, VR provider not up): nothing to look from
                return this.camera.rotation();
            }
            Vec3 direction = new Vec3(targetX, targetY, targetZ)
                    .subtract(source).normalize();

            // Vec3.normalize() returns ZERO below 1e-5, which would make the asin below
            // NaN and poison the whole quaternion - every billboard using it disappears.
            if (direction.lengthSqr() < 1.0E-6D) {
                return this.camera.rotation();
            }

            // Must match Camera#setRotation's convention: rotationYXZ(PI - yaw, -pitch, 0),
            // with FORWARDS = (0,0,-1). A billboard's front is +Z in model space, so the
            // quaternion has to map -Z onto the view direction. Without the PI term this
            // look-at is exactly camera.rotation() * rotY(180) - it turns every
            // billboard away from the eye, and the culling render types
            // (entityCutout for the fishing bobber, itemEntityTranslucentCull for xp orbs,
            // text for name tags) then discard it entirely.
            // MC 1.20.5 flipped this convention: 1.20.1 had FORWARDS = (0,0,+1) and
            // rotationYXZ(-yaw, +pitch, 0), which is what the old form was written against.
            return new Quaternionf()
                    .rotateY((float) (Math.PI - Math.atan2(-direction.x, direction.z)))
                    .rotateX((float) Math.asin(direction.y));
        }


    }


    /**
     * Vanilla AvatarRenderer declares no render() at all - the nearest declaration is
     * LivingEntityRenderer#render(S, ...) - so javac compiles super.render(...) in our
     * AvatarRenderer subclasses to
     * "invokespecial AvatarRenderer.render(LivingEntityRenderState, ...)", the erasure of the
     * inherited method. On Fabric that resolves straight up to LivingEntityRenderer#render.
     *
     * Forge and NeoForge both patch a render(AvatarRenderState, ...) override into AvatarRenderer
     * to fire their RenderPlayerEvent, which makes javac emit a synthetic bridge
     * render(LivingEntityRenderState, ...) into AvatarRenderer as well. That bridge now sits
     * exactly where our super call points, and all it does is invokevirtual back into
     * render(AvatarRenderState, ...) - i.e. straight back into the VR renderer's own override.
     * The result is unbounded mutual recursion and a StackOverflowError the first time a player
     * model is actually drawn (opening the survival inventory is usually the first time, since
     * the local player isn't rendered as an entity in first person).
     *
     * Routing the call through here keeps it bound to LivingEntityRenderer#render on every loader,
     * which is what the vanilla-compiled super call was always meant to reach. Note this skips
     * Forge/NeoForge's RenderPlayerEvent for VR-rendered players - it never fired for them anyway,
     * since our subclass overrides the very method that raises it.
     */
    @Mixin(AvatarRenderer.class)
    public abstract static class PlayerRendererMixin
            extends LivingEntityRenderer<AbstractClientPlayer, AvatarRenderState, PlayerModel>
            implements PlayerRendererExtension {

        // Mixins never merge constructors - this only exists so javac accepts the superclass.
        private PlayerRendererMixin(EntityRendererProvider.Context context, PlayerModel model,
                                    float shadowRadius) {
            super(context, model, shadowRadius);
        }

        // PORT-1.21.11: LivingEntityRenderer#render is gone - entity geometry is submitted
        // rather than drawn, so the entry point is submit(S, PoseStack, SubmitNodeCollector,
        // CameraRenderState) and the packed light rides on the render state as lightCoords.
        @Override
        public void visor$renderVanilla(AvatarRenderState renderState, PoseStack poseStack,
                                        SubmitNodeCollector collector, CameraRenderState cameraState) {
            super.submit(renderState, poseStack, collector, cameraState);
        }

        // PORT-1.21.11: in 1.21.4 PlayerRenderer#renderNameTag delegated both the score line and
        // the name line to super, so hooking EntityRenderer covered players too. AvatarRenderer
        // now inlines both SubmitNodeCollector#submitNameTag calls and never touches super, so the
        // two hooks in EntityRendererMixin have to be repeated here or players - the only entities
        // that ever carry a VR player - lose them entirely.
        @Inject(method = "submitNameTag(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/CameraRenderState;)V",
                at = @At("HEAD"), cancellable = true)
        private void visor$hideSpectatedVRNameTag(AvatarRenderState renderState, PoseStack poseStack,
                                                  SubmitNodeCollector collector,
                                                  CameraRenderState cameraState, CallbackInfo ci) {
            VRClientPlayer vrPlayer = ((EntityRenderStateExtension) renderState).visor$getVRPlayer();
            if (vrPlayer != null
                    && VRRenderState.isSpectatedVRView(vrPlayer.getMcPlayer())) {
                ci.cancel();
            }
        }

        // No ordinal: the score line and the name line both need to face the headset.
        @WrapOperation(method = "submitNameTag(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/CameraRenderState;)V",
                at = @At(value = "INVOKE",
                        target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitNameTag(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/phys/Vec3;ILnet/minecraft/network/chat/Component;ZIDLnet/minecraft/client/renderer/state/CameraRenderState;)V"))
        private void visor$vrNameTagCameraOrient(SubmitNodeCollector collector, PoseStack poseStack,
                                                 Vec3 nameTagAttachment, int yOffset, Component text,
                                                 boolean seeThrough, int lightCoords,
                                                 double distanceToCameraSq, CameraRenderState cameraState,
                                                 Operation<Void> original,
                                                 @Local(argsOnly = true) AvatarRenderState renderState) {
            float heightScale = 1.0f;
            VRClientPlayer vrPlayer = ((EntityRenderStateExtension) renderState).visor$getVRPlayer();
            if (vrPlayer != null) {
                heightScale = vrPlayer.getFullHeightScale();
            }

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

    }


}
