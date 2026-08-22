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
import net.minecraft.client.gui.GuiGraphicsExtractor;
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
    @Inject(at = @At("HEAD"), method = "extractConfusionOverlay", cancellable = true)
    private void visor$noConfusionOverlayInGUI(GuiGraphicsExtractor guiGraphics, float f, CallbackInfo ci) {
        if (VRRenderState.getPhase().isVRGui()) {
            ci.cancel();
        }
    }

    // 1.21.1: renderHotbar split into renderHotbarAndDecorations/renderItemHotbar
    @Inject(at = @At("HEAD"), method = "extractItemHotbar", cancellable = true)
    public void visor$noVanillaHotbar(CallbackInfo ci) {
        if(VisorState.get().isNotActive()
                || (minecraft.screen == null
                && !VRClientSettings.isHudDisableHotBar()
                && ClientContext.visor.isFeatureDisabled(ClientFeature.GUI_DISABLE_HUD))) return;
        ci.cancel();
    }
    @Inject(at = @At("HEAD"), method = "extractPlayerHealth", cancellable = true)
    public void visor$noVanillaPlayerHealth(CallbackInfo ci) {
        if(VisorState.get().isNotActive() || (minecraft.screen == null
                && ClientContext.visor.isFeatureDisabled(ClientFeature.GUI_DISABLE_HUD))) return;
        ci.cancel();
    }
    @Inject(at = @At("HEAD"), method = "extractVehicleHealth", cancellable = true)
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
            target = "Lnet/minecraft/client/gui/contextualbar/ContextualBarRenderer;extractBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V"),
            method = "extractHotbarAndDecorations")
    public void visor$noVanillaContextualBarBackground(ContextualBarRenderer instance,
                                                       GuiGraphicsExtractor guiGraphics,
                                                       DeltaTracker deltaTracker) {
        if(visor$keepsVanillaHud()) {
            instance.extractBackground(guiGraphics, deltaTracker);
        }
    }
    @Redirect(at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/contextualbar/ContextualBarRenderer;extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V"),
            method = "extractHotbarAndDecorations")
    public void visor$noVanillaContextualBar(ContextualBarRenderer instance,
                                             GuiGraphicsExtractor guiGraphics,
                                             DeltaTracker deltaTracker) {
        if(visor$keepsVanillaHud()) {
            instance.extractRenderState(guiGraphics, deltaTracker);
        }
    }
    // 1.21.1: the level number is drawn by its own layer now
    // 1.21.11: that layer is a static helper on ContextualBarRenderer, called straight from
    // renderHotbarAndDecorations, so it is skipped at the call site instead of cancelled
    @Redirect(at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/contextualbar/ContextualBarRenderer;extractExperienceLevel(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/gui/Font;I)V"),
            method = "extractHotbarAndDecorations")
    public void visor$noVanillaExperienceLevel(GuiGraphicsExtractor guiGraphics, Font font, int level) {
        if(visor$keepsVanillaHud()) {
            ContextualBarRenderer.extractExperienceLevel(guiGraphics, font, level);
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
    /**
     * PORT-26.1: ChatComponent.render(graphics, font, ticks, mouseX, mouseY, focused, cursor)
     * became extractRenderState(graphics, font, ticks, mouseX, mouseY, DisplayMode, cursor);
     * the HUD path passes DisplayMode.BACKGROUND where it used to pass focused=false.
     */
    @Redirect(at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/components/ChatComponent;extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/gui/Font;IIILnet/minecraft/client/gui/components/ChatComponent$DisplayMode;Z)V"),
            method = "extractChat")
    public void visor$noVanillaGuiChat(ChatComponent instance,
                                       GuiGraphicsExtractor guiGraphics,
                                       Font font,
                                       int ticks, int mouseX, int mouseY,
                                       ChatComponent.DisplayMode displayMode,
                                       boolean changeCursorOnInsertions) {
        if(VisorState.get().isNotActive()) {
            instance.extractRenderState(guiGraphics, font, ticks, mouseX, mouseY, displayMode, changeCursorOnInsertions);
            return;
        }
        if(minecraft.screen instanceof ChatScreen) {
            instance.extractRenderState(guiGraphics, font, ticks, mouseX, mouseY, displayMode, changeCursorOnInsertions);
        }
    }

    @Inject(at = @At("HEAD"), method = "extractVignette", cancellable = true)
    public void visor$noVanillaVignette(CallbackInfo ci) {
        if(VisorState.get().isNotActive()) return;
        ci.cancel();
    }
    @Inject(at = @At("HEAD"), method = "extractSpyglassOverlay", cancellable = true)
    public void visor$noVanillaSpyglassOverlay(CallbackInfo ci) {
        if(VisorState.get().isNotActive()) return;
        ci.cancel();
    }
    @Inject(at = @At("HEAD"), method = "extractEffects", cancellable = true)
    public void visor$noVanillaEffects(CallbackInfo ci) {
        if(VisorState.get().isNotActive()) return;
        ci.cancel();
    }
    @Inject(at = @At("HEAD"), method = "extractSelectedItemName", cancellable = true)
    public void visor$noVanillaSelectedItemName(CallbackInfo ci) {
        if(VisorState.get().isNotActive()) return;
        ci.cancel();
    }
    @Inject(at = @At("HEAD"), method = "extractSavingIndicator", cancellable = true)
    public void visor$noAutoSaveText(CallbackInfo ci) {
        if(VisorState.get().isNotActive()) return;
        ci.cancel();
    }

    @Inject(method = "extractTextureOverlay", at = @At("HEAD"), cancellable = true)
    public void visor$noTextureOverlay(GuiGraphicsExtractor guiGraphics, Identifier resourceLocation, float f, CallbackInfo ci) {
        if(VisorState.get().isNotActive()) return;
        ci.cancel();
    }

    @Inject(method = "extractPortalOverlay", at = @At("HEAD"), cancellable = true)
    public void visor$noPortalOverlay(GuiGraphicsExtractor guiGraphics, float f, CallbackInfo ci) {
        if(VisorState.get().isNotActive()) return;
        ci.cancel();
    }

    @Inject(at = @At("HEAD"), method = "extractCrosshair", cancellable = true)
    public void visor$noCrosshair(CallbackInfo ci) {
        if(VisorState.get().isNotActive()) return;
        ci.cancel();
    }

    // 1.21.1: sleep overlay drawn by its own layer method
    @Redirect(at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getSleepTimer()I"), method = "extractSleepOverlay")
    public int visor$noSleepOverlay(LocalPlayer instance) {
        return VisorState.get().isActive()
                ? 0
                : instance.getSleepTimer();
    }

}
