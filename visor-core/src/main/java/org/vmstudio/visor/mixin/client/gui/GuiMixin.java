package org.vmstudio.visor.mixin.client.gui;

import org.vmstudio.visor.api.client.ClientFeature;
import org.vmstudio.visor.core.client.ClientContext;
import org.vmstudio.visor.core.client.VisorState;
import org.vmstudio.visor.core.client.render.VRRenderState;
import org.vmstudio.visor.extensions.client.GuiExtension;
import org.vmstudio.visor.api.client.settings.VRClientSettings;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.contextualbar.ContextualBarRenderer;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


@Mixin(Gui.class)
public abstract class GuiMixin implements GuiExtension {

    @Final
    @Shadow
    private Minecraft minecraft;

    /* ********************************** *\
  //--------DISABLE VANILLA OVERLAYS--------\\
    \* ********************************** */
    // 1.21.4: renderConfusionOverlay moved here from GameRenderer
    @Inject(at = @At("HEAD"), method = "renderConfusionOverlay", cancellable = true)
    private void visor$noConfusionOverlayInGUI(GuiGraphics guiGraphics, float f, CallbackInfo ci) {
        if (VRRenderState.getPhase().isVRGui()) {
            ci.cancel();
        }
    }

    // 1.21.1: renderHotbar split into renderHotbarAndDecorations/renderItemHotbar
    @Inject(at = @At("HEAD"), method = "renderItemHotbar", cancellable = true)
    public void visor$noVanillaHotbar(CallbackInfo ci) {
        if(VisorState.get().isNotActive()
                || (minecraft.screen == null
                && !VRClientSettings.isHudDisableHotBar()
                && ClientContext.visor.isFeatureDisabled(ClientFeature.GUI_DISABLE_HUD))) return;
        ci.cancel();
    }
    @Inject(at = @At("HEAD"), method = "renderPlayerHealth", cancellable = true)
    public void visor$noVanillaPlayerHealth(CallbackInfo ci) {
        if(VisorState.get().isNotActive() || (minecraft.screen == null
                && ClientContext.visor.isFeatureDisabled(ClientFeature.GUI_DISABLE_HUD))) return;
        ci.cancel();
    }
    @Inject(at = @At("HEAD"), method = "renderVehicleHealth", cancellable = true)
    public void visor$noVanillaVehicleHealth(CallbackInfo ci) {
        if(VisorState.get().isNotActive() || (minecraft.screen == null
                && ClientContext.visor.isFeatureDisabled(ClientFeature.GUI_DISABLE_HUD))) return;
        ci.cancel();
    }
    // 1.21.11: renderJumpMeter and renderExperienceBar are gone from Gui. The jump meter, the
    // experience bar and the locator bar are now one ContextualBarRenderer that
    // renderHotbarAndDecorations draws in two passes - the bar background, then the filled bar.
    // Skipping both passes is what cancelling the two old methods did.
    @Redirect(at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/contextualbar/ContextualBarRenderer;renderBackground(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V"),
            method = "renderHotbarAndDecorations")
    public void visor$noVanillaContextualBarBackground(ContextualBarRenderer instance,
                                                       GuiGraphics guiGraphics,
                                                       DeltaTracker deltaTracker) {
        if(visor$keepsVanillaHud()) {
            instance.renderBackground(guiGraphics, deltaTracker);
        }
    }
    @Redirect(at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/contextualbar/ContextualBarRenderer;render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V"),
            method = "renderHotbarAndDecorations")
    public void visor$noVanillaContextualBar(ContextualBarRenderer instance,
                                             GuiGraphics guiGraphics,
                                             DeltaTracker deltaTracker) {
        if(visor$keepsVanillaHud()) {
            instance.render(guiGraphics, deltaTracker);
        }
    }
    // 1.21.1: the level number is drawn by its own layer now
    // 1.21.11: that layer is a static helper on ContextualBarRenderer, called straight from
    // renderHotbarAndDecorations, so it is skipped at the call site instead of cancelled
    @Redirect(at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/contextualbar/ContextualBarRenderer;renderExperienceLevel(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/gui/Font;I)V"),
            method = "renderHotbarAndDecorations")
    public void visor$noVanillaExperienceLevel(GuiGraphics guiGraphics, Font font, int level) {
        if(visor$keepsVanillaHud()) {
            ContextualBarRenderer.renderExperienceLevel(guiGraphics, font, level);
        }
    }
    // The guard the cancelled renderJumpMeter/renderExperienceBar/renderExperienceLevel
    // injections shared: vanilla keeps drawing while Visor is off, or while no screen is open
    // and HUD hiding was not requested.
    @Unique
    private boolean visor$keepsVanillaHud() {
        return VisorState.get().isNotActive()
                || (minecraft.screen == null
                && ClientContext.visor.isFeatureDisabled(ClientFeature.GUI_DISABLE_HUD));
    }
    // 1.21.1: the boss bar call lives in a constructor lambda now,
    // so it is cancelled in BossHealthOverlayMixin instead of redirected here
    // 1.21.11: ChatComponent.render gained an explicit Font and a trailing
    // "change cursor on insertions" flag
    @Redirect(at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/components/ChatComponent;render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/gui/Font;IIIZZ)V"),
            method = "renderChat")
    public void visor$noVanillaGuiChat(ChatComponent instance,
                                       GuiGraphics guiGraphics,
                                       Font font,
                                       int i, int j, int k, boolean focused,
                                       boolean changeCursorOnInsertions) {
        if(VisorState.get().isNotActive()) {
            instance.render(guiGraphics, font, i, j, k, focused, changeCursorOnInsertions);
            return;
        }
        if(minecraft.screen instanceof ChatScreen) {
            instance.render(guiGraphics, font, i, j, k, focused, changeCursorOnInsertions);
        }
    }


