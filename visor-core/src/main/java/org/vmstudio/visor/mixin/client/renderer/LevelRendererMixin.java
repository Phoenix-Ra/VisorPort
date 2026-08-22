package org.vmstudio.visor.mixin.client.renderer;

import com.google.common.collect.Sets;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.vertex.PoseStack;
import org.jetbrains.annotations.Nullable;
import org.vmstudio.visor.api.client.render.VRRenderPass;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.server.level.BlockDestructionProgress;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.vmstudio.visor.api.client.player.pose.PlayerPoseType;
import org.vmstudio.visor.api.common.HandType;
import org.vmstudio.visor.api.common.utils.LoggerUtils;
import org.vmstudio.visor.api.server.VRServerSettings;
import org.vmstudio.visor.core.client.VisorState;
import org.vmstudio.visor.extensions.client.render.GameRendererExtension;
import org.vmstudio.visor.extensions.client.render.LevelRendererExtension;
import org.vmstudio.visor.core.client.render.helpers.VREffectsHelper;
import org.vmstudio.visor.core.client.render.VRRenderState;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.entity.Entity;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import org.vmstudio.visor.core.client.ClientContext;

import java.util.*;

import static org.vmstudio.visor.core.client.VisorClientImpl.MC;


@Mixin(value = LevelRenderer.class, priority = 999)
public abstract class LevelRendererMixin implements ResourceManagerReloadListener, AutoCloseable, LevelRendererExtension {

    @Final
    @Shadow
    private Minecraft minecraft;


    @Shadow
    @Nullable
    private RenderTarget entityOutlineTarget;

    @Unique
    private final Map<VRRenderPass, RenderTarget> visor$vrOutlineTargets
            = new EnumMap<>(VRRenderPass.class);

    @Unique
    private RenderTarget visor$vanillaOutlineTarget;

    @Final
    @Shadow
    private Long2ObjectMap<SortedSet<BlockDestructionProgress>> destructionProgress;

    @Final
    @Shadow
    private Int2ObjectMap<BlockDestructionProgress> destroyingBlocks;

    @Unique
    private Map<Long, Long> visor$damagedBlocksVr;

    @Unique
    private Map<Long, BlockDestructionProgress> visor$damagedBlocksVrSave;

    @Unique
    private List<Runnable> visor$swingTasks;


    // PORT-1.21.11: the constructor gained LevelRenderState and FeatureRenderDispatcher params.
    // None of them are used here, so the handler takes only the CallbackInfo and stops tracking
    // the signature.
    @Inject(method = "<init>", at = @At("RETURN"))
    private void visor$initFields(CallbackInfo ci) {
        visor$damagedBlocksVr = Collections.synchronizedMap(new HashMap<>());
        visor$damagedBlocksVrSave = Collections.synchronizedMap(new HashMap<>());
        visor$swingTasks = Collections.synchronizedList(new ArrayList<>());
    }

    /* ****************** *\
  //--------RENDERING--------\\
    \* ****************** */

    // PORT-26.1: prepareCullFrustum moved from LevelRenderer into Camera.update(); the per-pass
    // widening of the culling projection now happens in VRGameCamera.updateVR.


    // PORT-1.21.11: collectVisibleEntities -> extractVisibleEntities (the extract/submit split).
    // It still guards the camera entity's own model with camera.isDetached(), which is the call
    // this redirect exists to override.
    @Redirect(
            method = "extractVisibleEntities",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;isDetached()Z")
    )
    private boolean visor$renderSpectatedVRSelfView(Camera camera) {
        if (VRRenderState.isSpectatedVRView(camera.entity())) {
            return true;
        }
        return camera.isDetached();
    }

    // PORT-1.21.11: LevelRenderer#renderEntity is gone. Per-entity work is extractEntity(Entity,
    // float) now - that is where the render state samples the entity's position, so it is where
    // the camera entity's real position has to be back in place. It returns the built state, so
    // the handlers take a CallbackInfoReturnable.
    // The pair also used to publish the entity as visor$renderedEntity for the VR name tag
    // orientation; that reader runs in the submit phase, which no longer overlaps this window, so
    // it takes its anchor off the render state instead and the field is gone.
    @Inject(at = @At("HEAD"), method = "extractEntity")
    public void visor$captureEntityRestore(CallbackInfoReturnable<EntityRenderState> cir,
                                              @Local(argsOnly = true) Entity entity,
                                              @Share("capturedEntity") LocalRef<Entity> capturedEntity
    ) {
        if (VRRenderState.getPhase().isNotVanilla()
                && entity == minecraft.getCameraEntity()) {
            capturedEntity.set(entity);
            ((GameRendererExtension) minecraft.gameRenderer)
                    .visor$applyCachedCameraEntityPosition(entity);
        }
    }

