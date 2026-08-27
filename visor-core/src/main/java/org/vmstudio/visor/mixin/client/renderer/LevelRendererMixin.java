package org.vmstudio.visor.mixin.client.renderer;

import com.google.common.collect.Sets;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.vertex.PoseStack;
import org.jetbrains.annotations.Nullable;
import org.vmstudio.visor.api.client.render.VRRenderPass;
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
import org.vmstudio.visor.extensions.client.level.ClientLevelExtension;

import java.util.*;

import static org.vmstudio.visor.core.client.VisorClientImpl.MC;


@Mixin(value = LevelRenderer.class, priority = 999)
public abstract class LevelRendererMixin implements ResourceManagerReloadListener, AutoCloseable {


    @Shadow
    @Nullable
    private RenderTarget entityOutlineTarget;

    @Unique
    private final Map<VRRenderPass, RenderTarget> visor$vrOutlineTargets
            = new EnumMap<>(VRRenderPass.class);

    @Unique
    private RenderTarget visor$vanillaOutlineTarget;

    // PORT-26.2: the swing bookkeeping maps (and the <init> hook that allocated them) moved to
    // ClientLevelMixin along with the destruction-progress maps they mirror. visor$swingTasks went
    // with them and turned out to have no readers at all, so it is simply gone.

    /* ****************** *\
  //--------RENDERING--------\\
    \* ****************** */

    // PORT-26.1: prepareCullFrustum moved from LevelRenderer into Camera.update(); the per-pass
    // widening of the culling projection now happens in VRGameCamera.updateVR.


    // PORT-1.21.11: renderLevel no longer takes a GameRenderer and never calls
    // GameRenderer#getRenderDistance, so the old anchor for this injection does not exist any
    // more. The body is a disabled @TODO, so the hook is parked at HEAD to keep it applying; the
    // exact point has to be re-established when the Quest 3 rework below is picked up.
    @Inject(at = @At("HEAD"), method = "render")
    public void visor$stencil(CallbackInfo info) {
        if (VRRenderState.getPhase().isNotVanilla()) {
            //@TODO rework to fix Quest 3 issue
            //VREffectsHelper.drawEyeStencil();
        }
    }


    /* ************************ *\
  //--------ENTITY OUTLINE--------\\
    \* ************************ */


    // PORT-26.2: initOutline is gone. The outline target is rebuilt in resize(II) now
    // (verified: <init>, close, doEntityOutline, render and resize are the only methods
    // that touch entityOutlineTarget), so that is the new "vanilla target changed" signal
    // to drop the per-pass VR copies on.
    @Inject(method = {"resize", "close"}, at = @At("HEAD"))
    private void visor$releaseVROutlineTargets(CallbackInfo ci) {
        if (this.visor$vanillaOutlineTarget != null) {
            this.entityOutlineTarget = this.visor$vanillaOutlineTarget;
            this.visor$vanillaOutlineTarget = null;
        }
        visor$discardVROutlineTargets();
    }


    // PORT-1.21.11: renderLevel's descriptor changed wholesale (no GameRenderer, extra fog slice
    // and flags). There is still only one renderLevel, so the name alone selects it.
    @Inject(method = "render", at = @At("HEAD"))
    private void visor$useVROutlineTarget(CallbackInfo ci) {
        if (VisorState.get().isNotActive() || VRRenderState.getPhase().isVanilla()) {
            if (this.visor$vanillaOutlineTarget != null) {
                this.entityOutlineTarget = this.visor$vanillaOutlineTarget;
            }
            return;
        }
        RenderTarget passTarget = MC.gameRenderer.mainRenderTarget;
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
            // PORT-26.2: TextureTarget takes the colour format explicitly now.
            outline = new TextureTarget("Visor VR Entity Outline " + renderPass,
                    passTarget.width, passTarget.height, true, GpuFormat.RGBA8_UNORM);
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
    /* ************************* *\
  //--------BETTER SWINGING--------\\
    \* ************************* */

    // PORT-26.2: destruction progress lives on ClientLevel now, so the whole better-swinging
    // cluster - the two bookkeeping maps, removeProgress, damageBlockProgress - moved to
    // ClientLevelMixin with it. The per-frame prune moved to LevelExtractorMixin: the render
    // state is built from the map during extract, so a prune anchored here ran one step after
    // the values it sanitises had already been copied out.
    // setLevel needed no replacement: the state is per-level, so a new one starts empty.

    /* ************************* *\
  //--------UTILITY METHODS--------\\
    \* ************************* */
}
