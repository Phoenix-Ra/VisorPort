package org.vmstudio.visor.mixin.client.gui;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Overlay;
import net.minecraft.client.gui.screens.Screen;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.At.Shift;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.vmstudio.visor.core.client.ClientContext;
import org.vmstudio.visor.core.client.VisorState;
import org.vmstudio.visor.core.client.render.VRRenderState;
import org.vmstudio.visor.core.client.gui.overlays.builtin.VROverlayGameScreen;

/**
 * PORT-26.2: screen and overlay management moved off {@code Minecraft} onto {@code Gui} - the
 * {@code screen} and {@code overlay} fields, {@code setScreen} and {@code setOverlay} all live
 * here now. The drop-in replacement for the old {@code Minecraft.setScreen} is
 * {@code gui.setScreen}; {@code Minecraft.setScreenAndShow} is NOT it - that one force-renders a
 * {@code renderFrame(false)} on the spot, which in VR submits a level-less (black) frame to the
 * headset and disturbs XR frame pacing. Vanilla only uses it on teardown paths. Both hooks below
 * used to sit in {@code MinecraftMixin}; they follow the methods they inject into.
 * <p>
 * Not to be confused with the HUD half of the old {@code Gui}, which became
 * {@code net.minecraft.client.gui.Hud} - see {@code HudMixin}.
 */
@Mixin(Gui.class)
public abstract class GuiMixin {

    @Shadow
    private Screen screen;

    /**
     * Handles screen changes.
     * <p>
     * Fires before the field write so the overlay still sees the outgoing screen, exactly as the
     * Minecraft-side version did.
     */
    @Inject(at = @At(value = "FIELD", opcode = Opcodes.PUTFIELD,
            target = "Lnet/minecraft/client/gui/Gui;screen:Lnet/minecraft/client/gui/screens/Screen;",
            shift = Shift.BEFORE, ordinal = 0),
            method = "setScreen(Lnet/minecraft/client/gui/screens/Screen;)V")
    public void visor$onOpenScreen(Screen pGuiScreen, CallbackInfo info) {
        if (VisorState.get().isNotActive()) return;

        ClientContext.overlayManager
                .getOverlay(VROverlayGameScreen.ID, VROverlayGameScreen.class)
                .onScreenChanged(this.screen, pGuiScreen, true);
    }

    /**
     * World passes never render the GUI, so extracting it per pass would only burn time and wipe
     * the state the GUI phase already recorded.
     * <p>
     * PORT-26.2: was {@code GameRenderer.extractGui}, which is gone - {@code extract()} calls
     * {@code Gui.extractRenderState} directly now, so the cancel follows the method here.
     */
    @Inject(at = @At("HEAD"), method = "extractRenderState", cancellable = true)
    private void visor$noGuiExtractionInWorldPass(CallbackInfo ci) {
        if (VRRenderState.getPhase().isVRWorld()) {
            ci.cancel();
        }
    }

    /**
     * Handles overlay changes.
     */
    @Inject(at = @At("TAIL"), method = "setOverlay")
    public void visor$onOverlaySet(Overlay overlay, CallbackInfo ci) {
        if (VisorState.get().isNotActive()) return;

        ClientContext.overlayManager
                .getOverlay(VROverlayGameScreen.ID, VROverlayGameScreen.class)
                .onScreenChanged(this.screen, this.screen, true);
    }
}