    @Inject(at = @At("TAIL"), method = "extractEntity")
    public void visor$captureEntitySetup(CallbackInfoReturnable<EntityRenderState> cir,
                                  @Local(argsOnly = true) Entity entity,
                                  @Share("capturedEntity") LocalRef<Entity> capturedEntity
    ) {
        if (capturedEntity.get() != null) {
            ((GameRendererExtension) minecraft.gameRenderer)
                    .visor$setupCameraEntityAsVRCamera();
        }
    }



    // PORT-1.21.11: renderLevel no longer takes a GameRenderer and never calls
    // GameRenderer#getRenderDistance, so the old anchor for this injection does not exist any
    // more. The body is a disabled @TODO, so the hook is parked at HEAD to keep it applying; the
    // exact point has to be re-established when the Quest 3 rework below is picked up.
    @Inject(at = @At("HEAD"), method = "renderLevel")
    public void visor$stencil(CallbackInfo info) {
        if (VRRenderState.getPhase().isNotVanilla()) {
            //@TODO rework to fix Quest 3 issue
            //VREffectsHelper.drawEyeStencil();
        }
    }


    /* ************************ *\
  //--------ENTITY OUTLINE--------\\
    \* ************************ */


    @Inject(method = {"initOutline", "close"}, at = @At("HEAD"))
    private void visor$releaseVROutlineTargets(CallbackInfo ci) {
        if (this.visor$vanillaOutlineTarget != null) {
            this.entityOutlineTarget = this.visor$vanillaOutlineTarget;
            this.visor$vanillaOutlineTarget = null;
        }
        visor$discardVROutlineTargets();
    }


    // PORT-1.21.11: renderLevel's descriptor changed wholesale (no GameRenderer, extra fog slice
    // and flags). There is still only one renderLevel, so the name alone selects it.
    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void visor$useVROutlineTarget(CallbackInfo ci) {
        if (VisorState.get().isNotActive() || VRRenderState.getPhase().isVanilla()) {
            if (this.visor$vanillaOutlineTarget != null) {
                this.entityOutlineTarget = this.visor$vanillaOutlineTarget;
            }
            return;
        }
        RenderTarget passTarget = MC.mainRenderTarget;
        if (passTarget == null) {
            return;
        }
        if (this.visor$vanillaOutlineTarget == null) {
            this.visor$vanillaOutlineTarget = this.entityOutlineTarget;
        }

        VRRenderPass renderPass = VRRenderState.getRenderPass();
        RenderTarget outline = this.visor$vrOutlineTargets.get(renderPass);
        if (outline == null) {
            // PORT-1.21.11: TextureTarget takes a debug label, and a RenderTarget no longer owns a
            // clear colour - vanilla's own initOutline dropped its setClearColor(0,0,0,0) call for
            // the same reason, the outline attachment is cleared by the pass that draws into it.
            outline = new TextureTarget("Visor VR Entity Outline " + renderPass,
                    passTarget.width, passTarget.height, true);
            this.visor$vrOutlineTargets.put(renderPass, outline);
        } else if (outline.width != passTarget.width
                || outline.height != passTarget.height) {
            outline.resize(passTarget.width, passTarget.height);
        }
        this.entityOutlineTarget = outline;
    }

    @Unique
    private void visor$discardVROutlineTargets() {
        if (this.visor$vrOutlineTargets.isEmpty()) {
            return;
        }
        this.visor$vrOutlineTargets.values().forEach(RenderTarget::destroyBuffers);
        this.visor$vrOutlineTargets.clear();
    }

    /* **************** *\
  //--------EVENTS--------\\
    \* **************** */
    @Inject(at = @At("TAIL"), method = "onResourceManagerReload")
    public void visor$onResourceManagerReload(ResourceManager resourceManager, CallbackInfo ci) {
        if (VisorState.get().isInitialized()) {
            ClientContext.renderer.prepareReinit(
                    "Resources Reload"
            );
        }
    }
    /* ************************* *\
  //--------BETTER SWINGING--------\\
    \* ************************* */