    @Inject(at = @At("HEAD"), method = "renderVignette", cancellable = true)
    public void visor$noVanillaVignette(CallbackInfo ci) {
        if(VisorState.get().isNotActive()) return;
        ci.cancel();
    }
    @Inject(at = @At("HEAD"), method = "renderSpyglassOverlay", cancellable = true)
    public void visor$noVanillaSpyglassOverlay(CallbackInfo ci) {
        if(VisorState.get().isNotActive()) return;
        ci.cancel();
    }
    @Inject(at = @At("HEAD"), method = "renderEffects", cancellable = true)
    public void visor$noVanillaEffects(CallbackInfo ci) {
        if(VisorState.get().isNotActive()) return;
        ci.cancel();
    }
    @Inject(at = @At("HEAD"), method = "renderSelectedItemName", cancellable = true)
    public void visor$noVanillaSelectedItemName(CallbackInfo ci) {
        if(VisorState.get().isNotActive()) return;
        ci.cancel();
    }
    @Inject(at = @At("HEAD"), method = "renderSavingIndicator", cancellable = true)
    public void visor$noAutoSaveText(CallbackInfo ci) {
        if(VisorState.get().isNotActive()) return;
        ci.cancel();
    }

    @Inject(method = "renderTextureOverlay", at = @At("HEAD"), cancellable = true)
    public void visor$noTextureOverlay(GuiGraphics guiGraphics, Identifier resourceLocation, float f, CallbackInfo ci) {
        if(VisorState.get().isNotActive()) return;
        ci.cancel();
    }

    @Inject(method = "renderPortalOverlay", at = @At("HEAD"), cancellable = true)
    public void visor$noPortalOverlay(GuiGraphics guiGraphics, float f, CallbackInfo ci) {
        if(VisorState.get().isNotActive()) return;
        ci.cancel();
    }

    @Inject(at = @At("HEAD"), method = "renderCrosshair", cancellable = true)
    public void visor$noCrosshair(CallbackInfo ci) {
        if(VisorState.get().isNotActive()) return;
        ci.cancel();
    }

    // 1.21.1: sleep overlay drawn by its own layer method
    @Redirect(at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getSleepTimer()I"), method = "renderSleepOverlay")
    public int visor$noSleepOverlay(LocalPlayer instance) {
        return VisorState.get().isActive()
                ? 0
                : instance.getSleepTimer();
    }

}
