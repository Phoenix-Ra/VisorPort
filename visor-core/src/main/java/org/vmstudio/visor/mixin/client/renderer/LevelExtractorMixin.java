package org.vmstudio.visor.mixin.client.renderer;

import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.vmstudio.visor.core.client.ClientContext;
import org.vmstudio.visor.core.client.VisorState;
import org.vmstudio.visor.core.client.render.VRRenderState;
import org.vmstudio.visor.extensions.client.level.ClientLevelExtension;
import org.vmstudio.visor.extensions.client.render.GameRendererExtension;

/**
 * PORT-26.2: the extraction half of {@code LevelRenderer} became its own class,
 * {@code net.minecraft.client.renderer.extract.LevelExtractor}, reachable as the public field
 * {@code Minecraft.levelExtractor}. Every hook here used to live in {@code LevelRendererMixin} and
 * followed the method it injects into: {@code extractVisibleEntities}, {@code extractEntity},
 * {@code onResourceManagerReload} and the {@code minecraft} field are all on the extractor now.
 * <p>
 * What stayed behind on the renderer is the drawing half - the outline targets and the stencil
 * hook - see {@code LevelRendererMixin}.
 */
@Mixin(LevelExtractor.class)
public abstract class LevelExtractorMixin {

    @Final
    @Shadow
    private Minecraft minecraft;

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

    @Inject(at = @At("TAIL"), method = "onResourceManagerReload")
    public void visor$onResourceManagerReload(ResourceManager resourceManager, CallbackInfo ci) {
        if (VisorState.get().isInitialized()) {
            ClientContext.renderer.prepareReinit(
                    "Resources Reload"
            );
        }
    }

    /**
     * The better-swinging per-frame prune (see ClientLevelMixin). Anchored here rather than on
     * LevelRenderer.render because extract is what copies destructionProgress into the render
     * state - pruning after the copy leaves a frame where an out-of-range stage reaches
     * DESTROY_TYPES.get and throws.
     */
    @Inject(at = @At("HEAD"), method = "extract")
    private void visor$betterSwinging(CallbackInfo ci) {
        ClientLevel level = this.minecraft.level;
        if (level != null) {
            ((ClientLevelExtension) level).visor$pruneSwingDamage();
        }
    }
}