    @Inject(at = @At("HEAD"), method = "setLevel")
    private void visor$clearSwingDamage(ClientLevel level, CallbackInfo ci) {
        visor$damagedBlocksVrSave.keySet().forEach(
                key -> destructionProgress.remove(key.longValue())
        );
        visor$damagedBlocksVr.clear();
        visor$damagedBlocksVrSave.clear();
    }

    @Inject(at = @At("HEAD"), method = "removeProgress", cancellable = true)
    private void visor$removeProgress(BlockDestructionProgress progress,
                                      CallbackInfo ci
    ) {
        //fix of crash bcz of vr swinging
        ci.cancel();
        long blockPos = progress.getPos().asLong();
        Set<BlockDestructionProgress> set = this.destructionProgress.get(blockPos);
        if (set == null) return; //here it is
        set.remove(progress);
        if (set.isEmpty()) {
            this.destructionProgress.remove(blockPos);
        }

    }

    @Inject(at = @At("HEAD"), method = "renderLevel")
    private void visor$betterSwinging(CallbackInfo ci) {
        if (visor$damagedBlocksVr.isEmpty()
                && visor$damagedBlocksVrSave.isEmpty()) {
            return;
        }

        try {

            List<Long> toRemove = new ArrayList<>();
            destructionProgress.forEach((key, value) -> {
                int stage = value.last().getProgress();
                if (stage < 0 || stage >= ModelBakery.DESTROY_TYPES.size()) {
                    toRemove.add(key);
                }
            });
            toRemove.forEach(it -> {
                destructionProgress.remove(it.longValue());
                visor$damagedBlocksVr.remove(it);
                visor$damagedBlocksVrSave.remove(it);
            });
            toRemove.clear();
            for (Map.Entry<Long, Long> entry : visor$damagedBlocksVr.entrySet()) {
                SortedSet<BlockDestructionProgress> set = destructionProgress.get(entry.getKey());
                if (set == null) {
                    toRemove.add(entry.getKey());
                    continue;
                }
                BlockDestructionProgress d = visor$damagedBlocksVrSave.get(entry.getKey());
                if (d == null) {
                    toRemove.add(entry.getKey());
                    continue;
                }
                if (!set.contains(d) || set.size() > 1) {
                    toRemove.add(entry.getKey());
                    continue;
                }
                //if anything happened with packet from server
                if (entry.getValue() + (VRServerSettings.getSwingingRepairDelay() * 50)
                        < System.currentTimeMillis()) {
                    toRemove.add(entry.getKey());
                }
            }
            toRemove.forEach(it -> {
                destructionProgress.remove(it.longValue());
                visor$damagedBlocksVr.remove(it);
                visor$damagedBlocksVrSave.remove(it);
            });
        }catch(Throwable e){
            LoggerUtils.printError(e);
        }
    }
    @Override
    @Unique
    public void visor$damageBlockProgress(@NotNull Player player,
                                          @NotNull BlockPos blockPos,
                                          int destroyStage
    ) {
        if (!VRServerSettings.isBetterSwinging()
                || VisorState.get().isNotActive()) return;

        if (destroyStage == -1) {
            visor$damagedBlocksVr.remove(blockPos.asLong());
            visor$damagedBlocksVrSave.remove(blockPos.asLong());
            destructionProgress.remove(blockPos.asLong());
            return;
        }

        if (destroyStage == -2) {
            visor$damagedBlocksVr.remove(blockPos.asLong());
            visor$damagedBlocksVrSave.remove(blockPos.asLong());
            return;
        }

        final List<Integer> toRemove = new ArrayList<>();

        destroyingBlocks.forEach((id, progress) -> {
            if (progress.getPos().asLong() == blockPos.asLong()) {
                toRemove.add(id);
                destructionProgress.remove(progress.getPos().asLong());
            }
        });

        toRemove.forEach(it -> destroyingBlocks.remove(it.intValue()));

        BlockDestructionProgress progress = new BlockDestructionProgress(
                player.getId(), blockPos
        );
        progress.setProgress(destroyStage);

        SortedSet<BlockDestructionProgress> set =
                destructionProgress.computeIfAbsent(
                        progress.getPos().asLong(), (p_234254_) -> {
                            return Sets.newTreeSet();
                        }
                );

        set.clear();
        set.add(progress);

        visor$damagedBlocksVr.put(blockPos.asLong(), System.currentTimeMillis());
        visor$damagedBlocksVrSave.put(blockPos.asLong(), progress);
    }


    /* ************************* *\
  //--------UTILITY METHODS--------\\
    \* ************************* */
}
